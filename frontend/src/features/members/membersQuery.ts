import { queryOptions } from '@tanstack/react-query'
import { readAllPages } from '@/api/readAllPages'
import { membersApi } from '@/api/services'

// 프로젝트 팀원 전체. 여러 창이 같은 키를 쓰므로 조회 조건도 하나로 둔다: 마지막 페이지까지 100명씩.
// 창마다 20명·100명을 같은 키로 따로 불러 결과가 섞이면, 나를 목록에서 못 찾아 내 작업이 읽기 전용이 됐다
// (PR #72 재전수 SECOND-AMP-04).
export const membersQuery = (projectId: string) =>
  queryOptions({
    queryKey: ['projects', projectId, 'members'],
    queryFn: () =>
      readAllPages((cursor) =>
        membersApi.list(projectId, { cursor, limit: 100 }),
      ),
  })
