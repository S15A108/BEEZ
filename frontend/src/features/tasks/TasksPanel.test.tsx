import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  authApi,
  contextsApi,
  membersApi,
  planningApi,
  tasksApi,
  validationsApi,
} from '@/api/services'
import { taskDetailSchema, type TaskDetail } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { TasksPanel } from './TasksPanel'

const timestamp = '2026-10-23T03:00:00Z'
let task: TaskDetail

beforeEach(() => {
  useWorkspaceStore.setState({ pane: 'tasks', focusId: '501' })
  task = taskDetailSchema.parse({
    id: '501',
    key: 'TASK-001',
    project_id: '101',
    assignee_member_id: '21',
    status: 'READY',
    lock_version: 7,
    current_plan_revision: {
      id: '511',
      task_id: '501',
      revision_no: 2,
      created_by: '11',
      created_at: timestamp,
      plan: {
        title: '사용자 프로필 구현',
        goal: '승인된 응답 필드를 구현한다.',
        included_scope: ['조회 응답'],
        excluded_scope: ['권한 변경'],
        approach: '기존 API 계약을 따른다.',
        completion_criteria: ['응답 필드 확인'],
        dependency_task_ids: [],
        design_refs: [],
        unlinked: [],
        no_contract_change: true,
      },
    },
    validation_status: 'PASS',
    validation: {
      id: '601',
      task_id: '501',
      plan_revision_id: '511',
      baseline_id: '401',
      status: 'PASS',
      trigger: 'AGENT',
      current_attempt: 1,
      design_refs: [],
      related_task_refs: [],
      findings: [],
      cache_hit: false,
      created_at: timestamp,
    },
    created_at: timestamp,
    updated_at: timestamp,
  })
  vi.spyOn(authApi, 'me').mockResolvedValue({
    id: '11',
    email: 'owner@example.com',
    name: '담당자',
    created_at: timestamp,
  })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [
      {
        id: '21',
        account_id: '11',
        name: '담당자',
        role: 'MEMBER',
        agent_connected: false,
      },
      {
        id: '22',
        account_id: '12',
        name: '다른 팀원',
        role: 'LEADER',
        agent_connected: false,
      },
    ],
    has_more: false,
  })
  vi.spyOn(tasksApi, 'list').mockImplementation(async () => ({
    items: [task],
    has_more: false,
  }))
  vi.spyOn(tasksApi, 'get').mockImplementation(async () => task)
  vi.spyOn(tasksApi, 'planRevisions').mockImplementation(async () => ({
    items: [task.current_plan_revision],
    has_more: false,
  }))
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.spyOn(contextsApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.spyOn(validationsApi, 'get').mockImplementation(
    async () => task.validation!,
  )
})
afterEach(() => vi.restoreAllMocks())

function show(isLeader: boolean) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <TasksPanel projectId="101" isLeader={isLeader} />
    </QueryClientProvider>,
  )
  return { ...view, client }
}

it('현재 담당자는 PASS에 연결된 계획·검증·잠금 번호로 시작한다', async () => {
  const state = vi.spyOn(tasksApi, 'state').mockImplementation(async () => {
    task = { ...task, status: 'IN_PROGRESS', lock_version: 8 }
    return task
  })
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '시작' }))
  await waitFor(() =>
    expect(state).toHaveBeenCalledWith('101', '501', {
      event: 'START',
      plan_revision_id: '511',
      expected_lock_version: 7,
      validation_id: '601',
    }),
  )
})

it('팀장도 담당자가 아니면 시작·검증을 대신하지 않고 취소 개입만 제공한다', async () => {
  task = { ...task, assignee_member_id: '22' }
  show(true)
  expect(await screen.findByRole('button', { name: '작업 취소' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: '시작' })).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '계획 검증' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '계획 새 리비전 작성' }),
  ).not.toBeInTheDocument()
})

it('편집 중 재조회가 일어나도 검토했던 리비전과 잠금 번호를 제출한다', async () => {
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  const { client } = show(false)
  fireEvent.click(
    await screen.findByRole('button', { name: '계획 새 리비전 작성' }),
  )
  expect(await screen.findByLabelText('이번 작업의 목표')).toHaveValue(
    '승인된 응답 필드를 구현한다.',
  )

  task = {
    ...task,
    lock_version: 9,
    current_plan_revision: {
      ...task.current_plan_revision,
      id: '512',
      revision_no: 3,
      plan: {
        ...task.current_plan_revision.plan,
        goal: '다른 사용자가 갱신한 목표',
      },
    },
  }
  await act(async () => {
    await client.invalidateQueries({
      queryKey: ['projects', '101', 'task', '501'],
    })
  })
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({
    expected_revision_no: 2,
    expected_lock_version: 7,
    goal: '승인된 응답 필드를 구현한다.',
  })
})
