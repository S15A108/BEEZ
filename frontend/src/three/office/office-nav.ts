import type { Floor } from './floors'
import type { Point } from './motion'

// 사무실에서 걷는 판정(D37 부딪힘). 위치는 px(1타일 = 1 m = 32px)이다.
// 칸 단위 막기(D36의 60% 규칙)는 가구가 칸에 반쯤 걸치면 비어 사람이 가구를 지나갔다. 그래서 가구·의자의 실제 바닥
// 사각형에서 몸 반지름만큼 떨어져야 걷고, 길은 1/4칸(8px) 격자에서 찾는다. 벽(방 밖 칸)과 엘리베이터 문도 같은 방식이다.

/** 몸 반지름(px). 사람·로봇 머리 지름(약 1.1 m)의 절반이다. 사람 사이 거리(34px)도 이 두 배다. */
export const BODY_RADIUS = 16
/**
 * 할 일 없이 다니는 사람이 멈춰 서는 곳의 여유(px). 둘레 이만큼 장애물이 없어야, 서 있는 사람 곁을 다른 사람이 사람 사이
 * 거리(34px)를 지키며 지나간다(34 + 몸 반지름 16 + 2). 좁은 통로에 서서 길을 막지 않는다.
 */
export const STAND_CLEARANCE = 52
// 길찾기 격자 한 칸(px). 회의실 유리 문(1.34 m)에서 몸이 지날 수 있는 폭이 11px뿐이라, 그보다 촘촘해야 문 안에 칸 가운데가 든다.
const CELL = 8
const T = 32

type Box = { x0: number; y0: number; x1: number; y1: number; chair: number }
/** 지나갈 수 있는 의자 번호(앉으러 가는 의자, 막 일어난 의자). -1은 없음. */
export type Exempt = number | readonly number[]
const exempted = (exempt: Exempt, chair: number) =>
  chair >= 0 &&
  (typeof exempt === 'number' ? chair === exempt : exempt.includes(chair))
export interface Nav {
  cols: number
  rows: number
  width: number
  height: number
  /** 타일마다 그 타일에서 몸 반지름 안에 들 수 있는 장애물 */
  buckets: Box[][]
  /** 의자(앉을 자리) 바닥 사각형. 앉으러 가는 사람만 그 의자를 지나간다. */
  chairs: Box[]
  /** 격자 칸 가운데가 비었는지(의자 포함 모든 장애물에서 떨어짐) */
  open: Uint8Array
  /** 격자 칸 가운데를 막는 것이 의자 하나뿐이면 그 의자 번호, 아니면 -1 */
  chairOf: Int16Array
  edges: Map<number, boolean>
  /** 엘리베이터 앞에서 걸어갈 수 있는 격자 칸(1). 처음 만들 때 한 번 채운다. */
  reach: Uint8Array
  reachReady: boolean
}

const navs = new WeakMap<Floor, Nav>()

const boxOf = (x: number, y: number, w: number, h: number, chair = -1) => ({
  x0: x * T,
  y0: y * T,
  x1: (x + w) * T,
  y1: (y + h) * T,
  chair,
})

