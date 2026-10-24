export const text = (value: unknown, fallback = '미등록') =>
  typeof value === 'string'
    ? value
    : typeof value === 'number'
      ? value.toLocaleString('ko-KR')
      : fallback
export const lines = (value: string) =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)

export const statusLabels: Record<string, string> = {
  DRAFT: '초안',
  READY: '시작 준비',
  IN_PROGRESS: '진행 중',
  WAITING: '대기',
  COMPLETED: '완료',
  CANCELLED: '취소',
  NOT_RUN: '검증 전',
  RUNNING: '검증 중',
  PASS: '계획 통과',
  NEEDS_INPUT: '보완 필요',
  NEEDS_REVIEW: '합의 필요',
  STALE: '재검증 필요',
  ERROR: '검증 오류',
  PROPOSED: '제안',
  REVIEWING: '검토 중',
  IN_REVIEW: '검토 중',
  APPROVED: '승인',
  PUBLISHED: '발행 완료',
  REJECTED: '반려',
  SUPERSEDED: '이전 버전',
  PENDING: '대기 중',
  FAILED: '실패',
  LEADER: '인간 팀장',
  MEMBER: '팀원',
  PLAN: '기획안',
  ERD: 'ERD',
  REQUIREMENTS: '요구사항 명세서',
  API_SPEC: 'API 명세서',
  REQUIREMENT: '요구사항',
  API_CONTRACT: 'API 계약',
  DATA_CONTRACT: '데이터 계약',
  POLICY: '정책',
  CONSTRAINT: '제약조건',
  INFO: '안내',
  WARNING: '주의',
  BLOCKING: '진행 차단',
  MOCK_PROJECT_PREPARED: '예시 프로젝트 준비',
  MOCK_UPDATE: '예시 활동',
  CHANGE_REVIEW_REQUESTED: '변경 검토 요청',
  INPUT: '입력 대기',
  APPROVAL: '승인 대기',
  DEPENDENCY: '선행 작업 대기',
  REVALIDATION: '재검증 대기',
}
export const statusLabel = (value: unknown) => {
  const key = text(value, '')
  return statusLabels[key] ?? key
}
