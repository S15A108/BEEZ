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
import { sceneQuery } from './sceneQuery'

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
  // 사무실 에이전트 자리(D37)를 위해 첫 조회가 작업 현황(MON-A05)도 읽는다.
  vi.spyOn(dashboardApi, 'agentWork').mockResolvedValue({
    items: [],
    has_more: false,
  })
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

// PR #128 리뷰: 작업 현황(MON-A05)은 에이전트 자리를 꾸미는 데만 쓴다. 500이나 시간 초과로 실패해도
// 워크스페이스 첫 조회 전체가 실패하지 않고, 자리는 팀원 목록의 연결 여부로 어림한다.
it('작업 현황 조회가 서버 오류로 실패해도 워크스페이스 첫 화면은 열린다', async () => {
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
  vi.spyOn(dashboardApi, 'agentWork').mockRejectedValue(
    new AxiosError(
      'Internal Server Error',
      'ERR_BAD_RESPONSE',
      undefined,
      undefined,
      {
        status: 500,
        statusText: 'Internal Server Error',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: { error: { code: 'internal_error', message: '서버 오류' } },
      },
    ),
  )
  const data = await new QueryClient().fetchQuery(sceneQuery('101', '11'))
  expect(data.project.id).toBe('101')
  expect(data.agentWork).toBeUndefined()
})
