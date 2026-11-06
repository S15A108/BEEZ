import { expect, it } from 'vitest'
import {
  codeMismatchDetailsSchema,
  confirmEmailVerificationResponseSchema,
  leaderOfTeamProjectsDetailsSchema,
  loginResponseSchema,
  registerInputSchema,
  sendEmailVerificationResponseSchema,
  withdrawalCheckResponseSchema,
} from './schemas'

// 응답 예시는 dev docs/api/01-backend-rest.md ACC-A11·A12와 같다. verification_id는 순번이 아닌 난수 문자열이다(PR #114 2f3e1c9).
it('인증 코드 보내기 응답을 받고, 필드가 빠지면 거부한다', () => {
  const sent = {
    verification_id: 'k3Jq9xV2mT7pLr0sYwE5bN8cHd1fGa4uZiQoXeRtMvA',
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

// S15P31A108-283: 로그인·내 계정 응답의 must_change_password(PR #118)는 예전 응답에 없어도 받는다.
it('로그인 응답은 must_change_password가 있으면 살리고 없어도 받는다', () => {
  const account = {
    id: '11',
    email: 'member@example.com',
    name: '김개발',
    created_at: '2026-10-23T03:00:00Z',
  }
  expect(
    loginResponseSchema.parse({ ...account, must_change_password: true }),
  ).toEqual({ ...account, must_change_password: true })
  expect(loginResponseSchema.parse(account)).toEqual(account)
})

// 응답 예시는 dev docs/api/01-backend-rest.md ACC-A13과 같다. ACC-A10 409의 details도 같은 BlockedProject 목록이다.
it('탈퇴 가능 여부와 409 details의 막는 프로젝트를 받고, 팀원 수가 빠지면 거부한다', () => {
  const blocked = {
    project_id: '101',
    name: 'BEEZ',
    my_role: 'LEADER',
    member_count: 4,
  }
  expect(
    withdrawalCheckResponseSchema.parse({
      can_withdraw: false,
      blocked_projects: [blocked],
    }).blocked_projects,
  ).toEqual([blocked])
  expect(
    leaderOfTeamProjectsDetailsSchema.parse({ blocked_projects: [blocked] }),
  ).toEqual({ blocked_projects: [blocked] })
  expect(
    leaderOfTeamProjectsDetailsSchema.safeParse({
      blocked_projects: [{ ...blocked, member_count: undefined }],
    }).success,
  ).toBe(false)
})
