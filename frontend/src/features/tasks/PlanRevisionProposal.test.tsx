import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { tasksApi, validationsApi } from '@/api/services'
import type { TaskDetail, Validation } from '@/api/contracts/common'
import type { PlanRevisionProposal as Proposal } from './schemas'
import { PlanRevisionProposal } from './PlanRevisionProposal'

const timestamp = '2026-10-23T03:00:00Z'
const validation: Validation = {
  id: '601',
  task_id: '501',
  plan_revision_id: '511',
  baseline_id: '401',
  status: 'NEEDS_INPUT',
  trigger: 'AGENT',
  current_attempt: 1,
  design_refs: [],
  related_task_refs: [],
  findings: [
    {
      id: '701',
      source: 'RULE',
      kind: 'MISSING_INPUT',
      severity: 'BLOCKING',
      message: '빈 목록의 완료 기준이 없어요.',
      sources: [],
      resolution: 'FIX_PLAN',
    },
  ],
  cache_hit: false,
  created_at: timestamp,
}
const task: TaskDetail = {
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
      title: '프로젝트 목록',
      goal: '목록을 보여준다.',
      included_scope: ['목록 조회'],
      excluded_scope: ['권한 변경'],
      approach: '승인 API 사용',
      completion_criteria: ['목록 표시'],
      dependency_task_ids: [],
      design_refs: [],
      unlinked: [],
      no_contract_change: true,
    },
  },
  validation_status: 'NEEDS_INPUT',
  validation,
  created_at: timestamp,
  updated_at: timestamp,
}
const proposal: Proposal = {
  id: '801',
  baseline_id: '401',
  source_validation_id: '601',
  expected_revision_no: 2,
  expected_lock_version: 7,
  before_plan: task.current_plan_revision.plan,
  proposed_plan: {
    ...task.current_plan_revision.plan,
    goal: '참여 프로젝트와 빈 목록 안내를 보여준다.',
    completion_criteria: ['목록 표시', '빈 목록 안내 표시'],
  },
  changes: [
    {
      field: 'goal',
      reason: '승인 요구사항의 빈 화면 범위를 반영해요.',
      finding_ids: ['701'],
    },
    {
      field: 'completion_criteria',
      reason: '검증 가능한 완료 조건을 추가해요.',
      finding_ids: ['701'],
    },
  ],
  evidence: [
    {
      field: 'goal',
      document_id: '201',
      document_type: 'REQUIREMENTS',
      document_version_id: '211',
      version_no: 1,
      location: '12행',
      quote: '참여 프로젝트가 없으면 안내한다.',
    },
  ],
  expires_at: '2099-01-01T00:00:00Z',
}
const appliedValidation: Validation = {
  ...validation,
  id: '602',
  plan_revision_id: '512',
  status: 'RUNNING',
  findings: [],
}
const appliedTask: TaskDetail = {
  ...task,
  lock_version: 8,
  validation_status: 'RUNNING',
  validation: appliedValidation,
  current_plan_revision: {
    ...task.current_plan_revision,
    id: '512',
    revision_no: 3,
    plan: proposal.proposed_plan,
  },
}

beforeEach(() => {
  vi.spyOn(tasksApi, 'proposePlanRevision').mockResolvedValue(
    structuredClone(proposal),
  )
  vi.spyOn(tasksApi, 'applyPlanRevisionProposal').mockResolvedValue({
    task: appliedTask,
    validation: appliedValidation,
  })
  vi.spyOn(tasksApi, 'savePlan')
  vi.spyOn(validationsApi, 'create')
})
afterEach(() => vi.restoreAllMocks())
function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['projects', '101', 'task', '501'], task)
  const view = (current = task, baselineId = '401') => (
    <QueryClientProvider client={client}>
      <PlanRevisionProposal
        projectId="101"
        task={current}
        validation={current.validation!}
        baselineId={baselineId}
        onOpenDocument={vi.fn()}
      />
    </QueryClientProvider>
  )
  const rendered = render(view())
  return {
    client,
    ...rendered,
    update: (current = task, baselineId = '401') =>
      rendered.rerender(view(current, baselineId)),
  }
}
async function open() {
  fireEvent.click(screen.getByRole('button', { name: 'AI 계획 수정안 받기' }))
  return screen.findByRole('button', { name: '승인하고 재검증' })
}

