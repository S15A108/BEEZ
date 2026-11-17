import type { Floor } from './floors'
import { T } from './floors'
import type { Point } from './motion'
import { PERSONAL_SPACE } from './office-life'
import { navClear, navOf, navReachable } from './office-nav'

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
): Point | undefined {
  // 가구 사이에 갇힌 틈이 아니라 엘리베이터 앞에서 걸어갈 수 있는 곳에 선다.
  const free = (x: number, y: number) =>
    navReachable(navOf(floor), { x, y }) &&
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
 * 3인칭 Enter로 고를 대상: `from`에서 `reach`(px) 안의 가장 가까운 사람. 사람들은 돌아다니므로(D37) 출발 자리가
 * 아니라 이번 프레임에 그린 좌표(`OfficeLifeSimulation.step`의 결과)를 넘긴다.
 */
export function enterTarget(
  actors: readonly { id: string; x: number; y: number }[],
  from: Point,
  reach = 100,
): string | undefined {
  let best: string | undefined
  let distance = reach
  for (const actor of actors) {
    const next = Math.hypot(actor.x - from.x, actor.y - from.y)
    if (next < distance) {
      best = actor.id
      distance = next
    }
  }
  return best
}
