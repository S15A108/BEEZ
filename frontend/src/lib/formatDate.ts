const formatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  dateStyle: 'medium',
  timeStyle: 'short',
})
export const formatDate = (value?: string) =>
  value ? formatter.format(new Date(value)) : '기록 없음'

// 날짜만('2026. 10. 23.'). 가입일처럼 시각이 필요 없는 곳에 쓴다.
const dayFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  dateStyle: 'medium',
})
export const formatDay = (value: string) => dayFormatter.format(new Date(value))

// '10분 전'처럼 지금부터 얼마나 지났는지. 한 달이 넘으면 날짜로 보여 준다.
export const formatAgo = (value: string) => {
  const minutes = Math.floor((Date.now() - new Date(value).getTime()) / 60000)
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)}시간 전`
  if (minutes < 60 * 24 * 30) return `${Math.floor(minutes / 60 / 24)}일 전`
  return formatDate(value)
}

// 시각만('오전 10:46'). 회의 대화처럼 날짜를 머리에 따로 보일 때 쓴다.
const timeFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  timeStyle: 'short',
})
export const formatTime = (value: string) =>
  timeFormatter.format(new Date(value))

// 목록용 짧은 날짜(캔버스 PNL-06-v2-meetings): 오늘이면 '오늘 오전 10:40', 아니면 '10월 26일'(한국시간 기준).
const monthDay = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'long',
  day: 'numeric',
})
export const formatShort = (value: string, now = new Date()) =>
  formatDay(value) === formatDay(now.toISOString())
    ? `오늘 ${formatTime(value)}`
    : monthDay.format(new Date(value))
