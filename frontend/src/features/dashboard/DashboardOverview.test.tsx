import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { changesApi } from '@/api/services/changes'
import { dashboardApi } from '@/api/services/dashboard'
import { tasksApi } from '@/api/services/tasks'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { DashboardOverview } from './DashboardOverview'
import type {
  ActivitiesResponse,
  AgentWorkResponse,
  UsageResponse,
} from './schemas'

vi.mock('@/api/services/dashboard', () => ({
  dashboardApi: { changes: vi.fn(), tasks: vi.fn() },
}))
vi.mock('@/api/services/changes', () => ({
  changesApi: { list: vi.fn() },
}))
vi.mock('@/api/services/tasks', () => ({
  tasksApi: { list: vi.fn() },
}))
const query = <T,>(data: T) => ({ data }) as UseQueryResult<T>
const usage = (call_count: number): UsageResponse => ({
  from: '2026-10-01T00:00:00Z',
  to: '2026-10-29T00:00:00Z',
  input_tokens: 0,
  output_tokens: 0,
  call_count,
  by_feature: call_count
    ? [
        {
          key: 'BEE_CHAT',
          input_tokens: 0,
          output_tokens: 0,
          call_count,
        },
      ]
    : [],
  by_task: [],
  by_validation: [],
  by_agent: [],
  by_day: [],
})
const none: ActivitiesResponse = { items: [], has_more: false }
const noWork: AgentWorkResponse = { items: [], has_more: false }

beforeEach(() => {
  useWorkspaceStore.setState({ pane: 'dashboard', focusId: null })
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 1,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [{ status: 'IN_PROGRESS', count: 2 }],
    by_waiting_reason: [],
  })
  vi.mocked(changesApi.list).mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
  vi.mocked(tasksApi.list).mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
})

function mount(calls: number) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(calls))}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
}

it('숫자 4개를 보여 주고 누르면 그 화면을 연다', async () => {
  const user = userEvent.setup()
  mount(96)
  await user.click(
    await screen.findByRole('button', { name: /진행 중 작업\s*2/ }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  expect(
    screen.getByRole('button', { name: /AI 호출\s*96회\s*비용 미보고/ }),
  ).toBeInTheDocument()
  expect(screen.getByText('비비 대화')).toBeInTheDocument()
})

it('비용을 모르는 호출이 있으면 요약 숫자에 그 건수를 붙인다', async () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query({
          ...usage(5),
          estimated_cost_amount: '0.2',
          currency: 'USD',
          cost_unknown_call_count: 2,
        })}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByRole('button', {
      name: /AI 호출\s*5회\s*추정 0\.2 USD \+ 미보고 2건/,
    }),
  ).toBeInTheDocument()
})

it('아직 기록이 없으면 시작 순서 3개를 보여 준다', async () => {
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 0,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [],
    by_waiting_reason: [],
  })
  const user = userEvent.setup()
  mount(0)
  expect(
    await screen.findByText('아직 기록이 없어요. 이렇게 시작해 보세요.'),
  ).toBeInTheDocument()
  // 호출이 없으면 서버가 비용을 생략한다. '비용 미보고'를 붙이지 않는다.
  expect(screen.getByText('0회')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '내 에이전트 연결' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'settings',
    focusId: 'agent',
  })
})

it('검토할 변경 제안이 있으면 그 칸에 검토하기를 붙여 먼저 보이게 한다', async () => {
  mount(96)
  expect(
    await screen.findByRole('button', {
      name: /검토할 변경 제안\s*1\s*검토하기 ›/,
    }),
  ).toBeInTheDocument()
})

// PR #72 전수 리뷰 TC-5: 작업 집계가 실패해도 변경 제안 집계는 그대로 보이고, 실패한 칸은 0건으로 보이지 않는다.
it('작업 집계만 실패하면 변경 제안 수는 그대로 두고 작업 칸은 —로 보인다', async () => {
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 2,
  })
  vi.mocked(dashboardApi.tasks).mockRejectedValue(new Error('500'))
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DashboardOverview
        projectId="10"
        usage={query(usage(0))}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByRole('button', { name: /검토할 변경 제안\s*2/ }),
  ).toBeInTheDocument()
  expect(
    await screen.findByRole('button', { name: /진행 중 작업\s*—/ }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('아직 기록이 없어요. 이렇게 시작해 보세요.'),
  ).not.toBeInTheDocument()
})

