import type { Dir, Floor } from './floors'
import { T } from './floors'
import { gridWalkable, moveContinuous, smoothRoute, type Point } from './motion'
import { findPath } from './path'
import { officeSeats, seatApproach } from './social'
import type { OfficeBehavior } from '@/lib/scene/office-intent'

export type OfficeLifeDestination =
  | 'working'
  | 'resting'
  | 'window'
  | 'coffee'
  | 'visiting'
  | 'following'
  | 'waiting'
export type OfficeLifeActivity = OfficeLifeDestination | 'walking' | 'standing'
export interface OfficeLifeSnapshot {
  activity: OfficeLifeActivity
  destination?: OfficeLifeDestination
  label: string
  moving: boolean
  behavior?: OfficeBehavior
  blocked?: boolean
}
export interface OfficeLifeActor extends Point {
  id: string
  dir: Dir
  status?: string
  stationary?: boolean
  moving: boolean
  heading?: number
  seated?: boolean
  seatLift?: number
  activity?: OfficeLifeActivity
}

const lifeByProject = new Map<string, Map<string, OfficeLifeSnapshot>>()
export const getOfficeLifeActivity = (
  projectId: string,
  agentId: string,
): Readonly<OfficeLifeSnapshot> | undefined =>
  lifeByProject.get(projectId)?.get(agentId)

export function isOfficeWorkingStatus(status?: string): boolean {
  return /^(working|thinking|coding|running|busy|reviewing|testing|reading|writing|executing|analyzing|fixing|briefing|meeting)$/i.test(
    status ?? '',
  )
}

const LABELS: Record<OfficeLifeDestination | 'standing', string> = {
  working: '책상에서 작업하는 중',
  resting: '소파에서 쉬는 중',
  window: '창밖을 구경하는 중',
  coffee: '차 한 잔 마시는 중',
  standing: '잠깐 서서 쉬는 중',
  visiting: '곁에서 대화하는 중',
  following: '곁에서 따라갈 준비를 하는 중',
  waiting: '말씀하신 자리에서 기다리는 중',
}
const WALK_LABELS: Record<OfficeLifeDestination, string> = {
  working: '작업하러 책상으로 가는 중',
  resting: '쉬러 소파로 가는 중',
  window: '창가로 산책하는 중',
  coffee: '차 한 잔 마시러 가는 중',
  visiting: '부른 사람에게 가는 중',
  following: '사용자를 따라가는 중',
  waiting: '멈출 자리로 이동하는 중',
}
// 사람끼리 지킬 거리(px, 1타일 = 32). 로봇 팀원 머리 지름(1.1타일, 약 35px)에 맞춘다.
export const PERSONAL_SPACE = 34
// 이 거리 안의 사람에게서 조금씩 비켜 걷는다.
const AVOID_RADIUS = 50
// 이만큼 못 나아가면 지금 자리에서 길을 다시 찾는다(초).
const STUCK_SECONDS = 1.2
const hash = (id: string) =>
  [...id].reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 7)
const feet = (x: number, y: number): Point => ({ x: x * T + 16, y: y * T + 27 })

interface Destination {
  id: string
  activity: OfficeLifeDestination
  landing: Point
  pose: Point
  dir: Dir
  heading?: number
  seated: boolean
}
interface Resident {
  x: number
  y: number
  dir: Dir
  heading?: number
  working: boolean
  homeKey: string
  target?: Destination
  route: Point[]
  activity: OfficeLifeActivity
  seated: boolean
  dwell: number
  visits: number
  behavior?: OfficeBehavior
  followTimer?: number
  blocked?: boolean
  transfer?: { from: Point; to: Point; progress: number; leaving: boolean }
  stuck?: number
}

/** Stateful positions survive snapshots; routes and collisions use the same floor as the player. */
/** 내딛은 자리가 누구에게도 개인 거리 안으로 더 다가가지 않으면 괜찮다. */
export function keepsSpace(
  from: Point,
  to: Point,
  others: readonly Point[],
  space = PERSONAL_SPACE,
) {
  return others.every((other) => {
    const after = Math.hypot(to.x - other.x, to.y - other.y)
    return (
      after >= space || after >= Math.hypot(from.x - other.x, from.y - other.y)
    )
  })
}