/** 배치의 장애물과 길찾기 격자. 배치마다 한 번 만든다. */
export function navOf(floor: Floor): Nav {
  const cached = navs.get(floor)
  if (cached) return cached
  const boxes: Box[] = []
  // 방 밖(벽) 칸
  for (let y = 0; y < floor.H; y++)
    for (let x = 0; x < floor.W; x++)
      if (['#', 'g', 'o'].includes(floor.grid[y]?.[x] ?? '#'))
        boxes.push(boxOf(x, y, 1, 1))
  const chairs: Box[] = []
  for (const item of floor.furniture) {
    if (item.kind === 'chair') {
      // 의자 칸은 1칸으로 잡혀 있어 실제 모델 크기(가로·세로가 돌면 바꾼다)를 쓴다.
      const kit = item.kit
      const turned = kit ? Math.abs(Math.sin(kit.ry)) > 0.5 : false
      const w = kit ? (turned ? kit.d : kit.w) : 0.5
      const d = kit ? (turned ? kit.w : kit.d) : 0.5
      const cx = item.x + item.w / 2,
        cy = item.y + item.h / 2
      const box = boxOf(cx - w / 2, cy - d / 2, w, d, chairs.length)
      chairs.push(box)
      boxes.push(box)
    } else if (item.solid) boxes.push(boxOf(item.x, item.y, item.w, item.h))
  }
  const exit = floor.outdoorExit
  if (exit) boxes.push(boxOf(exit.x, exit.y, exit.w, exit.h))
  // 가구·엘리베이터로 설명되지 않는 막힌 칸(벽 줄 등)은 칸 전체를 벽으로 본다. 가구가 막은 칸은 가구 사각형이 대신한다.
  const pieces = [
    ...floor.furniture.filter((item) => item.solid),
    ...(exit ? [{ x: exit.x, y: exit.y, w: exit.w, h: exit.h }] : []),
  ]
  for (let y = 0; y < floor.H; y++)
    for (let x = 0; x < floor.W; x++)
      if (
        floor.blocked[y]?.[x] &&
        !['#', 'g', 'o'].includes(floor.grid[y]?.[x] ?? '#') &&
        !pieces.some(
          (item) =>
            item.x < x + 1 &&
            item.x + item.w > x &&
            item.y < y + 1 &&
            item.y + item.h > y,
        )
      )
        boxes.push(boxOf(x, y, 1, 1))
  const buckets = Array.from({ length: floor.W * floor.H }, () => [] as Box[])
  for (const box of boxes)
    for (
      let y = Math.max(0, Math.floor((box.y0 - BODY_RADIUS) / T));
      y <= Math.min(floor.H - 1, Math.floor((box.y1 + BODY_RADIUS) / T));
      y++
    )
      for (
        let x = Math.max(0, Math.floor((box.x0 - BODY_RADIUS) / T));
        x <= Math.min(floor.W - 1, Math.floor((box.x1 + BODY_RADIUS) / T));
        x++
      )
        buckets[y * floor.W + x].push(box)
  const cols = (floor.W * T) / CELL,
    rows = (floor.H * T) / CELL
  const nav: Nav = {
    cols,
    rows,
    width: floor.W * T,
    height: floor.H * T,
    buckets,
    chairs,
    open: new Uint8Array(cols * rows),
    chairOf: new Int16Array(cols * rows).fill(-1),
    edges: new Map(),
    reach: new Uint8Array(cols * rows),
    reachReady: false,
  }
  for (let cy = 0; cy < rows; cy++)
    for (let cx = 0; cx < cols; cx++) {
      const x = cx * CELL + CELL / 2,
        y = cy * CELL + CELL / 2
      const index = cy * cols + cx
      if (navClear(nav, x, y)) nav.open[index] = 1
      else {
        const only = blockingChair(nav, x, y)
        if (only >= 0) nav.chairOf[index] = only
      }
    }
  // 엘리베이터 앞(없으면 출입 칸)에서 이어진 칸을 칠한다. 가구와 벽 사이에 갇힌 틈은 빠진다.
  const entry =
    floor.outdoorExit?.landing ??
    ({ x: floor.exit.x * T + 16, y: floor.exit.y * T + 27 } as Point)
  const startCell = cellIndex(nav, navNearest(nav, entry) ?? entry)
  const queue = [startCell]
  nav.reach[startCell] = 1
  while (queue.length) {
    const current = queue.pop()!
    const cx = current % cols,
      cy = Math.floor(current / cols)
    for (const [dx, dy] of DIRS) {
      const nx = cx + dx,
        ny = cy + dy
      if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
      const next = ny * cols + nx
      if (nav.reach[next] || !nav.open[next]) continue
      if (
        !navSegmentClear(nav, cellCenter(nav, current), cellCenter(nav, next))
      )
        continue
      nav.reach[next] = 1
      queue.push(next)
    }
  }
  nav.reachReady = true
  navs.set(floor, nav)
  return nav
}

const cellIndex = (nav: Nav, point: Point) =>
  Math.min(nav.rows - 1, Math.max(0, Math.floor(point.y / CELL))) * nav.cols +
  Math.min(nav.cols - 1, Math.max(0, Math.floor(point.x / CELL)))
const cellCenter = (nav: Nav, index: number): Point => ({
  x: (index % nav.cols) * CELL + CELL / 2,
  y: Math.floor(index / nav.cols) * CELL + CELL / 2,
})

