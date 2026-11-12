import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
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
import { planDraftResponseSchema, type PlanDraft } from './schemas'
import { PaneHeaderSlot } from '@/components/paneHeaderSlot'
import { PaneSheetHost } from '@/components/PaneSheet'
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

it('내 작업의 실제 ID와 기존 검증 절차를 에이전트 지시로 복사한다', async () => {
  const user = userEvent.setup()
  show(false)
  await user.click(
    await screen.findByRole('button', {
      name: '에이전트용 작업 지시 복사',
    }),
  )
  expect(
    await screen.findByText(/연결한 에이전트에 붙여 넣으세요/),
  ).toBeInTheDocument()
  const instructions = await navigator.clipboard.readText()
  expect(instructions).toContain('작업 ID 501')
  expect(instructions).toContain('같은 작업 ID로 upsert_task_plan')
  expect(instructions).toContain('validate_task_plan')
})

it('계획 PASS가 있어도 실제 코드 검증이나 테스트 통과로 표시하지 않는다', async () => {
  task = { ...task, status: 'IN_PROGRESS' }
  show(false)
  const card = (
    await screen.findByText('설계와 계획의 검증 결과')
  ).closest<HTMLElement>('[data-slot="card"]')!
  expect(within(card).getByText('계획 통과')).toBeInTheDocument()
  expect(
    within(card).getByText(
      /계획 검증은 코드 품질이나 테스트 통과를 보장하지 않아요/,
    ),
  ).toBeInTheDocument()
  expect(within(card).queryByText('구현 검증 완료')).not.toBeInTheDocument()
})

it('현재 담당자는 PASS에 연결된 계획·검증·잠금 번호로 시작한다', async () => {
  const state = vi.spyOn(tasksApi, 'state').mockImplementation(async () => {
    task = { ...task, status: 'IN_PROGRESS', lock_version: 8 }
    return task
  })
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '작업 시작' }))
  await waitFor(() =>
    expect(state).toHaveBeenCalledWith('101', '501', {
      event: 'START',
      plan_revision_id: '511',
      expected_lock_version: 7,
      validation_id: '601',
    }),
  )
})

it('서버 안내와 AI 후속 행동 제안을 구분해 보여 주고 이전 결과도 표시한다', async () => {
  task = taskDetailSchema.parse({
    ...task,
    validation_status: 'NEEDS_REVIEW',
    validation: {
      ...task.validation!,
      status: 'NEEDS_REVIEW',
      findings: [
        {
          id: '701',
          source: 'MODEL',
          kind: 'MISMATCH',
          severity: 'BLOCKING',
          message: '계획과 승인 설계가 달라요',
          sources: [],
          resolution: 'HUMAN_REVIEW',
          next_action: '팀과 검토하세요',
          ai_suggested_action: '관련 작업 담당자와 협의를 검토하세요',
        },
        {
          id: '702',
          source: 'RULE',
          kind: 'MISSING_INPUT',
          severity: 'BLOCKING',
          message: '기존 검증 결과',
          sources: [],
          resolution: 'FIX_PLAN',
          next_action: '계획을 보완하세요',
        },
      ],
    },
  })
  show(false)

  const proposal = await screen.findByRole('region', {
    name: 'AI 후속 행동 제안',
  })
  expect(
    within(proposal).getByText('관련 작업 담당자와 협의를 검토하세요'),
  ).toBeInTheDocument()
  expect(within(proposal).getByText(/참고용 제안/)).toBeInTheDocument()
  expect(screen.getByText('팀과 검토하세요')).toBeInTheDocument()
  expect(screen.getByText('계획을 보완하세요')).toBeInTheDocument()
  expect(
    screen.getAllByRole('region', { name: 'AI 후속 행동 제안' }),
  ).toHaveLength(1)
})

it('정책 분석과 모순 근거를 읽을 수 있는 설계 이름으로 보여 준다', async () => {
  vi.mocked(planningApi.items).mockResolvedValue({
    items: [
      {
        id: '311',
        design_item_id: '301',
        key: 'REQ-001',
        kind: 'REQUIREMENT',
        version_no: 1,
        status: 'APPROVED',
        schema_version: 1,
        content: {},
      },
      {
        id: '312',
        design_item_id: '302',
        key: 'REQ-003',
        kind: 'REQUIREMENT',
        version_no: 2,
        status: 'APPROVED',
        schema_version: 1,
        content: {},
      },
    ],
    has_more: false,
  })
  task = {
    ...task,
    validation_status: 'ERROR',
    validation: {
      ...task.validation!,
      status: 'ERROR',
      error_code: 'ai_evidence_insufficient',
      findings: [
        {
          id: '701',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message: '의미 분석을 일부 수행하지 못했습니다',
          sources: [],
          resolution: 'HUMAN_REVIEW',
          next_action: '근거를 확인하세요',
          validation_run_id: '801',
        },
        {
          id: '702',
          source: 'MODEL',
          kind: 'CONFLICT',
          severity: 'BLOCKING',
          message: '승인 정책이 충돌해요',
          sources: [],
          resolution: 'HUMAN_REVIEW',
          conflicting_design_refs: [
            {
              design_item_id: '302',
              design_item_version_id: '312',
              evidence_ids: [],
              content_paths: [],
            },
            {
              design_item_id: '399',
              design_item_version_id: '3991',
              evidence_ids: [],
              content_paths: [],
            },
          ],
        },
      ],
      policy_analysis: {
        policy_assessments_truncated: false,
        policy_assessments: [
          {
            need_id: 'retention',
            topic: '보관 기간',
            state: 'CONTRADICTORY',
            plan_evidence: { field: 'goal', quote: '보관 기간을 적용한다' },
            design_item_version_ids: ['311'],
            approved_content_refs: [
              {
                design_item_version_id: '311',
                path: ['description'],
                quote: '보관 조건',
              },
            ],
            evidence_ids: [],
            explanation: '보관 기간을 확인할 근거가 부족해요.',
          },
          {
            need_id: 'period',
            topic: '보관 기간 확인',
            state: 'INSUFFICIENT',
            plan_evidence: { field: 'goal', quote: '보관 기간을 적용한다' },
            design_item_version_ids: [],
            approved_content_refs: [],
            evidence_ids: [],
            explanation: '추가 근거가 필요합니다.',
          },
        ],
      },
    },
  }
  show(false)
  const analysis = await screen.findByRole('region', { name: '정책 분석' })
  expect(within(analysis).getByText('정책 불일치')).toHaveClass(
    'text-destructive',
  )
  expect(within(analysis).getByText('근거 부족')).toBeInTheDocument()
  expect(within(analysis).getAllByText(/보관 기간을 적용한다/)).toHaveLength(2)
  expect(
    within(analysis).getByText(/REQ-001 승인 v1:.*보관 조건/),
  ).toBeInTheDocument()
  expect(
    screen.getByText('충돌하는 승인 항목: REQ-003 승인 v2'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('충돌하는 승인 항목: 설계 항목 이전 승인본'),
  ).toBeInTheDocument()
  expect(
    screen.getAllByText('근거를 확인하고 보완한 뒤 새 검증을 요청해 주세요.'),
  ).toHaveLength(2)
  expect(
    screen.queryByRole('button', { name: '실행 재시도' }),
  ).not.toBeInTheDocument()
})

it('정책 분석이 잘리면 안내한다', async () => {
  task = {
    ...task,
    validation: {
      ...task.validation!,
      policy_analysis: {
        policy_assessments: [],
        policy_assessments_truncated: true,
      },
    },
  }
  show(false)
  const analysis = await screen.findByRole('region', { name: '정책 분석' })
  expect(
    within(analysis).getByText('분석할 정책이 많아 일부만 확인했어요.'),
  ).toBeInTheDocument()
})

it('정책이 필요 없다고 판단한 사유와 계획 근거를 보여 준다', async () => {
  task = {
    ...task,
    validation: {
      ...task.validation!,
      policy_analysis: {
        policy_assessments: [],
        policy_assessments_truncated: false,
        no_policy_requirements_reason: '맞춤법만 수정합니다.',
        no_policy_requirements_evidence: { field: 'goal', quote: '문구 수정' },
      },
    },
  }
  show(false)
  const analysis = await screen.findByRole('region', { name: '정책 분석' })
  expect(within(analysis).getByText('맞춤법만 수정합니다.')).toBeInTheDocument()
  expect(
    within(analysis).getByText(/계획 근거:.*문구 수정/),
  ).toBeInTheDocument()
})

it('사람 검토가 아닌 분석 오류는 실행 재시도를 제공한다', async () => {
  task = {
    ...task,
    validation_status: 'ERROR',
    validation: {
      ...task.validation!,
      status: 'ERROR',
      findings: [
        {
          id: '703',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message: '분석 실패',
          sources: [],
          resolution: 'RETRY',
        },
      ],
    },
  }
  show(false)
  expect(
    await screen.findByRole('button', { name: '실행 재시도' }),
  ).toBeEnabled()
  expect(
    screen.getByText('검증을 마치지 못했어요. 다시 실행해 주세요.'),
  ).toBeInTheDocument()
})

// S15P31A108-150: 롤백 실패 뒤 서버가 실행을 회수하면 ERROR(validation_lease_expired)로 닫힌다(docs/design/11-validation-rollback.md 5절).
it('롤백 실패로 서버가 회수한 검증 오류는 이유와 결과를 보이고 실행 재시도를 제공한다', async () => {
  task = {
    ...task,
    validation_status: 'ERROR',
    validation: {
      ...task.validation!,
      status: 'ERROR',
      error_code: 'validation_lease_expired',
      findings: [
        {
          id: '705',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message:
            '분석 실행이 시간 안에 결과를 반영하지 못했습니다 (워커 중단·재배포)',
          sources: [],
          resolution: 'RETRY',
          next_action: '같은 입력으로 다시 실행합니다 (VAL-A04)',
          validation_run_id: '801',
        },
      ],
    },
  }
  show(false)
  expect(
    await screen.findByText(
      /서버가 실행을 회수했어요\. 이번 실행 결과는 반영하지 않았고 작업 상태도 그대로예요\./,
    ),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '실행 재시도' })).toBeEnabled()
})

