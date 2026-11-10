import { isAxiosError } from 'axios'
import { z } from 'zod'

export const apiErrorSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string().optional(),
    // 명세가 정한 오류만 채운다(예: ACC-A12 remaining_attempts). 없으면 빠진다.
    details: z.record(z.string(), z.unknown()).optional(),
  }),
})
export function getApiErrorCode(error: unknown): string | undefined {
  if (!isAxiosError(error)) return undefined
  const parsed = apiErrorSchema.safeParse(error.response?.data)
  return parsed.success ? parsed.data.error.code : undefined
}
// 오류 본문의 error.details 객체(예: ACC-A12 remaining_attempts). 서버 문구 원문은 아래 getApiErrorDetail이다.
export function getApiErrorDetails(
  error: unknown,
): Record<string, unknown> | undefined {
  if (!isAxiosError(error)) return undefined
  return apiErrorSchema.safeParse(error.response?.data).data?.error.details
}
// 서버가 보낸 오류 문구 원문. 'design_item_id=301'처럼 어느 항목 때문인지 찾을 때 쓰고, 화면 문구는 getApiErrorMessage로 보인다.
export function getApiErrorDetail(error: unknown): string | undefined {
  if (!isAxiosError(error)) return undefined
  return apiErrorSchema.safeParse(error.response?.data).data?.error.message
}
export function getApiErrorStatus(error: unknown): number | undefined {
  return isAxiosError(error) ? error.response?.status : undefined
}

// 서버에 아직 없는 API인지. 백엔드는 없는 경로를 404 not_found + '없는 API 경로입니다'로, 없는 메서드를 405로 돌려준다
// (GlobalExceptionHandler). 있는 API의 '항목 없음' 404와 섞이지 않게 404는 그 문구이거나 오류 본문이 없을 때만 본다.
export const MISSING_API_MESSAGE = '없는 API 경로입니다'
export function isMissingApi(error: unknown): boolean {
  const status = getApiErrorStatus(error)
  if (status === 405 || status === 501) return true
  if (status !== 404 || !isAxiosError(error)) return false
  const parsed = apiErrorSchema.safeParse(error.response?.data)
  return !parsed.success || parsed.data.error.message === MISSING_API_MESSAGE
}

// 조회 재시도: 다시 불러도 같은 4xx(400·401·403·404·409, 없는 API 포함)와 응답 모양 오류는 바로 보이고,
// 서버 오류(5xx)와 응답을 못 받은 네트워크 오류만 3번까지 다시 부른다.
export function shouldRetryQuery(failureCount: number, error: unknown) {
  if (!isAxiosError(error)) return false
  const status = error.response?.status
  return (status === undefined || status >= 500) && failureCount < 3
}

// 서버에 아직 없을 수 있는 API는 없는 API(isMissingApi)일 때만 undefined로 바꿔, 그 부분만 '준비 중'으로 두고
// 화면 전체가 실패하지 않게 한다. 있는 API의 '항목 없음' 404 같은 다른 오류는 그대로 보인다.
export const unlessMissing = <T>(request: Promise<T>) =>
  request.catch((error: unknown) => {
    if (isMissingApi(error)) return undefined
    throw error
  })