export class OfficeLifeSimulation {
  private residents = new Map<string, Resident>()
  private lastFloor?: Floor
  private floorKey = ''
  private destinations: Destination[] = []
  private blocked: boolean[][] = []

  private readonly projectId: string
  constructor(projectId: string) {
    this.projectId = projectId
    lifeByProject.set(projectId, new Map())
  }

  private free = (x: number, y: number) =>
    gridWalkable({ x, y: y - 4 }, this.blocked, T, 6)

  private nearestLanding(point: Point): Point | undefined {
    if (this.free(point.x, point.y)) return point
    let nearest: Point | undefined,
      distance = Infinity
    for (let y = 0; y < this.blocked.length; y++)
      for (let x = 0; x < this.blocked[y].length; x++) {
        if (this.blocked[y][x]) continue
        const candidate = feet(x, y),
          next = Math.hypot(candidate.x - point.x, candidate.y - point.y)
        if (next < distance && this.free(candidate.x, candidate.y)) {
          nearest = candidate
          distance = next
        }
      }
    return nearest
  }

  private configure(floor: Floor): boolean {
    if (this.lastFloor === floor) return false
    this.lastFloor = floor
    const key = `${floor.staticKey ?? ''}/${floor.W}/${floor.H}/${JSON.stringify(floor.furniture)}/${floor.grid.map((row) => row.join('')).join('')}`
    if (this.floorKey === key) return false
    this.floorKey = key
    this.blocked = floor.blocked.map((row, y) =>
      row.map(
        (blocked, x) =>
          blocked ||
          floor.grid[y][x] === '#' ||
          floor.grid[y][x] === 'g' ||
          floor.grid[y][x] === 'o',
      ),
    )
    this.destinations = officeSeats({ ...floor, blocked: this.blocked })
      .filter((seat) => seat.kind === 'sofa' || seat.kind === 'bench')
      .map((seat) => ({
        id: seat.id,
        activity: 'resting',
        landing: seatApproach(seat),
        pose: { x: seat.x, y: seat.y },
        dir: seat.facing,
        seated: true,
      }))
    // The dream office has full-height rear windows even while wall decoration is hidden.
    const windows = floor.decor
      .filter((decor) => decor.kind === 'window')
      .map((decor) => decor.x + decor.w / 2)
    const windowEdge = floor.footprint?.right ?? Math.min(floor.W, 30)
    const rearWindowSpots = Array.from(
      { length: Math.max(1, Math.floor((windowEdge - 2) / 2)) },
      (_, index) => 2.5 + index * 2,
    )
    for (const [index, x] of (windows.length
      ? windows
      : rearWindowSpots
    ).entries()) {
      if (
        floor.furniture.some(
          (item) =>
            (item.kind === 'projector' || item.kind === 'designboard') &&
            item.y < 3 &&
            x >= item.x &&
            x <= item.x + item.w,
        )
      )
        continue
      const landing = this.nearestLanding({ x: x * T, y: 1.7 * T })
      if (
        landing &&
        landing.y < 4 * T &&
        !this.destinations.some(
          (target) =>
            Math.hypot(
              target.landing.x - landing.x,
              target.landing.y - landing.y,
            ) < 24,
        )
      )
        this.destinations.push({
          id: `window/${index}`,
          activity: 'window',
          landing,
          pose: landing,
          dir: 'up',
          seated: false,
        })
    }
    for (const [index, item] of floor.furniture.entries())
      if (
        item.kind === 'cooler' ||
        item.kind === 'coffee' ||
        item.kind === 'vending'
      ) {
        const landing = this.nearestLanding({
          x: (item.x + item.w + 0.55) * T,
          y: (item.y + item.h / 2 + 0.2) * T,
        })
        if (landing)
          this.destinations.push({
            id: `coffee/${index}`,
            activity: 'coffee',
            landing,
            pose: landing,
            dir: 'left',
            seated: false,
          })
      }
    return true
  }

