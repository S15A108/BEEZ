import { expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { officeFloorAt } from './dream-floor'

const base = () => makeBuilding(ALL_KEYS, 'glass', undefined, {}).floors[1]

// S15P31A108-305: 엘리베이터 문은 outdoorExit로 그린다. 시작 전(3)에 산책 출구를 닫으려고 outdoorExit를 비우면
// 문까지 사라지고, 엘리베이터 앞에서 시작하지도 못했다. 시작 전에도 문과 내리는 칸은 그대로 둔다.
it('시작 전 사무실에도 시작 뒤와 같은 자리에 엘리베이터가 있다', () => {
  const before = officeFloorAt(base(), 3)
  const after = officeFloorAt(base(), 4)
  expect(after.outdoorExit).toBeDefined()
  // 자리와 내리는 칸은 같고, 산책은 시작 뒤에 열린다는 안내만 다르다.
  expect(before.outdoorExit).toEqual({
    ...after.outdoorExit,
    label: '엘리베이터 · 프로젝트를 시작하면 산책할 수 있어요',
  })
  expect(after.outdoorExit?.label).toBe('엘리베이터 · 나의 도시로')
  // 시작 전 바닥은 따로 그리도록 정적 키를 나눈다.
  expect(before.staticKey).toBe('dream-3')
  expect(after.staticKey).toBe('dream-4')
})
