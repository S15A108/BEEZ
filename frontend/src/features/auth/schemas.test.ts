import { expect, it } from 'vitest'
import {
  codeMismatchDetailsSchema,
  confirmEmailVerificationResponseSchema,
  registerInputSchema,
  sendEmailVerificationResponseSchema,
} from './schemas'

// 응답 예시는 PR #114 docs/api/01-backend-rest.md ACC-A11·A12와 같다.
it('인증 코드 보내기 응답을 받고, 필드가 빠지면 거부한다', () => {
  const sent = {
    verification_id: '41',
    expires_at: '2026-10-28T03:05:00Z',
    resend_available_at: '2026-10-28T03:01:00Z',
  }
  expect(sendEmailVerificationResponseSchema.parse(sent)).toEqual(sent)
  expect(
    sendEmailVerificationResponseSchema.safeParse({
      ...sent,
      resend_available_at: undefined,
    }).success,
  ).toBe(false)
  expect(
    sendEmailVerificationResponseSchema.safeParse({
      ...sent,
      verification_id: 41,
    }).success,
  ).toBe(false)
})

it('인증 코드 확인 응답과 남은 시도 details를 받는다', () => {
  const confirmed = {
    verified_token: 'evt_example',
    email: 'member@example.com',
    expires_at: '2026-10-28T03:35:00Z',
  }
  expect(confirmEmailVerificationResponseSchema.parse(confirmed)).toEqual(
    confirmed,
  )
  expect(
    confirmEmailVerificationResponseSchema.safeParse({
      ...confirmed,
      verified_token: undefined,
    }).success,
  ).toBe(false)
  expect(codeMismatchDetailsSchema.parse({ remaining_attempts: 4 })).toEqual({
    remaining_attempts: 4,
  })
})

it('회원가입 요청에는 verified_token이 있어야 한다', () => {
  const input = {
    email: 'member@example.com',
    password: 'example-password1',
    name: '김개발',
  }
  expect(registerInputSchema.safeParse(input).success).toBe(false)
  expect(
    registerInputSchema.parse({ ...input, verified_token: 'evt_example' }),
  ).toEqual({ ...input, verified_token: 'evt_example' })
})