  // avoid: 다른 사람이 서 있는 자리. 그 칸을 막힌 칸으로 보고 길을 찾는다(막혔을 때 돌아가는 길).
  private route(
    from: Point,
    to: Point,
    avoid: readonly Point[] = [],
  ): Point[] | undefined {
    if (!this.free(to.x, to.y)) return undefined
    const tile = (point: Point) =>
      [Math.floor(point.x / T), Math.floor((point.y - 4) / T)] as const
    const [sx, sy] = tile(from),
      [gx, gy] = tile(to)
    const taken = new Set(
      avoid
        .map(tile)
        .filter(([x, y]) => !(x === sx && y === sy) && !(x === gx && y === gy))
        .map(([x, y]) => `${x}/${y}`),
    )
    const blocked = taken.size
      ? this.blocked.map((row, y) =>
          row.map((cell, x) => cell || taken.has(`${x}/${y}`)),
        )
      : this.blocked
    const free = taken.size
      ? (x: number, y: number) =>
          this.free(x, y) && !taken.has(tile({ x, y }).join('/'))
      : this.free
    const tiles = findPath(blocked, sx, sy, gx, gy)
    const route = smoothRoute(
      from,
      [...tiles.map(([x, y]) => feet(x, y)), to],
      free,
    )
    const end = route.at(-1)
    return end && Math.hypot(end.x - to.x, end.y - to.y) < 0.1
      ? route
      : undefined
  }

  private choose(
    resident: Resident,
    actor: OfficeLifeActor,
    completed: boolean,
    homeOnly = false,
  ): boolean {
    const home = this.nearestLanding(actor)
    let choices: Destination[]
    if (resident.working || homeOnly)
      choices = home
        ? [
            {
              id: `desk/${actor.id}`,
              activity: resident.working ? 'working' : 'waiting',
              landing: home,
              pose: home,
              dir: actor.dir,
              seated: true,
            },
          ]
        : []
    else {
      const idle: OfficeLifeDestination[] = ['resting', 'window', 'coffee']
      const first = completed
        ? 0
        : (hash(actor.id) + resident.visits) % idle.length
      const preferred = idle.slice(first).concat(idle.slice(0, first))
      const others = [...this.residents.entries()]
        .filter(([id]) => id !== actor.id)
        .map(([, state]) => state.target)
        .filter((target) => !!target)
      const reserved = new Set(others.map((target) => target.id))
      // 바로 옆 소파 자리처럼 다른 사람 자리와 너무 붙은 곳도 예약된 것으로 본다.
      const crowded = (target: Destination) =>
        others.some(
          (other) =>
            Math.hypot(
              other.pose.x - target.pose.x,
              other.pose.y - target.pose.y,
            ) < PERSONAL_SPACE,
        )
      choices = this.destinations
        .filter(
          (target) =>
            !reserved.has(target.id) &&
            !crowded(target) &&
            target.id !== resident.target?.id,
        )
        .sort(
          (a, b) =>
            preferred.indexOf(a.activity) - preferred.indexOf(b.activity) ||
            Math.hypot(a.landing.x - resident.x, a.landing.y - resident.y) -
              Math.hypot(b.landing.x - resident.x, b.landing.y - resident.y),
        )
    }
    // A seated resident first steps off the cushion, then follows an ordinary collision-safe route.
    const leavingSeat =
      resident.target?.seated &&
      (resident.seated || (!!resident.transfer && !resident.transfer.leaving))
    const start = resident.transfer?.leaving
      ? resident.transfer.to
      : leavingSeat && resident.target
        ? resident.target.landing
        : resident
    const next = choices
      .map((target) => ({ target, route: this.route(start, target.landing) }))
      .find((choice) => choice.route)
    if (!next) {
      resident.dwell = 3
      if (!resident.target) resident.activity = 'standing'
      return false
    }
    resident.transfer = undefined
    if (
      leavingSeat &&
      resident.target &&
      Math.hypot(resident.x - start.x, resident.y - start.y) > 0.1
    ) {
      resident.transfer = {
        from: { x: resident.x, y: resident.y },
        to: { x: start.x, y: start.y },
        progress: 0,
        leaving: true,
      }
    }
    resident.seated = false
    resident.target = next.target
    resident.route = next.route!
    resident.activity = 'walking'
    resident.visits++
    resident.dwell = 0
    return true
  }

  /** Walk to a reachable spot beside the caller, including getting off a sofa first. */
  summon(
    floor: Floor,
    agentId: string,
    caller: Point,
    heading = 0,
    occupied: readonly Point[] = [],
  ): boolean {
    const ok = this.approach(
      floor,
      agentId,
      caller,
      heading,
      occupied,
      'visiting',
    )
    if (ok) {
      const resident = this.residents.get(agentId)!
      resident.behavior = 'visiting'
      resident.blocked = false
    }
    return ok
  }