/** point가 비어 있고 엘리베이터 앞에서 걸어갈 수 있는 곳인지 */
export function navReachable(
  nav: Nav,
  point: Point,
  exempt: Exempt = -1,
): boolean {
  if (!navClear(nav, point.x, point.y, exempt)) return false
  const index = cellIndex(nav, point)
  if (nav.reach[index]) return true
  // 칸 가운데는 막혀도 이 점은 비었을 수 있다. 곧장 갈 수 있는 이웃 칸이 이어져 있으면 된다.
  const cx = index % nav.cols,
    cy = Math.floor(index / nav.cols)
  return DIRS.some(([dx, dy]) => {
    const next = (cy + dy) * nav.cols + cx + dx
    return (
      nav.reach[next] === 1 &&
      navSegmentClear(nav, point, cellCenter(nav, next), exempt)
    )
  })
}

const gap = (box: Box, x: number, y: number) =>
  Math.hypot(
    Math.max(box.x0 - x, 0, x - box.x1),
    Math.max(box.y0 - y, 0, y - box.y1),
  )

/** 몸 중심(x, y)이 모든 장애물에서 r(px) 넘게 떨어졌는지. exempt 번호의 의자는 지나갈 수 있다(앉으러 가는 의자). */
export function navClear(
  nav: Nav,
  x: number,
  y: number,
  exempt: Exempt = -1,
  r = BODY_RADIUS,
): boolean {
  if (x < r || y < r || x > nav.width - r || y > nav.height - r) return false
  const free = (box: Box) => exempted(exempt, box.chair) || gap(box, x, y) >= r
  const columns = nav.width / T
  // 타일 묶음은 몸 반지름까지의 장애물만 담는다. 더 넓게 보려면 둘레 타일 묶음도 본다.
  if (r <= BODY_RADIUS) {
    const bucket = nav.buckets[Math.floor(y / T) * columns + Math.floor(x / T)]
    return !!bucket && bucket.every(free)
  }
  for (let ty = Math.floor((y - r) / T); ty <= Math.floor((y + r) / T); ty++)
    for (let tx = Math.floor((x - r) / T); tx <= Math.floor((x + r) / T); tx++)
      if (!(nav.buckets[ty * columns + tx] ?? []).every(free)) return false
  return true
}

/** (x, y)를 막는 것이 의자 하나뿐이면 그 번호, 아니면 -1 */
function blockingChair(nav: Nav, x: number, y: number): number {
  const bucket =
    nav.buckets[Math.floor(y / T) * (nav.width / T) + Math.floor(x / T)] ?? []
  const hits = bucket.filter((box) => gap(box, x, y) < BODY_RADIUS)
  return hits.length === 1 && hits[0].chair >= 0 ? hits[0].chair : -1
}

/** 자리(앉는 곳)가 놓인 의자 번호. 의자가 아니면 -1 */
export function navChairAt(nav: Nav, point: Point): number {
  let best = -1,
    distance = T / 2
  for (const [index, box] of nav.chairs.entries()) {
    const next = Math.hypot(
      (box.x0 + box.x1) / 2 - point.x,
      (box.y0 + box.y1) / 2 - point.y,
    )
    if (next < distance) {
      best = index
      distance = next
    }
  }
  return best
}

export function navSegmentClear(
  nav: Nav,
  from: Point,
  to: Point,
  exempt: Exempt = -1,
  r = BODY_RADIUS,
): boolean {
  const steps = Math.max(
    1,
    Math.ceil(Math.hypot(to.x - from.x, to.y - from.y) / 3),
  )
  for (let i = 0; i <= steps; i++)
    if (
      !navClear(
        nav,
        from.x + ((to.x - from.x) * i) / steps,
        from.y + ((to.y - from.y) * i) / steps,
        exempt,
        r,
      )
    )
      return false
  return true
}