it('승인 전에는 변경 전후와 이유만 보여 주고 취소해도 계획이나 검증을 저장하지 않는다', async () => {
  const { client } = show()
  await open()
  const goal = screen.getByRole('region', { name: '목표 변경' })
  expect(
    within(goal).getByText(task.current_plan_revision.plan.goal),
  ).toBeInTheDocument()
  expect(
    within(goal).getByText(proposal.proposed_plan.goal),
  ).toBeInTheDocument()
  expect(within(goal).getByText(proposal.changes[0].reason)).toBeInTheDocument()
  expect(
    screen.getByRole('region', { name: '완료 기준 변경' }),
  ).toHaveTextContent('빈 목록 안내 표시')
  expect(screen.getByRole('region', { name: '수정안 근거' })).toHaveTextContent(
    '참여 프로젝트가 없으면 안내한다.',
  )
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(tasksApi.applyPlanRevisionProposal).not.toHaveBeenCalled()
  expect(tasksApi.savePlan).not.toHaveBeenCalled()
  expect(validationsApi.create).not.toHaveBeenCalled()
  expect(client.getQueryData(['projects', '101', 'task', '501'])).toEqual(task)
})

it('승인 한 번으로 서버의 저장과 재검증 응답을 반영하고 연속 클릭은 한 번만 보낸다', async () => {
  let complete!: (value: { task: TaskDetail; validation: Validation }) => void
  vi.mocked(tasksApi.applyPlanRevisionProposal).mockReturnValue(
    new Promise((resolve) => {
      complete = resolve
    }),
  )
  const { client } = show()
  const approve = await open()
  fireEvent.click(approve)
  fireEvent.click(approve)
  expect(tasksApi.applyPlanRevisionProposal).toHaveBeenCalledTimes(1)
  expect(tasksApi.applyPlanRevisionProposal).toHaveBeenCalledWith(
    '101',
    '501',
    '801',
  )
  expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  await act(async () =>
    complete({ task: appliedTask, validation: appliedValidation }),
  )
  await screen.findByText('수정안을 새 계획으로 저장하고 재검증을 요청했어요.')
  expect(client.getQueryData(['projects', '101', 'task', '501'])).toEqual(
    appliedTask,
  )
  expect(client.getQueryData(['projects', '101', 'validation', '602'])).toEqual(
    appliedValidation,
  )
  expect(tasksApi.savePlan).not.toHaveBeenCalled()
  expect(validationsApi.create).not.toHaveBeenCalled()
})

it.each(['revision', 'lock', 'baseline', 'validation'] as const)(
  '%s이 바뀌면 이전 수정안 승인을 막는다',
  async (changed) => {
    const { update } = show()
    await open()
    const current = structuredClone(task)
    if (changed === 'revision') current.current_plan_revision.revision_no++
    if (changed === 'lock') current.lock_version++
    if (changed === 'validation') current.validation!.id = '603'
    update(current, changed === 'baseline' ? '402' : '401')
    expect(
      screen.getByRole('button', { name: '승인하고 재검증' }),
    ).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '최신 내용으로 수정안을 다시 받아 주세요.',
    )
    expect(tasksApi.applyPlanRevisionProposal).not.toHaveBeenCalled()
  },
)

it('승인 응답을 받지 못하면 같은 제안 ID로 다시 적용해 결과를 확인한다', async () => {
  vi.mocked(tasksApi.applyPlanRevisionProposal).mockRejectedValueOnce(
    new AxiosError('offline'),
  )
  show()
  fireEvent.click(await open())
  await screen.findByText(/같은 수정안으로 다시 승인하면 적용 결과를 확인해요/)
  fireEvent.click(screen.getByRole('button', { name: '승인하고 재검증' }))
  await screen.findByText('수정안을 새 계획으로 저장하고 재검증을 요청했어요.')
  expect(tasksApi.applyPlanRevisionProposal).toHaveBeenNthCalledWith(
    1,
    '101',
    '501',
    '801',
  )
  expect(tasksApi.applyPlanRevisionProposal).toHaveBeenNthCalledWith(
    2,
    '101',
    '501',
    '801',
  )
  expect(tasksApi.proposePlanRevision).toHaveBeenCalledTimes(1)
})

it('서버가 기준 변경을 발견하면 승인을 막고 수정안을 다시 받을 수 있다', async () => {
  vi.mocked(tasksApi.applyPlanRevisionProposal).mockRejectedValueOnce(
    new AxiosError('stale', 'ERR_BAD_REQUEST', undefined, undefined, {
      data: { error: { code: 'proposal_stale' } },
      status: 409,
      statusText: 'Conflict',
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
    }),
  )
  show()
  fireEvent.click(await open())
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '승인하고 재검증' }),
    ).toBeDisabled(),
  )
  expect(screen.getByRole('button', { name: '수정안 다시 받기' })).toBeEnabled()
})

it('생성을 취소하면 뒤늦게 도착한 제안도 적용하거나 다시 열지 않는다', async () => {
  let complete!: (value: Proposal) => void
  vi.mocked(tasksApi.proposePlanRevision).mockReturnValue(
    new Promise((resolve) => {
      complete = resolve
    }),
  )
  show()
  fireEvent.click(screen.getByRole('button', { name: 'AI 계획 수정안 받기' }))
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  await act(async () => complete(proposal))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(tasksApi.applyPlanRevisionProposal).not.toHaveBeenCalled()
})
