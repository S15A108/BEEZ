import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { usageResponseSchema } from '@/features/dashboard/schemas'
import { createMockDatabase } from './state'
import { mockUsage } from './usage'

beforeEach(() => {
  vi.useFakeTimers({ now: new Date('2026-11-06T06:00:00Z'), toFake: ['Date'] })
})
afterEach(() => {
  vi.useRealTimers()
})
const seeded = () => createMockDatabase().projects[0]
const total = (rows: { call_count: number }[]) =>
  rows.reduce((sum, row) => sum + row.call_count, 0)

it('예시 프로젝트의 사용량은 기능별·날짜별·에이전트별 합이 전체 합과 같고 스키마를 통과한다', () => {
  const usage = usageResponseSchema.parse(
    mockUsage(seeded(), '2026-10-31T15:00:00.000Z', '2026-11-06T15:00:00.000Z'),
  )
  expect(usage.call_count).toBeGreaterThan(0)
  expect(total(usage.by_feature)).toBe(usage.call_count)
  expect(total(usage.by_day)).toBe(usage.call_count)
  expect(total(usage.by_agent)).toBe(usage.call_count)
  expect(usage.by_agent.some((row) => row.key === 'WEB')).toBe(true)
  // 한국 날짜 11월 1일~6일을 호출이 없는 날까지 빠짐없이 준다
  expect(usage.by_day.map((row) => row.key)).toEqual([
    '2026-11-01',
    '2026-11-02',
    '2026-11-03',
    '2026-11-04',
    '2026-11-05',
    '2026-11-06',
  ])
})

it('프로젝트가 생기기 전 기간과 새 프로젝트는 호출이 없고 비용을 생략한다', () => {
  const before = usageResponseSchema.parse(
    mockUsage(seeded(), '2026-09-30T15:00:00.000Z', '2026-10-10T15:00:00.000Z'),
  )
  expect(before.call_count).toBe(0)
  expect(before.currency).toBeUndefined()
  expect(before.by_agent).toEqual([])
  expect(before.by_day).toHaveLength(10)
  const other = createMockDatabase().projects[1]
  expect(
    mockUsage(other, '2026-10-31T15:00:00.000Z', '2026-11-06T15:00:00.000Z')
      .call_count,
  ).toBe(0)
})

it('서버처럼 뒤집힌 기간과 366일을 넘는 기간을 거절한다', () => {
  expect(() =>
    mockUsage(seeded(), '2026-11-02T00:00:00Z', '2026-11-01T00:00:00Z'),
  ).toThrow()
  expect(() =>
    mockUsage(seeded(), '2025-01-01T00:00:00Z', '2026-11-01T00:00:00Z'),
  ).toThrow()
})