it('부딪힌 작업이 있는 문제는 협의할 작업과 담당자를 함께 보인다', async () => {
  const other: TaskDetail = {
    ...task,
    id: '504',
    key: 'TASK-004',
    assignee_member_id: '22',
    current_plan_revision: {
      ...task.current_plan_revision,
      id: '514',
      task_id: '504',
      plan: {
        ...task.current_plan_revision.plan,
        title: '빈 목록 안내 API 문구',
      },
    },
  }
  vi.mocked(tasksApi.get).mockImplementation(async (_project, id) =>
    id === '504' ? other : task,
  )
  task = {
    ...task,
    validation_status: 'NEEDS_REVIEW',
    validation: {
      ...task.validation!,
      status: 'NEEDS_REVIEW',
      findings: [
        {
          id: '706',
          source: 'RULE',
          kind: 'CONFLICT',
          severity: 'BLOCKING',
          message: '다른 진행 작업이 같은 설계 항목의 변경을 점유하고 있어요.',
          related_task_id: '504',
          sources: [],
          resolution: 'HUMAN_REVIEW',
        },
      ],
    },
  }
  show(false)
  expect(
    await screen.findByText(
      /TASK-004 빈 목록 안내 API 문구 · 담당자 다른 팀원/,
    ),
  ).toBeInTheDocument()
})

// PR #231 리뷰: 작업 A로 연 작업 현황에서 B를 고르면 focusId는 A 그대로라, A로 openPane해도 선택이 B에 남았다.
it('작업 A로 연 뒤 B를 고르고 B의 협의할 작업 A에서 작업 보기를 누르면 A가 선택된다', async () => {
  const first = task
  const second: TaskDetail = {
    ...task,
    id: '504',
    key: 'TASK-004',
    current_plan_revision: {
      ...task.current_plan_revision,
      id: '514',
      task_id: '504',
      plan: {
        ...task.current_plan_revision.plan,
        title: '빈 목록 안내 API 문구',
      },
    },
    validation_status: 'NEEDS_REVIEW',
    validation: {
      ...task.validation!,
      id: '604',
      task_id: '504',
      plan_revision_id: '514',
      status: 'NEEDS_REVIEW',
      findings: [
        {
          id: '707',
          source: 'RULE',
          kind: 'CONFLICT',
          severity: 'BLOCKING',
          message: '다른 진행 작업이 같은 설계 항목의 변경을 점유하고 있어요.',
          related_task_id: '501',
          sources: [],
          resolution: 'HUMAN_REVIEW',
        },
      ],
    },
  }
  const byId: Record<string, TaskDetail> = { '501': first, '504': second }
  vi.mocked(tasksApi.list).mockImplementation(async () => ({
    items: [first, second],
    has_more: false,
  }))
  vi.mocked(tasksApi.get).mockImplementation(async (_project, id) => byId[id])
  vi.mocked(validationsApi.get).mockImplementation(
    async (_project, id) => (id === '604' ? second : first).validation!,
  )
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: /TASK-004/ }))
  await userEvent.click(
    await screen.findByRole('button', { name: '작업 보기' }),
  )
  await waitFor(() =>
    expect(screen.getByRole('button', { name: /TASK-001/ })).toHaveAttribute(
      'aria-current',
      'true',
    ),
  )
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
})

it('검증 결과에 BEEZ를 거치지 않은 작업은 검증하지 않는다고 적는다(AC-12)', async () => {
  show(false)
  expect(
    await screen.findByText(/BEEZ를 거치지 않은 작업은 검증하지 않아요\./),
  ).toBeInTheDocument()
})

it('완료 보고 재검증이 오류인데 새 계획 검증이 있으면 새 검증으로 재개하고 재검증 재시도도 따로 제공한다', async () => {
  const deltaError = {
    ...task.validation!,
    id: '602',
    status: 'ERROR' as const,
    trigger: 'DELTA' as const,
    findings: [
      {
        id: '704',
        source: 'MODEL' as const,
        kind: 'ERROR' as const,
        severity: 'BLOCKING' as const,
        message: '분석 실패',
        sources: [],
        resolution: 'RETRY' as const,
      },
    ],
  }
  task = {
    ...task,
    status: 'WAITING',
    waiting_reason: 'REVALIDATION',
    delta_validation_id: '602',
    validation: { ...task.validation!, id: '603', status: 'PASS' },
  }
  vi.spyOn(validationsApi, 'get').mockImplementation(async (_project, id) =>
    id === '602' ? deltaError : task.validation!,
  )
  const retry = vi
    .spyOn(validationsApi, 'retry')
    .mockImplementation(async () => ({ ...deltaError, status: 'RUNNING' }))
  const state = vi.spyOn(tasksApi, 'state').mockImplementation(async () => task)
  show(false)
  fireEvent.click(
    await screen.findByRole('button', { name: '완료 보고 재검증 재시도' }),
  )
  await waitFor(() =>
    expect(retry).toHaveBeenCalledWith(
      '101',
      '602',
      { expected_attempt: 1 },
      { signal: expect.any(AbortSignal) },
    ),
  )
  const resume = await screen.findByRole('button', { name: '재개' })
  await waitFor(() => expect(resume).toBeEnabled())
  fireEvent.click(resume)
  await waitFor(() =>
    expect(state).toHaveBeenCalledWith(
      '101',
      '501',
      expect.objectContaining({ event: 'RESUME', validation_id: '603' }),
    ),
  )
})

it('팀장도 담당자가 아니면 시작·검증을 대신하지 않고 취소 개입만 제공한다', async () => {
  task = { ...task, assignee_member_id: '22' }
  show(true)
  expect(
    screen.queryByRole('button', { name: '작업 시작' }),
  ).not.toBeInTheDocument()
  // 수정안: 새 리비전·담당자 변경·작업 취소는 '더 보기'로 묶는다.
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  expect(
    await screen.findByRole('menuitem', { name: '작업 취소' }),
  ).toBeEnabled()
  expect(
    screen.queryByRole('button', { name: '계획 검증' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('menuitem', { name: '계획 수정' }),
  ).not.toBeInTheDocument()
})

it('편집 중 재조회가 일어나도 검토했던 리비전과 잠금 번호를 제출한다', async () => {
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  const { client } = show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
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

it('검증 중에는 시작을 끄고 옆에 이유를 적는다', async () => {
  task = {
    ...task,
    validation_status: 'RUNNING',
    validation: { ...task.validation!, status: 'RUNNING' },
  }
  show(false)
  expect(
    await screen.findByRole('button', { name: '작업 시작' }),
  ).toBeDisabled()
  expect(
    screen.getByText('계획 검증 중이라 시작할 수 없어요.'),
  ).toBeInTheDocument()
})

it('검증이 끝나면 목록의 상태 칩도 다시 불러와 바뀐다', async () => {
  const passed = task
  task = {
    ...task,
    validation_status: 'RUNNING',
    validation: { ...task.validation!, status: 'RUNNING' },
  }
  show(false)
  const entry = await screen.findByRole('button', {
    name: /사용자 프로필 구현/,
  })
  expect(within(entry).getByText('검증 중')).toBeInTheDocument()

  task = passed
  await waitFor(() =>
    expect(within(entry).getByText('계획 통과')).toBeInTheDocument(),
  )
})
it('검증 중인 작업 목록을 다시 조회해 완료 상태를 표시한다', async () => {
  const running = {
    ...task,
    validation_status: 'RUNNING' as const,
    validation: { ...task.validation!, status: 'RUNNING' as const },
  }
  vi.mocked(tasksApi.list)
    .mockResolvedValueOnce({ items: [running], has_more: false })
    .mockImplementation(async () => ({ items: [task], has_more: false }))

  show(false)
  expect(
    await screen.findByRole('button', { name: /TASK-001.*검증 중/ }),
  ).toBeInTheDocument()
  expect(
    await screen.findByRole('button', { name: /TASK-001.*계획 통과/ }),
  ).toBeInTheDocument()
})

it('검증 요청 중 중복 호출과 이전 PASS를 이용한 시작을 막는다', async () => {
  let finish!: (value: NonNullable<TaskDetail['validation']>) => void
  const create = vi.spyOn(validationsApi, 'create').mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  show(false)
  const validate = await screen.findByRole('button', { name: '다시 검증' })
  fireEvent.click(validate)
  fireEvent.click(validate)
  await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  expect(create).toHaveBeenCalledWith(
    '101',
    '501',
    { plan_revision_id: '511', expected_lock_version: 7 },
    { signal: expect.any(AbortSignal) },
  )
  expect(screen.getByRole('button', { name: '작업 시작' })).toBeDisabled()
  expect(validate).toBeDisabled()

  task = {
    ...task,
    validation_status: 'RUNNING',
    validation: { ...task.validation!, id: '602', status: 'RUNNING' },
  }
  await act(async () => finish(task.validation!))
  await waitFor(() =>
    expect(
      screen.getByText('계획 검증 중이라 시작할 수 없어요.'),
    ).toBeInTheDocument(),
  )
  expect(screen.getByRole('button', { name: '작업 시작' })).toBeDisabled()
})

it('새 검증 조회에 다른 검증의 PASS 응답이 와도 현재 RUNNING 결과를 유지한다', async () => {
  const oldPass = task.validation!
  task = {
    ...task,
    validation_status: 'RUNNING',
    validation: { ...oldPass, id: '602', status: 'RUNNING' },
  }
  vi.spyOn(validationsApi, 'get').mockResolvedValue(oldPass)
  show(false)
  await waitFor(() => expect(validationsApi.get).toHaveBeenCalled())
  expect(
    await screen.findByText('계획 검증 중이라 시작할 수 없어요.'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '작업 시작' })).toBeDisabled()
})

it('요청 중 작업을 전환해도 서버 접수 결과를 목록에 반영하고 새 작업에는 오류를 표시하지 않는다', async () => {
  let first = task
  const second: TaskDetail = {
    ...first,
    id: '502',
    key: 'TASK-002',
    current_plan_revision: {
      ...first.current_plan_revision,
      task_id: '502',
      plan: { ...first.current_plan_revision.plan, title: '두 번째 작업' },
    },
    validation: undefined,
    validation_status: 'NOT_RUN',
  }
  vi.spyOn(tasksApi, 'list').mockImplementation(async () => ({
    items: [first, second],
    has_more: false,
  }))
  vi.spyOn(tasksApi, 'get').mockImplementation(async (_project, id) =>
    id === '501' ? first : second,
  )
  let resolve!: (value: NonNullable<TaskDetail['validation']>) => void
  const create = vi.spyOn(validationsApi, 'create').mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '다시 검증' }))
  await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: /두 번째 작업/ }))
  await waitFor(() =>
    expect(
      screen.getByText('TASK-002 · 담당자 담당자 · 계획 r2'),
    ).toBeInTheDocument(),
  )
  expect(create.mock.calls[0][3]?.signal?.aborted).toBe(false)
  first = {
    ...first,
    validation_status: 'RUNNING',
    validation: { ...first.validation!, id: '602', status: 'RUNNING' },
  }
  await act(async () => resolve(first.validation!))
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: /TASK-001.*검증 중/ }),
    ).toBeInTheDocument(),
  )
  expect(
    screen.queryByText(
      '요청을 처리하지 못했어요. 최신 상태와 권한을 확인해 주세요.',
    ),
  ).not.toBeInTheDocument()
})

