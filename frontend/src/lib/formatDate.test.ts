import { afterEach, expect, it, vi } from 'vitest'
import { formatAgo } from './formatDate'

afterEach(() => vi.useRealTimers())

it('한 시간 안은 분, 하루 안은 시간, 한 달 안은 일, 그 뒤는 날짜로 보여 준다', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-30T03:00:00Z'))
  expect(formatAgo('2026-10-30T02:59:40Z')).toBe('방금')
  expect(formatAgo('2026-10-30T02:50:00Z')).toBe('10분 전')
  expect(formatAgo('2026-10-29T23:00:00Z')).toBe('4시간 전')
  expect(formatAgo('2026-10-28T03:00:00Z')).toBe('2일 전')
  expect(formatAgo('2026-09-01T03:00:00Z')).toMatch(/2026\. 9\. 1\./)
})
