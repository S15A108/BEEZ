/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { authApi, tasksApi, validationsApi } from '../api/services'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// 예시 모드에서 검증 실행 실패(롤백 실패 뒤 서버 회수 등, docs/design/11-validation-rollback.md)와
// 작업 간 충돌 장면을 만들 수 있게 한다(S15P31A108-150).
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

const plan = (overrides = {}) => ({
  title: '프로젝트 목록 연결',
  goal: '승인 계약대로 프로젝트를 보여준다.',
  included_scope: ['프로젝트 목록'],
  excluded_scope: [],
  approach: '승인 요구사항 사용',
  completion_criteria: ['참여 프로젝트 표시'],
  dependency_task_ids: [],
  design_refs: [
    {
      design_item_id: '301',
      design_item_version_id: '311',
      relation: 'IMPLEMENTS' as const,
    },
  ],
  unlinked: [],
  no_contract_change: true,
  ...overrides,
})

// 검증을 접수하고 끝날 때까지 조회한다(목은 두 번째 조회에서 판정한다).
async function validate(taskId: string) {
  const task = await tasksApi.get('101', taskId)
  const validation = await validationsApi.create('101', task.id, {
    plan_revision_id: task.current_plan_revision.id,
    expected_lock_version: task.lock_version,
  })
  await validationsApi.get('101', validation.id)
  return validationsApi.get('101', validation.id)
}

describe('예시 모드 검증 실패 스위치(beez.mock.validation)', () => {
  beforeEach(() =>
    authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD }),
  )

  it('lease_expired면 실행 오류로 끝나고 작업 상태는 그대로이며, 스위치를 끄고 재시도하면 통과한다', async () => {
    const task = await tasksApi.create('101', plan())
    localStorage.setItem('beez.mock.validation', 'lease_expired')
    const failed = await validate(task.id)
    expect(failed).toMatchObject({
      status: 'ERROR',
      error_code: 'validation_lease_expired',
    })
    expect(failed.findings).toEqual([
      expect.objectContaining({
        source: 'MODEL',
        kind: 'ERROR',
        severity: 'BLOCKING',
        resolution: 'RETRY',
      }),
    ])
    expect(await tasksApi.get('101', task.id)).toMatchObject({
      status: 'READY',
      validation_status: 'ERROR',
    })

    localStorage.removeItem('beez.mock.validation')
    await validationsApi.retry('101', failed.id, {
      expected_attempt: failed.current_attempt,
    })
    await validationsApi.get('101', failed.id)
    const retried = await validationsApi.get('101', failed.id)
    expect(retried).toMatchObject({ status: 'PASS', current_attempt: 2 })
    expect(retried.error_code).toBeUndefined()
  })

  it('partial이면 사람 검토가 필요한 오류로 끝난다', async () => {
    const task = await tasksApi.create('101', plan())
    localStorage.setItem('beez.mock.validation', 'partial')
    const failed = await validate(task.id)
    expect(failed.error_code).toBe('ai_evidence_insufficient')
    expect(failed.findings).toEqual([
      expect.objectContaining({ kind: 'ERROR', resolution: 'HUMAN_REVIEW' }),
    ])
  })

  it('모르는 값이면 평소처럼 판정한다', async () => {
    const task = await tasksApi.create('101', plan())
    localStorage.setItem('beez.mock.validation', 'nonsense')
    expect((await validate(task.id)).status).toBe('PASS')
  })
})

it('같은 설계 항목을 바꾸는 진행 작업과 부딪히면 충돌 항목에 그 작업 번호를 싣는다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  const changes = plan({
    no_contract_change: false,
    design_refs: [
      {
        design_item_id: '301',
        design_item_version_id: '311',
        relation: 'CHANGES' as const,
        proposed_content: { description: '빈 목록 안내를 바꾼다' },
      },
    ],
  })
  const first = await tasksApi.create('101', changes)
  const passed = await validate(first.id)
  await tasksApi.state('101', first.id, {
    event: 'START',
    plan_revision_id: first.current_plan_revision.id,
    expected_lock_version: first.lock_version,
    validation_id: passed.id,
  })
  const second = await tasksApi.create('101', changes)
  const conflict = await validate(second.id)
  expect(conflict.findings).toContainEqual(
    expect.objectContaining({ kind: 'CONFLICT', related_task_id: first.id }),
  )
})