it('조회 실패 시 시작을 막고 다시 불러올 수 있다', async () => {
  vi.spyOn(validationsApi, 'get')
    .mockRejectedValueOnce(new Error('네트워크 오류'))
    .mockImplementation(async () => task.validation!)
  show(false)
  const retry = await screen.findByRole('button', {
    name: '검증 결과 다시 불러오기',
  })
  expect(screen.getByRole('button', { name: '작업 시작' })).toBeDisabled()
  fireEvent.click(retry)
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '검증 결과 다시 불러오기' }),
    ).not.toBeInTheDocument(),
  )
  expect(screen.getByRole('button', { name: '작업 시작' })).toBeEnabled()
})

it('완료 보고는 완료 기준을 모두 체크해야 보내고 달라진 점은 줄마다 보낸다', async () => {
  task = { ...task, status: 'IN_PROGRESS' }
  const state = vi.spyOn(tasksApi, 'state').mockImplementation(async () => task)
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '완료 보고' }))
  await screen.findByRole('dialog')
  fireEvent.change(screen.getByLabelText('완료한 내용'), {
    target: { value: '응답 필드를 붙였어요.' },
  })
  fireEvent.click(screen.getAllByRole('button', { name: '완료 보고' }).at(-1)!)
  expect(
    await screen.findByText(
      '모든 완료 기준을 확인해야 완료로 보고할 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(state).not.toHaveBeenCalled()
  fireEvent.click(screen.getByLabelText('응답 필드 확인'))
  fireEvent.click(screen.getByLabelText('있음'))
  fireEvent.change(screen.getByLabelText('달라진 점 · 한 줄에 하나씩'), {
    target: { value: '필드 이름을 바꿈\n\n정렬을 더함' },
  })
  fireEvent.click(screen.getAllByRole('button', { name: '완료 보고' }).at(-1)!)
  await waitFor(() => expect(state).toHaveBeenCalled())
  expect(state.mock.calls[0][2]).toMatchObject({
    event: 'COMPLETED',
    summary: '응답 필드를 붙였어요.',
    completion_checks: [{ criterion: '응답 필드 확인', met: true }],
    delta: [
      { description: '필드 이름을 바꿈' },
      { description: '정렬을 더함' },
    ],
  })
})

// PNL-05 수정안: 단계 표시와 '다음 할 일' 상자. 계획 검증은 통과 뒤 상자 안 '다시 검증'으로 남는다.
it('검증을 통과한 작업은 단계가 작업 시작에 있고 다음 할 일 상자에 다시 검증을 둔다', async () => {
  show(false)
  const steps = await screen.findByRole('list', { name: '작업 단계' })
  expect(within(steps).getByText('작업 시작')).toHaveAttribute(
    'aria-current',
    'step',
  )
  expect(screen.getByText('다음 할 일')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 검증' })).toBeEnabled()
})

// PR #50 d4db914: 진행 중 작업을 넘기면 대기(재검증)가 된다고 담당자 변경 창에서 먼저 알린다.
it('팀장이 진행 중 작업의 담당자를 바꾸려 하면 재검증 안내를 보여 준다', async () => {
  task = { ...task, status: 'IN_PROGRESS' }
  show(true)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '담당자 바꾸기' }),
  )
  expect(
    await screen.findByText(
      /진행 중인 작업이라 넘기면 대기\(재검증\)로 바뀌고/,
    ),
  ).toBeInTheDocument()
})

// 사용자 결정 10-30: 검증이 더 필요하다고 한 칸은 계획 수정을 열 때 '더 자세히'를 펼쳐 강조한다.
it('검증이 포함 범위를 더 달라고 하면 계획 수정에서 그 칸을 펼쳐 강조한다', async () => {
  task = {
    ...task,
    validation_status: 'NEEDS_INPUT',
    validation: {
      ...task.validation!,
      status: 'NEEDS_INPUT',
      findings: [
        {
          id: '701',
          source: 'RULE',
          kind: 'MISSING_INPUT',
          severity: 'BLOCKING',
          message: '목표·포함 범위·접근 방법·완료 기준을 모두 작성해 주세요.',
          sources: [],
          resolution: 'FIX_PLAN',
        },
      ],
    },
  }
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '계획 수정' }),
  )
  expect(
    screen.getByText('더 자세히 (선택)').closest('details'),
  ).toHaveAttribute('open')
  expect(
    screen.getAllByText('검증에서 이 칸이 더 필요하다고 했어요.'),
  ).toHaveLength(2)
})

// PR #72 리뷰 1: 계획 수정은 기존 design_refs·unlinked 객체에서 시작해, 제목만 고쳐도 제안한 method·path와 다른 필드를 지킨다.
it('계획을 고쳐도 기존 변경 제안 참조와 연결하지 않은 항목의 다른 필드를 그대로 보낸다', async () => {
  const proposed = {
    description: '주문을 만든다.',
    method: 'POST',
    path: '/v2/orders',
  }
  const unlinked = { description: '외부 결제 연동', reason: '다음 단계' }
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        no_contract_change: false,
        design_refs: [
          {
            design_item_id: '301',
            design_item_version_id: '311',
            relation: 'CHANGES',
            proposed_content: proposed,
          },
        ],
        unlinked: [unlinked],
      },
    },
  }
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items: [
      {
        id: '311',
        design_item_id: '301',
        key: 'API-001',
        kind: 'API_CONTRACT',
        version_no: 1,
        status: 'APPROVED',
        schema_version: 1,
        content: {
          description: '주문을 만든다.',
          method: 'POST',
          path: '/v1/orders',
        },
      },
    ],
    has_more: false,
  } as never)
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  const title = await screen.findByLabelText('작업 제목')
  await userEvent.type(title, ' (수정)')
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({
    title: '사용자 프로필 구현 (수정)',
    design_refs: [{ relation: 'CHANGES', proposed_content: proposed }],
    unlinked: [unlinked],
  })
})

// PR #72 리뷰 5: 접근 방법은 서버 필수값(@NotBlank)이라 기본 칸에 두고 비우면 막는다.
it('계획 수정에서 접근 방법을 비우면 저장하지 않고 칸에 알린다', async () => {
  const save = vi.spyOn(tasksApi, 'savePlan')
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  const approach = await screen.findByLabelText('어떻게 진행하나요?')
  await userEvent.clear(approach)
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  expect(await screen.findByText('내용을 입력해 주세요.')).toBeInTheDocument()
  expect(save).not.toHaveBeenCalled()
})

// PR #72 전수 리뷰 TC-4: 완료 보고가 실패하면 열린 폼 안에서 이유를 보인다.
it('완료 보고가 실패하면 폼 안에 실패 이유를 보인다', async () => {
  task = { ...task, status: 'IN_PROGRESS' }
  vi.spyOn(tasksApi, 'state').mockRejectedValue(new Error('500'))
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '완료 보고' }))
  const sheet = await screen.findByRole('dialog')
  fireEvent.click(screen.getByLabelText('응답 필드 확인'))
  fireEvent.change(screen.getByLabelText('완료한 내용'), {
    target: { value: '응답 필드를 붙였어요.' },
  })
  fireEvent.click(screen.getAllByRole('button', { name: '완료 보고' }).at(-1)!)
  expect(
    await within(sheet).findByText(
      '요청을 완료하지 못했습니다. 다시 시도해 주세요.',
    ),
  ).toHaveAttribute('role', 'alert')
})

// PR #72 재전수 R2-TC-01: 연결하지 않은 내용은 객체 하나씩 고친다. 여러 줄 설명도 한 객체로 남고,
// 설명이 같은 두 객체도 따로 지키며, 고친 객체는 다른 필드를 두고 설명만 바뀐다.
it('연결하지 않은 내용을 객체 하나씩 고치고 여러 줄·같은 설명의 다른 필드를 지킨다', async () => {
  const multiline = {
    description: '결제 연동\n환불 정책은 다음 단계',
    method: 'POST',
    path: '/payments',
  }
  const first = { description: '같은 설명', owner: 'A' }
  const second = { description: '같은 설명', owner: 'B' }
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        unlinked: [multiline, first, second],
      },
    },
  }
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  const third = await screen.findByLabelText('연결하지 못한 내용 3')
  await userEvent.clear(third)
  await userEvent.type(third, '설명을 고침')
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2].unlinked).toEqual([
    multiline,
    first,
    { description: '설명을 고침', owner: 'B' },
  ])
})

// PR #72 3차 조사 THIRD-TC-01: 고른 선행 작업이 취소되거나 참조 설계가 폐기되면 고르는 칸에서 사라져도 따로 보이고 뺄 수 있다.
it('취소된 선행 작업을 계획에서 빼고 저장한다', async () => {
  const cancelled: TaskDetail = {
    ...task,
    id: '502',
    key: 'TASK-002',
    status: 'CANCELLED',
    current_plan_revision: {
      ...task.current_plan_revision,
      id: '512',
      task_id: '502',
      plan: { ...task.current_plan_revision.plan, title: '로그인 화면' },
    },
  }
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        dependency_task_ids: ['502'],
      },
    },
  }
  vi.spyOn(tasksApi, 'list').mockImplementation(async () => ({
    items: [task, cancelled],
    has_more: false,
  }))
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  expect(
    await screen.findByText(/TASK-002 · 로그인 화면 · 취소된 작업/),
  ).toBeInTheDocument()
  await userEvent.click(
    screen.getByRole('button', { name: 'TASK-002 선행에서 빼기' }),
  )
  expect(screen.queryByText(/취소된 작업/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({ dependency_task_ids: [] })
})