const messages: Record<string, string> = {
  unauthenticated: '로그인이 필요합니다.',
  invalid_credentials: '이메일 또는 비밀번호를 확인해 주세요.',
  current_password_mismatch: '현재 비밀번호가 맞지 않아요.',
  forbidden: '이 작업의 권한이 없습니다.',
  not_found: '이 항목을 찾을 수 없습니다.',
  email_already_exists: '이미 가입된 이메일입니다.',
  email_not_verified:
    '이메일 인증이 만료됐거나 이미 사용됐어요. 이메일을 다시 인증해 주세요.',
  validation_failed: '입력 내용을 확인해 주세요.',
  version_conflict: '설계가 변경되었습니다. 최신 내용을 확인해 주세요.',
  summary_outdated:
    '회의 정리 또는 기준 문서가 바뀌었어요. 최신 내용을 확인한 뒤 다시 시도해 주세요.',
  revision_conflict: '작업 계획이 변경되었습니다. 다시 확인해 주세요.',
  baseline_conflict:
    '기준 설계가 변경되었습니다. 최신 기준으로 다시 검토해 주세요.',
  proposal_changed: '제안이 변경되었습니다. 최신 내용을 확인해 주세요.',
  // S15P31A108-204: 승인 게이트(241)와 상태 전이 오류. 화면이 따로 다루는 코드(analysis_running 등)도 일반 안내를 둔다.
  invalid_transition:
    '이미 처리했거나 지금은 할 수 없는 상태예요. 목록을 새로고침해 주세요.',
  baseline_missing: '먼저 첫 기준 설계를 만들어 주세요.',
  analysis_running: '영향 분석이 진행 중이에요. 끝난 뒤 다시 승인해 주세요.',
  impact_not_confirmed:
    '영향 분석이 완료되지 않았어요. 영향을 직접 확인했다면 사유를 남기고 승인해 주세요.',
  proposal_locked: '제출한 제안은 잠겨 있어요. 반려된 뒤에 고칠 수 있어요.',
  idempotency_conflict:
    '같은 요청이 다른 내용으로 이미 처리됐어요. 새로고침해서 확인해 주세요.',
  draft_changed: '검토 후 초안이 변경되었습니다.',
  validation_not_passed: '현재 계획의 검증을 먼저 완료해 주세요.',
  // 같은 입력으로 분석을 정한 횟수만큼 돌렸다(VAL-A01·A04). 재시도와 새 검증이 함께 막히고, 계획 입력 해시에 들지 않는 제목·접근 방법만 바꿔서는 풀리지 않는다.
  validation_attempt_limit:
    '같은 계획으로 분석할 수 있는 횟수를 모두 썼어요. 목표·범위·완료 기준이나 설계 참조를 고쳐 새로 검증하거나 팀에 알려 주세요.',
  dependency_not_completed: '선행 작업이 아직 완료되지 않았습니다.',
  task_conflict: '같은 설계를 변경하는 작업을 확인해 주세요.',
  ai_unavailable: '분석을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  ai_timeout: '분석 시간이 초과되었습니다. 다시 시도해 주세요.',
  recommendation_stale: '작업이나 팀원 정보가 바뀌었어요. 다시 추천해 주세요.',
  rate_limited: '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  file_too_large: '파일 용량이 허용 범위를 초과했습니다.',
  document_kind_exists: '같은 종류의 문서가 이미 있어요.',
  unsupported_media_type: '지원하지 않는 파일 형식입니다.',
  leader_transfer_required: '인간 팀장을 먼저 위임해 주세요.',
  leader_not_removable: '팀장은 회의에서 뺄 수 없어요.',
  meeting_closed: '끝난 회의라 바꿀 수 없어요.',
  invalid_transfer_target: '이 팀원에게는 넘길 수 없어요.',
  project_membership_remaining: '참여 중인 프로젝트를 먼저 정리해 주세요.',
  leader_of_team_projects:
    '팀원이 있는 프로젝트의 팀장이라 탈퇴할 수 없어요. 팀장을 위임하거나 프로젝트를 삭제해 주세요.',
  analysis_not_completed:
    '문서 4종의 색인이 아직 끝나지 않았어요. 색인이 끝난 뒤 다시 기준을 만들어 주세요.',
  document_not_approved:
    '아직 승인하지 않은 문서가 있어요. 4종을 모두 승인한 뒤 기준을 만들어 주세요.',
  baseline_exists:
    '이미 기준 v1이 있어요. 새로고침해서 지금 기준을 확인해 주세요.',
  required_documents_missing: '필수 문서 4종을 먼저 올려 주세요.',
  change_proposal_required:
    '기준 설계 생성 이후 문서 수정은 변경 제안을 통해 요청해 주세요.',
  submission_locked: '팀장 검토를 요청한 뒤에는 고칠 수 없어요.',
  review_stale: '본문이 바뀌어 비비 검토를 다시 받아야 해요.',
  review_decisions_pending:
    '처리하지 않은 보완 제안이 있어요. 반영하거나 넘겨 주세요.',
  submission_decided: '이미 결정한 검토 요청이에요.',
  // 202 PR-2: 문서 검토 대화·직접 요청·검토 요청 취소
  submission_withdrawn:
    '올린 사람이 이 검토 요청을 거뒀어요. 목록을 새로고침해 주세요.',
  review_in_progress:
    '비비가 이 문서를 검토하고 있어요. 잠시 뒤 다시 확인해 주세요.',
  review_unavailable:
    '지금은 비비를 부를 수 없어요. 잠시 뒤 다시 시도해 주세요.',
  invalid_ai_output: '비비의 답을 확인하지 못했어요. 다시 시도해 주세요.',
  review_chat_limit:
    '이 검토에서 비비와 나눌 수 있는 대화를 모두 썼어요. 직접 고치거나 비비 검토를 다시 받아 주세요.',
  review_request_limit:
    '이 검토에서 비비에게 직접 요청할 수 있는 횟수를 모두 썼어요.',
  already_member: '이미 팀원이에요.',
  invitation_email_mismatch: '초대받은 이메일로 로그인해 주세요.',
  invitation_not_pending: '이미 참여했거나 취소된 초대예요.',
  agent_limit_reached:
    '활성 토큰은 프로젝트마다 5개까지예요. 쓰지 않는 토큰을 폐기한 뒤 다시 발급해 주세요.',
}
// S15P31A108-202 기획 보완 질문·초안 후보(PR-1)의 오류. 다른 PR과 겹치지 않게 따로 둔다.
Object.assign(messages, {
  candidate_stale:
    '후보를 만든 뒤 문서나 답변, 설계 항목이 바뀌었어요. 초안을 다시 만들어 주세요.',
  candidate_discarded: '이미 폐기한 후보예요. 목록을 새로고침해 주세요.',
  candidate_adopted: '이미 채택한 후보예요. 목록을 새로고침해 주세요.',
  draft_in_progress: '초안을 만드는 중이에요. 끝난 뒤 다시 시도해 주세요.',
  question_superseded:
    '문서가 다시 분석되어 질문이 바뀌었어요. 목록을 새로고침해 주세요.',
  design_already_published:
    '이미 기준 설계가 발행되어 답변을 바꿀 수 없어요. 변경 제안에서 요청해 주세요.',
  invalid_ai_output:
    '비비가 만든 결과를 쓸 수 없었어요. 잠시 후 다시 시도해 주세요.',
})
// 오류 코드만 있을 때(초안 작업 실패의 error_code 등) 안내 문구. 없으면 undefined.
export function getErrorCodeMessage(code: string | undefined) {
  return code ? messages[code] : undefined
}
export function getApiErrorMessage(error: unknown): string {
  if (error instanceof z.ZodError)
    return '서버 응답 형식이 API 계약과 다릅니다.'
  if (isMissingApi(error)) return '이 기능은 서버에서 아직 준비 중이에요.'
  const code = getApiErrorCode(error)
  if (code && messages[code]) return messages[code]
  if (isAxiosError(error) && !error.response)
    return '서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.'
  return '요청을 완료하지 못했습니다. 다시 시도해 주세요.'
}
// 나가기·제외·탈퇴는 담당자를 넘기거나 비우는 동안 그 작업 상태가 바뀌면 409 revision_conflict로 전체가 되돌아간다(MEM-A09·A10, ACC-A10).
// 계획을 고치는 화면과 달리 다시 확인할 내용이 없으므로 다시 시도하라고 알린다.
export function getHandoverErrorMessage(error: unknown): string {
  return getApiErrorCode(error) === 'revision_conflict'
    ? '작업 상태가 방금 바뀌었어요. 다시 시도해 주세요.'
    : getApiErrorMessage(error)
}
