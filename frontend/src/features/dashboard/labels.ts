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