it('폐기된 설계 참조 때문에 저장이 막히면 그 줄에 알리고, 빼면 저장한다', async () => {
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        design_refs: [
          {
            design_item_id: '301',
            design_item_version_id: '311',
            relation: 'IMPLEMENTS',
          },
        ],
      },
    },
  }
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockRejectedValueOnce(
      new AxiosError('bad request', '400', undefined, undefined, {
        status: 400,
        statusText: 'Bad Request',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: {
          error: {
            code: 'validation_failed',
            message:
              '현재 기준 설계에 없거나 폐기된 항목입니다 (design_item_id=301)',
          },
        },
      }),
    )
    .mockImplementation(async () => task)
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  expect(
    await screen.findByText(/설계 항목 301 · 폐기된 설계예요/),
  ).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  expect(
    await screen.findByText('이 항목 때문에 저장하지 못했어요.'),
  ).toBeInTheDocument()
  await userEvent.click(
    screen.getByRole('button', { name: '설계 항목 301 빼기' }),
  )
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
  expect(save.mock.calls[1][2]).toMatchObject({ design_refs: [] })
})

// S15P31A108-319: 설계 항목 검색과 완료 기준 칸(캔버스 PNL-05-v2-plan)
const designItem = (
  id: string,
  key: string,
  kind: string,
  description: string,
) => ({
  id: `${id}1`,
  design_item_id: id,
  key,
  kind,
  version_no: 1,
  status: 'APPROVED',
  schema_version: 1,
  content: { description },
})
const designItems = [
  designItem('301', 'REQ-001', 'REQUIREMENT', '참여 프로젝트를 조회한다.'),
  designItem('302', 'API-001', 'API_CONTRACT', '주문을 만든다.'),
  designItem('303', 'ENT-001', 'DATA_CONTRACT', '주문 테이블'),
]
async function openPlanEditor(items = designItems, hasMore = false) {
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items,
    has_more: hasMore,
    ...(hasMore ? { next_cursor: 'c2' } : {}),
  } as never)
  const save = vi
    .spyOn(tasksApi, 'savePlan')
    .mockImplementation(async () => task)
  show(false)
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  await screen.findByLabelText('어떻게 진행하나요?')
  return save
}
const itemSearch = () =>
  screen.getByRole('searchbox', { name: '설계 항목 검색' })
const itemBox = (key: string) => screen.queryByLabelText(new RegExp(`^${key} `))

it('설계 항목을 대소문자 없이 키와 설명으로 찾는다', async () => {
  await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.type(itemSearch(), 'api')
  expect(itemBox('API-001')).toBeInTheDocument()
  expect(itemBox('REQ-001')).toBeNull()
  await userEvent.clear(itemSearch())
  await userEvent.type(itemSearch(), '테이블')
  expect(itemBox('ENT-001')).toBeInTheDocument()
  expect(itemBox('API-001')).toBeNull()
})

// 서버의 데이터 계약 키는 ENT-지만 캔버스와 문서는 ERD라고 부른다.
it('ERD로 찾으면 데이터 계약 항목이 나온다', async () => {
  await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.type(itemSearch(), 'ERD')
  expect(itemBox('ENT-001')).toBeInTheDocument()
  expect(itemBox('REQ-001')).toBeNull()
})

it('검색으로 찾아 고르고 관계를 정한 항목은 검색에 가려져도 함께 보낸다', async () => {
  const save = await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.type(itemSearch(), 'API')
  await userEvent.click(itemBox('API-001')!)
  await userEvent.selectOptions(
    screen.getByLabelText('API-001의 참조 관계'),
    'READS',
  )
  await userEvent.clear(itemSearch())
  await userEvent.type(itemSearch(), 'REQ')
  expect(itemBox('API-001')).toBeNull()
  expect(screen.getByText(/^1개 선택/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({
    design_refs: [
      {
        design_item_id: '302',
        design_item_version_id: '3021',
        relation: 'READS',
      },
    ],
  })
})

it('검색칸에서 Enter를 눌러도 계획을 저장하지 않는다', async () => {
  const save = await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.type(itemSearch(), 'API{Enter}')
  expect(itemBox('API-001')).toBeInTheDocument()
  expect(save).not.toHaveBeenCalled()
})

// S15P31A108-360: '계약 변경 없음' 체크가 접힌 칸에 꺼진 채 있어, 필수 칸만 채운 웹 계획이 첫 검증 규칙 검사에서 늘 막혔다.
// 계약 변경으로 고른 항목도 설계에 없는 내용도 없으면 바꿀 계약이 없으므로 true 로 보낸다.
it('계약 변경으로 고른 항목이 없으면 계약 변경 없음으로 저장한다', async () => {
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: { ...task.current_plan_revision.plan, no_contract_change: false },
    },
  }
  const save = await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.click(itemBox('API-001')!)
  expect(
    screen.queryByLabelText('승인된 계약을 변경하지 않는 작업입니다.'),
  ).toBeNull()
  expect(
    screen.getByText(
      '계약 변경으로 고른 항목이 없어 승인된 계약을 바꾸지 않는 작업으로 저장돼요.',
    ),
  ).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({ no_contract_change: true })
})

it('계약 변경으로 고른 항목이 있으면 계약을 바꾸는 작업으로 저장한다', async () => {
  const save = await openPlanEditor()
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.click(itemBox('API-001')!)
  await userEvent.selectOptions(
    screen.getByLabelText('API-001의 참조 관계'),
    'CHANGES',
  )
  expect(
    screen.getByText(
      '계약 변경으로 고른 설계 항목이 있어 계약을 바꾸는 작업으로 저장돼요.',
    ),
  ).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({
    no_contract_change: false,
    design_refs: [expect.objectContaining({ relation: 'CHANGES' })],
  })
})

it('설계에 없는 내용만 있으면 계약 변경 여부를 사용자가 정한다', async () => {
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        no_contract_change: false,
        unlinked: [{ description: '외부 결제 연동' }],
      },
    },
  }
  const save = await openPlanEditor()
  const box = screen.getByLabelText('승인된 계약을 변경하지 않는 작업입니다.')
  expect(box).not.toBeChecked()
  await userEvent.click(box)
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2]).toMatchObject({ no_contract_change: true })
})

it('찾는 설계 항목이 없으면 안내하고, 더 있으면 더 불러오는 버튼을 둔다', async () => {
  await openPlanEditor(designItems, true)
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.type(itemSearch(), '없는 항목')
  expect(
    screen.getByText(/‘없는 항목’에 맞는 설계 항목이 없어요/),
  ).toBeVisible()
  expect(
    screen.getByRole('button', { name: '설계 항목 더 보기' }),
  ).toBeVisible()
})

it('검색에 가려진 항목 때문에 저장이 막히면 검색을 풀어 그 줄의 안내를 보인다', async () => {
  const save = await openPlanEditor()
  save.mockRejectedValueOnce(
    new AxiosError('bad request', '400', undefined, undefined, {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: {
          code: 'validation_failed',
          message: '현재 기준 설계에 없는 항목입니다 (design_item_id=302)',
        },
      },
    }),
  )
  await screen.findByLabelText(/^REQ-001 /)
  await userEvent.click(itemBox('API-001')!)
  await userEvent.type(itemSearch(), 'REQ')
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  expect(
    await screen.findByText(
      '이 항목 때문에 저장하지 못했어요. 현재 승인본으로 갱신하거나 빼 주세요.',
    ),
  ).toBeInTheDocument()
  expect(itemSearch()).toHaveValue('')
})

it('완료 기준을 줄마다 더하면 빈 줄을 빼고 문자열 배열로 보낸다', async () => {
  const save = await openPlanEditor()
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('응답 필드 확인')
  await userEvent.click(screen.getByRole('button', { name: '기준 추가' }))
  await userEvent.type(
    screen.getByLabelText('완료 기준 2'),
    '  오류 응답 확인 ',
  )
  await userEvent.click(screen.getByRole('button', { name: '기준 추가' }))
  await userEvent.type(screen.getByLabelText('완료 기준 3'), '   ')
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(save.mock.calls[0][2].completion_criteria).toEqual([
    '응답 필드 확인',
    '오류 응답 확인',
  ])
})

it('완료 기준 줄을 지우면 그 줄만 사라지고, 마지막 한 줄은 비운다', async () => {
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: {
        ...task.current_plan_revision.plan,
        completion_criteria: ['첫째 기준', '둘째 기준'],
      },
    },
  }
  await openPlanEditor()
  await userEvent.click(
    screen.getByRole('button', { name: '완료 기준 1 지우기' }),
  )
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('둘째 기준')
  expect(screen.queryByLabelText('완료 기준 2')).toBeNull()
  await userEvent.click(
    screen.getByRole('button', { name: '완료 기준 1 지우기' }),
  )
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('')
})

it('완료 기준이 모두 비어 있으면 저장하지 않고 칸에 알린다', async () => {
  const save = await openPlanEditor()
  await userEvent.clear(screen.getByLabelText('완료 기준 1'))
  await userEvent.click(screen.getByRole('button', { name: '기준 추가' }))
  await userEvent.type(screen.getByLabelText('완료 기준 2'), '   ')
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  expect(await screen.findByText('내용을 입력해 주세요.')).toBeInTheDocument()
  expect(save).not.toHaveBeenCalled()
})

it('완료 기준 칸에서 Enter를 누르면 저장하지 않고 다음 줄로 간다', async () => {
  const save = await openPlanEditor()
  await userEvent.type(screen.getByLabelText('완료 기준 1'), '{Enter}')
  expect(screen.getByLabelText('완료 기준 2')).toHaveFocus()
  expect(save).not.toHaveBeenCalled()
})

it('한글 조합을 끝내는 Enter로는 완료 기준 줄을 더하지 않는다', async () => {
  await openPlanEditor()
  fireEvent.keyDown(screen.getByLabelText('완료 기준 1'), {
    key: 'Enter',
    isComposing: true,
  })
  expect(screen.queryByLabelText('완료 기준 2')).toBeNull()
})

