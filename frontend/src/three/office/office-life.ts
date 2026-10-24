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
}

/** Stateful positions survive snapshots; routes and collisions use the same floor as the player. */
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

  private route(from: Point, to: Point): Point[] | undefined {
    if (!this.free(to.x, to.y)) return undefined
    const tiles = findPath(
      this.blocked,
      Math.floor(from.x / T),
      Math.floor((from.y - 4) / T),
      Math.floor(to.x / T),
      Math.floor((to.y - 4) / T),
    )
    const route = smoothRoute(
      from,
      [...tiles.map(([x, y]) => feet(x, y)), to],
      this.free,
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
      const reserved = new Set(
        [...this.residents.entries()]
          .filter(([id]) => id !== actor.id)
          .map(([, state]) => state.target?.id),
      )
      choices = this.destinations
        .filter(
          (target) =>
            !reserved.has(target.id) && target.id !== resident.target?.id,
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
          else
            Object.assign(
              resident,
              moveContinuous(
                resident,
                {
                  x: ((next.x - resident.x) / distance) * step,
                  y: ((next.y - resident.y) / distance) * step,
                },
                this.free,
              ),
            )
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