/** point가 막혔으면 가장 가까운 빈 곳. 바로 곁(2칸 안)을 먼저 보고, 없으면 격자 전체에서 찾는다. */
export function navNearest(
  nav: Nav,
  point: Point,
  exempt: Exempt = -1,
  r = BODY_RADIUS,
  /** 엘리베이터 앞에서 걸어갈 수 있는 곳만(가구 사이 갇힌 틈 제외) */
  reachable = false,
): Point | undefined {
  const ok = (x: number, y: number) =>
    navClear(nav, x, y, exempt, r) &&
    (!reachable || !nav.reachReady || navReachable(nav, { x, y }, exempt))
  if (ok(point.x, point.y)) return { x: point.x, y: point.y }
  for (let radius = 2; radius <= 64; radius += 2)
    for (let i = 0; i < 32; i++) {
      const angle = (i * Math.PI) / 16,
        x = point.x + Math.cos(angle) * radius,
        y = point.y + Math.sin(angle) * radius
      if (ok(x, y)) return { x, y }
    }
  let best: Point | undefined,
    distance = Infinity
  for (let index = 0; index < nav.open.length; index++) {
    if (!nav.open[index] || (reachable && nav.reachReady && !nav.reach[index]))
      continue
    const x = (index % nav.cols) * CELL + CELL / 2,
      y = Math.floor(index / nav.cols) * CELL + CELL / 2
    const next = Math.hypot(x - point.x, y - point.y)
    if (next < distance && (r === BODY_RADIUS || navClear(nav, x, y, -1, r))) {
      best = { x, y }
      distance = next
    }
  }
  return best
}

const DIRS = [
  [1, 0],
  [-1, 0],
  [0, 1],
  [0, -1],
  [1, 1],
  [1, -1],
  [-1, 1],
  [-1, -1],
] as const

/**
 * from에서 to까지 걷는 길(from 제외, to 포함). 격자에서 8방향 A*로 찾고, 몸이 장애물에 닿지 않는 직선으로 줄인다.
 * avoid는 지금 서 있는 사람들이다. 그 사람과 지킬 거리(space, 없으면 38px)보다 조금 넓게 둘레를 피해 돌아간다. 갈 수 없으면
 * undefined.
 */
