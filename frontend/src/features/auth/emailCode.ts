// 가입 이메일 인증(ACC-A11 코드 보내기 · ACC-A12 코드 확인, ACC-A01 verified_token)은 dev의 docs/api에 들어왔지만
// 상태가 '수정안 요청(협의 필요)'이라 서버 구현이 정해지지 않았다. 그때까지는 실제 API를 부르지 않고, 목 모드에서만
// 같은 응답 모양을 브라우저 안에서 흉내 낸다. 협의가 끝나면 sendCode·confirmCode를 authApi 호출로 바꾸고 가입 요청에 verified_token을 넣는다.

export const CODE_TTL_MS = 5 * 60_000
export const RESEND_GAP_MS = 30_000
export const MAX_ATTEMPTS = 5

export class VerificationError extends Error {
  readonly code
  readonly remaining
  constructor(
    code:
      | 'verification_code_mismatch'
      | 'verification_expired'
      | 'too_many_attempts'
      | 'not_found',
    remaining?: number,
  ) {
    super(code)
    this.code = code
    this.remaining = remaining
  }
}

const pending = new Map<
  string,
  { email: string; code: string; expiresAt: number; attempts: number }
>()
let sequence = 0

export function sendCode(email: string, now = Date.now()) {
  // 다시 받으면 이전 코드는 바로 무효다.
  for (const [id, item] of pending) if (item.email === email) pending.delete(id)
  const id = `mock-verification-${++sequence}`
  const code = String(Math.floor(100000 + Math.random() * 900000))
  pending.set(id, { email, code, expiresAt: now + CODE_TTL_MS, attempts: 0 })
  return {
    verification_id: id,
    expires_at: new Date(now + CODE_TTL_MS).toISOString(),
    resend_available_at: new Date(now + RESEND_GAP_MS).toISOString(),
    // 목 모드는 메일을 보내지 않아 화면에 예시 코드를 보여 준다.
    mock_code: code,
  }
}

export function confirmCode(id: string, code: string, now = Date.now()) {
  const item = pending.get(id)
  if (!item) throw new VerificationError('not_found')
  if (now >= item.expiresAt) {
    pending.delete(id)
    throw new VerificationError('verification_expired')
  }
  if (item.code !== code.trim()) {
    item.attempts += 1
    const remaining = MAX_ATTEMPTS - item.attempts
    if (remaining <= 0) {
      pending.delete(id)
      throw new VerificationError('too_many_attempts')
    }
    throw new VerificationError('verification_code_mismatch', remaining)
  }
  pending.delete(id)
  return {
    verified_token: `mock-verified-${id}`,
    email: item.email,
    expires_at: new Date(now + 10 * 60_000).toISOString(),
  }
}

export function verificationMessage(error: unknown) {
  if (!(error instanceof VerificationError))
    return '인증하지 못했어요. 다시 시도해 주세요.'
  if (error.code === 'verification_code_mismatch')
    return `코드가 맞지 않아요 (남은 시도 ${error.remaining}번)`
  if (error.code === 'too_many_attempts')
    return '틀린 횟수를 넘었어요. 코드를 다시 받아 주세요.'
  return '인증 시간이 지났어요. 코드를 다시 받아 주세요.'
}

export const clock = (ms: number) => {
  const seconds = Math.max(0, Math.ceil(ms / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
