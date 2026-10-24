const formatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  dateStyle: 'medium',
  timeStyle: 'short',
})
export const formatDate = (value?: string) =>
  value ? formatter.format(new Date(value)) : '기록 없음'
