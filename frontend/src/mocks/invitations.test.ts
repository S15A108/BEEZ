/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { api } from '@/api/client'
import { authApi, membersApi } from '@/api/services'
import { getApiErrorCode, getApiErrorStatus } from '@/api/error'
import { verifyEmail } from '@/test/verifyEmail'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'
import { loadMockDatabase } from './state'

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
  expect(result.invitation).not.toHaveProperty('expires_at')
  expect(
    (await membersApi.list('101', { limit: 100 })).items.map((m) => m.name),
  ).not.toContain('새 팀원')
  await authApi.register({
    name: '새 팀원',
    email: 'new@beez.local',
    password: 'beez-2026!',
    verified_token: await verifyEmail('new@beez.local'),
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

// PR #134(S15P31A108-220) 이메일 초대·다시 보내기·자동 참여 규칙. 링크 토큰은 메일에만 있어 목 저장소에서 꺼낸다.
const tokenFor = (email: string) =>
  loadMockDatabase().invitations.findLast(
    (item) => item.invited_email === email,
  )!.token
const failure = (request: Promise<unknown>) =>
  request.catch((cause: unknown) => cause)
const register = async (email: string) =>
  authApi.register({
    name: '새 팀원',
    email,
    password: 'beez-2026!',
    verified_token: await verifyEmail(email),
  })
// 팀원인데 남은 대기 초대(예: 나간 뒤 다시 초대받고 다른 링크로 돌아온 경우)
const leftOverInvitation = (email: string) =>
  loadMockDatabase().invitations.push({
    id: '9001',
    project_id: '101',
    invited_email: email,
    token: 'mock-invite-left-over',
  })

it('이메일 초대는 한 번에 보내고 이메일마다 새 초대·이미 팀원·이미 초대 중으로 답한다', async () => {
  const first = await membersApi.createEmailInvitations('101', {
    emails: ['new@beez.local'],
  })
  const { results } = await membersApi.createEmailInvitations('101', {
    emails: [
      'Other@Beez.local ',
      'member@beez.local',
      'new@beez.local',
      'other@beez.local',
    ],
    message: '같이 해요',
  })
  expect(results).toEqual([
    {
      email: 'other@beez.local',
      status: 'INVITED',
      invitation_id: expect.any(String),
    },
    { email: 'member@beez.local', status: 'ALREADY_MEMBER' },
    {
      email: 'new@beez.local',
      status: 'ALREADY_INVITED',
      invitation_id: first.results[0].invitation_id,
    },
  ])
})

// 화면 스키마가 먼저 막으므로 목 규칙은 클라이언트를 거치지 않고 본다.
it('email과 emails를 함께 보내거나 emails 없이 message를 보내면 400 validation_failed', async () => {
  for (const body of [
    { email: 'a@beez.local', emails: ['b@beez.local'] },
    { message: '안녕하세요' },
    { emails: [] },
    { emails: Array.from({ length: 21 }, (_, i) => `u${i}@beez.local`) },
    { emails: ['not-an-email'] },
    { emails: ['a@beez.local'], message: 'a'.repeat(501) },
  ]) {
    const error = await failure(api.post('/projects/101/invitations', body))
    expect(getApiErrorCode(error)).toBe('validation_failed')
  }
})

it('이메일로 초대받아 가입하면 팀원이 되고, 메일 링크로 돌아오면 미리보기는 되고 수락은 409 already_member', async () => {
  await membersApi.createEmailInvitations('101', {
    emails: ['new@beez.local'],
  })
  const token = tokenFor('new@beez.local')
  await register('new@beez.local')
  expect(await membersApi.previewInvitation({ token })).toMatchObject({
    account_matches: true,
  })
  expect(
    getApiErrorCode(await failure(membersApi.acceptInvitation({ token }))),
  ).toBe('already_member')
})

it('다시 보내면 새 링크가 생기고 이전 링크는 404', async () => {
  const { results } = await membersApi.createEmailInvitations('101', {
    emails: ['new@beez.local'],
  })
  const before = tokenFor('new@beez.local')
  const { invitation } = await membersApi.resendInvitation(
    '101',
    results[0].invitation_id!,
  )
  expect(invitation).toMatchObject({
    id: results[0].invitation_id,
    invited_email: 'new@beez.local',
  })
  const after = tokenFor('new@beez.local')
  expect(after).not.toBe(before)
  expect(
    getApiErrorStatus(
      await failure(membersApi.previewInvitation({ token: before })),
    ),
  ).toBe(404)
  expect(
    (await membersApi.previewInvitation({ token: after })).project_name,
  ).toBeTruthy()
})

it('취소한 초대를 다시 보내면 409 invitation_not_pending, 이메일 없는 링크 초대는 404', async () => {
  const { results } = await membersApi.createEmailInvitations('101', {
    emails: ['new@beez.local'],
  })
  await membersApi.revokeInvitation('101', results[0].invitation_id!)
  expect(
    getApiErrorCode(
      await failure(
        membersApi.resendInvitation('101', results[0].invitation_id!),
      ),
    ),
  ).toBe('invitation_not_pending')
  const link = await membersApi.createInvitation('101')
  expect(
    getApiErrorStatus(
      await failure(membersApi.resendInvitation('101', link.invitation.id)),
    ),
  ).toBe(404)
})

it('없는·취소한·이미 쓴 링크·다른 이메일 초대는 원인을 나누지 않고 404', async () => {
  const revoked = await membersApi.createEmailInvitations('101', {
    emails: ['gone@beez.local'],
  })
  const revokedToken = tokenFor('gone@beez.local')
  await membersApi.revokeInvitation('101', revoked.results[0].invitation_id!)
  await membersApi.createEmailInvitations('101', {
    emails: ['other@beez.local'],
  })
  const otherToken = tokenFor('other@beez.local')
  const link = await membersApi.createInvitation('101')
  const linkToken = new URL(link.invite_url).searchParams.get('token')!
  await register('new@beez.local')
  await membersApi.acceptInvitation({ token: linkToken })
  for (const token of ['mock-invite-unknown', revokedToken, linkToken])
    expect(
      getApiErrorStatus(await failure(membersApi.previewInvitation({ token }))),
    ).toBe(404)
  for (const token of ['mock-invite-unknown', revokedToken, otherToken])
    expect(
      getApiErrorStatus(await failure(membersApi.acceptInvitation({ token }))),
    ).toBe(404)
  // 미리보기는 다른 이메일 초대를 막지 않고 계정이 다르다고만 알린다.
  expect(
    (await membersApi.previewInvitation({ token: otherToken })).account_matches,
  ).toBe(false)
})

it('이미 팀원인데 남은 대기 초대는 로그인하면 수락으로 닫힌다', async () => {
  leftOverInvitation('member@beez.local')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  await leader()
  const { items } = await membersApi.invitations('101')
  expect(items.find((item) => item.id === '9001')?.accepted_at).toBeTruthy()
})

it('팀장이 제외하면 그 이메일의 대기 초대도 취소되어 다음 로그인으로 돌아오지 않는다', async () => {
  leftOverInvitation('member@beez.local')
  const members = (await membersApi.list('101', { limit: 100 })).items
  const leaderId = members.find((member) => member.role === 'LEADER')!.id
  const colleague = members.find((member) => member.name === '김동료')!
  await membersApi.remove('101', colleague.id, {
    reassign_to_member_id: leaderId,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  expect(await membersApi.list('101').catch(() => null)).toBeNull()
})

it('팀원이 나가면 그 이메일의 대기 초대도 취소되어 예전 메일 링크로 돌아오지 않는다', async () => {
  const leaderId = (await membersApi.list('101', { limit: 100 })).items.find(
    (member) => member.role === 'LEADER',
  )!.id
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  leftOverInvitation('member@beez.local')
  await membersApi.leave('101', { reassign_to_member_id: leaderId })
  expect(
    getApiErrorStatus(
      await failure(
        membersApi.acceptInvitation({ token: 'mock-invite-left-over' }),
      ),
    ),
  ).toBe(404)
})
