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
