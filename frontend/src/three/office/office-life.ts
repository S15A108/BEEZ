import type { Dir, Floor } from './floors'
import { T } from './floors'
import { clearSegment, moveContinuous, type Point } from './motion'
import {
  navChairAt,
  navClear,
  BODY_RADIUS,
  navNearest,
  navOf,
  navReachable,
  navRoute,
  STAND_CLEARANCE,
  type Nav,
} from './office-nav'
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
  | 'strolling'
  | 'dropby'
  | 'home'
  | 'pause'
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
  /** 집 자리(x, y)에 앉지 않고 서 있는다(D37: 할 일 없는 에이전트가 주인 자리 뒤에 선다) */
  standing?: boolean
  /** 할 일이 없을 때 다니는 곳(D37). 'stroll'은 가구를 쓰지 않고 빈 바닥만 걷다 멈춘다. 없으면 소파·창가·음료대를 오간다. */
  idle?: 'stroll'
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
  strolling: '잠깐 멈춰 둘러보는 중',
  dropby: '동료 자리에 들른 중',
  home: '자기 자리 곁에 있는 중',
  pause: '길을 비켜 잠깐 서 있는 중',
}
const WALK_LABELS: Record<OfficeLifeDestination, string> = {
  working: '작업하러 책상으로 가는 중',
  resting: '쉬러 소파로 가는 중',
  window: '창가로 산책하는 중',
  coffee: '차 한 잔 마시러 가는 중',
  visiting: '부른 사람에게 가는 중',
  following: '사용자를 따라가는 중',
  waiting: '멈출 자리로 이동하는 중',
  strolling: '사무실을 돌아다니는 중',
  dropby: '동료 자리에 들르는 중',
  home: '자기 자리로 돌아가는 중',
  pause: '길을 비켜 주는 중',
}
// 사람끼리 지킬 거리(px, 1타일 = 32). 로봇 팀원 머리 지름(1.1타일, 약 35px)에 맞춘다.
export const PERSONAL_SPACE = 34
// 걷는 사람이 다른 사람에게 다가갈 수 있는 거리. 사람 폭(약 1.13타일, 36px)보다 넓혀 지나칠 때 어깨가 겹쳐 보이지 않게 한다.
// 자리 간격과 서 있는 자리 규칙은 PERSONAL_SPACE 그대로다.
const WALK_SPACE = 38
// 걷는 사람과 의자에 앉은 사람 사이(의자 반 폭 7px + 몸 반지름 16px + 1px)
export const SEATED_SPACE = 24
// 이 거리 안의 사람에게서 조금씩 비켜 걷는다.
const AVOID_RADIUS = 50
// 할 일 없이 다니는 사람(팀원·대기 중인 에이전트) 중 한 번에 걷는 사람 수. 여럿이 함께 걸으면 산만하다.
const MAX_IDLE_WALKERS = 2
// 걷는 빠르기(px/초). 할 일 없이 다닐 때는 천천히(1.25 m/s), 일하러 갈 때는 조금 빨리 걷는다.
const IDLE_SPEED = 40
const WORK_SPEED = 55
// 출발·멈춤 가속도(px/초²)와 도는 빠르기(라디안/초). 서서 먼저 돌고 나서 걷는다.
const ACCEL = 60
const TURN_RATE = 4
const WALK_TURN_RATE = 6
// 이만큼 못 나아가면 지금 자리에서 길을 다시 찾는다(초).
const STUCK_SECONDS = 1.2
// 돌아갈 길 없이 막혀 서 있는 사람이 길을 다시 찾기까지 기다리는 시간(초)
const BLOCKED_RETRY_SECONDS = 5
const hash = (id: string) =>
  [...id].reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 7)
const feet = (x: number, y: number): Point => ({ x: x * T + 16, y: y * T + 27 })
// 정수 하나를 고르게 흩는다. hash는 끝 글자만 다른 문자열의 순서가 거의 같아 섞는 데 쓰지 않는다.
const mix = (value: number) => {
  value = Math.imul(value ^ (value >>> 16), 0x45d9f3b)
  value = Math.imul(value ^ (value >>> 16), 0x45d9f3b)
  return (value ^ (value >>> 16)) >>> 0
}

