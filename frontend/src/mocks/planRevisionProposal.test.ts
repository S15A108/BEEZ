/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi, tasksApi, validationsApi } from '../api/services'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => {
  restore()
  vi.unstubAllEnvs()
})

async function needsFix() {
  const created = await tasksApi.create('101', {
    title: '참여 프로젝트 목록',
    goal: '참여한 프로젝트를 보여준다.',
    included_scope: ['프로젝트 조회'],
    excluded_scope: [],
    approach: '승인 API 사용',
    completion_criteria: [],
    dependency_task_ids: [],
    design_refs: [],
    unlinked: [],
    no_contract_change: true,
  })
  const validation = await validationsApi.create('101', created.id, {
    plan_revision_id: created.current_plan_revision.id,
    expected_lock_version: created.lock_version,
  })
  await validationsApi.get('101', validation.id)
  await validationsApi.get('101', validation.id)
  return tasksApi.get('101', created.id)
}

it('제안을 받아도 계획은 그대로이며 승인과 재검증, 재전송까지 리비전 하나만 만든다', async () => {
  const before = await needsFix()
  const proposal = await tasksApi.proposePlanRevision('101', before.id)
  expect(await tasksApi.get('101', before.id)).toEqual(before)
  expect(proposal.before_plan).toEqual(before.current_plan_revision.plan)
  expect(proposal.changes).toContainEqual(
    expect.objectContaining({ field: 'completion_criteria' }),
  )
  const applied = await tasksApi.applyPlanRevisionProposal(
    '101',
    before.id,
    proposal.id,
  )
  expect(applied.task.current_plan_revision.revision_no).toBe(
    before.current_plan_revision.revision_no + 1,
  )
  expect(applied.validation).toMatchObject({
    status: 'RUNNING',
    plan_revision_id: applied.task.current_plan_revision.id,
  })
  const replay = await tasksApi.applyPlanRevisionProposal(
    '101',
    before.id,
    proposal.id,
  )
  expect(replay.validation.id).toBe(applied.validation.id)
  expect(replay.task.current_plan_revision.id).toBe(
    applied.task.current_plan_revision.id,
  )
  expect((await tasksApi.planRevisions('101', before.id)).items).toHaveLength(2)
  await validationsApi.get('101', applied.validation.id)
  expect((await validationsApi.get('101', applied.validation.id)).status).toBe(
    'PASS',
  )
})

it('검토 중 계획을 수정하면 예전 제안은 적용되지 않는다', async () => {
  const before = await needsFix()
  const proposal = await tasksApi.proposePlanRevision('101', before.id)
  const changed = await tasksApi.savePlan('101', before.id, {
    ...before.current_plan_revision.plan,
    goal: '담당자가 직접 고친 목표',
    expected_revision_no: before.current_plan_revision.revision_no,
    expected_lock_version: before.lock_version,
  })
  await expect(
    tasksApi.applyPlanRevisionProposal('101', before.id, proposal.id),
  ).rejects.toMatchObject({
    response: { status: 409, data: { error: { code: 'proposal_stale' } } },
  })
  expect(await tasksApi.get('101', before.id)).toEqual(changed)
})

it('환경 시나리오로 수집 실패를 재현하고 같은 검증을 재시도하면 실패 메타를 지운다', async () => {
  vi.stubEnv('VITE_MOCK_VALIDATION_SCENARIO', 'evidence_collection')
  const failed = await needsFix()
  expect(failed.validation).toMatchObject({
    status: 'ERROR',
    execution_failure: {
      type: 'EVIDENCE_COLLECTION_FAILED',
      retryable: true,
      reason_codes: ['source_evidence_unavailable'],
    },
  })
  await expect(
    tasksApi.proposePlanRevision('101', failed.id),
  ).rejects.toMatchObject({
    response: { status: 409, data: { error: { code: 'proposal_not_needed' } } },
  })
  localStorage.setItem('beez.mock.validation', '')
  const retry = await validationsApi.retry('101', failed.validation!.id, {
    expected_attempt: 1,
  })
  expect(retry.execution_failure).toBeUndefined()
  expect(retry).toMatchObject({
    id: failed.validation!.id,
    status: 'RUNNING',
    current_attempt: 2,
  })
})
