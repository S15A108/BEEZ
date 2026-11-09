import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { membersApi, tasksApi } from '@/api/services'
import { taskDetailSchema } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { FindingRelatedTask } from './FindingRelatedTask'

const timestamp = '2026-11-09T03:00:00Z'
const related = taskDetailSchema.parse({
  id: '504',
  key: 'TASK-004',
  project_id: '101',
  assignee_member_id: '26',
  status: 'WAITING',
  waiting_reason: 'REVALIDATION',
  lock_version: 3,
  current_plan_revision: {
    id: '514',
    task_id: '504',
    revision_no: 1,
    created_by: '14',
    created_at: timestamp,
    plan: {
      title: '빈 목록 안내 API 문구',
      goal: '빈 목록 안내를 바꾼다.',
      included_scope: ['빈 목록 안내'],
      excluded_scope: [],
      approach: '승인 요구사항을 따른다.',
      completion_criteria: ['새 안내가 보인다'],
      dependency_task_ids: [],
      design_refs: [],
      unlinked: [],
      no_contract_change: true,
    },
  },
  validation_status: 'STALE',
  created_at: timestamp,
  updated_at: timestamp,
})

beforeEach(() => {
  useWorkspaceStore.setState({ pane: 'tasks', focusId: '501' })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [
      {
        id: '26',
        account_id: '14',
        name: '박팀원',
        role: 'MEMBER',
        agent_connected: true,
      },
    ],
    has_more: false,
  })
})
afterEach(() => vi.restoreAllMocks())

function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <FindingRelatedTask projectId="101" taskId="504" />
    </QueryClientProvider>,
  )
}

it('부딪힌 작업의 키·제목·담당자를 보이고 누르면 그 작업을 연다', async () => {
  vi.spyOn(tasksApi, 'get').mockResolvedValue(related)
  show()
  expect(
    await screen.findByText(/TASK-004 빈 목록 안내 API 문구 · 담당자 박팀원/),
  ).toHaveTextContent('협의할 작업')
  await userEvent.click(screen.getByRole('button', { name: '작업 보기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: '504',
  })
})

it('작업을 불러오지 못하면 그렇게 알린다', async () => {
  vi.spyOn(tasksApi, 'get').mockRejectedValue(new Error('down'))
  show()
  expect(
    await screen.findByText('협의할 작업 정보를 불러오지 못했어요.'),
  ).toBeInTheDocument()
})
