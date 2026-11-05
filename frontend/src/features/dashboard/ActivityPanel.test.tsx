import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { dashboardApi } from '@/api/services/dashboard'
import { membersApi } from '@/api/services/members'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ActivityPanel } from './ActivityPanel'

vi.mock('@/api/services/dashboard', () => ({
  dashboardApi: {
    usage: vi.fn(),
    relations: vi.fn(),
    activities: vi.fn(),
    agentWork: vi.fn(),
  },
}))
vi.mock('@/api/services/members', () => ({ membersApi: { list: vi.fn() } }))
beforeEach(() => {
  vi.clearAllMocks()
  useWorkspaceStore.setState({ pane: 'dashboard', focusId: null })
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 120,
    output_tokens: 30,
    call_count: 2,
    by_feature: [],
    by_task: [],
    by_validation: [],
  })
  vi.mocked(dashboardApi.relations).mockResolvedValue({ nodes: [], edges: [] })
  vi.mocked(dashboardApi.activities).mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.mocked(dashboardApi.agentWork).mockResolvedValue({
    items: [
      {
        member_id: '1',
        current_task_id: '8',
        task_status: 'WAITING',
        validation_status: 'PASS',
        waiting_reason: 'DEPENDENCY',
      },
    ],
    has_more: false,
  })
  vi.mocked(membersApi.list).mockResolvedValue({
    items: [
      {
        id: '1',
        account_id: '11',
        name: '민수',
        role: 'MEMBER',
        agent_connected: true,
      },
    ],
    has_more: false,
  })
})
function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ActivityPanel projectId="10" />
    </QueryClientProvider>,
  )
}
it('연결·대기 작업·통과한 계획을 분리하며 누락 비용을 0원으로 만들지 않는다', async () => {
  const user = userEvent.setup()
  mount()
  // 수정안: 대시보드는 한눈에 보기 / 활동 타임라인 / 에이전트 현황 / 사용량·비용 탭으로 나눈다.
  await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
  const name = await screen.findByText('민수')
  const row = name.closest('tr')!
  expect(within(row).getByText('연결됨')).toBeInTheDocument()
  expect(within(row).getByText('대기', { exact: true })).toBeInTheDocument()
  expect(within(row).getByText('계획 통과')).toBeInTheDocument()
  expect(within(row).getByText('선행 작업 대기')).toBeInTheDocument()
  await user.click(within(row).getByRole('button', { name: '작업 #8' }))
  expect(useWorkspaceStore.getState().focusId).toBe('8')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  expect(
    await screen.findByText('미보고', { selector: 'dd' }),
  ).toBeInTheDocument()
})
it('한국시간 날짜 범위를 UTC로 전달하고 역전된 기간은 조회하지 않는다', async () => {
  const user = userEvent.setup()
  mount()
  const from = screen.getByLabelText('시작일 (한국시간)'),
    to = screen.getByLabelText('종료일 (한국시간)')
  await user.clear(from)
  await user.type(from, '2026-10-22')
  await user.clear(to)
  await user.type(to, '2026-10-23')
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  await waitFor(() =>
    expect(dashboardApi.usage).toHaveBeenCalledWith('10', {
      from: '2026-10-21T15:00:00.000Z',
      to: '2026-10-23T14:59:59.999Z',
    }),
  )
  const count = vi.mocked(dashboardApi.usage).mock.calls.length
  await user.clear(to)
  await user.type(to, '2026-10-21')
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후',
  )
  expect(dashboardApi.usage).toHaveBeenCalledTimes(count)
})
it('비용·토큰을 모르는 호출이 섞이면 0원·전체 값처럼 보이지 않게 건수를 붙인다', async () => {
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 120,
    output_tokens: 30,
    call_count: 3,
    estimated_cost_amount: '0.01',
    currency: 'USD',
    tokens_unknown_call_count: 1,
    cost_unknown_call_count: 1,
    by_feature: [
      {
        key: 'DOCUMENT_PARSE',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 1,
        estimated_cost_amount: '0',
        tokens_unknown_call_count: 1,
        cost_unknown_call_count: 1,
      },
      {
        key: 'TASK_VALIDATION',
        input_tokens: 120,
        output_tokens: 30,
        call_count: 2,
        estimated_cost_amount: '0.01',
        tokens_unknown_call_count: 0,
        cost_unknown_call_count: 0,
      },
    ],
    by_task: [],
    by_validation: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  // 건수는 값 아래 줄에 붙는다(좁은 칸에서 꺾이지 않게). 기대값 변경은 사용자 승인 2026-11-03.
  const cost = await screen.findByText('0.01 USD', { selector: 'dd' })
  expect(within(cost).getByText('미보고 1건')).toBeInTheDocument()
  const input = screen.getByText('120', { selector: 'dd' })
  expect(within(input).getByText('미보고 1건')).toBeInTheDocument()
  // 비용을 모르는 문서 분석 줄이 '0 USD'만으로 보이지 않는다
  const parse = screen.getByText('문서 분석').closest('li')!
  expect(
    within(within(parse).getByText('0 USD')).getByText('미보고 1건'),
  ).toBeInTheDocument()
  const validation = screen.getByText('작업 검증').closest('li')!
  expect(within(validation).getByText('0.01 USD')).toBeInTheDocument()
  expect(within(validation).queryByText(/미보고/)).not.toBeInTheDocument()
})
it('호출이 없으면 비용을 미보고로 보이지 않고, 모든 기능이 0회면 빈 안내를 보인다', async () => {
  const empty = (key: string) => ({
    key,
    input_tokens: 0,
    output_tokens: 0,
    call_count: 0,
    tokens_unknown_call_count: 0,
    cost_unknown_call_count: 0,
  })
  // 서버처럼: 비용 있는 호출이 없으면 비용·통화를 생략하고 기능 8개를 0으로 채운다.
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 0,
    output_tokens: 0,
    call_count: 0,
    by_feature: [empty('DOCUMENT_PARSE'), empty('TASK_VALIDATION')],
    by_task: [],
    by_validation: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  expect(await screen.findByText('—', { selector: 'dd' })).toBeInTheDocument()
  expect(
    screen.queryByText('미보고', { selector: 'dd' }),
  ).not.toBeInTheDocument()
  expect(screen.getByText('기록된 사용량이 없어요.')).toBeInTheDocument()
})
it('호출이 없는 기능 줄은 0 토큰·0원 대신 비어 있다고 보인다', async () => {
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 10,
    output_tokens: 0,
    call_count: 1,
    estimated_cost_amount: '0.01',
    currency: 'USD',
    by_feature: [
      {
        key: 'TASK_VALIDATION',
        input_tokens: 10,
        output_tokens: 0,
        call_count: 1,
        estimated_cost_amount: '0.01',
      },
      {
        key: 'BEE_CHAT',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 0,
        estimated_cost_amount: '0',
      },
    ],
    by_task: [],
    by_validation: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  const chat = (await screen.findByText('비비 대화')).closest('li')!
  expect(within(chat).getAllByText('—')).toHaveLength(2)
  expect(within(chat).queryByText('0 USD')).not.toBeInTheDocument()
})
