import { isAxiosError } from 'axios'
import { z } from 'zod'

export const apiErrorSchema = z.object({
  error: z.object({ code: z.string(), message: z.string().optional() }),
})
export function getApiErrorCode(error: unknown): string | undefined {
  if (!isAxiosError(error)) return undefined
  const parsed = apiErrorSchema.safeParse(error.response?.data)
  return parsed.success ? parsed.data.error.code : undefined
}
export function getApiErrorStatus(error: unknown): number | undefined {
  return isAxiosError(error) ? error.response?.status : undefined
}
const messages: Record<string, string> = {
  unauthenticated: '로그인이 필요합니다.',
  invalid_credentials: '이메일 또는 비밀번호를 확인해 주세요.',
  forbidden: '이 작업의 권한이 없습니다.',
  not_found: '이 항목을 찾을 수 없습니다.',
  email_already_exists: '이미 가입된 이메일입니다.',
  validation_failed: '입력 내용을 확인해 주세요.',
  version_conflict: '설계가 변경되었습니다. 최신 내용을 확인해 주세요.',
  revision_conflict: '작업 계획이 변경되었습니다. 다시 확인해 주세요.',
  baseline_conflict:
    '기준 설계가 변경되었습니다. 최신 기준으로 다시 검토해 주세요.',
  proposal_changed: '제안이 변경되었습니다. 최신 내용을 확인해 주세요.',
  draft_changed: '검토 후 초안이 변경되었습니다.',
  validation_not_passed: '현재 계획의 검증을 먼저 완료해 주세요.',
  dependency_not_completed: '선행 작업이 아직 완료되지 않았습니다.',
  task_conflict: '같은 설계를 변경하는 작업을 확인해 주세요.',
  ai_unavailable: '분석을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  ai_timeout: '분석 시간이 초과되었습니다. 다시 시도해 주세요.',
  rate_limited: '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  file_too_large: '파일 용량이 허용 범위를 초과했습니다.',
  unsupported_media_type: '지원하지 않는 파일 형식입니다.',
  invitation_expired: '초대 링크가 만료되었습니다.',
  reset_token_expired: '비밀번호 재설정 링크가 만료되었습니다.',
  leader_transfer_required: '인간 팀장을 먼저 위임해 주세요.',
  active_tasks_exist: '진행 중인 작업의 담당자를 먼저 정리해 주세요.',
  project_membership_remaining: '참여 중인 프로젝트를 먼저 정리해 주세요.',
}
export function getApiErrorMessage(error: unknown): string {
  if (error instanceof z.ZodError)
    return '서버 응답 형식이 API 계약과 다릅니다.'
  const code = getApiErrorCode(error)
  if (code && messages[code]) return messages[code]
  if (isAxiosError(error) && !error.response)
    return '서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.'
  return '요청을 완료하지 못했습니다. 다시 시도해 주세요.'
}
