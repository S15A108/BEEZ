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
