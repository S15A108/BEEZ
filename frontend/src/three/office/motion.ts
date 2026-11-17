export interface Point {
  x: number
  y: number
}
export type Walkable = (x: number, y: number) => boolean

export const MOVE_KEYS = new Set([
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'w',
  'a',
  's',
  'd',
])
export const movementKey = (key: string) =>
  key.length === 1 ? key.toLowerCase() : key
export const RUN_MULTIPLIER = 1.8
export const hasMovementKeys = (keys: ReadonlySet<string>) =>
  [...keys].some((key) => MOVE_KEYS.has(movementKey(key)))
export const movementSpeed = (base: number, keys: ReadonlySet<string>) =>
  base * (keys.has('Shift') ? RUN_MULTIPLIER : 1)

/** Aliases count once; opposing directions cancel; diagonals keep the same speed. */
export function movementInput(keys: ReadonlySet<string>): Point {
  const held = new Set([...keys].map(movementKey))
  const x =
    Number(held.has('ArrowRight') || held.has('d')) -
    Number(held.has('ArrowLeft') || held.has('a'))
  const y =
    Number(held.has('ArrowDown') || held.has('s')) -
    Number(held.has('ArrowUp') || held.has('w'))
  const length = Math.hypot(x, y) || 1
  return { x: x / length, y: y / length }
}

/** Test the robot's feet against every tile touched by its circular footprint. */
export function gridWalkable(
  point: Point,
  blocked: boolean[][],
  tileSize: number,
  radius: number,
): boolean {
  const height = blocked.length,
    width = blocked[0]?.length ?? 0
  if (
    point.x - radius < 0 ||
    point.y - radius < 0 ||
    point.x + radius > width * tileSize ||
    point.y + radius > height * tileSize
  )
    return false
  for (
    let row = Math.floor((point.y - radius) / tileSize);
    row <= Math.floor((point.y + radius) / tileSize);
    row++
  ) {
    for (
      let col = Math.floor((point.x - radius) / tileSize);
      col <= Math.floor((point.x + radius) / tileSize);
      col++
    ) {
      if (!blocked[row]?.[col]) continue
      const dx =
        point.x -
        Math.max(col * tileSize, Math.min((col + 1) * tileSize, point.x))
      const dy =
        point.y -
        Math.max(row * tileSize, Math.min((row + 1) * tileSize, point.y))
      if (dx * dx + dy * dy < radius * radius - 1e-6) return false
    }
  }
  return true
}

// 걷는 사람 몸이 단단한 가구의 실제 바닥면에서 떨어질 거리(px, 1타일 = 32). 칸 단위 막기는 가구가 두 칸에 반씩
// 걸치면 어느 칸도 막지 않아(모던 화이트 가운데 테이블) 사람이 가구 위를 지나갔다. 몸 폭의 절반(약 18px)보다 조금
// 작게 두어 의자에 앉으러 붙을 자리를 남긴다.
export const FURNITURE_GAP = 12
// 의자·스툴은 앉을 수 있게 칸을 열어 둔다(D36). 걷는 사람은 의자 가운데에서 이만큼 떨어진 길을 먼저 고르고 그곳에서
// 멈추지 않는다. 막지는 않는다. 의자가 촘촘해 막으면 일하러 가는 에이전트가 자기 자리에 닿지 못한다.
export const CHAIR_GAP = 18
type Piece = {
  kind: string
  x: number
  y: number
  w: number
  h: number
  solid: boolean
}
const solidRects = new WeakMap<object, number[][]>()
const chairCenters = new WeakMap<object, number[][]>()

/** 몸 중심(x, y)이 단단한 가구의 바닥면에서 FURNITURE_GAP - slack(px)보다 멀면 참이다. 가구 위치·크기는 타일 단위다. */
export function clearOfFurniture(
  furniture: readonly Piece[],
  x: number,
  y: number,
  slack = 0,
): boolean {
  let rects = solidRects.get(furniture)
  if (!rects) {
    rects = furniture
      // 유리 칸막이는 벽처럼 지나는 칸을 이미 막는다. 얇은 벽까지 띄우면 회의실 안 한 줄 통로가 막힌다.
      .filter((item) => item.solid && item.kind !== 'glass-partition')
      .map((item) => [
        item.x * 32,
        item.y * 32,
        (item.x + item.w) * 32,
        (item.y + item.h) * 32,
      ])
    solidRects.set(furniture, rects)
  }
  const gap = FURNITURE_GAP - slack
  return rects.every(([x0, y0, x1, y1]) => {
    const dx = Math.max(x0 - x, 0, x - x1),
      dy = Math.max(y0 - y, 0, y - y1)
    return dx * dx + dy * dy >= gap * gap
  })
}

/** 몸 중심(x, y)이 모든 의자·스툴 가운데에서 CHAIR_GAP(px)보다 멀면 참이다. */
export function clearOfChairs(
  furniture: readonly Piece[],
  x: number,
  y: number,
): boolean {
  let centers = chairCenters.get(furniture)
  if (!centers) {
    centers = furniture
      .filter((item) => item.kind === 'chair')
      .map((item) => [(item.x + item.w / 2) * 32, (item.y + item.h / 2) * 32])
    chairCenters.set(furniture, centers)
  }
  return centers.every(([cx, cy]) => Math.hypot(cx - x, cy - y) >= CHAIR_GAP)
}

/** Small substeps prevent tunnelling; blocked diagonals slide along the free axis. */
export function moveContinuous(
  from: Point,
  delta: Point,
  free: Walkable,
): Point {
  const out = { ...from }
  const steps = Math.max(1, Math.ceil(Math.hypot(delta.x, delta.y) / 3))
  const dx = delta.x / steps,
    dy = delta.y / steps
  for (let i = 0; i < steps; i++) {
    if (free(out.x + dx, out.y + dy)) {
      out.x += dx
      out.y += dy
    } else {
      if (dx && free(out.x + dx, out.y)) out.x += dx
      if (dy && free(out.x, out.y + dy)) out.y += dy
    }
  }
  return out
}

export function clearSegment(from: Point, to: Point, free: Walkable): boolean {
  const steps = Math.max(
    1,
    Math.ceil(Math.hypot(to.x - from.x, to.y - from.y) / 3),
  )
  for (let i = 1; i <= steps; i++)
    if (
      !free(
        from.x + ((to.x - from.x) * i) / steps,
        from.y + ((to.y - from.y) * i) / steps,
      )
    )
      return false
  return true
}

/** Keep obstacle avoidance while skipping visible tile centres, including the exact click. */
export function smoothRoute(
  from: Point,
  route: Point[],
  free: Walkable,
): Point[] {
  const out: Point[] = []
  let cursor = from
  for (let i = 0; i < route.length;) {
    let next = route.length - 1
    while (next >= i && !clearSegment(cursor, route[next], free)) next--
    if (next < i) return out
    out.push(route[next])
    cursor = route[next]
    i = next + 1
  }
  return out
}

/**
 * 비비 Wave(D35). 서 있는 비비에게 내가 2칸 안으로 다가오면 한 번 손을 흔든다. 3칸 밖으로 멀어져야 다시 흔들어,
 * 경계에서 오가도 되풀이하지 않는다. 걷는 동안은 흔들지 않는다.
 */
export function bibiWave(distance: number, waved: boolean, walking: boolean) {
  if (distance > 3) return { wave: false, waved: false }
  const wave = !waved && !walking && distance < 2
  return { wave, waved: waved || wave }
}
