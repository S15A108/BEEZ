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
