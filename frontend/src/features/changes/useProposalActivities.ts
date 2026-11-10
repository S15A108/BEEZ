import { useInfiniteQuery } from '@tanstack/react-query'
import { dashboardApi } from '@/api/services'
import type { Activity } from '@/api/contracts/common'

export type ActorFilter = '' | Activity['actor_type']
export const PROPOSAL_ACTIVITY_PAGE = 10

// 한 변경 제안의 지나온 길(MON-A04 change_proposal_id). 제안·필터가 바뀌면 키가 달라져 목록이 섞이지 않는다.
// cursor 는 서버가 준 값을 그대로 쓴다. 정렬은 서버(id 내림차순) 그대로다.
export function useProposalActivities(
  projectId: string,
  proposalId: string | undefined,
  actor: ActorFilter,
) {
  return useInfiniteQuery({
    queryKey: ['projects', projectId, 'change-activities', proposalId, actor],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      dashboardApi.activities(projectId, {
        change_proposal_id: proposalId,
        ...(actor ? { actor_type: actor } : {}),
        cursor: pageParam,
        limit: PROPOSAL_ACTIVITY_PAGE,
      }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    enabled: !!proposalId,
  })
}