export function navRoute(
  nav: Nav,
  from: Point,
  to: Point,
  {
    exempt = -1 as Exempt,
    avoid = [],
  }: {
    exempt?: Exempt
    avoid?: readonly (Point & { space?: number })[]
  } = {},
): Point[] | undefined {
  if (!navClear(nav, to.x, to.y, exempt)) return undefined
  // 지금 자리가 장애물에 붙어 있으면(의자에서 막 일어남 등) 가장 가까운 빈 곳으로 먼저 비켜 선다.
  if (!navClear(nav, from.x, from.y, exempt)) {
    // 피할 사람 곁이 아닌 가장 가까운 빈 곳으로 빠져나온다.
    let out: Point | undefined
    for (let radius = 2; radius <= 64 && !out; radius += 2)
      for (let i = 0; i < 32; i++) {
        const angle = (i * Math.PI) / 16,
          x = from.x + Math.cos(angle) * radius,
          y = from.y + Math.sin(angle) * radius
        if (
          navClear(nav, x, y, exempt) &&
          avoid.every(
            (other) =>
              Math.hypot(other.x - x, other.y - y) >= (other.space ?? 38),
          )
        ) {
          out = { x, y }
          break
        }
      }
    out ??= navNearest(nav, from, exempt)
    const rest = out && navRoute(nav, out, to, { exempt, avoid })
    return out && rest ? [out, ...rest] : undefined
  }
  const cell = (point: Point) =>
    Math.min(nav.rows - 1, Math.max(0, Math.floor(point.y / CELL))) * nav.cols +
    Math.min(nav.cols - 1, Math.max(0, Math.floor(point.x / CELL)))
  const center = (index: number): Point => ({
    x: (index % nav.cols) * CELL + CELL / 2,
    y: Math.floor(index / nav.cols) * CELL + CELL / 2,
  })
  const start = cell(from),
    goal = cell(to)
  const avoided = (index: number) => {
    const point = center(index)
    return avoid.some(
      (other) =>
        Math.hypot(other.x - point.x, other.y - point.y) <
        (other.space ?? 38) + 2,
    )
  }
  const passable = (index: number) =>
    index === start ||
    index === goal ||
    ((nav.open[index] === 1 || exempted(exempt, nav.chairOf[index])) &&
      !avoided(index))
  const edgeClear = (a: number, b: number) => {
    // 의자를 지나가는 길이나 출발·도착 칸은 그때 확인하고, 나머지는 배치마다 한 번만 확인해 둔다.
    if (
      (typeof exempt === 'number' ? exempt >= 0 : exempt.some((c) => c >= 0)) ||
      a === start ||
      b === goal
    )
      return navSegmentClear(
        nav,
        a === start ? from : center(a),
        b === goal ? to : center(b),
        exempt,
      )
    const key = a * nav.cols * nav.rows + b
    let clear = nav.edges.get(key)
    if (clear === undefined) {
      clear = navSegmentClear(nav, center(a), center(b))
      nav.edges.set(key, clear)
    }
    return clear
  }
  const g = new Float64Array(nav.open.length).fill(Infinity)
  const prev = new Int32Array(nav.open.length).fill(-1)
  const goalPoint = center(goal)
  const h = (index: number) => {
    const point = center(index)
    const dx = Math.abs(point.x - goalPoint.x) / CELL,
      dy = Math.abs(point.y - goalPoint.y) / CELL
    return Math.max(dx, dy) + (Math.SQRT2 - 1) * Math.min(dx, dy)
  }
  // 이진 힙(우선순위 = 지난 거리 + 남은 거리 어림)
  const heap: [number, number][] = []
  const push = (priority: number, index: number) => {
    heap.push([priority, index])
    for (let i = heap.length - 1; i > 0;) {
      const parent = (i - 1) >> 1
      if (heap[parent][0] <= heap[i][0]) break
      ;[heap[parent], heap[i]] = [heap[i], heap[parent]]
      i = parent
    }
  }
  const pop = () => {
    const top = heap[0]
    const last = heap.pop()!
    if (heap.length) {
      heap[0] = last
      for (let i = 0; ;) {
        const left = i * 2 + 1,
          right = left + 1
        let small = i
        if (left < heap.length && heap[left][0] < heap[small][0]) small = left
        if (right < heap.length && heap[right][0] < heap[small][0])
          small = right
        if (small === i) break
        ;[heap[small], heap[i]] = [heap[i], heap[small]]
        i = small
      }
    }
    return top
  }
  g[start] = 0
  push(h(start), start)
  while (heap.length) {
    const [priority, current] = pop()
    if (current === goal) break
    if (priority > g[current] + h(current) + 1e-9) continue
    const cx = current % nav.cols,
      cy = Math.floor(current / nav.cols)
    for (const [dx, dy] of DIRS) {
      const nx = cx + dx,
        ny = cy + dy
      if (nx < 0 || ny < 0 || nx >= nav.cols || ny >= nav.rows) continue
      const next = ny * nav.cols + nx
      if (!passable(next) || !edgeClear(current, next)) continue
      const cost = g[current] + (dx && dy ? Math.SQRT2 : 1)
      if (cost < g[next]) {
        g[next] = cost
        prev[next] = current
        push(cost + h(next), next)
      }
    }
  }
  if (start !== goal && prev[goal] < 0) return undefined
  const cells: Point[] = []
  for (let index = goal; index !== start && index >= 0; index = prev[index])
    if (index !== goal) cells.unshift(center(index))
  const points = [...cells, to]
  // 보이는 가장 먼 지점으로 곧장 간다(몸이 닿지 않고 피할 사람 둘레를 지나지 않는 직선만). 처음 구간은 지금보다 더
  // 다가가지만 않으면 된다(이미 가까이 서 있을 때).
  const keepsAway = (a: Point, b: Point, first: boolean) => {
    if (!avoid.length) return true
    const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 3))
    for (let i = 1; i <= steps; i++) {
      const x = a.x + ((b.x - a.x) * i) / steps,
        y = a.y + ((b.y - a.y) * i) / steps
      for (const other of avoid) {
        const need = (other.space ?? 38) + 2
        const limit = first
          ? Math.min(need, Math.hypot(other.x - from.x, other.y - from.y))
          : need
        if (Math.hypot(other.x - x, other.y - y) < limit - (first ? 0 : 0.5))
          return false
      }
    }
    return true
  }
  const visible = (a: Point, b: Point) =>
    navSegmentClear(nav, a, b, exempt) && keepsAway(a, b, a === from)
  const route: Point[] = []
  let cursor = from
  for (let i = 0; i < points.length;) {
    let next = points.length - 1
    while (next > i && !visible(cursor, points[next])) next--
    if (!navSegmentClear(nav, cursor, points[next], exempt)) return undefined
    route.push(points[next])
    cursor = points[next]
    i = next + 1
  }
  return route
}
