import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { dashboardApi } from '@/api/services/dashboard'
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
  expect(screen.getByText('96회 · 비용 미보고')).toBeInTheDocument()
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
    await screen.findByText('5회 · 0.2 USD + 미보고 2건'),
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
