import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { MISSING_API_MESSAGE } from '@/api/error'
import { documentsApi, membersApi, projectsApi, tasksApi } from '@/api/services'
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
