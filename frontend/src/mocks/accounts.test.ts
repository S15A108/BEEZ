/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, membersApi, projectsApi } from '@/api/services'
import {
  getApiErrorCode,
  getApiErrorDetails,
  getApiErrorStatus,
} from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'
import { loadMockDatabase } from './state'

// 계정 규칙(PR #118 ACC-A05~A07·A10·A13)을 목이 서버와 같게 따르는지 본다.
// 예시 계정: 이팀장(11)은 프로젝트 101(팀원 3명)·102(팀원 1명)의 팀장, 김동료(12)는 두 프로젝트의 팀원이다.
const MEMBER_EMAIL = 'member@beez.local'
let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => restore())

const failure = (request: Promise<unknown>) =>
  request.then(
    () => undefined,
    (error: unknown) => error,
  )

it('이름이 틀린 비밀번호 찾기도 같은 202 안내를 주고, 같은 이메일로 곧바로 다시 요청하면 429 rate_limited', async () => {
  const sent = await authApi.forgotPassword({
    email: MEMBER_EMAIL,
    name: '다른 이름',
  })
  expect(sent.message).toBe(
    '입력한 정보가 맞으면 임시 비밀번호를 메일로 보냅니다.',
  )
  const again = await failure(
    authApi.forgotPassword({ email: MEMBER_EMAIL, name: '김동료' }),
  )
  expect(getApiErrorStatus(again)).toBe(429)
  expect(getApiErrorCode(again)).toBe('rate_limited')
})

it('임시 비밀번호를 받아도 기존 비밀번호로 로그인되고 must_change_password는 false다', async () => {
  const sent = await authApi.forgotPassword({
    email: MEMBER_EMAIL,
    name: '김동료',
  })
  expect(sent.message).toMatch(/임시 비밀번호: \S{12}$/)
  const account = await authApi.login({
    email: MEMBER_EMAIL,
    password: DEMO_PASSWORD,
  })
  expect(account.must_change_password).toBe(false)
})

it('프로필 수정은 avatar ""면 지우고 생략하면 두며, 빈 이름은 400 validation_failed', async () => {
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  const cleared = await authApi.updateMe({ avatar: '' })
  expect(cleared).not.toHaveProperty('avatar')
  expect(cleared.must_change_password).toBe(false)
  expect((await authApi.updateMe({ name: '김동료2' })).name).toBe('김동료2')
  const empty = await failure(authApi.updateMe({ name: '  ' }))
  expect(getApiErrorCode(empty)).toBe('validation_failed')
})

it('탈퇴 가능 여부는 다른 팀원이 있는 프로젝트의 팀장만 막고 member_count는 본인을 뺀 수다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  expect(await authApi.withdrawalCheck()).toEqual({
    can_withdraw: false,
    blocked_projects: [
      expect.objectContaining({
        project_id: '101',
        my_role: 'LEADER',
        member_count: 3,
      }),
      expect.objectContaining({
        project_id: '102',
        my_role: 'LEADER',
        member_count: 1,
      }),
    ],
  })
  await authApi.logout()
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  expect(await authApi.withdrawalCheck()).toEqual({
    can_withdraw: true,
    blocked_projects: [],
  })
})

it('탈퇴 비밀번호가 틀리면 401이 아닌 400 invalid_credentials이고 세션은 남는다', async () => {
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  const wrong = await failure(authApi.withdraw({ password: 'wrong-pass-1' }))
  expect(getApiErrorStatus(wrong)).toBe(400)
  expect(getApiErrorCode(wrong)).toBe('invalid_credentials')
  expect((await authApi.me()).email).toBe(MEMBER_EMAIL)
})

it('팀 프로젝트의 팀장이 탈퇴하면 409 leader_of_team_projects와 막는 프로젝트를 details로 준다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  const blocked = await failure(authApi.withdraw({ password: DEMO_PASSWORD }))
  expect(getApiErrorStatus(blocked)).toBe(409)
  expect(getApiErrorCode(blocked)).toBe('leader_of_team_projects')
  expect(getApiErrorDetails(blocked)).toEqual({
    blocked_projects: [
      expect.objectContaining({ project_id: '101', member_count: 3 }),
      expect.objectContaining({ project_id: '102', member_count: 1 }),
    ],
  })
})

it('탈퇴하면 혼자 쓰는 프로젝트는 지우고 팀원으로 참여한 프로젝트에서는 빠지며 다시 로그인할 수 없다', async () => {
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  const solo = await projectsApi.create({ name: '혼자 쓰는 프로젝트' })
  await authApi.withdraw({ password: DEMO_PASSWORD })
  expect(getApiErrorStatus(await failure(authApi.me()))).toBe(401)
  expect(
    getApiErrorCode(
      await failure(
        authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD }),
      ),
    ),
  ).toBe('invalid_credentials')
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  expect(
    (await membersApi.list('101')).items.map((member) => member.account_id),
  ).not.toContain('12')
  expect(
    loadMockDatabase().projects.map((item) => item.project.id),
  ).not.toContain(solo.id)
})