const day = (key: string, call_count: number) => ({
  key,
  input_tokens: 0,
  output_tokens: 0,
  call_count,
})
function mountWith(data: UsageResponse) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(data)}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
}

it('날짜별 AI 호출을 기간과 합계와 함께 보여 준다', async () => {
  mountWith({
    ...usage(12),
    by_day: [day('2026-10-30', 3), day('2026-10-31', 0), day('2026-11-01', 9)],
  })
  expect(await screen.findByText('날짜별 AI 호출')).toBeInTheDocument()
  expect(screen.getByText('10월 30일~11월 1일')).toBeInTheDocument()
  const table = screen.getByRole('table', {
    name: /합계 12회, 가장 많은 날은 11월 1일 9회/,
  })
  expect(table).toHaveTextContent('10월 31일')
})

it('기간에 호출이 없으면 그래프와 기능별 칸이 비어 있다고 보인다', async () => {
  // 서버처럼 날짜와 기능을 0으로 채워 준다. 진행 중 작업이 있어 시작 안내 대신 칸이 보인다.
  mountWith({
    ...usage(0),
    by_feature: [day('BEE_CHAT', 0)],
    by_day: [day('2026-11-01', 0), day('2026-11-02', 0)],
  })
  expect(await screen.findByText('날짜별 AI 호출')).toBeInTheDocument()
  expect(
    screen.getAllByText('이 기간에 기록된 AI 호출이 없어요.'),
  ).toHaveLength(2)
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText('비비 대화')).not.toBeInTheDocument()
})

it('사용량을 못 받으면 호출 칸은 0회 대신 —이고 그래프 자리에 오류를 보인다', async () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={
          {
            data: undefined,
            isError: true,
            error: new Error('500'),
            isPending: false,
            refetch: vi.fn(),
          } as unknown as UseQueryResult<UsageResponse>
        }
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByRole('button', { name: /선택 기간 AI 호출\s*—/ }),
  ).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent(
    '정보를 불러오지 못했어요',
  )
})

it('기간을 바꿔 받는 동안 호출 칸은 0회 대신 …이고 호출이 없다고 말하지 않는다', async () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={
          {
            data: undefined,
            isError: false,
            error: null,
            isPending: true,
            refetch: vi.fn(),
          } as unknown as UseQueryResult<UsageResponse>
        }
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByRole('button', { name: /선택 기간 AI 호출\s*…/ }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('이 기간에 기록된 AI 호출이 없어요.'),
  ).not.toBeInTheDocument()
})

it('할 일이 있는 칸을 앞에 두고 칸마다 보조 줄을 보인다', async () => {
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [
      { status: 'IN_PROGRESS', count: 1 },
      { status: 'DRAFT', count: 1 },
      { status: 'READY', count: 1 },
    ],
    by_waiting_reason: [],
  })
  vi.mocked(changesApi.list).mockResolvedValue({
    items: [{ id: '71', title: '빈 화면 안내', status: 'IN_REVIEW' }],
    has_more: false,
  } as never)
  vi.mocked(tasksApi.list).mockResolvedValue({
    items: [{ id: '503', key: 'TASK-003', assignee_member_id: '22' }],
    has_more: false,
  } as never)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query({
          ...usage(9),
          estimated_cost_amount: '1.84',
          currency: 'USD',
        })}
        activities={query(none)}
        work={query({
          items: [{ member_id: '22', current_task_id: '503' }],
          has_more: false,
        })}
        members={
          [
            { id: '21', name: '이팀장', agent_connected: false },
            { id: '22', name: '김동료', agent_connected: true },
          ] as never
        }
        actorName={() => '시스템'}
        onTab={() => undefined}
        periodName="11월"
      />
    </QueryClientProvider>,
  )
  expect(await screen.findByText("'빈 화면 안내' 검토 중")).toBeInTheDocument()
  expect(
    await screen.findByText('TASK-003 · 김동료의 에이전트 · 할 것 2'),
  ).toBeInTheDocument()
  expect(screen.getByText('이팀장의 에이전트 연결 미확인')).toBeInTheDocument()
  expect(screen.getByText('추정 1.84 USD')).toBeInTheDocument()
  const cards = screen
    .getAllByRole('button')
    .slice(0, 4)
    .map((button) => button.textContent)
  expect(cards[0]).toContain('검토할 변경 제안')
  expect(cards[1]).toContain('에이전트 연결')
  expect(cards[2]).toContain('진행 중 작업')
  expect(cards[3]).toContain('11월 AI 호출')
})