  private approach(
    floor: Floor,
    agentId: string,
    caller: Point,
    heading: number,
    occupied: readonly Point[],
    activity: 'visiting' | 'following',
  ): boolean {
    // A new layout must first reconcile every resident in step(), before a call can route.
    if (this.lastFloor !== floor) return false
    const resident = this.residents.get(agentId)
    if (!resident || !Number.isFinite(caller.x + caller.y + heading))
      return false
    const leavingSeat =
      resident.target?.seated &&
      (resident.seated || (!!resident.transfer && !resident.transfer.leaving))
    const start = resident.transfer?.leaving
      ? resident.transfer.to
      : leavingSeat && resident.target
        ? resident.target.landing
        : resident
    const candidates = [48, 64]
      .flatMap((radius) =>
        Array.from({ length: 16 }, (_, i) => {
          const angle = heading + (i * Math.PI) / 8
          return {
            x: caller.x + Math.sin(angle) * radius,
            y: caller.y + Math.cos(angle) * radius,
          }
        }),
      )
      .sort(
        (a, b) =>
          Math.hypot(
            a.x - caller.x - Math.sin(heading) * 48,
            a.y - caller.y - Math.cos(heading) * 48,
          ) -
          Math.hypot(
            b.x - caller.x - Math.sin(heading) * 48,
            b.y - caller.y - Math.cos(heading) * 48,
          ),
      )
    const reserved = [...this.residents.entries()]
      .filter(([id]) => id !== agentId)
      .flatMap(([, state]) => (state.target ? [state.target.landing] : []))
    const next = candidates
      .filter(
        (point) =>
          this.free(point.x, point.y) &&
          [...occupied, ...reserved].every(
            (other) => Math.hypot(other.x - point.x, other.y - point.y) >= 26,
          ),
      )
      .map((point) => ({ point, route: this.route(start, point) }))
      .find((choice) => choice.route !== undefined)
    if (!next) return false
    const facing = Math.atan2(caller.x - next.point.x, caller.y - next.point.y)
    resident.transfer =
      Math.hypot(resident.x - start.x, resident.y - start.y) > 0.1
        ? {
            from: { x: resident.x, y: resident.y },
            to: { x: start.x, y: start.y },
            progress: 0,
            leaving: true,
          }
        : undefined
    resident.target = {
      id: `${activity}/${agentId}`,
      activity,
      landing: next.point,
      pose: next.point,
      seated: false,
      dir:
        Math.abs(Math.sin(facing)) > Math.abs(Math.cos(facing))
          ? Math.sin(facing) > 0
            ? 'right'
            : 'left'
          : Math.cos(facing) > 0
            ? 'down'
            : 'up',
      heading: facing,
    }
    resident.route = next.route!
    resident.seated = false
    resident.activity =
      resident.route.length || resident.transfer ? 'walking' : activity
    resident.dwell = 0
    return true
  }

  private hold(
    resident: Resident,
    activity: 'waiting' | 'following',
    caller?: Point,
  ) {
    const seat =
      resident.seated || (!!resident.transfer && !resident.transfer.leaving)
    const landing =
      seat && resident.target
        ? resident.target.landing
        : (resident.transfer?.to ?? { x: resident.x, y: resident.y })
    const pose = seat && resident.target ? resident.target.pose : landing
    resident.route = []
    resident.activity = activity
    const heading = caller
      ? Math.atan2(caller.x - resident.x, caller.y - resident.y)
      : resident.heading
    resident.target = {
      id: `hold/${activity}`,
      activity,
      landing,
      pose,
      seated: seat,
      dir: resident.dir,
      heading,
    }
  }

  follow(
    floor: Floor,
    agentId: string,
    caller: Point,
    heading = 0,
    occupied: readonly Point[] = [],
  ): boolean {
    const resident = this.residents.get(agentId)
    if (
      this.lastFloor !== floor ||
      !resident ||
      !Number.isFinite(caller.x + caller.y + heading)
    )
      return false
    resident.behavior = 'following'
    resident.followTimer = 0.4
    if (
      Math.hypot(resident.x - caller.x, resident.y - caller.y) <= 72 &&
      !resident.seated &&
      !resident.transfer
    ) {
      this.hold(resident, 'following', caller)
      resident.blocked = false
    } else {
      resident.blocked = !this.approach(
        floor,
        agentId,
        caller,
        heading + Math.PI,
        occupied,
        'following',
      )
      if (resident.blocked) this.hold(resident, 'following', caller)
    }
    return true
  }

