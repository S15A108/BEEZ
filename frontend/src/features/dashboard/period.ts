// 대시보드 기간. 화면의 날짜(YYYY-MM-DD)는 한국시간이고, 서버에는 그 날의 처음과 끝 시각(UTC)으로 보낸다.
const seoulDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' })
export const seoulDay = (value: Date | string | number) =>
  seoulDate.format(new Date(value))
const DAY_MS = 86_400_000
const dayStart = (day: string) => Date.parse(`${day}T00:00:00.000+09:00`)

export type DayRange = { from: string; to: string }

// 서버의 기간은 [from, to)다(MON-A02). 끝은 다음날 00:00으로 보내야 그날의 마지막 순간까지 들어간다.
export const usageQueryOf = (range: DayRange) => ({
  from: new Date(dayStart(range.from)).toISOString(),
  to: new Date(dayStart(range.to) + DAY_MS).toISOString(),
})

export const periods = [
  ['this_month', '이번 달'],
  ['last_month', '지난달'],
  ['last_7_days', '최근 7일'],
] as const
export type Period = (typeof periods)[number][0]

const utcDay = (year: number, monthIndex: number, day: number) =>
  new Date(Date.UTC(year, monthIndex, day)).toISOString().slice(0, 10)

// 이번 달과 최근 7일은 오늘까지, 지난달은 그 달 말일까지다. 한국은 일광 절약 시간이 없어 하루를 24시간으로 센다.
export function periodRange(period: Period, now = new Date()): DayRange {
  const today = seoulDay(now)
  const [year, month] = today.split('-').map(Number)
  if (period === 'this_month')
    return { from: utcDay(year, month - 1, 1), to: today }
  if (period === 'last_month')
    return { from: utcDay(year, month - 2, 1), to: utcDay(year, month - 1, 0) }
  return { from: seoulDay(now.getTime() - 6 * DAY_MS), to: today }
}

// 서버가 받는 가장 긴 기간(by_day를 0으로 채우는 범위, UsageQueryService.MAX_DAYS).
export const MAX_RANGE_DAYS = 366
export const rangeDays = ({ from, to }: DayRange) =>
  (Date.parse(to) - Date.parse(from)) / DAY_MS + 1

// 날짜 키('2026-10-27')는 이미 한국시간 날짜라 시간대를 다시 바꾸지 않는다.
const weekday = new Intl.DateTimeFormat('ko-KR', {
  weekday: 'short',
  timeZone: 'UTC',
})
export const monthDayText = (day: string) => {
  const [, month, date] = day.split('-').map(Number)
  return `${month}월 ${date}일`
}
export const weekdayText = (day: string) =>
  weekday.format(new Date(`${day}T00:00:00Z`))
// '11월 1일~6일', 달이 바뀌면 '10월 28일~11월 3일'
export function rangeText(from: string, to: string) {
  const end = monthDayText(to)
  return `${monthDayText(from)}~${from.slice(0, 7) === to.slice(0, 7) ? end.split(' ')[1] : end}`
}

// 그래프 세로축 끝. 가장 큰 값을 넘는 1·2·5·10 단위의 깔끔한 수.
export function axisMax(most: number) {
  const unit = 10 ** Math.floor(Math.log10(Math.max(most, 1)))
  return ([1, 2, 5, 10].find((step) => step * unit >= most) ?? 10) * unit
}
