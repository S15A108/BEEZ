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
  if (unknownCalls > 0)
    return `${known} + 미보고 ${unknownCalls.toLocaleString('ko-KR')}건`
  return known
}

// 토큰 수 표시. 토큰을 모르는 호출은 합계에 없으므로 그 건수를 붙인다.
export function tokenText(tokens: number, unknownCalls = 0) {
  const known = tokens.toLocaleString('ko-KR')
  if (unknownCalls > 0)
    return `${known} + 미보고 ${unknownCalls.toLocaleString('ko-KR')}건`
  return known
}