it('완료 기준이 없던 계획을 고칠 때도 빈 줄 하나로 시작한다', async () => {
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: { ...task.current_plan_revision.plan, completion_criteria: [] },
    },
  }
  await openPlanEditor()
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('')
})

// PR #88 리뷰(재현 경로): 계획을 고쳐 저장하는 동안 머리의 '+ 작업 계획'으로 새 폼을 열면,
// 앞의 저장이 끝나며 새 폼과 입력이 사라졌다. 계획 폼이 열려 있거나 저장 중이면 머리 버튼을 숨긴다.
it('계획 폼이 열려 있거나 저장 중이면 창 머리의 작업 계획 버튼을 숨긴다', async () => {
  let finish = () => {}
  const save = vi.spyOn(tasksApi, 'savePlan').mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = () => resolve({} as never)
      }),
  )
  const slot = document.createElement('div')
  document.body.append(slot)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PaneHeaderSlot value={slot}>
        <PaneSheetHost>
          <TasksPanel projectId="101" isLeader={false} />
        </PaneSheetHost>
      </PaneHeaderSlot>
    </QueryClientProvider>,
  )
  expect(
    await within(slot).findByRole('button', { name: '작업 계획' }),
  ).toBeInTheDocument()
  await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  await userEvent.click(
    await screen.findByRole('menuitem', { name: '계획 수정' }),
  )
  await screen.findByLabelText('어떻게 진행하나요?')
  expect(within(slot).queryByRole('button', { name: '작업 계획' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '계획 저장' }))
  await waitFor(() => expect(save).toHaveBeenCalled())
  expect(within(slot).queryByRole('button', { name: '작업 계획' })).toBeNull()
  await act(async () => finish())
  expect(
    await within(slot).findByRole('button', { name: '작업 계획' }),
  ).toBeInTheDocument()
  slot.remove()
})

// 11-04 사용자 결정(S15P31A108-282): 담당자가 나가거나 제외되면 서버는 assignee_member_id를 생략한다.
const withoutAssignee = () => {
  const unassigned: Record<string, unknown> = { ...task }
  delete unassigned.assignee_member_id
  return taskDetailSchema.parse(unassigned)
}

it('담당자가 없는 작업도 읽어서 목록에 담당자 없음 칩을, 상세에 비어 있다는 안내를 보여 준다', async () => {
  task = withoutAssignee()
  show(true)
  expect(await screen.findByText('담당자가 비어 있어요')).toBeInTheDocument()
  expect(screen.getByText(/TASK-001 · 담당자 없음/)).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '작업 시작' }),
  ).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('tab', { name: '팀 전체' }))
  const entry = await screen.findByRole('button', {
    name: /사용자 프로필 구현/,
  })
  expect(within(entry).getByText('담당자 없음')).toBeInTheDocument()
})

it('팀원이 담당자 없는 작업에서 내가 맡기를 누르면 확인 뒤 내 멤버 ID와 잠금 번호로 맡는다', async () => {
  task = withoutAssignee()
  const assign = vi.spyOn(tasksApi, 'assign').mockImplementation(async () => {
    task = { ...task, assignee_member_id: '21', lock_version: 8 }
    return task
  })
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '내가 맡기' }),
  )
  const dialog = await screen.findByRole('dialog', {
    name: '이 작업을 내가 맡을까요?',
  })
  await userEvent.click(
    within(dialog).getByRole('button', { name: '내가 맡기' }),
  )
  await waitFor(() =>
    expect(assign).toHaveBeenCalledWith('101', '501', {
      assignee_member_id: '21',
      expected_lock_version: 7,
    }),
  )
  expect(
    await screen.findByRole('button', { name: '작업 시작' }),
  ).toBeInTheDocument()
})

const revisionConflict = () => {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config,
    data: { error: { code: 'revision_conflict' } },
  })
}

it('다른 팀원이 먼저 맡아 409 revision_conflict가 오면 먼저 맡은 사람을 알린다', async () => {
  task = withoutAssignee()
  // 기본 픽스처 이름 '다른 팀원'은 이름을 못 찾을 때 쓰는 문구와 같아, 이 테스트에서는 다른 이름을 쓴다.
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
        name: '김동료',
        role: 'LEADER',
        agent_connected: false,
      },
    ],
    has_more: false,
  })
  vi.spyOn(tasksApi, 'assign').mockImplementation(async () => {
    task = { ...task, assignee_member_id: '22', lock_version: 8 }
    throw revisionConflict()
  })
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '내가 맡기' }),
  )
  await userEvent.click(
    within(await screen.findByRole('dialog')).getByRole('button', {
      name: '내가 맡기',
    }),
  )
  const taken = await screen.findByRole('dialog', {
    name: '다른 팀원이 먼저 맡았어요',
  })
  expect(
    within(taken).getByText(/김동료님이 조금 전에 TASK-001 작업을 맡았어요/),
  ).toBeInTheDocument()
})

it('409 뒤에도 담당자가 비어 있으면 다시 누를 때 새로 받은 잠금 번호로 맡는다', async () => {
  task = withoutAssignee()
  const assign = vi
    .spyOn(tasksApi, 'assign')
    .mockImplementationOnce(async () => {
      // 다른 변경으로 잠금 번호만 올랐고 담당자는 여전히 비어 있다.
      task = { ...task, lock_version: 8 }
      throw revisionConflict()
    })
    .mockImplementationOnce(async () => {
      task = { ...task, assignee_member_id: '21', lock_version: 9 }
      return task
    })
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '내가 맡기' }),
  )
  const dialog = await screen.findByRole('dialog', {
    name: '이 작업을 내가 맡을까요?',
  })
  await userEvent.click(
    within(dialog).getByRole('button', { name: '내가 맡기' }),
  )
  expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
  await userEvent.click(
    within(dialog).getByRole('button', { name: '내가 맡기' }),
  )
  await waitFor(() => expect(assign).toHaveBeenCalledTimes(2))
  expect(assign).toHaveBeenLastCalledWith('101', '501', {
    assignee_member_id: '21',
    expected_lock_version: 8,
  })
})

it('팀장이 담당자 없는 작업의 담당자를 정할 때는 첫 팀원을 미리 고르지 않는다', async () => {
  task = withoutAssignee()
  const assign = vi
    .spyOn(tasksApi, 'assign')
    .mockImplementation(async () => task)
  show(true)
  await userEvent.click(
    await screen.findByRole('button', { name: '담당자 정하기' }),
  )
  await userEvent.click(await screen.findByRole('button', { name: '저장' }))
  expect(
    await screen.findByText('새 담당자를 골라 주세요.'),
  ).toBeInTheDocument()
  expect(assign).not.toHaveBeenCalled()
  await userEvent.selectOptions(screen.getByLabelText(/새 담당자/), '다른 팀원')
  await userEvent.click(screen.getByRole('button', { name: '저장' }))
  await waitFor(() =>
    expect(assign).toHaveBeenCalledWith('101', '501', {
      assignee_member_id: '22',
      expected_lock_version: 7,
    }),
  )
})

// S15P31A108-340: 백엔드가 stale_reason에 쓰는 값 전부(ValidationStatusLookup·TaskHandoverService·TaskPlanService·
// TaskStateService·AnalysisResultApplier·PublicationImpactAdapter)와 모르는 값.
it.each([
  ['ASSIGNEE_CHANGED', '새 담당자가 다시 검증받아야 해요'],
  ['PLAN_CHANGED', '계획이 바뀌어 지금 계획으로 다시 검증해야 해요'],
  ['RELATED_PLAN_CHANGED', '같은 설계 항목을 다루는 다른 작업이 바뀌어'],
  ['INPUT_CHANGED', '분석하는 동안 기준 설계나 계획이 바뀌어'],
  ['DESIGN_PUBLISHED', '참조한 설계가 새로 발행되어'],
  ['TASK_RESUMED', '마치면 다시 완료 보고해 주세요'],
  ['TASK_CANCELLED', '작업이 취소되어 이 검증은 더 쓰지 않아요'],
  ['SOMETHING_NEW', '기준이 바뀌어 다시 검증해야 해요'],
])(
  '검증 무효 사유 %s는 코드 대신 바뀐 것과 할 일을 문장으로 보인다',
  async (reason, sentence) => {
    task = taskDetailSchema.parse({
      ...task,
      validation_status: 'STALE',
      validation: {
        ...task.validation!,
        status: 'STALE',
        stale_reason: reason,
      },
    })
    show(false)
    const card = (
      await screen.findByText('설계와 계획의 검증 결과')
    ).closest<HTMLElement>('[data-slot="card"]')!
    expect(within(card).getByText(new RegExp(sentence))).toBeInTheDocument()
    expect(screen.queryByText(reason)).not.toBeInTheDocument()
  },
)

const attemptLimit = () => {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config,
    data: {
      error: {
        code: 'validation_attempt_limit',
        message:
          '같은 계획으로 분석을 3번 돌렸습니다. 계획을 고치거나 팀에 문의하세요',
      },
    },
  })
}

