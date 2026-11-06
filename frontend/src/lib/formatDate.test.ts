import { afterEach, expect, it, vi } from 'vitest'
import { formatAgo, formatShort, formatTime } from './formatDate'

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

it('목록용 짧은 날짜는 한국시간으로 오늘이면 시각, 아니면 월·일이다', () => {
  const now = new Date('2026-10-28T03:00:00Z') // 한국시간 10월 28일 낮 12시
  expect(formatShort('2026-10-28T01:40:00Z', now)).toBe('오늘 오전 10:40')
  expect(formatShort('2026-10-26T05:40:00Z', now)).toBe('10월 26일')
  // 한국시간으로는 이미 다음 날(10월 29일 오전 0:30)
  expect(formatShort('2026-10-28T15:30:00Z', now)).toBe('10월 29일')
  expect(formatTime('2026-10-28T01:46:00Z')).toBe('오전 10:46')
})
