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
  FAILURE: '실패',
  SUCCESS: '성공',
  LEADER: '인간 팀장',
  MEMBER: '팀원',
  PLAN: '기획안',
  ERD: 'ERD',
  REQUIREMENTS: '요구사항 명세서',
  // 비비 검토 단계 코드(DocumentBeeReview.steps·unavailable_references)
  REQ_SPEC: '요구사항 명세서',
  BODY: '본문',
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

// 활동 문구(공통 계약 6.2). 행위자 이름과 조사 뒤에 붙는다: '이팀장님이 작업을 시작했어요'(PR #187 표기 안내).
const activityLabels: Record<string, string> = {
  TASK_CREATED: '작업을 만들었어요',
  TASK_PLAN_REVISED: '작업 계획을 고쳤어요',
  TASK_STARTED: '작업을 시작했어요',
  TASK_RESUMED: '작업을 다시 시작했어요',
  TASK_WAITING: '작업을 대기로 돌렸어요',
  TASK_COMPLETION_REPORTED: '작업 완료를 보고하고 재검증을 기다려요',
  TASK_COMPLETED: '작업을 완료했어요',
  TASK_CANCELLED: '작업을 취소했어요',
  TASK_ASSIGNEE_CHANGED: '작업 담당자를 바꿨어요',
  VALIDATION_FINISHED: '계획 검증을 마쳤어요',
  DOCUMENT_REGISTERED: '문서를 올렸어요',
  DOCUMENT_APPROVED: '문서를 승인했어요',
  DOCUMENT_REJECTED: '문서를 반려했어요',
  DOCUMENT_DISCARDED: '문서 등록을 취소했어요',
  BASELINE_CREATED: '기준 설계를 발행했어요',
  CHANGE_PROPOSAL_SUBMITTED: '변경 제안을 올렸어요',
  CHANGE_PROPOSAL_WITHDRAWN: '변경 제안을 거뒀어요',
  CHANGE_PROPOSAL_REVIEW_STARTED: '변경 제안 검토를 시작했어요',
  CHANGE_PROPOSAL_REVIEW_REQUESTED: '변경 제안 검토를 요청했어요',
  CHANGE_PROPOSAL_COMMENTED: '변경 제안에 의견을 남겼어요',
  CHANGE_PROPOSAL_APPROVED: '변경 제안을 승인했어요',
  CHANGE_PROPOSAL_REJECTED: '변경 제안을 반려했어요',
  CHANGE_PROPOSAL_PUBLISHED: '변경 제안을 발행했어요',
  MEETING_CLOSED: '회의를 끝냈어요',
  PROJECT_CREATED: '프로젝트를 만들었어요',
  MEMBER_JOINED: '프로젝트에 참여했어요',
  MEMBER_LEFT: '프로젝트에서 나갔어요',
  // 제외된 사람의 이름은 남기지 않는다(PR #187 표기 안내)
  MEMBER_REMOVED: '팀원을 제외했어요',
  // 행위자가 대개 '○○의 에이전트'라 '에이전트'를 다시 쓰지 않는다
  AGENT_CONNECTED: '연결됐어요',
  AGENT_DISCONNECTED: '연결이 끊겼어요',
  MOCK_PROJECT_PREPARED: '예시 프로젝트를 준비했어요',
}

// 위임(LEADER_TRANSFERRED)의 target_id는 새 팀장의 멤버 id다. 팀원 목록(활성 멤버만)에 없으면 이름 없이 쓴다.
export function activityText(
  item: { type: string; target_id?: string },
  members: { id: string; name: string }[] = [],
) {
  if (item.type === 'LEADER_TRANSFERRED') {
    const leader = members.find((member) => member.id === item.target_id)
    return leader ? `${leader.name}님에게 팀장을 넘겼어요` : '팀장을 넘겼어요'
  }
  return activityLabels[item.type] ?? '활동을 기록했어요'
}

// 출처 위치(sources.location)의 'L4-L6'을 화면 표기 '4~6행'으로. 비비·변경 제안 초안·회의 요약·설계 초안은 서버가 L 표기로 준다.
export const lineRangeText = (location: string) =>
  location.replace(/\bL(\d+)-L(\d+)\b/g, '$1~$2행')

// 활동 행위자 이름 뒤 조사. 비비·시스템·에이전트도 사람처럼 '님이'다(사용자 결정 11-09): '비비님이', '시스템님이'.
export const ACTOR_PARTICLE = '님이'

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

// 업무 창 목록에서 고른 항목 표시. 작업 목록의 노란 테두리·바탕을 기준으로 작업·변경 제안·회의·설계 관계 목록이 같이 쓴다
// (사용자 요청 11-02). 탭 밑줄, 단축 바의 열린 칸, 비비 대화 테두리는 목록 선택이 아니라 따로 둔다.
export const LIST_SELECTED = 'border-primary/30 bg-primary/5'

// 나의 도시의 안 읽은 알림 배지. 이름표와 프로젝트 카드의 오른쪽 위에 걸친다.
export const UNREAD_BADGE =
  'absolute -top-[9px] -right-[9px] inline-flex h-[22px] min-w-[22px] items-center justify-center rounded-full bg-destructive px-1.5 text-xs leading-4 font-bold text-white ring-2 ring-white'
