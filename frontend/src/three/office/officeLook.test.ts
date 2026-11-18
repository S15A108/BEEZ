import { expect, it } from 'vitest'
import { makeBuilding, ALL_KEYS } from './building'
import { OFFICE_THEMES, officeDesignOf, officeTimeAt } from './officeLook'

// D39: 기본은 한국 시각에 따라 낮·노을·밤이 바뀐다(사용자 결정 '실제 시각 + 설정에서 고정').
it('한국 시각 7시부터 낮, 17시부터 노을, 19시부터 밤이다', () => {
  // UTC 시각이라 한국 시각은 9시간 뒤다
  expect(officeTimeAt(new Date('2026-11-04T21:59:00Z'))).toBe('night')
  expect(officeTimeAt(new Date('2026-11-04T22:00:00Z'))).toBe('day')
  expect(officeTimeAt(new Date('2026-11-05T07:59:00Z'))).toBe('day')
  expect(officeTimeAt(new Date('2026-11-05T08:00:00Z'))).toBe('sunset')
  expect(officeTimeAt(new Date('2026-11-05T09:59:00Z'))).toBe('sunset')
  expect(officeTimeAt(new Date('2026-11-05T10:00:00Z'))).toBe('night')
})

// 프로젝트 건물 외관 다섯 개가 캔버스 SCR-06-rec-office-buildings 다섯 안과 1:1이다. 값이 없으면 도시처럼 모던 화이트다.
it('건물 외관마다 사무실 테마가 있고, 사무실 바닥이 외관을 따른다', () => {
  expect(Object.keys(OFFICE_THEMES).sort()).toEqual(
    ['brick', 'glass', 'modern', 'tower', 'wood'].sort(),
  )
  expect(officeDesignOf(undefined)).toBe('modern')
  expect(officeDesignOf('castle')).toBe('modern')
  const office = (design: string) =>
    makeBuilding(ALL_KEYS, design, null, { dev: 1 }).floors[1]
  expect(office('tower').design).toBe('tower')
  expect(office('wood').design).toBe('wood')
})
