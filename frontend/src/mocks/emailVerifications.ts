import { fail, requiredString } from './helpers'
import type { MockContext, MockReply } from './helpers'

// 가입 이메일 인증(ACC-A11·A12)과 가입 때 토큰 소비(ACC-A01). 판정 순서와 오류 코드는 PR #114 EmailVerificationService와 같다.
// 틀린 횟수는 오류 응답이어도 남아야 해서, 실패하면 되돌리는 예시 DB(state.ts) 대신 이 모듈에 둔다.
// 명세가 정한 시간은 코드 5분뿐이다. 다시 받기 간격(Q04)과 토큰 유효 시간은 숫자가 없어 기존 목 값을 둔다(서버 기본값은 60초·30분).
export const CODE_TTL_MS = 5 * 60_000
export const RESEND_INTERVAL_MS = 30_000
export const SEND_LIMIT = 5
const SEND_LIMIT_WINDOW_MS = 60 * 60_000
export const MAX_ATTEMPTS = 5
export const TOKEN_TTL_MS = 10 * 60_000

interface Verification {
  id: string
  email: string
  code: string
  created_at: number
  code_expires_at: number
  failed_attempts: number
  closed_at?: number
  token?: string
  token_expires_at?: number
  token_consumed_at?: number
}

const verifications: Verification[] = []

const iso = (ms: number) => new Date(ms).toISOString()
// 서버 SecretTokens.generate와 같은 32바이트 난수 base64url(43자). verification_id와 토큰에 쓴다.
const randomToken = () =>
  btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

export function resetEmailVerifications() {
  verifications.length = 0
}

/** 예시 환경은 메일을 보내지 않아 화면이 방금 보낸 코드를 여기서 읽는다. 서버 응답에는 없다. */
export function sentCode(verificationId: string) {
  return verifications.find((item) => item.id === verificationId)?.code
}

export function sendVerification({ database, body }: MockContext): MockReply {
  const email = requiredString(body, 'email').toLowerCase()
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    fail(400, 'validation_failed', '이메일 형식을 확인해 주세요.')
  if (database.accounts.some((item) => item.profile.email === email))
    fail(409, 'email_already_exists', '이미 등록된 이메일이에요.')
  const now = Date.now()
  const sent = verifications.filter((item) => item.email === email)
  if (sent.length && now < sent.at(-1)!.created_at + RESEND_INTERVAL_MS)
    fail(429, 'rate_limited', '다시 받기는 잠시 뒤에 할 수 있어요.')
  if (
    sent.filter((item) => item.created_at > now - SEND_LIMIT_WINDOW_MS)
      .length >= SEND_LIMIT
  )
    fail(429, 'rate_limited', '이 이메일로 보낼 수 있는 횟수를 넘었어요.')
  // 다시 받으면 이전 코드는 바로 무효다.
  for (const item of sent) item.closed_at ??= now
  const verification: Verification = {
    id: randomToken(),
    email,
    code: String(Math.floor(Math.random() * 1_000_000)).padStart(6, '0'),
    created_at: now,
    code_expires_at: now + CODE_TTL_MS,
    failed_attempts: 0,
  }
  verifications.push(verification)
  return {
    status: 201,
    data: {
      verification_id: verification.id,
      expires_at: iso(verification.code_expires_at),
      resend_available_at: iso(now + RESEND_INTERVAL_MS),
    },
  }
}

export function confirmVerification({ body, params }: MockContext): MockReply {
  const code = body.code
  if (typeof code !== 'string' || !/^\d{6}$/.test(code))
    fail(400, 'validation_failed', 'code: 6자리 숫자여야 합니다')
  const verification =
    verifications.find((item) => item.id === params.verification_id) ??
    fail(404, 'not_found', '없는 인증 요청이에요.')
  const now = Date.now()
  // 서버와 같은 순서: 이미 쓴 코드 → 틀린 횟수 초과 → 다시 받기로 닫힘·만료
  if (verification.token) expired()
  if (verification.failed_attempts >= MAX_ATTEMPTS) tooManyAttempts()
  if (verification.closed_at || now >= verification.code_expires_at) expired()
  if (code !== verification.code) {
    verification.failed_attempts += 1
    if (verification.failed_attempts >= MAX_ATTEMPTS) {
      verification.closed_at = now
      tooManyAttempts()
    }
    fail(400, 'verification_code_mismatch', '인증 코드가 맞지 않아요.', {
      remaining_attempts: MAX_ATTEMPTS - verification.failed_attempts,
    })
  }
  verification.closed_at = now
  verification.token = `evt_${randomToken()}`
  verification.token_expires_at = now + TOKEN_TTL_MS
  return {
    data: {
      verified_token: verification.token,
      email: verification.email,
      expires_at: iso(verification.token_expires_at),
    },
  }
}

/** ACC-A01: 이 이메일로 쓸 수 있는 토큰이면 소비 함수를 준다. 가입이 끝까지 성공한 뒤에 부른다. */
export function verifiedTokenFor(token: unknown, email: string) {
  const now = Date.now()
  const verification = verifications.find(
    (item) =>
      item.token !== undefined &&
      item.token === token &&
      !item.token_consumed_at &&
      now < item.token_expires_at! &&
      item.email === email,
  )
  if (!verification)
    fail(
      400,
      'email_not_verified',
      '이메일 인증이 없거나 만료됐거나 다른 이메일의 인증이에요.',
    )
  return () => {
    verification.token_consumed_at = Date.now()
  }
}

function expired(): never {
  fail(
    410,
    'verification_expired',
    '인증 코드가 만료됐거나 더 쓸 수 없어요. 코드를 다시 받아 주세요.',
  )
}

function tooManyAttempts(): never {
  fail(
    429,
    'too_many_attempts',
    '틀린 횟수를 넘었어요. 코드를 다시 받아 주세요.',
  )
}
