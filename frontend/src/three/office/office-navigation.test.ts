import { expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { moveContinuous } from './motion'
import { PERSONAL_SPACE } from './office-life'
import { officeLanding, officeWalkable } from './office-navigation'

it.each([3, 4])(
  '준비 단계 %i에서도 가구와 숨겨진 바닥을 피해 걸을 수 있는 곳에 등장한다',
  (phase) => {
    const floor = makeDreamFloor(
      makeBuilding(ALL_KEYS, 'glass', undefined, {
        dev: 1,
        lead: 1,
        humandesk: 1,
      }).floors[1],
      phase,
    )
    const landing = officeLanding(floor, { x: 7.5 * 32, y: 4.5 * 32 })
    expect(landing).toBeDefined()
    if (!landing) return
    const free = (x: number, y: number) => officeWalkable(floor, x, y)
    expect(free(landing.x, landing.y)).toBe(true)
    const steps = [
      { x: 3, y: 0 },
      { x: -3, y: 0 },
      { x: 0, y: 3 },
      { x: 0, y: -3 },
    ]
    expect(
      steps.some((delta) => {
        const moved = moveContinuous(landing, delta, free)
        return Math.hypot(moved.x - landing.x, moved.y - landing.y) > 0
      }),
    ).toBe(true)
    expect(officeLanding(floor, landing)).toEqual(landing)
  },
)

it('공간이 아직 보이지 않는 단계에서는 잘못된 출발점을 만들지 않는다', () => {
  const floor = makeDreamFloor(makeBuilding(ALL_KEYS, 'glass').floors[1], 0)
  expect(officeLanding(floor, { x: 240, y: 144 })).toBeUndefined()
})

// S15P31A108-379: 산책에서 돌아오면 나가기 전 자리로 오는데, 그 사이 누가 그 자리에 와 있으면 겹치지 않게 비켜 선다.
it('돌아올 자리에 다른 사람이 서 있으면 그 사람과 떨어진 가장 가까운 빈 칸에 선다', () => {
  const floor = makeDreamFloor(makeBuilding(ALL_KEYS, 'glass').floors[1], 4)
  const spot = officeLanding(floor, { x: 7.5 * 32, y: 4.5 * 32 })
  expect(spot).toBeDefined()
  if (!spot) return
  expect(officeLanding(floor, spot, [{ x: spot.x + 200, y: spot.y }])).toEqual(
    spot,
  )
  const landing = officeLanding(floor, spot, [spot])
  expect(landing).toBeDefined()
  if (!landing) return
  expect(officeWalkable(floor, landing.x, landing.y)).toBe(true)
  const away = Math.hypot(landing.x - spot.x, landing.y - spot.y)
  expect(away).toBeGreaterThanOrEqual(PERSONAL_SPACE)
  expect(away).toBeLessThan(2 * 32)
})