export interface Destination {
  id: string
  activity: OfficeLifeDestination
  landing: Point
  pose: Point
  dir: Dir
  heading?: number
  seated: boolean
  /** 앉을 의자 번호(office-nav). 이 의자에 앉으러 가는 사람만 그 의자 곁을 지난다. */
  chair?: number
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
  /** 다음 지점까지 남은 거리 중 가장 가까웠던 값. 실제로 나아가는지 본다. */
  progress?: { to: Point; left: number }
  /** 할 일이 없을 때 다니는 방식(OfficeLifeActor.idle) */
  idle?: OfficeLifeActor['idle']
  /** 처음 선 자리(자기 자리 곁). 돌아다니다 돌아온다. */
  home?: Point
  /** 지금 걷는 빠르기(px/초). 출발할 때 천천히 올리고 멈출 곳 앞에서 줄인다. */
  speed: number
  /** 새 길을 받으면 서서 그쪽으로 돈 뒤에 걷는다. */
  turning?: boolean
  /** 길을 비켜 서기 전에 가던 곳. 비켜 선 뒤 다시 간다. */
  resume?: Destination
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

// 돌아다니다 멈출 자리는 지금 자리에서 3칸 넘게 떨어진 곳이다. 바로 옆 칸만 오가면 제자리처럼 보인다.
const STROLL_MIN = 3 * T

/**
 * 돌아다닐 다음 자리 후보(D37). 같은 seed면 늘 같은 순서로 섞는다. 지금 자리에서 가까운 곳과, 누가 서 있거나 가기로 한
 * 자리 곁(개인 거리의 1.5배 안)은 뺀다.
 */
export function strollCandidates(
  spots: readonly Point[],
  from: Point,
  seed: number,
  avoid: readonly Point[] = [],
): Point[] {
  return spots
    .filter(
      (spot) =>
        Math.hypot(spot.x - from.x, spot.y - from.y) > STROLL_MIN &&
        avoid.every(
          (other) =>
            Math.hypot(spot.x - other.x, spot.y - other.y) >=
            PERSONAL_SPACE * 1.5,
        ),
    )
    .map((spot, index) => ({ spot, order: mix(mix(seed) + index) }))
    .sort((a, b) => a.order - b.order)
    .map(({ spot }) => spot)
}

/**
 * 막고 선 사람들에게서 한 걸음(20~40px) 물러설 자리. 열여섯 방향 중 벽 없이 곧장 갈 수 있고 가장 가까운 사람과 가장
 * 멀어지는 곳이고, 어디로 가도 지금보다 멀어지지 않으면 없다. 구석에서는 짧게 물러선다.
 */
export function retreatSpot(
  from: Point,
  blockers: readonly Point[],
  free: (x: number, y: number) => boolean,
): Point | undefined {
  const gap = (point: Point) =>
    Math.min(
      ...blockers.map((other) =>
        Math.hypot(point.x - other.x, point.y - other.y),
      ),
    )
  let best: Point | undefined,
    score = gap(from)
  for (const length of [40, 30, 20])
    for (let i = 0; i < 16; i++) {
      const angle = (i * Math.PI) / 8,
        point = {
          x: from.x + Math.cos(angle) * length,
          y: from.y + Math.sin(angle) * length,
        }
      if (clearSegment(from, point, free) && gap(point) > score + 0.5) {
        best = point
        score = gap(point)
      }
    }
  return best
}

const dirOf = (dx: number, dy: number): Dir =>
  Math.abs(dx) > Math.abs(dy)
    ? dx > 0
      ? 'right'
      : 'left'
    : dy > 0
      ? 'down'
      : 'up'

const DIR_HEADING: Record<Dir, number> = {
  down: 0,
  right: Math.PI / 2,
  up: Math.PI,
  left: -Math.PI / 2,
}
const headingDir = (heading: number): Dir =>
  dirOf(Math.sin(heading), Math.cos(heading))
/** from에서 to로 최대 max(라디안)만큼 돈다. */
export const turnToward = (from: number, to: number, max: number) => {
  const diff = Math.atan2(Math.sin(to - from), Math.cos(to - from))
  return Math.abs(diff) <= max ? to : from + Math.sign(diff) * max
}

/** 쉬는 자리(소파·리클라이너·창가·음료)와 할 일 없이 다니는 사람이 들르는 곳(창가·음료·동료 자리 곁). 모두 몸이 가구에 닿지 않는 곳이다. */
export function officeLifeSpots(floor: Floor): {
  destinations: Destination[]
  visits: Destination[]
} {
  const nav = navOf(floor)
  const blocked = floor.blocked.map((row, y) =>
    row.map(
      (cell, x) =>
        cell ||
        floor.grid[y][x] === '#' ||
        floor.grid[y][x] === 'g' ||
        floor.grid[y][x] === 'o',
    ),
  )
  const destinations: Destination[] = officeSeats({ ...floor, blocked })
    .filter((seat) => seat.kind === 'sofa' || seat.kind === 'bench')
    .flatMap((seat): Destination[] => {
      // 라운지 리클라이너는 의자라 앉는 사람만 그 곁을 지난다. 소파·벤치는 앞에서 올라앉는다.
      const chair = navChairAt(nav, seat)
      const landing = navNearest(nav, seatApproach(seat), chair)
      return landing
        ? [
            {
              id: seat.id,
              activity: 'resting',
              landing,
              pose: { x: seat.x, y: seat.y },
              dir: seat.facing,
              seated: true,
              chair,
            },
          ]
        : []
    })
  const visits: Destination[] = []
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
    const landing = navNearest(nav, { x: x * T, y: 1.7 * T })
    if (
      landing &&
      landing.y < 4 * T &&
      ![...destinations, ...visits].some(
        (target) =>
          Math.hypot(
            target.landing.x - landing.x,
            target.landing.y - landing.y,
          ) < 24,
      )
    ) {
      const spot: Destination = {
        id: `window/${index}`,
        activity: 'window',
        landing,
        pose: landing,
        dir: 'up',
        seated: false,
      }
      destinations.push(spot)
      visits.push(spot)
    }
  }
  for (const [index, item] of floor.furniture.entries()) {
    // 음료대(옛 배치)와 고정 사무실의 커피 머신·주방 바 앞에 서서 차를 마신다.
    const model = item.kit?.model ?? ''
    if (
      item.kind !== 'cooler' &&
      item.kind !== 'coffee' &&
      item.kind !== 'vending' &&
      !/^kitchen(CoffeeMachine|Bar|Cabinet)$/.test(model)
    )
      continue
    const front = item.kit
      ? {
          x:
            (item.x + item.w / 2) * T +
            Math.sin(item.kit.ry) * (item.kit.d / 2 + 0.7) * T,
          y:
            (item.y + item.h / 2) * T +
            Math.cos(item.kit.ry) * (item.kit.d / 2 + 0.7) * T,
        }
      : {
          x: (item.x + item.w + 0.55) * T,
          y: (item.y + item.h / 2 + 0.2) * T,
        }
    const landing = navNearest(nav, front)
    if (!landing) continue
    const heading = Math.atan2(
      (item.x + item.w / 2) * T - landing.x,
      (item.y + item.h / 2) * T - landing.y,
    )
    const spot: Destination = {
      id: `coffee/${index}`,
      activity: 'coffee',
      landing,
      pose: landing,
      dir: headingDir(heading),
      heading,
      seated: false,
    }
    destinations.push(spot)
    visits.push(spot)
  }
  // 동료 자리 곁: 작업 의자 뒤 1.1칸에 서서 의자 쪽을 본다. 책상 사이 통로라, 서 있는 사람 곁을 다른 사람이 지나갈
  // 만큼 넓은 곳만 쓴다(STAND_CLEARANCE).
  for (const [index, seat] of [
    ...(floor.seats.dev ?? []),
    ...(floor.seats.humandesk ?? []),
  ].entries()) {
    const chair = feet(seat.x, seat.y)
    const back = DIR_HEADING[seat.facing] + Math.PI
    const landing = navNearest(nav, {
      x: chair.x + Math.sin(back) * 1.1 * T,
      y: chair.y + Math.cos(back) * 1.1 * T,
    })
    if (!landing || !navClear(nav, landing.x, landing.y, -1, STAND_CLEARANCE))
      continue
    const heading = Math.atan2(chair.x - landing.x, chair.y - landing.y)
    visits.push({
      id: `dropby/${index}`,
      activity: 'dropby',
      landing,
      pose: landing,
      dir: headingDir(heading),
      heading,
      seated: false,
    })
  }
  // 비비가 서는 세 자리(D35) 곁에는 들르지 않는다.
  const bibi = [
    floor.seats.lead?.[0],
    floor.bibiSpots?.documents,
    floor.bibiSpots?.meeting,
  ].flatMap((spot) => (spot ? [feet(spot.x, spot.y)] : []))
  // 가구에 둘러싸여 갈 수 없는 구석(회의실 테이블 너머 창가 등)은 뺀다. 방 안 어디서든 엘리베이터 앞에서 가는 길이 있어야 한다.
  const entry =
    floor.outdoorExit?.landing ??
    navNearest(nav, feet(floor.exit.x, floor.exit.y))
  const reachable = (target: Destination) =>
    !entry || !!navRoute(nav, entry, target.landing, { exempt: target.chair })
  return {
    destinations: destinations.filter(reachable),
    visits: visits.filter(
      (target) =>
        reachable(target) &&
        bibi.every(
          (spot) =>
            Math.hypot(spot.x - target.landing.x, spot.y - target.landing.y) >=
            PERSONAL_SPACE * 1.5,
        ),
    ),
  }
}

