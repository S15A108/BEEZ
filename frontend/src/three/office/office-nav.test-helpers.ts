import type { Floor } from './floors'
import type { Point } from './motion'

// 테스트에서 office-nav와 따로 장애물을 모아 잰다. 타일 묶음·격자를 거치지 않고 모든 장애물과의 거리를 바로 구한다.
const T = 32

type Rect = { x0: number; y0: number; x1: number; y1: number; chair?: boolean }

const obstacles = new WeakMap<Floor, Rect[]>()

function obstaclesOf(floor: Floor): Rect[] {
  const cached = obstacles.get(floor)
  if (cached) return cached
  const rects: Rect[] = []
  const add = (x: number, y: number, w: number, h: number, chair = false) =>
    rects.push({
      x0: x * T,
      y0: y * T,
      x1: (x + w) * T,
      y1: (y + h) * T,
      chair,
    })
  for (let y = 0; y < floor.H; y++)
    for (let x = 0; x < floor.W; x++)
      if (['#', 'g', 'o'].includes(floor.grid[y][x])) add(x, y, 1, 1)
  for (const item of floor.furniture) {
    if (item.kind === 'chair') {
      // 의자는 칸(1 m)이 아니라 모델 크기다.
      const turned = item.kit ? Math.abs(Math.sin(item.kit.ry)) > 0.5 : false
      const w = item.kit ? (turned ? item.kit.d : item.kit.w) : 0.5
      const d = item.kit ? (turned ? item.kit.w : item.kit.d) : 0.5
      add(item.x + item.w / 2 - w / 2, item.y + item.h / 2 - d / 2, w, d, true)
    } else if (item.solid) add(item.x, item.y, item.w, item.h)
  }
  const exit = floor.outdoorExit
  if (exit) add(exit.x, exit.y, exit.w, exit.h)
  obstacles.set(floor, rects)
  return rects
}

/** 몸 중심과 가장 가까운 벽·가구·의자 사이 거리(px). withChairs가 거짓이면 의자는 빼고 잰다(의자에 앉으러 가는 사람). */
export function bodyGap(floor: Floor, point: Point, withChairs = true): number {
  return Math.min(
    ...obstaclesOf(floor)
      .filter((rect) => withChairs || !rect.chair)
      .map((rect) =>
        Math.hypot(
          Math.max(rect.x0 - point.x, 0, point.x - rect.x1),
          Math.max(rect.y0 - point.y, 0, point.y - rect.y1),
        ),
      ),
  )
}

/** from에서 route를 따라 2px마다 짚은 점 */
export function routePoints(from: Point, route: readonly Point[]): Point[] {
  const points: Point[] = []
  let cursor = from
  for (const next of route) {
    const steps = Math.max(
      1,
      Math.ceil(Math.hypot(next.x - cursor.x, next.y - cursor.y) / 2),
    )
    for (let i = 0; i <= steps; i++)
      points.push({
        x: cursor.x + ((next.x - cursor.x) * i) / steps,
        y: cursor.y + ((next.y - cursor.y) * i) / steps,
      })
    cursor = next
  }
  return points
}