it('분석 횟수를 다 써 재시도가 거절되면 재시도를 거두고 계획을 고치라고 알린다', async () => {
  task = {
    ...task,
    validation_status: 'ERROR',
    validation: {
      ...task.validation!,
      status: 'ERROR',
      current_attempt: 3,
      findings: [
        {
          id: '705',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message: '분석 시간 초과',
          sources: [],
          resolution: 'RETRY',
        },
      ],
    },
  }
  const retry = vi
    .spyOn(validationsApi, 'retry')
    .mockRejectedValue(attemptLimit())
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '실행 재시도' }))
  expect(
    await screen.findByText(/같은 계획으로 분석할 수 있는 횟수를 모두 썼어요/),
  ).toBeInTheDocument()
  expect(retry).toHaveBeenCalledTimes(1)
  expect(
    screen.queryByRole('button', { name: '실행 재시도' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText(
      '같은 계획으로는 다시 실행할 수 없어요. 계획을 고쳐 새로 검증해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(screen.queryByText(/요청을 처리하지 못했어요/)).not.toBeInTheDocument()
})

it('같은 계획의 새 검증이 횟수 제한에 걸려도 일반 오류 대신 계획을 고치라고 알린다', async () => {
  vi.spyOn(validationsApi, 'create').mockRejectedValue(attemptLimit())
  show(false)
  fireEvent.click(await screen.findByRole('button', { name: '다시 검증' }))
  expect(
    await screen.findByText(/같은 계획으로 분석할 수 있는 횟수를 모두 썼어요/),
  ).toBeInTheDocument()
  expect(screen.queryByText(/요청을 처리하지 못했어요/)).not.toBeInTheDocument()
})

it('검증이 함께 비교한 작업을 키·제목·담당자로 보이고, 못 읽은 작업은 따로 알린다', async () => {
  const related = taskDetailSchema.parse({
    ...task,
    id: '503',
    key: 'TASK-003',
    assignee_member_id: '22',
    current_plan_revision: {
      ...task.current_plan_revision,
      id: '513',
      task_id: '503',
      plan: {
        ...task.current_plan_revision.plan,
        title: '참여 프로젝트 목록 API 구현',
      },
    },
  })
  task = {
    ...task,
    validation: {
      ...task.validation!,
      related_task_refs: [
        { task_id: '503', plan_revision_id: '513' },
        { task_id: '599', plan_revision_id: '519' },
      ],
    },
  }
  const get = vi
    .spyOn(tasksApi, 'get')
    .mockImplementation(async (_project, id) => {
      if (id === '503') return related
      if (id === '599') throw new Error('not found')
      return task
    })
  show(false)
  const section = await screen.findByRole('region', {
    name: '함께 비교한 작업',
  })
  expect(
    await within(section).findByText('참여 프로젝트 목록 API 구현'),
  ).toBeInTheDocument()
  expect(within(section).getByText('TASK-003')).toBeInTheDocument()
  expect(within(section).getByText(/담당자 다른 팀원/)).toBeInTheDocument()
  expect(
    await within(section).findByText('작업 정보를 불러오지 못했어요.'),
  ).toBeInTheDocument()
  expect(get).toHaveBeenCalledWith('101', '503')
})

// S15P31A108-313: 작업 현황 목록·상세를 캔버스 배치로(PNL-05-v2-tasks·blocked·history·record, 11-05 결정)
const listed = (
  id: string,
  key: string,
  status: TaskDetail['status'],
  title: string,
): TaskDetail => ({
  ...task,
  id,
  key,
  status,
  current_plan_revision: {
    ...task.current_plan_revision,
    id: `${id}1`,
    task_id: id,
    plan: { ...task.current_plan_revision.plan, title },
  },
})

it('상태 타일은 할 것·진행 중·대기·끝남으로 불러온 목록을 세고 목록을 상태별 머리글로 묶는다', async () => {
  vi.spyOn(tasksApi, 'list').mockResolvedValue({
    items: [
      listed('509', 'TASK-009', 'COMPLETED', '끝난 작업'),
      listed('508', 'TASK-008', 'CANCELLED', '취소한 작업'),
      listed('507', 'TASK-007', 'WAITING', '기다리는 작업'),
      listed('506', 'TASK-006', 'IN_PROGRESS', '진행 중인 작업'),
      listed('505', 'TASK-005', 'DRAFT', '초안 작업'),
      task,
    ],
    has_more: false,
  })
  show(false)
  const tiles = await screen.findByRole('list', { name: '상태별 작업 수' })
  expect(
    within(tiles)
      .getAllByRole('listitem')
      .map((tile) => tile.textContent),
  ).toEqual(['2할 것', '1진행 중', '1대기', '2끝남'])
  const todo = screen.getByRole('region', { name: '지금 할 것' })
  expect(within(todo).getByText('초안 작업')).toBeInTheDocument()
  expect(within(todo).getByText('사용자 프로필 구현')).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '진행 중' })).getByText(
      '진행 중인 작업',
    ),
  ).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '대기' })).getByText(
      '기다리는 작업',
    ),
  ).toBeInTheDocument()
  const done = screen.getByRole('region', { name: '끝남' })
  expect(within(done).getByText('끝난 작업')).toBeInTheDocument()
  expect(within(done).getByText('취소한 작업')).toBeInTheDocument()
})

it('검색하면 키나 제목이 맞는 작업만 남기고, 없으면 안내한다', async () => {
  vi.spyOn(tasksApi, 'list').mockResolvedValue({
    items: [task, listed('502', 'TASK-002', 'IN_PROGRESS', '로그인 화면')],
    has_more: false,
  })
  show(false)
  const search = await screen.findByRole('searchbox', { name: '작업 검색' })
  await userEvent.type(search, '로그인')
  expect(
    screen.getByRole('button', { name: /로그인 화면/ }),
  ).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /TASK-001/ })).toBeNull()
  expect(screen.queryByRole('region', { name: '지금 할 것' })).toBeNull()
  // 타일은 검색과 상관없이 탭의 작업 수를 센다.
  expect(
    within(screen.getByRole('list', { name: '상태별 작업 수' }))
      .getAllByRole('listitem')
      .map((tile) => tile.textContent),
  ).toEqual(['1할 것', '1진행 중', '0대기', '0끝남'])
  await userEvent.clear(search)
  await userEvent.type(search, 'task-001')
  expect(screen.getByRole('button', { name: /TASK-001/ })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /로그인 화면/ })).toBeNull()
  await userEvent.clear(search)
  await userEvent.type(search, '없는 작업')
  expect(
    screen.getByText('찾는 작업이 없어요. 다른 이름이나 번호로 찾아보세요.'),
  ).toBeInTheDocument()
})

it('작업이 한 페이지(50개)를 넘으면 뒤 페이지까지 받아 타일을 세고 검색한다', async () => {
  const first = [
    task,
    ...Array.from({ length: 49 }, (_, index) =>
      listed(`6${index}`, `TASK-${100 + index}`, 'READY', `앞 페이지 ${index}`),
    ),
  ]
  const list = vi
    .spyOn(tasksApi, 'list')
    .mockImplementation(async (_projectId, params) =>
      params?.cursor === 'c2'
        ? {
            items: [listed('701', 'TASK-701', 'IN_PROGRESS', '뒤 페이지 작업')],
            has_more: false,
          }
        : { items: first, has_more: true, next_cursor: 'c2' },
    )
  show(false)
  const tiles = await screen.findByRole('list', { name: '상태별 작업 수' })
  await waitFor(() =>
    expect(
      within(tiles)
        .getAllByRole('listitem')
        .map((tile) => tile.textContent),
    ).toEqual(['50할 것', '1진행 중', '0대기', '0끝남']),
  )
  expect(list).toHaveBeenCalledWith('101', { cursor: 'c2', limit: 50 })
  await userEvent.type(
    screen.getByRole('searchbox', { name: '작업 검색' }),
    'task-701',
  )
  expect(
    screen.getByRole('button', { name: /뒤 페이지 작업/ }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('찾는 작업이 없어요. 다른 이름이나 번호로 찾아보세요.'),
  ).toBeNull()
})

it('검증을 통과하면 검증 결과를 접어 두고 단계는 번호 원과 상태 글로 보인다', async () => {
  show(false)
  const steps = await screen.findByRole('list', { name: '작업 단계' })
  expect(within(steps).getByText('완료')).toBeInTheDocument()
  expect(within(steps).getByText('통과')).toBeInTheDocument()
  expect(within(steps).getByText('지금')).toBeInTheDocument()
  expect(screen.getByText('설계와 계획의 검증 결과')).toBeVisible()
  expect(
    await screen.findByText(/현재 계획과 입력에서 시작을 막는 항목을/),
  ).not.toBeVisible()
})

it.each(['NEEDS_INPUT', 'NEEDS_REVIEW'] as const)(
  '검증 결과가 %s이면 검증 결과를 펼쳐 막힌 항목을 보인다',
  async (status) => {
    task = {
      ...task,
      validation_status: status,
      validation: {
        ...task.validation!,
        status,
        findings: [
          {
            id: '701',
            source: 'RULE',
            kind: 'MISSING_INPUT',
            severity: 'BLOCKING',
            message: '포함 범위를 더 적어 주세요.',
            sources: [],
            resolution: 'FIX_PLAN',
          },
        ],
      },
    }
    show(false)
    expect(await screen.findByText('포함 범위를 더 적어 주세요.')).toBeVisible()
  },
)

it('통과했어도 대기 이유가 있으면 검증 결과를 펼친다', async () => {
  task = { ...task, status: 'WAITING', waiting_reason: 'INPUT' }
  show(false)
  expect(await screen.findByText(/대기 이유/)).toBeVisible()
})

it('계획 이력 카드는 리비전과 지금 계획의 당시 개발 맥락·작성자를 창 안 화면으로 연다', async () => {
  const earlier = {
    ...task.current_plan_revision,
    id: '510',
    revision_no: 1,
    plan: { ...task.current_plan_revision.plan, title: '처음 계획' },
  }
  task = {
    ...task,
    current_plan_revision: {
      ...task.current_plan_revision,
      plan: { ...task.current_plan_revision.plan, context_packet_id: '701' },
    },
  }
  vi.spyOn(tasksApi, 'planRevisions').mockResolvedValue({
    items: [task.current_plan_revision, earlier],
    has_more: false,
  })
  vi.spyOn(contextsApi, 'packet').mockResolvedValue({
    id: '701',
    project_id: '101',
    task_id: '501',
    baseline_id: '401',
    request: '프로필 응답을 붙여 주세요.',
    design_items: [],
    constraints: [],
    unresolved: ['정렬 기준'],
    related_tasks: [],
    sources: [],
    created_at: timestamp,
    content_hash: 'sha256:0',
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PaneSheetHost>
        <TasksPanel projectId="101" isLeader={false} />
      </PaneSheetHost>
    </QueryClientProvider>,
  )
  await userEvent.click(
    await screen.findByRole('button', { name: /계획 이력 r2 · 2개/ }),
  )
  const sheet = screen.getByRole('region', { name: '계획 이력' })
  expect(within(sheet).getByText('r2 · 지금 계획')).toBeInTheDocument()
  expect(within(sheet).getByText('r1 · 처음 계획')).toBeInTheDocument()
  expect(
    await within(sheet).findByText('프로필 응답을 붙여 주세요.'),
  ).toBeInTheDocument()
  expect(within(sheet).getByText('미정: 정렬 기준')).toBeInTheDocument()
  expect(within(sheet).getAllByText(/담당자 · 2026/)).toHaveLength(2)
  // 당시 개발 맥락은 지금 계획에만 있다(리비전별 맥락 API 없음).
  expect(within(sheet).getAllByText('당시 개발 맥락')).toHaveLength(1)
  await userEvent.click(
    within(sheet).getByRole('button', { name: '계획 수정' }),
  )
  expect(await screen.findByLabelText('어떻게 진행하나요?')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '계획 이력' })).toBeNull()
})