export class OfficeLifeSimulation {
  private residents = new Map<string, Resident>()
  private lastFloor?: Floor
  private floorKey = ''
  private destinations: Destination[] = []
  private nav!: Nav
  /** 할 일 없이 다니는 사람이 들르는 곳: 창가, 동료 자리 곁, 음료·주방 앞 */
  private visits: Destination[] = []
  /** 이번 걸음의 제자리 배우(연결 전 에이전트 등)와 나. 돌아다닐 자리를 고를 때 피한다. */
  private fixed: Point[] = []

  private readonly projectId: string
  constructor(projectId: string) {
    this.projectId = projectId
    lifeByProject.set(projectId, new Map())
  }

  /** 몸이 벽·가구·의자에 닿지 않는 곳인지(office-nav). chair 번호의 의자는 지나갈 수 있다. */
  private free = (x: number, y: number, chair = -1) =>
    navClear(this.nav, x, y, chair)

  private nearestLanding(point: Point, chair = -1): Point | undefined {
    return navNearest(this.nav, point, chair)
  }

  private configure(floor: Floor): boolean {
    if (this.lastFloor === floor) return false
    this.lastFloor = floor
    const key = `${floor.staticKey ?? ''}/${floor.W}/${floor.H}/${JSON.stringify(floor.furniture)}/${floor.grid.map((row) => row.join('')).join('')}`
    if (this.floorKey === key) return false
    this.floorKey = key
    this.nav = navOf(floor)
    const spots = officeLifeSpots(floor)
    this.destinations = spots.destinations
    this.visits = spots.visits
    return true
  }

  // avoid: 다른 사람이 서 있는 자리. 그 둘레를 피해 길을 찾는다(막혔을 때 돌아가는 길).
  private route(
    from: Point,
    to: Point,
    avoid: readonly (Point & { space?: number })[] = [],
    chair = -1,
  ): Point[] | undefined {
    return navRoute(this.nav, from, to, { avoid, exempt: chair })
  }