it('기능별 줄에 비용과 합계를 보인다', async () => {
  mountWith({
    ...usage(9),
    estimated_cost_amount: '0.5',
    currency: 'USD',
    by_feature: [
      {
        key: 'TASK_VALIDATION',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 9,
        estimated_cost_amount: '0.5',
      },
    ],
  })
  const row = (await screen.findByText('작업 검증')).closest('li')!
  expect(row).toHaveTextContent('9회')
  expect(row).toHaveTextContent('0.5 USD')
  expect(screen.getByText('합계 9회 · 추정 0.5 USD')).toBeInTheDocument()
})

it('최근 활동 줄을 누르면 그 화면으로 가고 성공 칩은 보이지 않는다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(1))}
        activities={query({
          items: [
            {
              id: '2',
              type: 'VALIDATION_FINISHED',
              actor_type: 'AGENT',
              task_id: '503',
              result: 'SUCCESS',
              created_at: '2026-10-27T01:00:00Z',
            },
            {
              id: '1',
              type: 'TASK_STARTED',
              actor_type: 'HUMAN',
              result: 'SUCCESS',
              created_at: '2026-10-27T01:00:00Z',
            },
          ],
          has_more: false,
        })}
        work={query(noWork)}
        members={[]}
        actorName={() => '김동료'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('통과')).toBeInTheDocument()
  expect(screen.queryByText('성공')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /VALIDATION_FINISHED/ }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: '503',
  })
})

it('에이전트 요약은 연결 상태와 사용량을 보이고, 서버가 나누지 않으면 사용량을 적지 않는다', async () => {
  const user = userEvent.setup()
  const members = [
    { id: '22', name: '김동료', agent_connected: true },
    { id: '21', name: '이팀장', agent_connected: false },
  ] as never
  const work = query({
    items: [
      {
        member_id: '22',
        agent_session_id: '53',
        current_task_id: '503',
        task_title: '목록 구현',
        task_status: 'IN_PROGRESS' as const,
        reported_at: '2026-10-28T05:10:00Z',
      },
      { member_id: '21', agent_session_id: '51' },
    ],
    has_more: false,
  })
  const { unmount } = render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query({
          ...usage(2),
          currency: 'USD',
          by_agent: [
            {
              key: '53',
              input_tokens: 0,
              output_tokens: 0,
              call_count: 2,
              estimated_cost_amount: '0.5',
            },
          ],
        })}
        activities={query(none)}
        work={work}
        members={members}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  const first = (await screen.findByText('김동료의 에이전트')).closest('li')!
  expect(first).toHaveTextContent('연결됨')
  expect(first).toHaveTextContent('목록 구현 진행 중')
  expect(first).toHaveTextContent('10월 28일 보고')
  expect(first).toHaveTextContent('2회 · 0.5 USD')
  const second = screen.getByText('이팀장의 에이전트').closest('li')!
  expect(second).toHaveTextContent('연결 미확인')
  expect(second).toHaveTextContent('0회')
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'settings',
    focusId: 'agent',
  })
  unmount()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(2))}
        activities={query(none)}
        work={work}
        members={members}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    (await screen.findByText('이팀장의 에이전트')).closest('li'),
  ).not.toHaveTextContent('0회')
})

const failed = <T,>(error = new Error('500')) =>
  ({
    data: undefined,
    isError: true,
    error,
    isPending: false,
    refetch: vi.fn(),
  }) as unknown as UseQueryResult<T>

it('건수가 0이면 캐시에 남은 제안과 작업을 보조 줄에 쓰지 않는다', async () => {
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 0,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [],
    by_waiting_reason: [],
  })
  const client = new QueryClient()
  client.setQueryData(['projects', '10', 'hud-review'], {
    items: [{ id: '71', title: '지난 제안', status: 'PROPOSED' }],
    has_more: false,
  })
  client.setQueryData(['projects', '10', 'dashboard-counts', 'running-task'], {
    items: [{ id: '503', key: 'TASK-003', assignee_member_id: '22' }],
    has_more: false,
  })
  render(
    <QueryClientProvider client={client}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(1))}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  await screen.findByRole('button', { name: /검토할 변경 제안\s*0/ })
  await screen.findByRole('button', { name: /진행 중 작업\s*0/ })
  expect(screen.queryByText(/지난 제안/)).not.toBeInTheDocument()
  expect(screen.queryByText(/TASK-003/)).not.toBeInTheDocument()
})

