export const FEATURE_LABELS: Record<string, string> = {
  DOCUMENT_PARSE: '문서 분석',
  TASK_VALIDATION: '작업 검증',
  CHANGE_IMPACT: '변경 영향 분석',
  EMBEDDING: '검색 색인',
  BEE_CHAT: '비비 대화',
  BEE_DRAFT: '비비 초안',
  TASK_ASSIGNMENT: '작업 배정 추천',
  MEETING_ANALYSIS: '회의 분석',
}

export const dashboardTabs = [
  ['overview', '한눈에 보기'],
  ['timeline', '활동 타임라인'],
  ['agents', '에이전트 현황'],
  ['usage', '사용량·비용'],
] as const
export type DashboardTab = (typeof dashboardTabs)[number][0]

// 추정 비용 표시. 비용이 하나도 보고되지 않으면 missing을, 일부 호출의 비용을 모르면
// 그 건수를 붙여 0원·전체 금액처럼 보이지 않게 한다.
export function costText(
  amount: string | undefined,
  currency: string | undefined,
  unknownCalls = 0,
  missing = '미보고',
) {
  if (amount === undefined) return missing
  const known = `${amount} ${currency ?? ''}`.trim()
  const unknown = unknownText(unknownCalls)
  return unknown ? `${known} + ${unknown}` : known
}

// '27회 · 0.51 USD'. 호출이 없으면 서버가 비용을 생략하므로 '0회'만 쓴다.
export function callsText(
  row: {
    call_count: number
    estimated_cost_amount?: string
    cost_unknown_call_count?: number
  },
  currency: string | undefined,
) {
  const calls = `${row.call_count.toLocaleString('ko-KR')}회`
  return row.call_count
    ? `${calls} · ${costText(row.estimated_cost_amount, currency, row.cost_unknown_call_count, '비용 미보고')}`
    : calls
}

// 토큰·비용을 몰라 합계에 들어가지 못한 호출 수. 없으면 null.
export function unknownText(unknownCalls = 0) {
  return unknownCalls > 0
    ? `미보고 ${unknownCalls.toLocaleString('ko-KR')}건`
    : null
}