  /**
   * 걷는 사람이 other에게 다가갈 수 있는 거리. 걷는 사람끼리는 사람 폭보다 넓게(38px), 서 있는 사람과는 사람 사이
   * 거리(34px), 의자에 앉은 사람과는 의자 반 폭과 몸 반지름만큼(24px) 떨어진다. 앉은 사람은 의자가 막아 주고,
   * 34px를 지키면 회의실 스툴 뒤 통로를 아무도 지나지 못한다(D35: 자리가 다 차도 드나든다).
   */
  private spaceTo(other: Point): number {
    const seated =
      ('seated' in other && !!other.seated) ||
      ('stationary' in other &&
        !!other.stationary &&
        navChairAt(this.nav, other) >= 0)
    if (seated) return SEATED_SPACE
    // 걷는 사람끼리만 넓게 떨어지고, 서 있는 사람 곁은 사람 사이 거리로 지나간다.
    return 'route' in other &&
      Array.isArray(other.route) &&
      other.route.length > 0
      ? WALK_SPACE
      : PERSONAL_SPACE
  }

  /**
   * point에서 가장 가까운, 몸 둘레 r(px) 안에 장애물이 없고 엘리베이터 앞에서 걸어갈 수 있으며 avoid의 모든 점에서 gap(px)
   * 넘게 떨어진 곳. 곁(5칸 안)에서 못 찾으면 없다.
   */
  private spotNear(
    point: Point,
    r: number,
    avoid: readonly Point[],
    gap: number,
  ): Point | undefined {
    const ok = (x: number, y: number) =>
      navClear(this.nav, x, y, -1, r) &&
      navReachable(this.nav, { x, y }) &&
      avoid.every((other) => Math.hypot(other.x - x, other.y - y) >= gap)
    if (ok(point.x, point.y)) return { x: point.x, y: point.y }
    for (let radius = 4; radius <= 5 * T; radius += 4)
      for (let i = 0; i < 32; i++) {
        const angle = (i * Math.PI) / 16,
          x = point.x + Math.cos(angle) * radius,
          y = point.y + Math.sin(angle) * radius
        if (ok(x, y)) return { x, y }
      }
    return undefined
  }

  /**
   * walker에게 길을 비켜 줄 자리. 그 사람에게서 지금보다 멀고, 다른 사람이 곁을 지나갈 만큼 넓은 곳(5칸 안)이다. 한
   * 걸음만 물러서면 문간이나 통로에 다시 섰다. 그런 곳이 없으면 한 걸음 물러선다.
   */
  private makeWay(
    other: Resident,
    id: string,
    walker: Point,
  ): Point | undefined {
    const now = Math.hypot(other.x - walker.x, other.y - walker.y)
    const people = [...this.residents.entries()]
      .filter(([key]) => key !== id)
      .map(([, state]) => state)
    const chair = other.target?.chair ?? -1
    for (let radius = 24; radius <= 5 * T; radius += 8)
      for (let i = 0; i < 24; i++) {
        const angle = (i * Math.PI) / 12,
          spot = {
            x: other.x + Math.cos(angle) * radius,
            y: other.y + Math.sin(angle) * radius,
          }
        if (
          Math.hypot(spot.x - walker.x, spot.y - walker.y) > now + 16 &&
          navClear(this.nav, spot.x, spot.y, -1, STAND_CLEARANCE) &&
          people.every(
            (person) =>
              Math.hypot(person.x - spot.x, person.y - spot.y) >=
              PERSONAL_SPACE,
          ) &&
          this.route(
            other,
            spot,
            [{ x: walker.x, y: walker.y, space: PERSONAL_SPACE }],
            chair,
          )
        )
          return spot
      }
    return retreatSpot(other, [walker], (x, y) => this.free(x, y, chair))
  }

  /** 길을 비켜 잠깐(3~6초) 선다. step이 있으면 그리로 한 걸음 물러선 뒤 선다. 그 뒤 갈 곳을 새로 고른다. */
  private pause(resident: Resident, id: string, step?: Point) {
    const at = step ?? { x: resident.x, y: resident.y }
    if (resident.target && resident.target.activity !== 'pause')
      resident.resume = resident.target
    // 비켜 설 자리가 멀면 곁의 사람을 피해 길을 따라간다.
    const people = [...this.residents.values()].filter(
      (other) =>
        other !== resident &&
        Math.hypot(other.x - resident.x, other.y - resident.y) < AVOID_RADIUS,
    )
    resident.route = step
      ? Math.hypot(step.x - resident.x, step.y - resident.y) > 40
        ? (this.route(
            resident,
            step,
            people.map((other) => ({
              x: other.x,
              y: other.y,
              space: PERSONAL_SPACE,
            })),
            resident.target?.chair ?? -1,
          ) ?? [step])
        : [step]
      : []
    resident.target = {
      id: `pause/${id}`,
      activity: 'pause',
      landing: at,
      pose: at,
      dir: resident.dir,
      heading: resident.heading,
      seated: false,
    }
    resident.activity = step ? 'walking' : 'pause'
    resident.dwell = 3 + (mix(hash(id) + resident.visits) % 4)
    resident.speed = 0
    resident.turning = !!step
    resident.stuck = 0
  }