it('작업 기록 카드는 기록 목록을 열고, 기록 남기기로 기록 폼을 연다', async () => {
  vi.spyOn(contextsApi, 'list').mockResolvedValue({
    items: [
      {
        id: '801',
        root_id: '801',
        revision_no: 1,
        task_id: '501',
        kind: 'PROGRESS',
        summary: '응답 필드를 붙였어요.',
        author_account_id: '11',
        visibility: 'SHARED',
        is_latest_revision: true,
        references_current: true,
        created_at: timestamp,
      },
    ],
    has_more: false,
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PaneSheetHost>
        <TasksPanel projectId="101" isLeader={false} />
      </PaneSheetHost>
    </QueryClientProvider>,
  )
  await userEvent.click(
    await screen.findByRole('button', { name: /작업 기록 1개/ }),
  )
  const sheet = screen.getByRole('region', { name: '작업 기록' })
  expect(within(sheet).getByText('응답 필드를 붙였어요.')).toBeInTheDocument()
  expect(within(sheet).getByText('리비전 1 · 팀 공유')).toBeInTheDocument()
  await userEvent.click(
    within(sheet).getByRole('button', { name: '기록 남기기' }),
  )
  expect(
    await screen.findByRole('region', { name: '작업 기록 남기기' }),
  ).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '작업 기록' })).toBeNull()
})

// PR #72 5차 TC-03: 계획 이력이 100개를 넘으면 첫 페이지 밖의 이전 계획을 읽을 수 없었다.
it('계획 이력이 한 페이지를 넘으면 다음 페이지 리비전까지 모두 보여 준다', async () => {
  const older = {
    ...task.current_plan_revision,
    id: '510',
    revision_no: 1,
    plan: { ...task.current_plan_revision.plan, title: '첫 계획' },
  }
  const revisions = vi
    .spyOn(tasksApi, 'planRevisions')
    .mockImplementation(async (_project, _task, query) =>
      query?.cursor === 'r2'
        ? { items: [older], has_more: false }
        : {
            items: [task.current_plan_revision],
            has_more: true,
            next_cursor: 'r2',
          },
    )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PaneSheetHost>
        <TasksPanel projectId="101" isLeader={false} />
      </PaneSheetHost>
    </QueryClientProvider>,
  )
  await userEvent.click(
    await screen.findByRole('button', { name: /계획 이력 r2 · 2개/ }),
  )
  const sheet = screen.getByRole('region', { name: '계획 이력' })
  expect(within(sheet).getByText(/r1 · 첫 계획/)).toBeInTheDocument()
  expect(revisions).toHaveBeenCalledWith('101', '501', {
    cursor: 'r2',
    limit: 100,
  })
})

// S15P31A108-385: 새 작업 계획 폼의 '문서로 초안 받기'. 저장하지 않고 빈 칸만 채우며 연결 후보는 사람이 체크한다.
const documentDraft = (): PlanDraft =>
  planDraftResponseSchema.parse({
    status: 'DRAFTED',
    plan: {
      goal: '고객이 고른 메뉴로 주문을 만든다.',
      included_scope: ['주문 생성', '번호 발급'],
      excluded_scope: ['환불'],
      approach: '주문을 저장하고 번호를 발급한다.',
      completion_criteria: ['번호가 응답된다', '없는 메뉴는 거절된다'],
    },
    evidence: [
      {
        field: 'goal',
        document_id: '201',
        document_type: 'REQUIREMENTS',
        document_version_id: '211',
        version_no: 2,
        location: '12~14행',
        quote: '메뉴를 고르고 결제하면 주문 번호를 받는다',
      },
    ],
    unsupported_fields: ['approach'],
    design_ref_candidates: [
      {
        design_item_id: '302',
        design_item_version_id: '3021',
        key: 'API-001',
        kind: 'API_CONTRACT',
        title: '주문 생성 API',
        reason: '주문 생성 API라서',
      },
    ],
    new_design_item_candidates: [
      {
        kind: 'POLICY',
        content: { description: '주문은 10분 안에 취소할 수 있다' },
        rationale: '요구사항 명세서에 있어요.',
        evidence: [
          {
            document_id: '201',
            document_type: 'REQUIREMENTS',
            document_version_id: '211',
            version_no: 2,
            location: '20~21행',
            quote: '10분 안에 취소',
          },
        ],
      },
    ],
    unlinked: [],
    baseline_id: '401',
  })

async function openNewPlan(items = designItems) {
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items,
    has_more: false,
  } as never)
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '작업 계획' }),
  )
  return screen.findByLabelText('작업 제목')
}

it('문서로 초안 받기는 제목만으로 빈 칸을 채우고 저장하지 않으며 근거와 후보를 보여 준다', async () => {
  const draft = vi
    .spyOn(tasksApi, 'draftPlan')
    .mockResolvedValue(documentDraft())
  const create = vi.spyOn(tasksApi, 'create')
  const title = await openNewPlan()
  const button = screen.getByRole('button', { name: '문서로 초안 받기' })
  expect(button).toBeDisabled()
  await userEvent.type(title, '주문 생성 API')
  expect(button).toBeEnabled()
  await userEvent.click(button)
  await waitFor(() =>
    expect(draft).toHaveBeenCalledWith('101', { title: '주문 생성 API' }),
  )
  expect(await screen.findByLabelText('이번 작업의 목표')).toHaveValue(
    '고객이 고른 메뉴로 주문을 만든다.',
  )
  expect(screen.getByLabelText('어떻게 진행하나요?')).toHaveValue(
    '주문을 저장하고 번호를 발급한다.',
  )
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('번호가 응답된다')
  expect(screen.getByLabelText('완료 기준 2')).toHaveValue(
    '없는 메뉴는 거절된다',
  )
  expect(screen.getByLabelText(/포함 범위/)).toHaveValue('주문 생성\n번호 발급')
  // 초안 그대로인 칸만 노란 배경이다.
  expect(screen.getByLabelText('이번 작업의 목표')).toHaveClass('bg-primary/10')
  expect(screen.getByText(/빈 칸 5개를 채웠어요/)).toBeInTheDocument()
  expect(screen.getByText(/요구사항 명세서 v2 · 12~14행/)).toBeInTheDocument()
  expect(screen.getByText(/원문 근거 없이 정리한 글이에요/)).toBeInTheDocument()
  // 같은 입력으로는 다시 받을 수 없다.
  expect(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  ).toBeDisabled()
  expect(create).not.toHaveBeenCalled()
})

it('문서 초안은 이미 적은 칸을 덮어쓰지 않고 그 목표를 메모로 보낸다', async () => {
  const draft = vi
    .spyOn(tasksApi, 'draftPlan')
    .mockResolvedValue(documentDraft())
  const title = await openNewPlan()
  await userEvent.type(title, '주문 생성 API')
  await userEvent.type(
    screen.getByLabelText('이번 작업의 목표'),
    '내가 쓴 목표',
  )
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  await waitFor(() =>
    expect(draft).toHaveBeenCalledWith('101', {
      title: '주문 생성 API',
      memo: '내가 쓴 목표',
    }),
  )
  expect(await screen.findByText(/빈 칸 4개를 채웠어요/)).toBeInTheDocument()
  expect(screen.getByLabelText('이번 작업의 목표')).toHaveValue('내가 쓴 목표')
  expect(screen.getByLabelText('이번 작업의 목표')).not.toHaveClass(
    'bg-primary/10',
  )
  expect(screen.getByLabelText('어떻게 진행하나요?')).toHaveValue(
    '주문을 저장하고 번호를 발급한다.',
  )
})

it('되돌리기는 채운 칸을 이전 값으로 돌리고 고친 칸은 노란 배경을 거둔다', async () => {
  vi.spyOn(tasksApi, 'draftPlan').mockResolvedValue(documentDraft())
  const title = await openNewPlan()
  await userEvent.type(title, '주문 생성 API')
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  const approach = await screen.findByLabelText('어떻게 진행하나요?')
  expect(approach).toHaveClass('bg-primary/10')
  await userEvent.type(approach, ' 추가')
  expect(approach).not.toHaveClass('bg-primary/10')
  await userEvent.click(screen.getByRole('button', { name: '되돌리기' }))
  // 그대로인 칸은 돌아가고, 채운 뒤 직접 고친 접근 방법은 남는다.
  expect(screen.getByLabelText('이번 작업의 목표')).toHaveValue('')
  expect(screen.getByLabelText('어떻게 진행하나요?')).toHaveValue(
    '주문을 저장하고 번호를 발급한다. 추가',
  )
  expect(screen.getByLabelText('완료 기준 1')).toHaveValue('')
  expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull()
  expect(
    screen.getByText(/직접 고친 칸 1개\(접근 방법\)는 그대로 두었어요/),
  ).toBeInTheDocument()
})

it('연결 후보는 체크해야 계획에 연결되고 설계에 없는 내용은 연결하지 못한 내용에 더한다', async () => {
  vi.spyOn(tasksApi, 'draftPlan').mockResolvedValue(documentDraft())
  const title = await openNewPlan()
  await userEvent.type(title, '주문 생성 API')
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  expect(
    await screen.findByText(
      /연결된 설계 항목이 없으면 이 작업은 충돌 감지에 잡히지 않아요/,
    ),
  ).toBeInTheDocument()
  const candidate = screen.getByRole('checkbox', {
    name: /API-001 · 주문 생성 API/,
  })
  expect(candidate).not.toBeChecked()
  await userEvent.click(candidate)
  expect(candidate).toBeChecked()
  expect(
    screen.queryByText(/연결된 설계 항목이 없으면 이 작업은 충돌 감지에/),
  ).toBeNull()
  await userEvent.click(candidate)
  expect(candidate).not.toBeChecked()
  await userEvent.click(
    screen.getByRole('button', { name: '연결하지 못한 내용에 추가' }),
  )
  expect(screen.getByLabelText('연결하지 못한 내용 1')).toHaveValue(
    '주문은 10분 안에 취소할 수 있다',
  )
  expect(screen.getByRole('button', { name: '추가했어요' })).toBeDisabled()
})

