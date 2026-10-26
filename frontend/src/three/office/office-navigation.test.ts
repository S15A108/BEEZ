import { expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { moveContinuous } from './motion'
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