it('기능별 줄은 비용을 모르는 호출 수를 함께 보인다', async () => {
  mountWith({
    ...usage(3),
    estimated_cost_amount: '0.5',
    currency: 'USD',
    cost_unknown_call_count: 1,
    by_feature: [
      {
        key: 'TASK_VALIDATION',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 3,
        estimated_cost_amount: '0.5',
        cost_unknown_call_count: 1,
      },
    ],
  })
  const row = (await screen.findByText('작업 검증')).closest('li')!
  expect(row).toHaveTextContent('0.5 USD')
  expect(row).toHaveTextContent('미보고 1건')
})

it.each([
  ['error', '—'],
  ['pending', '…'],
] as const)(
  '팀원을 %s 상태면 연결 칸을 단정하지 않고 에이전트 줄은 연결 상태 미확인으로 보인다',
  async (membersStatus, value) => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <DashboardOverview
          projectId="10"
          usage={query(usage(1))}
          activities={query(none)}
          work={query({
            items: [{ member_id: '21', agent_session_id: '51' }],
            has_more: false,
          })}
          members={[]}
          membersStatus={membersStatus}
          actorName={() => '시스템'}
          onTab={() => undefined}
        />
      </QueryClientProvider>,
    )
    expect(
      await screen.findByRole('button', {
        name: new RegExp(`^에이전트 연결\\s*${value}`),
      }),
    ).toBeInTheDocument()
    expect(screen.queryByText('0 / 0')).not.toBeInTheDocument()
    expect(screen.getByText('연결 상태 미확인')).toBeInTheDocument()
    expect(screen.queryByText(/연결을 확인해요/)).not.toBeInTheDocument()
    expect(screen.getByText('보고된 작업 없음')).toBeInTheDocument()
  },
)

it('활동을 못 받았으면 시작 안내 대신 못 받았다고 보인다', async () => {
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 0,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [],
    by_waiting_reason: [],
  })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(0))}
        activities={failed<ActivitiesResponse>()}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText('최근 활동을 불러오지 못했어요.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('아직 기록이 없어요. 이렇게 시작해 보세요.'),
  ).not.toBeInTheDocument()
})

it('할 것이 있으면 시작 안내를 띄우지 않는다', async () => {
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 0,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [{ status: 'DRAFT', count: 2 }],
    by_waiting_reason: [],
  })
  mount(0)
  expect(await screen.findByText('할 것 2')).toBeInTheDocument()
  expect(
    screen.queryByText('아직 기록이 없어요. 이렇게 시작해 보세요.'),
  ).not.toBeInTheDocument()
})

it('작업 현황이 서버에 없으면 준비 중, 못 받으면 오류로 보이고 없다고 말하지 않는다', async () => {
  const show = (work: UseQueryResult<AgentWorkResponse | undefined>) =>
    render(
      <QueryClientProvider client={new QueryClient()}>
        <DashboardOverview
          projectId="10"
          usage={query(usage(1))}
          activities={query(none)}
          work={work}
          members={[]}
          actorName={() => '시스템'}
          onTab={() => undefined}
        />
      </QueryClientProvider>,
    )
  const missing = show(query<AgentWorkResponse | undefined>(undefined))
  expect(
    await screen.findByText('에이전트 현황은 준비 중이에요.'),
  ).toBeInTheDocument()
  missing.unmount()
  show(failed())
  expect(
    await screen.findByText('에이전트 현황을 불러오지 못했어요.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('연결된 에이전트가 없어요.'),
  ).not.toBeInTheDocument()
})

it('담당자 이름을 못 찾으면 담당자 없음으로 말하지 않고, 정말 없을 때만 그렇게 적는다', async () => {
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [{ status: 'IN_PROGRESS', count: 1 }],
    by_waiting_reason: [],
  })
  vi.mocked(tasksApi.list).mockResolvedValue({
    items: [{ id: '503', key: 'TASK-003', assignee_member_id: '99' }],
    has_more: false,
  } as never)
  const first = render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(1))}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('TASK-003')).toBeInTheDocument()
  expect(screen.queryByText(/담당자 없음/)).not.toBeInTheDocument()
  first.unmount()
  vi.mocked(tasksApi.list).mockResolvedValue({
    items: [{ id: '503', key: 'TASK-003' }],
    has_more: false,
  } as never)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DashboardOverview
        projectId="10"
        usage={query(usage(1))}
        activities={query(none)}
        work={query(noWork)}
        members={[]}
        actorName={() => '시스템'}
        onTab={() => undefined}
      />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('TASK-003 · 담당자 없음')).toBeInTheDocument()
})
