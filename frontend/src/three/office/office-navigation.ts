import type { Floor } from './floors'
import { T } from './floors'
import { gridWalkable } from './motion'
import type { Point } from './motion'
import { PERSONAL_SPACE } from './office-life'

export function officeWalkable(floor: Floor, x: number, y: number): boolean {
  return (
    gridWalkable({ x, y: y - 4 }, floor.blocked, T, 6) &&
    floor.grid[Math.floor((y - 4) / T)]?.[Math.floor(x / T)] !== '#'
  )
}

/**
 * A restored layout can place the old position inside a desk or an unseen tile. `others` are people standing in the
 * office; the landing keeps their personal space so a returning walker does not appear inside someone (S15P31A108-379).
 */
export function officeLanding(
  floor: Floor,
  from: Point,
  others: readonly Point[] = [],
): Point | undefined {
  const free = (x: number, y: number) =>
    officeWalkable(floor, x, y) &&
    others.every(
      (other) => Math.hypot(x - other.x, y - other.y) >= PERSONAL_SPACE,
    )
  if (free(from.x, from.y)) return { ...from }
  let nearest: Point | undefined
  let distance = Infinity
  for (let y = 0; y < floor.H; y++) {
    for (let x = 0; x < floor.W; x++) {
      const candidate = { x: x * T + 16, y: y * T + 27 }
      const next = Math.hypot(candidate.x - from.x, candidate.y - from.y)
      if (next < distance && free(candidate.x, candidate.y)) {
        nearest = candidate
        distance = next
      }
    }
  }
  return nearest
}
