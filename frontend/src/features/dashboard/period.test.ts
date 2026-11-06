import { describe, expect, it } from 'vitest'
import {
  axisMax,
  periodRange,
  rangeDays,
  rangeText,
  usageQueryOf,
  weekdayText,
} from './period'

describe('periodRange', () => {
  it('한국시간으로 새 달이 된 순간부터 이번 달은 그 달 1일이다', () => {
    // 한국시간 11월 1일 00:00 = UTC 10월 31일 15:00
    const range = periodRange('this_month', new Date('2026-10-31T15:00:00Z'))
    expect(range).toEqual({ from: '2026-11-01', to: '2026-11-01' })
    expect(usageQueryOf(range)).toEqual({
      from: '2026-10-31T15:00:00.000Z',
      // 서버는 끝을 빼고 계산한다([from, to)). 다음날 00:00이어야 그날 마지막 순간까지 들어간다.
      to: '2026-11-01T15:00:00.000Z',
    })
  })

  it('한국시간으로 달의 마지막 순간까지는 이전 달이다', () => {
    // 한국시간 10월 31일 23:59:59.999. UTC 날짜로 계산하면 같은 날이지만 11월 1일 전이어야 한다.
    expect(
      periodRange('this_month', new Date('2026-10-31T14:59:59.999Z')),
    ).toEqual({ from: '2026-10-01', to: '2026-10-31' })
  })

  it('1월의 지난달은 작년 12월이다', () => {
    expect(periodRange('last_month', new Date('2027-01-15T00:00:00Z'))).toEqual(
      { from: '2026-12-01', to: '2026-12-31' },
    )
  })

  it('UTC로는 12월 31일이어도 한국시간 1월 1일이면 지난달은 12월이다', () => {
    expect(periodRange('last_month', new Date('2026-12-31T15:00:00Z'))).toEqual(
      { from: '2026-12-01', to: '2026-12-31' },
    )
  })

  it('3월의 지난달은 평년 28일, 윤년 29일까지다', () => {
    expect(periodRange('last_month', new Date('2027-03-31T00:00:00Z')).to).toBe(
      '2027-02-28',
    )
    expect(periodRange('last_month', new Date('2028-03-31T00:00:00Z')).to).toBe(
      '2028-02-29',
    )
  })

  it('최근 7일은 오늘을 포함하고 달을 넘어갈 수 있다', () => {
    const range = periodRange('last_7_days', new Date('2026-11-02T03:00:00Z'))
    expect(range).toEqual({ from: '2026-10-27', to: '2026-11-02' })
    expect(rangeDays(range)).toBe(7)
  })
})

it('기간 글자는 같은 달이면 달을 한 번만 쓴다', () => {
  expect(rangeText('2026-11-01', '2026-11-06')).toBe('11월 1일~6일')
  expect(rangeText('2026-10-28', '2026-11-03')).toBe('10월 28일~11월 3일')
})

it('날짜 키의 요일은 실행 환경 시간대와 상관없이 그 날짜의 요일이다', () => {
  expect(weekdayText('2026-10-27')).toBe('화')
})

it('세로축 끝은 가장 큰 값을 넘는 깔끔한 수다', () => {
  expect([0, 1, 3, 9, 10, 11, 96, 240].map(axisMax)).toEqual([
    1, 1, 5, 10, 10, 20, 100, 500,
  ])
})
