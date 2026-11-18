import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { agentsApi } from '@/api/services'
import { agentConnectionsQuery, useAgentStatusOf } from './useAgentStatusOf'

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

// 기대값 변경(사용자 승인 2026-11-09, S15P31A108-341): 줄이 있고 끊겼으면 AGT-A05 정의대로 none → disconnected.
it('연결된 세션이 없으면 가장 최근 세션의 보고 시각을 보인다', async () => {
  respond([
    session('51', false, '2026-11-01T00:00:00Z'),
    session('52', false, '2026-11-03T00:00:00Z'),
  ])
  const { result } = mount()
  await waitFor(() =>
    expect(result.current(me).lastReportAt).toBe('2026-11-03T00:00:00Z'),
  )
  expect(result.current(me).status).toBe('disconnected')
})

// S15P31A108-341: 서버는 끊긴 세션에 status_check_required를 주지 않지만, 예시 데이터가 끊긴 줄을 연결 미확인으로 두었다.
it('끊긴 세션 줄은 status_check_required가 있어도 연결 미확인이 아니라 연결 끊김이다', async () => {
  respond([session('51', false, '2026-11-01T00:00:00Z', true)])
  const { result } = mount()
  await waitFor(() => expect(result.current(me).status).toBe('disconnected'))
})

it('연결 현황을 받았는데 그 팀원 줄이 없으면 팀원 목록의 agent_connected와 상관없이 연결 전이다', async () => {
  respond([{ ...session('52', true, '2026-11-02T00:00:00Z'), member_id: '22' }])
  const { result } = mount()
  await waitFor(() =>
    expect(result.current({ id: '22', agent_connected: false }).status).toBe(
      'connected',
    ),
  )
  expect(result.current({ id: '21', agent_connected: true }).status).toBe(
    'none',
  )
})

it('연결 현황 API가 없으면 팀원 목록의 agent_connected로 연결됨·연결 전을 정한다', async () => {
  const list = vi.spyOn(agentsApi, 'list').mockRejectedValue(
    new AxiosError('없음', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 404,
      statusText: 'Not Found',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
    }),
  )
  const { result } = mount()
  await waitFor(() => expect(list).toHaveBeenCalled())
  await waitFor(() =>
    expect(result.current({ id: '21', agent_connected: true }).status).toBe(
      'connected',
    ),
  )
  expect(result.current(me).status).toBe('none')
})

// S15P31A108-341: 연결 현황은 팀원마다 한 줄이고 한 쪽 기본 20개다. 첫 쪽만 읽어 21번째 팀원부터 연결 전으로 보였다.
it('팀원이 20명을 넘어도 연결 현황을 마지막 쪽까지 읽는다', async () => {
  const list = vi
    .spyOn(agentsApi, 'list')
    .mockImplementation(async (_projectId, query) =>
      query?.cursor === '40'
        ? {
            items: [session('51', true, '2026-11-02T00:00:00Z')],
            has_more: false,
          }
        : {
            items: Array.from({ length: 20 }, (_, index) => ({
              ...session(String(100 + index), true, '2026-11-02T00:00:00Z'),
              member_id: String(59 - index),
            })),
            has_more: true,
            next_cursor: '40',
          },
    )
  const { result } = mount()
  await waitFor(() => expect(result.current(me).status).toBe('connected'))
  expect(list).toHaveBeenCalledWith('101', { cursor: '40', limit: 100 })
})

// S15P31A108-341: 연결 현황을 한 번만 받아, 설정을 열어 둔 동안 끊기거나 연결돼도 사무실(30초마다 다시 받음)과 달랐다.
it('연결 현황은 사무실처럼 30초마다 다시 받고, 없는 API면 멈춘다', () => {
  const interval = agentConnectionsQuery('101').refetchInterval as (query: {
    state: { data: unknown }
  }) => number | false
  expect(interval({ state: { data: { items: [], has_more: false } } })).toBe(
    30_000,
  )
  expect(interval({ state: { data: undefined } })).toBe(30_000)
  expect(interval({ state: { data: null } })).toBe(false)
})
