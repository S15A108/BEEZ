/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { api } from '@/api/client'
import { authApi, membersApi, tasksApi } from '@/api/services'
import { getApiErrorCode, getApiErrorStatus } from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// 팀원 관리 규칙(PR #50 MEM-A06~A10)을 목이 따르는지 본다. 프로젝트 101: 팀장 21, 김동료 22.
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

it('표시 수정은 앞뒤 공백을 빼고 색을 대문자로 저장하며, 빈 값은 지우고 생략한 값은 둔다', async () => {
  const saved = await membersApi.update('101', '22', {
    role_label: '  프론트  ',
    color: '#79a48a',
  })
  expect(saved).toMatchObject({
    role_label: '프론트',
    color: '#79A48A',
    domain: '화면',
  })
  const cleared = await membersApi.update('101', '22', { domain: '' })
  expect(cleared.domain).toBeUndefined()
  expect(cleared.role_label).toBe('프론트')
})

// 화면 스키마가 먼저 막으므로 목 규칙은 클라이언트를 거치지 않고 본다.
it('표시 수정에서 50자를 넘거나 색 형식이 틀리면 400 validation_failed', async () => {
  for (const body of [{ domain: 'a'.repeat(51) }, { color: 'red' }]) {
    const error = await api
      .patch('/projects/101/members/22', body)
      .catch((cause: unknown) => cause)
    expect(getApiErrorCode(error)).toBe('validation_failed')
  }
  expect(() =>
    membersApi.update('101', '22', { domain: 'a'.repeat(51) }),
  ).toThrow()
})

it('팀장 위임은 [이전 팀장, 새 팀장] 순서로 주고, 나에게 위임하면 409 invalid_transfer_target', async () => {
  const self = await membersApi
    .transfer('101', { target_member_id: '21' })
    .catch((cause: unknown) => cause)
  expect(getApiErrorCode(self)).toBe('invalid_transfer_target')
  const { members } = await membersApi.transfer('101', {
    target_member_id: '22',
  })
  expect(members.map((member) => [member.id, member.role])).toEqual([
    ['21', 'MEMBER'],
    ['22', 'LEADER'],
  ])
})

it('작업 인계 대상이 떠나는 본인이거나 팀원이 아니면 400', async () => {
  const self = await membersApi
    .remove('101', '22', { reassign_to_member_id: '22' })
    .catch((cause: unknown) => cause)
  expect(getApiErrorStatus(self)).toBe(400)
  const stranger = await membersApi
    .remove('101', '22', { reassign_to_member_id: '999' })
    .catch((cause: unknown) => cause)
  expect(getApiErrorStatus(stranger)).toBe(400)
})

// PR #50 d4db914: 진행 중 작업의 담당자를 바꾸면 WAITING(REVALIDATION)으로 내리고 검증을 무효로 만든다.
it('진행 중 작업의 담당자를 바꾸면 재검증 대기가 되고 검증은 ASSIGNEE_CHANGED로 무효가 된다', async () => {
  const task = await tasksApi.get('101', '501')
  const started = await tasksApi.state('101', '501', {
    event: 'START',
    plan_revision_id: task.current_plan_revision.id,
    expected_lock_version: task.lock_version,
    validation_id: task.validation!.id,
  })
  expect(started.status).toBe('IN_PROGRESS')
  const moved = await tasksApi.assign('101', '501', {
    assignee_member_id: '22',
    expected_lock_version: started.lock_version,
  })
  expect(moved).toMatchObject({
    status: 'WAITING',
    waiting_reason: 'REVALIDATION',
    assignee_member_id: '22',
    validation_status: 'STALE',
  })
  expect(moved.validation?.stale_reason).toBe('ASSIGNEE_CHANGED')
  const same = await tasksApi.assign('101', '501', {
    assignee_member_id: '22',
    expected_lock_version: moved.lock_version,
  })
  expect(same.lock_version).toBe(moved.lock_version)
})
