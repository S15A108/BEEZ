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