it('후보로 연결한 항목을 계약 변경으로 바꾸면 제목이 아니라 항목 내용이 바꿀 계약 문장에 채워진다', async () => {
  vi.spyOn(tasksApi, 'draftPlan').mockResolvedValue(documentDraft())
  const title = await openNewPlan()
  await userEvent.type(title, '주문 생성 API')
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  // 후보 패널의 체크 칸과 아래 '관련 설계 항목' 목록의 같은 항목이 모두 보이므로 패널 쪽을 고른다.
  const panel = await screen.findByRole('region', { name: '문서 초안' })
  await userEvent.click(
    within(panel).getByRole('checkbox', { name: /API-001 · 주문 생성 API/ }),
  )
  await userEvent.selectOptions(
    screen.getByLabelText('API-001의 참조 관계'),
    'CHANGES',
  )
  // 후보 제목('주문 생성 API')이 아니라 승인된 항목의 내용이다.
  expect(screen.getByLabelText('바꿀 계약 문장')).toHaveValue('주문을 만든다.')
})

it('아직 불러오지 않은 항목을 후보로 연결하면 목록 밖 참조로 보이고 빼기를 할 수 있다', async () => {
  vi.spyOn(tasksApi, 'draftPlan').mockResolvedValue(documentDraft())
  // 후보 항목(302)이 불러온 승인 목록에 없다.
  const title = await openNewPlan([designItems[0]])
  await userEvent.type(title, '주문 생성 API')
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  const panel = await screen.findByRole('region', { name: '문서 초안' })
  await userEvent.click(
    within(panel).getByRole('checkbox', { name: /API-001 · 주문 생성 API/ }),
  )
  expect(
    screen.getByText(/설계 항목 302 · 폐기된 설계예요/),
  ).toBeInTheDocument()
  await userEvent.click(
    screen.getByRole('button', { name: '설계 항목 302 빼기' }),
  )
  expect(screen.queryByText(/설계 항목 302 ·/)).toBeNull()
})

const draftError = (status: number, code: string) => {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('failed', 'ERR_BAD_RESPONSE', config, undefined, {
    status,
    statusText: 'error',
    headers: {},
    config,
    data: { error: { code, message: code } },
  })
}

it.each([
  ['ai_timeout', 504, /시간이 너무 오래 걸렸어요/],
  ['ai_unavailable', 502, /지금은 비비를 부를 수 없어요/],
  ['ai_output_too_long', 502, /응답 생성 한도에 도달해/],
])(
  '문서 초안 실패 %s는 서로 다른 안내와 오른쪽 다시 받기를 보인다',
  async (code, status, text) => {
    const draft = vi
      .spyOn(tasksApi, 'draftPlan')
      .mockRejectedValueOnce(draftError(status, code))
      .mockResolvedValue(documentDraft())
    const title = await openNewPlan()
    await userEvent.type(title, '주문 생성 API')
    await userEvent.click(
      screen.getByRole('button', { name: '문서로 초안 받기' }),
    )
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(text)
    // 실패는 같은 입력으로 다시 받을 수 있다.
    await userEvent.click(
      within(alert).getByRole('button', { name: '다시 받기' }),
    )
    await waitFor(() => expect(draft).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/빈 칸 5개를 채웠어요/)).toBeInTheDocument()
  },
)

it('초안 횟수를 다 쓰면 안내하고 버튼을 비활성으로 둔다', async () => {
  vi.spyOn(tasksApi, 'draftPlan').mockRejectedValue(
    draftError(429, 'draft_limit_exceeded'),
  )
  const title = await openNewPlan()
  await userEvent.type(title, '주문 생성 API')
  await userEvent.click(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    /초안 횟수를 모두 썼어요/,
  )
  expect(screen.queryByRole('button', { name: '다시 받기' })).toBeNull()
  expect(
    screen.getByRole('button', { name: '문서로 초안 받기' }),
  ).toBeDisabled()
})

it('계획 수정에는 문서로 초안 받기를 두지 않는다', async () => {
  await openPlanEditor()
  expect(screen.queryByRole('button', { name: '문서로 초안 받기' })).toBeNull()
})

it.each(['typed', 'legacy-fetch'] as const)(
  '%s 근거 수집 실패는 계획 보완 대신 같은 검증을 재시도한다',
  async (mode) => {
    const failed = {
      ...task.validation!,
      status: 'ERROR' as const,
      error_code:
        mode === 'typed'
          ? 'evidence_collection_failed'
          : 'evidence_fetch_failed',
      findings: [
        {
          id: '701',
          source: 'MODEL' as const,
          kind: 'ERROR' as const,
          severity: 'BLOCKING' as const,
          message: '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
          sources: [],
          resolution: 'RETRY' as const,
          next_action: '근거 수집을 다시 시도해 주세요.',
        },
      ],
      ...(mode === 'typed'
        ? {
            execution_failure: {
              type: 'EVIDENCE_COLLECTION_FAILED' as const,
              message: '원문을 가져오지 못했어요.',
              reason_codes: ['evidence_fetch_failed'],
              retryable: true,
            },
          }
        : {}),
    }
    task = { ...task, validation_status: 'ERROR', validation: failed }
    const retry = vi
      .spyOn(validationsApi, 'retry')
      .mockResolvedValue({ ...failed, status: 'RUNNING' })
    const save = vi.spyOn(tasksApi, 'savePlan')
    const proposal = vi.spyOn(tasksApi, 'proposePlanRevision')
    show(false)
    const button = await screen.findByRole('button', {
      name: '근거 수집 재시도',
    })
    expect(screen.queryByText(/보완한 뒤 새 검증/)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '계획 보완' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'AI 계획 수정안 받기' }),
    ).not.toBeInTheDocument()
    await userEvent.click(button)
    expect(retry).toHaveBeenCalledWith(
      '101',
      '601',
      { expected_attempt: 1 },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(save).not.toHaveBeenCalled()
    expect(proposal).not.toHaveBeenCalled()
  },
)

it.each(['durable', 'mixed', 'source', 'legacy-unknown'] as const)(
  '%s 근거 수집 실패는 수동 범위 검토를 열고 재시도나 AI 수정을 제안하지 않는다',
  async (mode) => {
    const failed = {
      ...task.validation!,
      status: 'ERROR' as const,
      error_code:
        mode === 'legacy-unknown'
          ? 'ai_evidence_insufficient'
          : 'evidence_collection_failed',
      findings: [
        {
          id: '701',
          source: 'MODEL' as const,
          kind: 'ERROR' as const,
          severity: 'BLOCKING' as const,
          message: '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
          sources: [],
          // 이전 서버가 RETRY로 잘못 저장한 결과도 그대로 실행시키지 않는다.
          resolution: 'RETRY' as const,
          next_action: '근거 수집을 다시 시도해 주세요.',
        },
      ],
      ...(mode === 'legacy-unknown'
        ? {}
        : {
            execution_failure: {
              type: 'EVIDENCE_COLLECTION_FAILED' as const,
              message: '근거 수집 실패',
              reason_codes:
                mode === 'mixed'
                  ? ['search_results_truncated', 'evidence_fetch_failed']
                  : mode === 'source'
                    ? ['source_evidence_unavailable']
                    : ['search_query_too_long'],
              retryable: true,
            },
          }),
    }
    task = { ...task, validation_status: 'ERROR', validation: failed }
    const retry = vi.spyOn(validationsApi, 'retry')
    const proposal = vi.spyOn(tasksApi, 'proposePlanRevision')
    const save = vi.spyOn(tasksApi, 'savePlan')
    show(false)
    const manual = await screen.findByRole('button', {
      name: '계획·참조 범위 검토',
    })
    expect(
      screen.getByRole('list', { name: '근거 수집 실패 원인' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '근거 문서 확인' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '근거 수집 재시도' }),
    ).toBeNull()
    expect(screen.queryByRole('button', { name: '실행 재시도' })).toBeNull()
    expect(screen.queryByRole('button', { name: '계획 검증' })).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 검증' })).toBeNull()
    if (mode === 'durable')
      expect(
        screen.queryByRole('button', { name: '보완 후 새 검증' }),
      ).toBeNull()
    else
      expect(
        screen.getByRole('button', { name: '보완 후 새 검증' }),
      ).toBeEnabled()
    expect(
      screen.queryByRole('button', { name: 'AI 계획 수정안 받기' }),
    ).toBeNull()
    expect(screen.getByRole('button', { name: '작업 시작' })).toBeDisabled()
    await userEvent.click(manual)
    expect(await screen.findByLabelText('이번 작업의 목표')).toHaveValue(
      task.current_plan_revision.plan.goal,
    )
    expect(retry).not.toHaveBeenCalled()
    expect(proposal).not.toHaveBeenCalled()
    expect(save).not.toHaveBeenCalled()
  },
)

it('원문 근거를 외부에서 보완하면 계획을 저장하지 않고 새 검증을 요청할 수 있다', async () => {
  const failed = {
    ...task.validation!,
    status: 'ERROR' as const,
    error_code: 'evidence_collection_failed',
    execution_failure: {
      type: 'EVIDENCE_COLLECTION_FAILED' as const,
      message: '원문 근거 확인 필요',
      reason_codes: ['source_evidence_unavailable'],
      retryable: false,
    },
  }
  task = { ...task, validation_status: 'ERROR', validation: failed }
  const create = vi
    .spyOn(validationsApi, 'create')
    .mockResolvedValue({ ...failed, id: '602', status: 'RUNNING' })
  const retry = vi.spyOn(validationsApi, 'retry')
  const save = vi.spyOn(tasksApi, 'savePlan')
  show(false)
  await userEvent.click(
    await screen.findByRole('button', { name: '보완 후 새 검증' }),
  )
  expect(create).toHaveBeenCalledWith(
    '101',
    '501',
    {
      plan_revision_id: '511',
      expected_lock_version: 7,
    },
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(retry).not.toHaveBeenCalled()
  expect(save).not.toHaveBeenCalled()
})
