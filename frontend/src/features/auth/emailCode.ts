import {
  getApiErrorCode,
  getApiErrorDetails,
  getApiErrorMessage,
  isMissingApi,
} from '@/api/error'
import { codeMismatchDetailsSchema } from './schemas'

// 가입 이메일 인증(ACC-A11 코드 보내기 · ACC-A12 코드 확인)의 오류 코드별 안내. 목 모드 규칙은 src/mocks/emailVerifications.ts에 있다.
const messages: Record<string, string> = {
  too_many_attempts: '틀린 횟수를 넘었어요. 코드를 다시 받아 주세요.',
  verification_expired: '인증 시간이 지났어요. 코드를 다시 받아 주세요.',
  not_found: '인증 요청을 찾을 수 없어요. 코드를 다시 받아 주세요.',
  email_already_exists: '이미 가입된 이메일이에요. 로그인해 주세요.',
  rate_limited: '코드를 너무 자주 받았어요. 잠시 뒤에 다시 받아 주세요.',
  mail_send_failed: '인증 메일을 보내지 못했어요. 다시 받아 주세요.',
}

export function verificationMessage(error: unknown) {
  const code = getApiErrorCode(error)
  if (code === 'verification_code_mismatch') {
    const details = codeMismatchDetailsSchema.safeParse(
      getApiErrorDetails(error),
    )
    return details.success
      ? `코드가 맞지 않아요 (남은 시도 ${details.data.remaining_attempts}번)`
      : '코드가 맞지 않아요.'
  }
  if (code && messages[code] && !isMissingApi(error)) return messages[code]
  return getApiErrorMessage(error)
}

export const clock = (ms: number) => {
  const seconds = Math.max(0, Math.ceil(ms / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
