import type { Floor } from './floors'
import { T } from './floors'
import { gridWalkable } from './motion'
import type { Point } from './motion'

export function officeWalkable(floor: Floor, x: number, y: number): boolean {
  return (
    gridWalkable({ x, y: y - 4 }, floor.blocked, T, 6) &&
    floor.grid[Math.floor((y - 4) / T)]?.[Math.floor(x / T)] !== '#'
  )
}

/** A restored layout can place the old position inside a desk or an unseen tile. */
export function officeLanding(floor: Floor, from: Point): Point | undefined {
  if (officeWalkable(floor, from.x, from.y)) return { ...from }
  let nearest: Point | undefined
  let distance = Infinity
  for (let y = 0; y < floor.H; y++) {
    for (let x = 0; x < floor.W; x++) {
      const candidate = { x: x * T + 16, y: y * T + 27 }
      const next = Math.hypot(candidate.x - from.x, candidate.y - from.y)
      if (next < distance && officeWalkable(floor, candidate.x, candidate.y)) {
        nearest = candidate
        distance = next
      }
    }
  }
  return nearest
}