  /** 할 일 없이 다니는 사람 중 지금 걷고 있는 사람 수(self 제외) */
  private idleWalkers(selfId: string): number {
    return [...this.residents.entries()].filter(
      ([id, other]) =>
        id !== selfId &&
        !!other.idle &&
        !other.working &&
        other.target?.activity !== 'pause' &&
        (other.route.length > 0 || !!other.transfer),
    ).length
  }

  private choose(
    resident: Resident,
    actor: OfficeLifeActor,
    completed: boolean,
    homeOnly = false,
  ): boolean {
    // 앉는 자리는 의자 번호를 알아 두고, 곁의 빈 곳까지 걸어가 자리로 옮겨 앉는다.
    const seat = actor.standing ? -1 : navChairAt(this.nav, actor)
    const home = this.nearestLanding(actor, seat)
    let choices: Destination[]
    if (resident.working || homeOnly)
      choices = home
        ? [
            {
              id: `desk/${actor.id}`,
              activity:
                resident.working && !actor.standing ? 'working' : 'waiting',
              landing: home,
              pose: actor.standing ? home : { x: actor.x, y: actor.y },
              dir: actor.dir,
              seated: !actor.standing,
              chair: seat,
            },
          ]
        : []
    else if (actor.idle) {
      // 한 번에 걷는 사람은 둘까지다. 자리가 나면 몇 초 뒤 다시 본다.
      if (
        !resident.route.length &&
        this.idleWalkers(actor.id) >= MAX_IDLE_WALKERS
      ) {
        resident.dwell = 2 + (mix(hash(actor.id) + resident.visits) % 4)
        return false
      }
      choices = this.idleChoices(resident, actor)
    } else {
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
      .map((target) => ({
        target,
        route: this.route(start, target.landing, [], target.chair),
      }))
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
    resident.turning = true
    resident.speed = 0
    return true
  }

  /**
   * 할 일 없이 다니는 사람이 갈 곳. 자리를 떠나 창가·동료 자리 곁·음료 앞 중 한 곳에 들렀다가 자기 자리 곁으로 돌아온다.
   * 다른 사람이 지금 선 곳, 가기로 한 곳, 제자리 배우와 나를 피한다. 길찾기는 몇 곳만 해 본다.
   */
  private idleChoices(
    resident: Resident,
    actor: OfficeLifeActor,
  ): Destination[] {
    const home = resident.home && this.nearestLanding(resident.home)
    const homeSpot: Destination[] = home
      ? [
          {
            id: `home/${actor.id}`,
            activity: 'home',
            landing: home,
            pose: home,
            dir: actor.dir,
            heading: DIR_HEADING[actor.dir],
            seated: false,
          },
        ]
      : []
    // 길을 비켜 서느라 멈췄으면 가던 곳으로 다시 간다.
    const resume = resident.resume
    resident.resume = undefined
    if (resume) return [resume, ...homeSpot]
    const away =
      !!home && Math.hypot(resident.x - home.x, resident.y - home.y) > T
    // 자기 자리 곁에 다른 사람이 서 있거나 가는 중이면 다른 곳에 들렀다가 나중에 돌아온다.
    const homeBusy =
      !!home &&
      [...this.residents.entries()].some(
        ([id, other]) =>
          id !== actor.id &&
          [other, other.target?.landing].some(
            (point) =>
              !!point &&
              Math.hypot(point.x - home.x, point.y - home.y) <
                PERSONAL_SPACE * 1.5,
          ),
      )
    if (away && !homeBusy) return homeSpot
    const avoid = [
      ...[...this.residents.entries()]
        .filter(([id]) => id !== actor.id)
        .flatMap(([, state]) =>
          state.target ? [state, state.target.landing] : [state],
        ),
      ...this.fixed,
    ]
    const near = (spot: Point) =>
      !!home && Math.hypot(spot.x - home.x, spot.y - home.y) < 2.5 * T
    const order = strollCandidates(
      this.visits
        .filter((target) => !near(target.landing))
        .map((target) => target.landing),
      resident,
      hash(actor.id) + resident.visits,
      avoid,
    )
    return order
      .slice(0, 6)
      .map((spot) => this.visits.find((target) => target.landing === spot)!)
      .concat(homeBusy ? [] : homeSpot)
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
    this.fixed = [
      ...actors.filter((other) => other.stationary),
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
        // 가구 사이에 갇힌 틈에서 시작하지 않고(엘리베이터 앞에서 걸어갈 수 있는 곳), 먼저 선 사람과 겹치지 않는다.
        const others = [
          ...this.residents.values(),
          ...actors.filter((other) => other.stationary),
        ]
        // 일하는 사람은 자기 자리 곁에서 시작한다(곧 앉는다). 갇힌 틈에서 옮길 때는 두 칸 안에서만 옮긴다. 더 먼 곳은
        // 벽 너머일 수 있다.
        const near = (point?: Point) =>
          point && Math.hypot(point.x - actor.x, point.y - actor.y) <= 2 * T
            ? point
            : undefined
        const direct = near(navNearest(this.nav, actor, -1, undefined, true))
        const spawn =
          (working
            ? direct && Math.hypot(direct.x - actor.x, direct.y - actor.y) < T
              ? direct
              : near(this.spotNear(actor, BODY_RADIUS, others, PERSONAL_SPACE))
            : this.spotNear(actor, BODY_RADIUS, others, PERSONAL_SPACE)) ??
          direct ??
          this.nearestLanding(actor) ??
          actor
        resident = {
          ...spawn,
          dir: actor.dir,
          heading: DIR_HEADING[actor.dir],
          working,
          homeKey,
          route: [],
          activity: working ? 'working' : 'walking',
          seated: false,
          dwell: 0,
          visits: 0,
          idle: actor.idle,
          // 돌아와 서 있는 자리도 통로를 막지 않는 넓은 곳이고, 다른 사람이 돌아올 자리와 겹치지 않는다.
          home: actor.idle
            ? (this.spotNear(
                spawn,
                STAND_CLEARANCE,
                [...this.residents.values()].flatMap((other) =>
                  other.home ? [other.home] : [],
                ),
                PERSONAL_SPACE * 1.5,
              ) ?? { x: spawn.x, y: spawn.y })
            : { x: spawn.x, y: spawn.y },
          speed: 0,
        }
        this.residents.set(actor.id, resident)
        // 할 일 없이 다니는 사람은 자기 자리 곁에서 2~20초 서 있다가 하나씩 걷기 시작한다.
        if (!working && actor.idle) {
          resident.activity = 'home'
          resident.dwell = 2 + (mix(hash(actor.id)) % 19)
        } else {
          this.choose(resident, actor, false)
          // 사무실을 처음 열 때 일하는 사람은 이미 자기 자리에 있다(곁에서 옮겨 앉는 걸음을 보이지 않는다).
          const target = resident.target
          if (
            working &&
            target &&
            Math.hypot(target.pose.x - actor.x, target.pose.y - actor.y) < T
          ) {
            resident.x = target.pose.x
            resident.y = target.pose.y
            resident.route = []
            resident.transfer = undefined
            resident.turning = false
            resident.seated = target.seated
            resident.activity = target.activity
            resident.heading = target.heading ?? DIR_HEADING[target.dir]
          }
        }
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
            distance = Math.hypot(next.x - resident.x, next.y - resident.y)
          // 앉으러 가는 의자 곁은 지나갈 수 있다. 배치가 바뀌어 가구 곁에 갇혔으면 가장 가까운 빈 곳(길 첫 지점)까지는
          // 판정 없이 빠져나온다.
          const chair = resident.target?.chair ?? -1
          const trapped = !this.free(resident.x, resident.y, chair)
          const free = (x: number, y: number) =>
            trapped || this.free(x, y, chair)
          const others = neighbors(actor.id)
          const stuckBefore = resident.stuck ?? 0
          const want = Math.atan2(next.x - resident.x, next.y - resident.y)
          if (distance < 0.5) {
            // 길 끝에서는 그 자리에 정확히 선다.
            resident.x = next.x
            resident.y = next.y
            resident.route.shift()
          } else if (resident.turning) {
            // 새 길을 받으면 서서 그쪽으로 다 돈 뒤에 걷는다.
            resident.heading = turnToward(
              resident.heading ?? want,
              want,
              TURN_RATE * dt,
            )
            if (Math.abs(Math.sin((resident.heading - want) / 2)) < 0.08)
              resident.turning = false
          } else {
            const top =
              resident.behavior === 'following'
                ? 132
                : resident.working || !resident.idle
                  ? WORK_SPEED
                  : IDLE_SPEED
            // 멈출 곳(길 끝) 앞에서는 미리 줄여 부드럽게 선다.
            let remaining = distance
            for (let i = 1; i < resident.route.length; i++)
              remaining += Math.hypot(
                resident.route[i].x - resident.route[i - 1].x,
                resident.route[i].y - resident.route[i - 1].y,
              )
            const target = Math.min(top, Math.sqrt(2 * ACCEL * remaining) + 6)
            resident.speed =
              resident.speed < target
                ? Math.min(target, resident.speed + ACCEL * dt)
                : target
            const step = Math.min(resident.speed * dt, distance)
            let vx = ((next.x - resident.x) / distance) * step,
              vy = ((next.y - resident.y) / distance) * step
            // 일하러 가는 사람은 곁의 사람에게서 조금 밀려 비켜 걷는다. 할 일 없이 다니는 사람은 곧게 걷다 막히면 선다.
            if (!resident.idle || resident.working) {
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
            }
            const moved = moveContinuous(resident, { x: vx, y: vy }, free)
            // 곁의 사람을 스치듯 돌아갈 때는 한 걸음마다 아주 조금 가까워진다. 지금 자리에서 향하는 지점까지의 직선이 그
            // 사람에게 지금보다 0.5px 넘게 다가가지 않으면 지나간다(그렇지 않으면 첫 걸음에서 서로 멈춰 섰다).
            const spaced = (point: Point) =>
              others.every((other) => {
                if (keepsSpace(resident!, point, [other], this.spaceTo(other)))
                  return true
                const now = Math.hypot(
                  other.x - resident!.x,
                  other.y - resident!.y,
                )
                const dx = next.x - resident!.x,
                  dy = next.y - resident!.y
                const t = Math.max(
                  0,
                  Math.min(
                    1,
                    ((other.x - resident!.x) * dx +
                      (other.y - resident!.y) * dy) /
                      (dx * dx + dy * dy || 1),
                  ),
                )
                // 이 걸음이 향하는 지점 쪽으로 곧게 가는 걸음일 때만 봐준다(옆걸음·밀려 걷기는 그대로 막는다).
                const sx = point.x - resident!.x,
                  sy = point.y - resident!.y
                const along =
                  (sx * dx + sy * dy) /
                  ((Math.hypot(sx, sy) || 1) * (Math.hypot(dx, dy) || 1))
                return (
                  along > 0.98 &&
                  Math.hypot(
                    other.x - (resident!.x + dx * t),
                    other.y - (resident!.y + dy * t),
                  ) >=
                    now - 0.5
                )
              })
            // 벽에 막혀 한 걸음도 못 나간 것도 막힌 것으로 센다(옆걸음 뒤 지름길이 벽을 지날 때).
            if (
              spaced(moved) &&
              Math.hypot(moved.x - resident.x, moved.y - resident.y) > 0.001
            ) {
              resident.heading = turnToward(
                resident.heading ?? want,
                Math.atan2(moved.x - resident.x, moved.y - resident.y),
                WALK_TURN_RATE * dt,
              )
              Object.assign(resident, moved)
            } else if (!resident.idle || resident.working) {
              // 막히면 오른쪽, 왼쪽 순서로 옆걸음을 해 본다. 마주 오는 두 사람이 서로 오른쪽으로 비켜 지나간다.
              // 같은 정적 경로를 다시 찾기만 하면 서로 마주 선 채 멈췄다(PR #72 전수 리뷰 THREE-01).
              const ux = (next.x - resident.x) / distance,
                uy = (next.y - resident.y) / distance
              const sidestep = [1, -1]
                .map((side) =>
                  moveContinuous(
                    resident!,
                    { x: -uy * step * side, y: ux * step * side },
                    free,
                  ),
                )
                .find(
                  (point) =>
                    Math.hypot(point.x - resident!.x, point.y - resident!.y) >
                      0.001 && spaced(point),
                )
              if (sidestep) Object.assign(resident, sidestep)
              else resident.speed = 0
            } else resident.speed = 0
            // 다음 지점에 실제로 가까워져야 막힘을 푼다. 곁의 사람 앞에서 옆걸음과 앞걸음을 되풀이하면 제자리걸음이다.
            const left = Math.hypot(next.x - resident.x, next.y - resident.y)
            if (
              resident.progress?.to !== next ||
              left < resident.progress.left - 0.5
            ) {
              resident.progress = { to: next, left }
              resident.stuck = 0
            } else resident.stuck = stuckBefore + dt
            // 오래 막힐 때마다 막고 있는 사람의 칸을 피해 같은 목적지로 길을 다시 찾는다. 돌아갈 길이 없으면
            // 쉬러 다니는 사람은 다른 곳으로 간다. 일하러 가는 사람은 제자리걸음 없이 서서 기다리다 다시 찾는다.
            if (
              Math.floor(stuckBefore / STUCK_SECONDS) <
                Math.floor((resident.stuck ?? 0) / STUCK_SECONDS) &&
              resident.target
            ) {
              const blockers = others.filter(
                (other) =>
                  Math.hypot(other.x - resident.x, other.y - resident.y) <
                  AVOID_RADIUS,
              )
              const walkers = new Set<Point>(this.residents.values())
              const yields =
                !resident.working &&
                (!resident.behavior || resident.behavior === 'autonomous') &&
                (blockers.some((other) => !walkers.has(other)) ||
                  [...this.residents].some(
                    ([id, other]) =>
                      id !== actor.id &&
                      Math.hypot(other.x - resident.x, other.y - resident.y) <
                        AVOID_RADIUS &&
                      (other.working || hash(id) > hash(actor.id)),
                  ))
              const detour = this.route(
                resident,
                resident.target.landing,
                blockers.map((other) => ({
                  x: other.x,
                  y: other.y,
                  space: this.spaceTo(other),
                })),
                chair,
              )
              // 할 일 없이 다니는 사람은 막힌 사람을 돌아 앞으로 가는 길이 있으면 그리로 간다. 없으면 되돌아 걷지 않고
              // 그 자리(비켜 줘야 하면 한 걸음 물러난 자리)에서 잠깐 섰다가 갈 곳을 새로 고른다. 물러섰다 곧바로 같은 곳으로
              // 다시 가면 왔다 갔다 했다.
              const forward =
                !!detour?.length &&
                Math.cos(
                  Math.atan2(
                    detour[0].x - resident.x,
                    detour[0].y - resident.y,
                  ) - (resident.heading ?? 0),
                ) > -0.3
              if (
                resident.idle &&
                !resident.working &&
                (!resident.behavior || resident.behavior === 'autonomous')
              ) {
                if (resident.target.activity === 'pause')
                  // 비켜 서러 가던 길이 막히면 그 자리에 선다.
                  resident.route = []
                else if ((resident.stuck ?? 0) > 3 * STUCK_SECONDS) {
                  // 오래 기다리거나 돌아가는 길로도 못 나아가면 기다리기를 접고 잠깐 섰다가 다른 곳을 고른다.
                  this.pause(resident, actor.id)
                  resident.resume = undefined
                } else if (detour && forward) {
                  resident.route = detour
                  resident.turning = true
                } else if (yields) {
                  // 순서가 늦으면 넓은 곳으로 비켜 섰다가 가던 곳으로 다시 간다.
                  const nearest = blockers.reduce((a, b) =>
                    Math.hypot(a.x - resident.x, a.y - resident.y) <=
                    Math.hypot(b.x - resident.x, b.y - resident.y)
                      ? a
                      : b,
                  )
                  this.pause(
                    resident,
                    actor.id,
                    this.makeWay(resident, actor.id, nearest),
                  )
                } else {
                  // 순서가 앞서면 비켜 서지 않고 기다린다. 막고 선 다른 '할 일 없는' 사람에게 넓은 곳으로 비켜 달라고 한다.
                  for (const [id, other] of this.residents)
                    if (
                      id !== actor.id &&
                      other.idle &&
                      !other.working &&
                      !other.seated &&
                      !other.transfer &&
                      other.target?.activity !== 'pause' &&
                      (!other.behavior || other.behavior === 'autonomous') &&
                      Math.hypot(other.x - resident.x, other.y - resident.y) <
                        AVOID_RADIUS
                    )
                      this.pause(other, id, this.makeWay(other, id, resident))
                }
              } else if (detour) resident.route = detour
              else if (
                !resident.behavior ||
                resident.behavior === 'autonomous'
              ) {
                // 돌아갈 길이 없을 때만 곁의 사람에게 비켜 달라고 한다. 먼저 비키게 하면 멈춰 쉬던 사람이 자꾸 일찍 떠났다.
                // 일하러 가는 사람의 길을 막은, 할 일 없이 다니는 사람은 걷던 중이어도 한 걸음 물러섰다가 가던 곳으로 간다.
                if (resident.working)
                  for (const [id, other] of this.residents) {
                    if (
                      id === actor.id ||
                      other.working ||
                      !other.idle ||
                      other.seated ||
                      other.transfer ||
                      (other.behavior && other.behavior !== 'autonomous') ||
                      Math.hypot(other.x - resident.x, other.y - resident.y) >=
                        AVOID_RADIUS
                    )
                      continue
                    this.pause(other, id, this.makeWay(other, id, resident))
                  }
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
                  ) {
                    // 할 일 없이 다니는 사람은 새로 떠나지 않고 한 걸음 비켜 선다(한 번에 걷는 사람 수를 지킨다).
                    if (other.idle)
                      this.pause(other, id, this.makeWay(other, id, resident))
                    else other.dwell = 0
                  }
                // 좁은 문에서 걷는 사람끼리 서로 막으면 어느 쪽도 개인 거리 안으로 내딛지 못해 함께 멈춘다. 돌아다니는
                // 사람이 한 걸음 물러서 길을 내준다. 일하러 가는 사람에게는 늘 비키고, 둘 다 돌아다니면 id 순서가 늦은
                // 쪽만 비켜 둘이 같이 물러섰다 다시 막히지 않는다. 움직이지 않는 사람(서 있는 비비, 충전 중인 에이전트, 나)과
                // 가구 사이에 끼었을 때도 물러선다. 그 사람은 비키지 않는다.
                if (resident.working) {
                  this.hold(resident, 'waiting')
                  resident.stuck = 0
                } else this.choose(resident, actor, false)
              }
            }
          }
          if (!resident.route.length && resident.target) {
            const target = resident.target
            resident.activity = target.activity
            resident.speed = 0
            // 할 일 없이 다니는 사람은 닿은 곳에서 15~40초 머문다. 자주 걸으면 산만하다.
            resident.dwell =
              target.id === 'hold/waiting'
                ? BLOCKED_RETRY_SECONDS
                : target.activity === 'pause'
                  ? 3 + (mix(hash(actor.id) + resident.visits) % 4)
                  : resident.idle && !resident.working
                    ? 15 + (mix(hash(`${actor.id}/${resident.visits}`)) % 26)
                    : 11 + (hash(`${actor.id}/${resident.visits}`) % 10)
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
          // 일하는 사람은 자기 자리에 닿으면 머문다. 막혀 서 있거나 길을 못 찾았으면 다시 찾는다.
          (!resident.working || resident.target?.id !== `desk/${actor.id}`) &&
          (!resident.behavior || resident.behavior === 'autonomous')
        ) {
          resident.dwell -= dt
          if (resident.dwell <= 0) this.choose(resident, actor, false)
        }
      }
      const dx = resident.x - before.x,
        dy = resident.y - before.y,
        moving = !paused && Math.hypot(dx, dy) > 0.001
      // 몸 방향은 한꺼번에 꺾지 않고 돈다. 멈춘 뒤에는 그 자리에서 볼 쪽으로 천천히 돈다.
      if (!resident.route.length && resident.target) {
        const want = resident.target.heading ?? DIR_HEADING[resident.target.dir]
        resident.heading = turnToward(
          resident.heading ?? want,
          want,
          TURN_RATE * dt,
        )
      }
      resident.dir = headingDir(resident.heading ?? DIR_HEADING[resident.dir])
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
