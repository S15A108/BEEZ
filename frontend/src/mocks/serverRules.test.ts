/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  authApi,
  dashboardApi,
  documentsApi,
  inboxApi,
  tasksApi,
  validationsApi,
} from '../api/services'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// PR #72 mock/live 전수 대조 LA-TC-02~09: mock 작업·문서 집계가 서버(TaskPlanService·TaskStateService·
// TaskTransitionPolicy·TaskAssigneeService·TaskQueryService·StatusRepository)와 같은 결과를 낸다.
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
afterEach(() => {
  restore()
  vi.useRealTimers()
})

const login = (email = DEMO_EMAIL) =>
  authApi.login({ email, password: DEMO_PASSWORD })
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

// 계획을 만들고 검증을 통과시켜 시작한 작업
async function startedTask() {
  const task = await tasksApi.create('101', plan())
  const validation = await validationsApi.create('101', task.id, {
    plan_revision_id: task.current_plan_revision.id,
    expected_lock_version: task.lock_version,
  })
  await validationsApi.get('101', validation.id)
  await validationsApi.get('101', validation.id)
  return tasksApi.state('101', task.id, {
    event: 'START',
    plan_revision_id: task.current_plan_revision.id,
    expected_lock_version: task.lock_version,
    validation_id: validation.id,
  })
}

describe('mock 작업 규칙은 서버와 같다', () => {
  it('진행 중 계획을 고치면 WAITING(REVALIDATION)이 되고 이전 검증은 무효가 된다', async () => {
    await login()
    const task = await startedTask()
    const revised = await tasksApi.savePlan('101', task.id, {
      ...plan({ title: '바꾼 계획' }),
      expected_revision_no: 1,
      expected_lock_version: task.lock_version,
    })
    expect(revised).toMatchObject({
      status: 'WAITING',
      waiting_reason: 'REVALIDATION',
      validation_status: 'NOT_RUN',
    })
    expect(
      (await validationsApi.get('101', task.started_validation_id!)).status,
    ).toBe('STALE')
  })

  it('계획과 다른 점(delta)이 있는 완료 보고는 WAITING(REVALIDATION)으로 간다', async () => {
    await login()
    const task = await startedTask()
    const reported = await tasksApi.state('101', task.id, {
      event: 'COMPLETED',
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
      summary: '목록을 붙였어요.',
      completion_checks: [{ criterion: '참여 프로젝트 표시', met: true }],
      delta: [{ description: '정렬을 더함' }],
    })
    expect(reported).toMatchObject({
      status: 'WAITING',
      waiting_reason: 'REVALIDATION',
    })
  })

  it('지금 승인 버전이 아닌 설계 참조는 409 version_conflict, 1단계 순환 선행은 409 dependency_cycle', async () => {
    await login()
    await expect(
      tasksApi.create(
        '101',
        plan({
          design_refs: [
            {
              design_item_id: '301',
              design_item_version_id: '399',
              relation: 'IMPLEMENTS',
            },
          ],
        }),
      ),
    ).rejects.toMatchObject({
      response: { status: 409, data: { error: { code: 'version_conflict' } } },
    })
    const first = await tasksApi.create('101', plan())
    const second = await tasksApi.create(
      '101',
      plan({ dependency_task_ids: [first.id] }),
    )
    await expect(
      tasksApi.savePlan('101', first.id, {
        ...plan({ dependency_task_ids: [second.id] }),
        expected_revision_no: 1,
        expected_lock_version: first.lock_version,
      }),
    ).rejects.toMatchObject({
      response: { status: 409, data: { error: { code: 'dependency_cycle' } } },
    })
  })

  it('팀장은 보고가 30분 넘게 끊긴 남의 진행 중 작업만 취소할 수 있다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-02T00:00:00Z'))
    await login('member@beez.local')
    const task = await startedTask()
    await login()
    const cancel = (lock: number) =>
      tasksApi.state('101', task.id, {
        event: 'CANCEL',
        plan_revision_id: task.current_plan_revision.id,
        expected_lock_version: lock,
      })
    await expect(cancel(task.lock_version)).rejects.toMatchObject({
      response: { status: 403 },
    })
    vi.setSystemTime(new Date('2026-11-02T00:31:00Z'))
    expect((await cancel(task.lock_version)).status).toBe('CANCELLED')
  })

  it('완료·취소 작업은 재배정하지 못하고, 진행 중이 아니면 WAIT를 거절한다', async () => {
    await login()
    const task = await tasksApi.create('101', plan())
    await expect(
      tasksApi.state('101', task.id, {
        event: 'WAIT',
        plan_revision_id: task.current_plan_revision.id,
        expected_lock_version: task.lock_version,
        waiting_reason: 'INPUT',
      }),
    ).rejects.toMatchObject({
      response: {
        status: 400,
        data: { error: { code: 'invalid_transition' } },
      },
    })
    const cancelled = await tasksApi.state('101', task.id, {
      event: 'CANCEL',
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
    })
    await expect(
      tasksApi.assign('101', task.id, {
        assignee_member_id: '22',
        expected_lock_version: cancelled.lock_version,
      }),
    ).rejects.toMatchObject({
      response: {
        status: 400,
        data: { error: { code: 'invalid_transition' } },
      },
    })
  })

  it('작업 목록의 validation_status 필터는 페이지를 나누기 전에 건다', async () => {
    await login()
    await tasksApi.create('101', plan())
    const passed = await tasksApi.list('101', {
      validation_status: 'PASS',
      limit: 1,
    })
    expect(
      passed.items.every((task) => task.validation_status === 'PASS'),
    ).toBe(true)
    expect(passed.items.length).toBe(1)
  })
})

it('기준 전에 승인한 문서를 다시 고치기 시작하면 문서 집계의 draft_count가 1이다', async () => {
  await login()
  const documentId = '223'
  const count = async () => {
    const { document } = await documentsApi.get('102', documentId)
    return (await dashboardApi.documents('102')).by_kind.find(
      (item) => item.kind === document.kind,
    )?.draft_count
  }
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  let current = copy
  for (const suggestion of review.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  await documentsApi.decideSubmission('102', documentId, submission.id, {
    decision: 'APPROVE',
  })
  expect(await count()).toBe(0)
  const approved = await documentsApi.workingCopy('102', documentId)
  await documentsApi.updateWorkingCopy('102', documentId, {
    expected_revision: approved.revision,
    content: `${approved.content}
고친 줄`,
    file_name: approved.file_name,
  })
  expect(await count()).toBe(1)
})

// PR #72 3차 조사 THIRD-TC-02: mock 알림 '모두 읽음'(all=true)은 페이지와 상관없이 그 프로젝트의 안 읽은 알림을 모두 읽음 처리한다.
it('알림 all=true 읽음은 그 프로젝트의 안 읽은 알림 전체를 읽음 처리한다', async () => {
  await login()
  const unread = (projectId: string) =>
    inboxApi.list({ project_id: projectId, unread: true, limit: 100 })
  const before = await unread('101')
  expect(before.items.length).toBeGreaterThan(0)
  const { updated_count } = await inboxApi.markRead({
    all: true,
    project_id: '101',
  })
  expect(updated_count).toBe(before.items.length)
  expect((await unread('101')).items).toHaveLength(0)
})
