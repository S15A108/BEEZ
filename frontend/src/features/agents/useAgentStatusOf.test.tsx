import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { agentsApi } from '@/api/services'
import { useAgentStatusOf } from './useAgentStatusOf'

afterEach(() => vi.restoreAllMocks())

function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return renderHook(() => useAgentStatusOf('101'), { wrapper })
}

const session = (
  id: string,
  connected: boolean,
  last_report_at: string,
  status_check_required = false,
) => ({
  id,
  member_id: '21',
  connected,
  status_check_required,
  last_report_at,
})
const me = { id: '21', agent_connected: false }
const respond = (items: ReturnType<typeof session>[]) =>
  vi.spyOn(agentsApi, 'list').mockResolvedValue({ items, has_more: false })

// PR #195 리뷰(P2): 연결 현황은 세션 줄 단위인데 첫 줄만 봐서, 다른 세션이 연결돼 있어도 '연결 전'이었다.
it('첫 세션이 끊기고 다음 세션이 연결돼 있으면 연결됨으로 본다', async () => {
  respond([
    session('51', false, '2026-11-01T00:00:00Z'),
    session('52', true, '2026-11-02T00:00:00Z'),
  ])
  const { result } = mount()
  await waitFor(() => expect(result.current(me).status).toBe('connected'))
  expect(result.current(me).lastReportAt).toBe('2026-11-02T00:00:00Z')
})

it('연결된 세션이 여럿이면 가장 최근에 통신한 세션으로 정한다', async () => {
  respond([
    session('51', true, '2026-11-02T00:00:00Z'),
    session('52', true, '2026-11-01T00:00:00Z', true),
  ])
  const { result } = mount()
  await waitFor(() => expect(result.current(me).status).toBe('connected'))
  expect(result.current(me).lastReportAt).toBe('2026-11-02T00:00:00Z')
})

it('연결된 세션이 없으면 가장 최근 세션의 보고 시각을 보인다', async () => {
  respond([
    session('51', false, '2026-11-01T00:00:00Z'),
    session('52', false, '2026-11-03T00:00:00Z'),
  ])
  const { result } = mount()
  await waitFor(() =>
    expect(result.current(me).lastReportAt).toBe('2026-11-03T00:00:00Z'),
  )
  expect(result.current(me).status).toBe('none')
})
