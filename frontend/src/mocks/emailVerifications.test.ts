/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getApiErrorCode, getApiErrorDetails } from '@/api/error'
import { authApi } from '@/api/services'
import { verifyEmail } from '@/test/verifyEmail'
import {
  CODE_TTL_MS,
  RESEND_INTERVAL_MS,
  SEND_LIMIT,
  sentCode,
  TOKEN_TTL_MS,
} from './emailVerifications'
import { DEMO_EMAIL, resetMockApi, setupMockApi } from './setup'

// 목 규칙이 서버(PR #114 EmailVerificationService)와 같은지 본다. 다시 받기 30초·토큰 10분은 기존 목 값이다.
let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-04T00:00:00Z'))
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => {
  restore()
  vi.useRealTimers()
})

const later = (ms: number) => vi.setSystemTime(Date.now() + ms)
const codeOf = (error: unknown) => getApiErrorCode(error)
const caught = (request: Promise<unknown>) =>
  request.then(
    () => {
      throw new Error('통과하면 안 된다')
    },
    (error: unknown) => error,
  )
const send = (email: string) => authApi.sendEmailVerification({ email })
const confirm = (id: string, code: string) =>
  authApi.confirmEmailVerification(id, { code })
const wrong = (code: string) => (code === '000000' ? '111111' : '000000')
const register = (email: string, verified_token: string) =>
  authApi.register({
    name: '새 팀원',
    email,
    password: 'beez-2026!',
    verified_token,
  })

describe('가입 인증 코드 보내기 (ACC-A11)', () => {
  it('보내면 5분 뒤 만료와 30초 뒤 다시 받기 시각을 준다', async () => {
    const sent = await send('a@beez.local')
    expect(sent.verification_id).toMatch(/^\d+$/)
    expect(sent.expires_at).toBe(
      new Date(Date.now() + CODE_TTL_MS).toISOString(),
    )
    expect(sent.resend_available_at).toBe(
      new Date(Date.now() + RESEND_INTERVAL_MS).toISOString(),
    )
  })

  it('30초 안에 다시 받으면 429 rate_limited이고 이전 코드는 그대로 쓸 수 있다', async () => {
    const first = await send('b@beez.local')
    later(RESEND_INTERVAL_MS - 1)
    expect(codeOf(await caught(send('b@beez.local')))).toBe('rate_limited')
    await expect(
      confirm(first.verification_id, sentCode(first.verification_id)!),
    ).resolves.toMatchObject({ email: 'b@beez.local' })
  })

  it('다시 받으면 이전 코드는 410이 되고, 한 시간에 5번까지만 보낸다', async () => {
    const first = await send('c@beez.local')
    for (let i = 1; i < SEND_LIMIT; i += 1) {
      later(RESEND_INTERVAL_MS)
      await send('c@beez.local')
    }
    expect(
      codeOf(
        await caught(
          confirm(first.verification_id, sentCode(first.verification_id)!),
        ),
      ),
    ).toBe('verification_expired')
    later(RESEND_INTERVAL_MS)
    expect(codeOf(await caught(send('c@beez.local')))).toBe('rate_limited')
    later(60 * 60_000)
    await expect(send('c@beez.local')).resolves.toBeDefined()
  })

  it('이미 가입한 이메일은 409, 형식이 틀리면 400', async () => {
    expect(codeOf(await caught(send(DEMO_EMAIL)))).toBe('email_already_exists')
    expect(codeOf(await caught(send('not-an-email')))).toBe('validation_failed')
  })
})

describe('가입 인증 코드 확인 (ACC-A12)', () => {
  it('틀리면 남은 시도를 주고, 다섯 번째로 틀리면 429이며 그 뒤엔 맞는 코드도 429다', async () => {
    const { verification_id: id } = await send('d@beez.local')
    const code = sentCode(id)!
    for (const remaining of [4, 3, 2, 1]) {
      const error = await caught(confirm(id, wrong(code)))
      expect(codeOf(error)).toBe('verification_code_mismatch')
      expect(getApiErrorDetails(error)).toEqual({
        remaining_attempts: remaining,
      })
    }
    expect(codeOf(await caught(confirm(id, wrong(code))))).toBe(
      'too_many_attempts',
    )
    expect(codeOf(await caught(confirm(id, code)))).toBe('too_many_attempts')
  })

  it('5분이 지난 코드와 이미 확인한 코드는 410, 없는 요청은 404', async () => {
    const { verification_id: id } = await send('e@beez.local')
    later(CODE_TTL_MS)
    expect(codeOf(await caught(confirm(id, sentCode(id)!)))).toBe(
      'verification_expired',
    )
    later(RESEND_INTERVAL_MS)
    const again = await send('e@beez.local')
    const code = sentCode(again.verification_id)!
    await confirm(again.verification_id, code)
    expect(codeOf(await caught(confirm(again.verification_id, code)))).toBe(
      'verification_expired',
    )
    expect(codeOf(await caught(confirm('999', code)))).toBe('not_found')
  })

  it('토큰은 10분 동안 쓸 수 있다', async () => {
    const { verification_id: id } = await send('f@beez.local')
    const result = await confirm(id, sentCode(id)!)
    expect(result.verified_token).toMatch(/^evt_/)
    expect(result.expires_at).toBe(
      new Date(Date.now() + TOKEN_TTL_MS).toISOString(),
    )
  })
})

describe('회원가입 토큰 (ACC-A01)', () => {
  it('토큰이 없거나 10분이 지났으면 400 email_not_verified', async () => {
    expect(codeOf(await caught(register('g@beez.local', '')))).toBe(
      'email_not_verified',
    )
    const token = await verifyEmail('g@beez.local')
    later(TOKEN_TTL_MS)
    expect(codeOf(await caught(register('g@beez.local', token)))).toBe(
      'email_not_verified',
    )
  })

  it('다른 이메일의 토큰은 거부하고 토큰은 남으며, 가입에 쓴 토큰은 다시 못 쓴다', async () => {
    const token = await verifyEmail('h@beez.local')
    expect(codeOf(await caught(register('other@beez.local', token)))).toBe(
      'email_not_verified',
    )
    await expect(register('h@beez.local', token)).resolves.toMatchObject({
      email: 'h@beez.local',
    })
    await authApi.logout()
    expect(codeOf(await caught(register('h@beez.local', token)))).toBe(
      'email_not_verified',
    )
  })
})
