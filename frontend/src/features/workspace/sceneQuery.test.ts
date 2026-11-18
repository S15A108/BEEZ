import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { MISSING_API_MESSAGE } from '@/api/error'
import {
  dashboardApi,
  documentsApi,
  membersApi,
  projectsApi,
  tasksApi,
} from '@/api/services'
import { agentWorkQuery, sceneQuery } from './sceneQuery'

afterEach(() => vi.restoreAllMocks())

// 추가 점검 5: 대체 계산이 내 계정을 보므로 계정 id가 키에 들어가고, 세션이 없으면 부르지 않는다.
it('워크스페이스 첫 화면 조회는 계정 id를 키에 넣고 세션이 준비된 뒤에만 부른다', () => {
  expect(sceneQuery('101').enabled).toBe(false)
  expect(sceneQuery('101', '11').enabled).toBe(true)
  expect(sceneQuery('101', '11').queryKey).toEqual([
    'projects',
    '101',
    'scene',
    '11',
  ])
})

it('초기 설정 현황 API가 없으면 그 계정의 에이전트 연결로 대체 값을 만든다', async () => {
  vi.spyOn(projectsApi, 'get').mockResolvedValue({
    id: '101',
    name: '우리 팀 협업 서비스',
    my_role: 'LEADER',
    created_at: '2026-10-23T03:00:00Z',
    updated_at: '2026-10-23T03:00:00Z',
  })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [
      { account_id: '11', agent_connected: true },
      { account_id: '12', agent_connected: false },
    ],
    has_more: false,
  } as never)
  vi.spyOn(tasksApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
  vi.spyOn(documentsApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
  vi.spyOn(projectsApi, 'onboarding').mockRejectedValue(
    new AxiosError('Not Found', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 404,
      statusText: 'Not Found',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
    }),
  )
  const data = await new QueryClient().fetchQuery(sceneQuery('101', '11'))
  expect(data.onboarding).toMatchObject({
    member_count: 2,
    my_agent_connected: true,
    baseline_published: false,
  })
})

// PR #72 5차 UI-01: 최신 100개 밖에 있는 내 시작 대기 작업이 HUD에서 빠지지 않게 마지막 페이지까지 읽는다.
it('작업이 100개를 넘으면 다음 페이지 작업까지 모두 읽는다', async () => {
  vi.spyOn(projectsApi, 'get').mockResolvedValue({
    id: '101',
    name: '우리 팀 협업 서비스',
    my_role: 'MEMBER',
    created_at: '2026-10-23T03:00:00Z',
    updated_at: '2026-10-23T03:00:00Z',
  })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
  const list = vi
    .spyOn(tasksApi, 'list')
    .mockResolvedValueOnce({
      items: [{ id: '1' }],
      has_more: true,
      next_cursor: 'c2',
    } as never)
    .mockResolvedValueOnce({
      items: [{ id: '2', status: 'READY' }],
      has_more: false,
    } as never)
  vi.spyOn(projectsApi, 'onboarding').mockResolvedValue({} as never)
  const data = await new QueryClient().fetchQuery(sceneQuery('101', '11'))
  expect(data.tasks.items.map((task) => task.id)).toEqual(['1', '2'])
  expect(list).toHaveBeenLastCalledWith('101', { cursor: 'c2', limit: 100 })
})

const axiosError = (status: number, message: string) =>
  new AxiosError(message, 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: message,
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code: 'error', message } },
  })

const work = {
  member_id: '21',
  agent_session_id: '91',
  connection_status: 'ONLINE',
  task_id: '3',
  task_status: 'IN_PROGRESS',
} as never

// PR #128 리뷰(전진): 작업 현황(MON-A05)은 에이전트 자리를 꾸미는 데만 쓴다. 첫 화면 조회에 묶지 않아서
// 500이나 시간 초과가 나도 워크스페이스 첫 화면은 열린다.
it('워크스페이스 첫 화면 조회는 작업 현황을 부르지 않아 그 실패에 영향받지 않는다', async () => {
  vi.spyOn(projectsApi, 'get').mockResolvedValue({
    id: '101',
    name: '우리 팀 협업 서비스',
    my_role: 'LEADER',
    created_at: '2026-10-23T03:00:00Z',
    updated_at: '2026-10-23T03:00:00Z',
  })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [{ account_id: '11', agent_connected: true }],
    has_more: false,
  } as never)
  vi.spyOn(tasksApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  } as never)
  vi.spyOn(projectsApi, 'onboarding').mockResolvedValue({
    member_count: 1,
    my_agent_connected: true,
    baseline_published: false,
  } as never)
  const agentWork = vi
    .spyOn(dashboardApi, 'agentWork')
    .mockRejectedValue(axiosError(500, 'Internal Server Error'))
  const data = await new QueryClient().fetchQuery(sceneQuery('101', '11'))
  expect(data.project.id).toBe('101')
  expect(agentWork).not.toHaveBeenCalled()
})

// PR #128 리뷰(양지훈): 사무실을 열어 둔 동안에도 다른 사람·에이전트의 상태 변화가 보이도록 작업 현황만 30초마다
// 다시 부른다. 없는 API면 null로 두고 다시 부르지 않는다.
it('작업 현황은 30초마다 다시 부르고, 없는 API면 null로 두고 멈춘다', async () => {
  const query = agentWorkQuery('101', '11')
  expect(query.queryKey).toEqual(['projects', '101', 'agent-work', '11'])
  expect(agentWorkQuery('101').enabled).toBe(false)
  const interval = query.refetchInterval as (q: {
    state: { data: unknown }
  }) => number | false
  expect(interval({ state: { data: [work] } })).toBe(30_000)
  expect(interval({ state: { data: undefined } })).toBe(30_000)
  expect(interval({ state: { data: null } })).toBe(false)

  vi.spyOn(dashboardApi, 'agentWork').mockRejectedValue(
    axiosError(404, MISSING_API_MESSAGE),
  )
  expect(await new QueryClient().fetchQuery(query)).toBeNull()
})

it('작업 현황을 다시 부르다 실패하면 마지막으로 받은 상태를 그대로 쓴다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const agentWork = vi
    .spyOn(dashboardApi, 'agentWork')
    .mockResolvedValue({ items: [work], has_more: false })
  const query = agentWorkQuery('101', '11')
  expect(await client.fetchQuery(query)).toEqual([work])

  agentWork.mockRejectedValue(axiosError(500, 'Internal Server Error'))
  await client.refetchQueries({ queryKey: query.queryKey })
  expect(client.getQueryData(query.queryKey)).toEqual([work])
  expect(client.getQueryState(query.queryKey)?.status).toBe('error')
})
