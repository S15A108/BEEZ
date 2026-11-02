/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, membersApi } from '@/api/services'
import { getApiErrorCode } from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// Q05 초대 규칙(PR #42, 2026-10-29 확정)을 목이 따르는지 본다.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

const leader = () =>
  authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
const invite = async (email: string) => {
  const result = await membersApi.createInvitation('101', { email })
  return new URL(result.invite_url).searchParams.get('token')!
}
// member@beez.local 계정이 김동료다.
const colleagueIsMember = async () =>
  (await membersApi.list('101', { limit: 100 })).items.some(
    (member) => member.name === '김동료',
  )

it('초대할 때는 팀원이 아니고, 그 이메일로 가입하면 팀원이 된다. 만료는 없다', async () => {
  const result = await membersApi.createInvitation('101', {
    email: 'New@Beez.local ',
  })
  expect(result.invitation.expires_at).toBeUndefined()
  expect(
    (await membersApi.list('101', { limit: 100 })).items.map((m) => m.name),
  ).not.toContain('새 팀원')
  await authApi.register({
    name: '새 팀원',
    email: 'new@beez.local',
    password: 'beez-2026!',
  })
  expect(
    (await membersApi.list('101', { limit: 100 })).items.map((m) => m.name),
  ).toContain('새 팀원')
})

it('이미 팀원인 이메일은 409 already_member', async () => {
  const error = await membersApi
    .createInvitation('101', { email: 'member@beez.local' })
    .catch((cause: unknown) => cause)
  expect(getApiErrorCode(error)).toBe('already_member')
})

it('스스로 나간 사람은 다시 초대받아도 로그인만으로는 돌아오지 않고 링크를 열면 참여한다', async () => {
  const leaderId = (await membersApi.list('101', { limit: 100 })).items.find(
    (member) => member.role === 'LEADER',
  )!.id
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  await membersApi.leave('101', { reassign_to_member_id: leaderId })
  await leader()
  const token = await invite('member@beez.local')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  expect(await membersApi.list('101').catch(() => null)).toBeNull()
  const joined = await membersApi.acceptInvitation({ token })
  expect(joined.project.id).toBe('101')
  expect(await colleagueIsMember()).toBe(true)
})

it('팀장이 제외한 사람은 다시 초대하면 로그인할 때 참여한다', async () => {
  const members = (await membersApi.list('101', { limit: 100 })).items
  const leaderId = members.find((member) => member.role === 'LEADER')!.id
  const colleague = members.find((member) => member.name === '김동료')!
  await membersApi.remove('101', colleague.id, {
    reassign_to_member_id: leaderId,
  })
  await invite('member@beez.local')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  expect(await colleagueIsMember()).toBe(true)
})