  stop(agentId: string): boolean {
    const resident = this.residents.get(agentId)
    if (!resident) return false
    resident.behavior = 'waiting'
    resident.blocked = false
    this.hold(resident, 'waiting')
    return true
  }

  resume(agent: OfficeLifeActor): boolean {
    const resident = this.residents.get(agent.id)
    if (!resident) return false
    const ok = this.choose(resident, agent, false, true)
    resident.behavior = ok ? 'autonomous' : 'waiting'
    resident.blocked = false
    if (!ok) this.hold(resident, 'waiting')
    return ok
  }

  step<A extends OfficeLifeActor>(
    floor: Floor,
    actors: readonly A[],
    seconds: number,
    options: {
      pausedId?: string
      enabled?: boolean
      caller?: Point & { heading?: number }
    } = {},
  ): A[] {
    const changed = this.configure(floor),
      dt = Math.min(0.1, Math.max(0, seconds))
    const live = new Set(actors.map((actor) => actor.id)),
      snapshots = lifeByProject.get(this.projectId)!
    // 나를 뺀 사람들의 지금 자리: 걷는 사람, 앉거나 서 있는 사람, 제자리 배우(비비), 나(caller)
    const neighbors = (selfId: string): Point[] => [
      ...[...this.residents.entries()]
        .filter(([id]) => id !== selfId)
        .map(([, state]) => state),
      ...actors.filter((other) => other.stationary && other.id !== selfId),
      ...(options.caller ? [options.caller] : []),
    ]
    for (const id of this.residents.keys())
      if (!live.has(id)) {
        this.residents.delete(id)
        snapshots.delete(id)
      }
    return actors.map((actor) => {
      if (actor.stationary || options.enabled === false) {
        snapshots.delete(actor.id)
        return actor
      }
      let resident = this.residents.get(actor.id)
      // The server temporarily marks conversational agents as talking; retain the
      // actual job state until the restored status arrives.
      const working =
        actor.status === 'talking' && resident
          ? resident.working
          : isOfficeWorkingStatus(actor.status)
      const homeKey = `${actor.x}/${actor.y}/${actor.dir}`
      if (!resident) {
        const spawn = this.nearestLanding(actor) ?? actor
        resident = {
          ...spawn,
          dir: actor.dir,
          working,
          homeKey,
          route: [],
          activity: working ? 'working' : 'walking',
          seated: false,
          dwell: 0,
          visits: 0,
        }
        this.residents.set(actor.id, resident)
        this.choose(resident, actor, false)
      } else if (
        changed ||
        resident.working !== working ||
        (working && resident.homeKey !== homeKey)
      ) {
        const completed = resident.working && !working
        resident.working = working
        resident.homeKey = homeKey
        const position = { x: resident.x, y: resident.y }
        const stillOnCushion =
          resident.target?.activity === 'resting' &&
          this.destinations.some(
            (target) =>
              target.activity === 'resting' &&
              Math.hypot(
                target.pose.x - position.x,
                target.pose.y - position.y,
              ) < 2,
          )
        if (changed && !stillOnCushion && !this.free(resident.x, resident.y)) {
          resident.seated = false
          resident.transfer = undefined
          resident.target = undefined
          Object.assign(resident, this.nearestLanding(resident))
        }
        if (!resident.behavior || resident.behavior === 'autonomous')
          this.choose(resident, actor, completed)
        else if (changed) {
          resident.route = []
          resident.followTimer = 0
          this.hold(
            resident,
            resident.behavior === 'following' ? 'following' : 'waiting',
          )
        }
      }
      if (resident.behavior === 'following' && options.caller) {
        const caller = options.caller,
          distance = Math.hypot(caller.x - resident.x, caller.y - resident.y)
        resident.followTimer = Math.max(0, (resident.followTimer ?? 0) - dt)
        if (distance <= 52 && !resident.seated && !resident.transfer) {
          this.hold(resident, 'following', caller)
          resident.blocked = false
        } else if (
          resident.followTimer === 0 &&
          !resident.transfer &&
          (distance > 80 || resident.blocked)
        ) {
          const occupied = actors
            .filter((other) => other.id !== actor.id)
            .map((other) => this.residents.get(other.id) ?? other)
          resident.blocked = !this.approach(
            floor,
            actor.id,
            caller,
            (caller.heading ?? 0) + Math.PI,
            occupied,
            'following',
          )
          resident.followTimer = resident.blocked ? 1 : 0.4
          if (resident.blocked) this.hold(resident, 'following', caller)
        } else if (!resident.route.length && !resident.transfer)
          this.hold(resident, 'following', caller)
      }
      const before = { x: resident.x, y: resident.y },
        paused =
          options.pausedId === actor.id &&
          !['visiting', 'following', 'waiting'].includes(
            resident.behavior ?? 'autonomous',
          )
      if (!paused) {
        if (resident.transfer) {
          const transfer = resident.transfer
          transfer.progress = Math.min(1, transfer.progress + dt / 0.45)
          const t =
            transfer.progress * transfer.progress * (3 - 2 * transfer.progress)
          resident.x = transfer.from.x + (transfer.to.x - transfer.from.x) * t
          resident.y = transfer.from.y + (transfer.to.y - transfer.from.y) * t
          if (transfer.progress === 1) {
            resident.transfer = undefined
            if (!transfer.leaving) resident.seated = true
          }
        } else if (resident.route.length) {
          const next = resident.route[0],
            distance = Math.hypot(next.x - resident.x, next.y - resident.y),
            step = Math.min(
              (resident.behavior === 'following' ? 132 : 62) * dt,
              distance,
            )
          if (distance < 0.2) resident.route.shift()
          else {
            // 곁의 사람에게서 밀려나는 힘을 더해 비켜 걷고, 다른 사람에게 너무 붙는 걸음은 내딛지 않는다.
            const others = neighbors(actor.id)
            let vx = ((next.x - resident.x) / distance) * step,
              vy = ((next.y - resident.y) / distance) * step
            for (const other of others) {
              const ox = resident.x - other.x,
                oy = resident.y - other.y,
                gap = Math.hypot(ox, oy)
              if (gap < 0.01 || gap >= AVOID_RADIUS) continue
              const push = ((AVOID_RADIUS - gap) / AVOID_RADIUS) * step * 1.4
              vx += (ox / gap) * push
              vy += (oy / gap) * push
            }
            const length = Math.hypot(vx, vy)
            if (length > step) {
              vx = (vx / length) * step
              vy = (vy / length) * step
            }
            const moved = moveContinuous(resident, { x: vx, y: vy }, this.free)
            // 벽에 막혀 한 걸음도 못 나간 것도 막힌 것으로 센다(옆걸음 뒤 지름길이 벽을 지날 때).
            if (
              keepsSpace(resident, moved, others) &&
              Math.hypot(moved.x - resident.x, moved.y - resident.y) > 0.001
            ) {
              Object.assign(resident, moved)
              resident.stuck = 0
            } else {
              const stuckBefore = resident.stuck ?? 0
              resident.stuck = stuckBefore + dt
              // 막히면 오른쪽, 왼쪽 순서로 옆걸음을 해 본다. 마주 오는 두 사람이 서로 오른쪽으로 비켜 지나간다.
              // 같은 정적 경로를 다시 찾기만 하면 서로 마주 선 채 멈췄다(PR #72 전수 리뷰 THREE-01).
              const ux = (next.x - resident.x) / distance,
                uy = (next.y - resident.y) / distance
              const sidestep = [1, -1]
                .map((side) =>
                  moveContinuous(
                    resident,
                    { x: -uy * step * side, y: ux * step * side },
                    this.free,
                  ),
                )
                .find(
                  (point) =>
                    Math.hypot(point.x - resident.x, point.y - resident.y) >
                      0.001 && keepsSpace(resident, point, others),
                )
              if (sidestep) Object.assign(resident, sidestep)
              // 오래 막힐 때마다 막고 있는 사람의 칸을 피해 같은 목적지로 길을 다시 찾는다. 돌아갈 길이 없으면
              // 쉬러 다니는 사람은 다른 곳으로 간다. 일하러 가는 사람은 비켜 줄 때까지 기다린다.
              if (
                Math.floor(stuckBefore / STUCK_SECONDS) <
                  Math.floor(resident.stuck / STUCK_SECONDS) &&
                resident.target
              ) {
                const blockers = others.filter(
                  (other) =>
                    Math.hypot(other.x - resident.x, other.y - resident.y) <
                    AVOID_RADIUS,
                )
                // 길을 막고 서서 쉬는 사람은 쉬기를 끝내고 다른 곳으로 비켜 준다.
                for (const [id, other] of this.residents)
                  if (
                    id !== actor.id &&
                    !other.route.length &&
                    !other.transfer &&
                    !other.working &&
                    !other.seated &&
                    (!other.behavior || other.behavior === 'autonomous') &&
                    Math.hypot(other.x - resident.x, other.y - resident.y) <
                      AVOID_RADIUS
                  )
                    other.dwell = 0
                const detour = this.route(
                  resident,
                  resident.target.landing,
                  blockers,
                )
                if (detour) resident.route = detour
                else if (
                  !resident.working &&
                  (!resident.behavior || resident.behavior === 'autonomous')
                )
                  this.choose(resident, actor, false)
              }
            }
          }
          if (!resident.route.length && resident.target) {
            const target = resident.target
            resident.activity = target.activity
            resident.dir = target.dir
            resident.heading = target.heading
            resident.dwell = 11 + (hash(`${actor.id}/${resident.visits}`) % 10)
            if (
              target.seated &&
              Math.hypot(
                target.pose.x - resident.x,
                target.pose.y - resident.y,
              ) > 0.2
            )
              resident.transfer = {
                from: { x: resident.x, y: resident.y },
                to: target.pose,
                progress: 0,
                leaving: false,
              }
            else resident.seated = target.seated
          }
        } else if (
          !resident.working &&
          (!resident.behavior || resident.behavior === 'autonomous')
        ) {
          resident.dwell -= dt
          if (resident.dwell <= 0) this.choose(resident, actor, false)
        }
      }
      const dx = resident.x - before.x,
        dy = resident.y - before.y,
        moving = !paused && Math.hypot(dx, dy) > 0.001
      if (moving) {
        resident.dir =
          Math.abs(dx) > Math.abs(dy)
            ? dx > 0
              ? 'right'
              : 'left'
            : dy > 0
              ? 'down'
              : 'up'
        resident.heading = Math.atan2(dx, dy)
      }
      if (!resident.route.length && !resident.transfer && resident.target) {
        resident.dir = resident.target.dir
        resident.heading = resident.target.heading
      }
      const activity = resident.transfer
        ? resident.transfer.leaving
          ? 'walking'
          : (resident.target?.activity ?? resident.activity)
        : resident.activity
      const destination = resident.target?.activity
      const workLabel =
        actor.status === 'meeting'
          ? '회의에 참여하는 중'
          : actor.status === 'briefing'
            ? '업무 설명을 듣는 중'
            : LABELS.working
      const workWalkLabel =
        actor.status === 'meeting'
          ? '회의 자리로 가는 중'
          : actor.status === 'briefing'
            ? '업무 설명을 들으러 가는 중'
            : WALK_LABELS.working
      const snapshot = {
        activity,
        destination: activity === 'walking' ? destination : undefined,
        moving,
        behavior: resident.behavior ?? 'autonomous',
        blocked: !!resident.blocked,
        label: resident.blocked
          ? '길이 막혀 기다리는 중'
          : activity === 'walking'
            ? destination === 'working'
              ? workWalkLabel
              : WALK_LABELS[destination ?? 'resting']
            : activity === 'working'
              ? workLabel
              : LABELS[activity],
      }
      const previous = snapshots.get(actor.id)
      if (
        !previous ||
        previous.activity !== snapshot.activity ||
        previous.destination !== snapshot.destination ||
        previous.moving !== moving ||
        previous.label !== snapshot.label ||
        previous.behavior !== snapshot.behavior ||
        previous.blocked !== snapshot.blocked
      )
        snapshots.set(actor.id, snapshot)
      return {
        ...actor,
        x: resident.x,
        y: resident.y,
        dir: resident.dir,
        heading: resident.heading,
        moving,
        seated: resident.seated,
        seatLift: resident.target?.activity === 'resting' ? 0.2 : 0.08,
        activity,
      }
    })
  }

  dispose() {
    this.residents.clear()
    lifeByProject.delete(this.projectId)
  }
}
