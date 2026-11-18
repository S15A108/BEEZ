import type { Floor } from './floors'
import { T } from './floors'
import type { Point } from './motion'
import { PERSONAL_SPACE } from './office-life'
import {
  navClear,
  navNearest,
  navOf,
  navReachable,
  navRoute,
} from './office-nav'

/** 몸(반지름 16px)이 벽·가구·의자에 닿지 않는 곳인지. 시뮬레이션의 사람들과 같은 판정이다(office-nav). */
export function officeWalkable(floor: Floor, x: number, y: number): boolean {
  return navClear(navOf(floor), x, y)
}

/**
 * A restored layout can place the old position inside a desk or an unseen tile. `others` are people standing in the
 * office; the landing keeps their personal space so a returning walker does not appear inside someone (S15P31A108-379).
 */
export function officeLanding(
  floor: Floor,
  from: Point,
  others: readonly Point[] = [],
  /** 둘레 이만큼(px) 장애물이 없어야 한다. 기본은 몸 반지름이다. */
  clearance?: number,
): Point | undefined {
  // 가구 사이에 갇힌 틈이 아니라 엘리베이터 앞에서 걸어갈 수 있는 곳에 선다.
  const nav = navOf(floor)
  const free = (x: number, y: number) =>
    navReachable(nav, { x, y }) &&
    (!clearance || navClear(nav, x, y, -1, clearance)) &&
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

/**
 * 내 캐릭터가 누른 곳으로 걸어갈 길. 가구 사이에 갇힌 틈(엘리베이터에서 이어지지 않는 곳)을 누르면 길이 없어
 * 제자리에 섰으므로, 누른 곳에서 가장 가까운 '걸어갈 수 있는 곳'으로 간다. 길이 없으면 빈 배열이다.
 */
export function officeWalkRoute(
  floor: Floor,
  from: Point,
  destination: Point,
): Point[] {
  const nav = navOf(floor)
  const goal = navNearest(nav, destination, -1, undefined, true)
  return (goal && navRoute(nav, from, goal)) ?? []
}
