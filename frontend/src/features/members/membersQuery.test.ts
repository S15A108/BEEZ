import { QueryClient } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import {
  dashboardApi,
  documentsApi,
  membersApi,
  projectsApi,
  tasksApi,
} from '@/api/services'
import { sceneQuery } from '@/features/workspace/sceneQuery'
import { membersQuery } from './membersQuery'

afterEach(() => vi.restoreAllMocks())

const member = (id: string) => ({ id }) as never

// PR #72 재전수 SECOND-AMP-04: 팀원 조회는 한 가지(100명씩 마지막 페이지까지)라 같은 키의 결과가 섞이지 않고,
// 101번째 팀원(나일 수도 있다)도 목록에 있다. 워크스페이스 첫 조회도 같다.
function twoPages() {
  return vi
    .spyOn(membersApi, 'list')
    .mockImplementation(async (_projectId, query) =>
      query?.cursor
        ? { items: [member('101')], has_more: false }
        : {
            items: Array.from({ length: 100 }, (_, index) =>
              member(String(index + 1)),
            ),
            has_more: true,
            next_cursor: 'c2',
          },
    )
}

it('팀원 조회는 100명씩 마지막 페이지까지 읽는다', async () => {
  const list = twoPages()
  const data = await new QueryClient().fetchQuery(membersQuery('10'))
  expect(data.items).toHaveLength(101)
  expect(list).toHaveBeenCalledWith('10', { cursor: undefined, limit: 100 })
  expect(list).toHaveBeenLastCalledWith('10', { cursor: 'c2', limit: 100 })
})

it('워크스페이스 첫 조회도 팀원을 마지막 페이지까지 읽는다', async () => {
  twoPages()
  vi.spyOn(projectsApi, 'get').mockResolvedValue({ id: '10' } as never)
  vi.spyOn(projectsApi, 'onboarding').mockResolvedValue({} as never)
  // 사무실 에이전트 자리(D29)를 위해 첫 조회가 작업 현황(MON-A05)도 읽는다.
  vi.spyOn(dashboardApi, 'agentWork').mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.spyOn(tasksApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.spyOn(documentsApi, 'list').mockResolvedValue({
    items: [],
    has_more: false,
  })
  const data = await new QueryClient().fetchQuery(sceneQuery('10', '11'))
  expect(data.members.items).toHaveLength(101)
})
