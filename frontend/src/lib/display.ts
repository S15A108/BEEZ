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
  WITHDRAWN: '거둠',
  SUPERSEDED: '이전 버전',
  PENDING: '대기 중',
  FAILED: '실패',
  SUCCESS: '성공',
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

// 받침이 있으면 '을', 없으면 '를'. 한글이 아니면 받침 없음으로 본다.
export const objectParticle = (word: string) => {
  const code = word.trim().charCodeAt(word.trim().length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0 ? '을' : '를'
}

// 설계 항목 내용처럼 구조가 정해지지 않은 값을 한 줄로 보여 준다. description 문장이 있으면 그것을 쓴다.
export function describeContent(content: unknown) {
  if (content && typeof content === 'object' && 'description' in content) {
    const description = (content as { description: unknown }).description
    if (typeof description === 'string') return description
  }
  return typeof content === 'string' ? content : JSON.stringify(content ?? '')
}

// 표시 색 동그라미(캔버스 SCR-06-v2-settings-role)
export const DISPLAY_COLORS = [
  '#dbe7f0',
  '#f3c530',
  '#cfe8d8',
  '#f5d0c5',
  '#e2d9f3',
  '#e5e5e5',
]

// 집계 숫자 한 칸. 불러오는 중이면 '…', 실패하면 0 대신 '—'를 보여 실패를 0건으로 읽지 않게 한다(PR #72 전수 리뷰 TC-5).
export const countText = (
  query: { isPending: boolean; isError: boolean },
  value: number | undefined,
) => (query.isError ? '—' : query.isPending ? '…' : String(value ?? 0))
