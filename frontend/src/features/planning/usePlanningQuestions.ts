import { useInfiniteQuery } from '@tanstack/react-query'
import { planningApi } from '@/api/services'
import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'

// 보완 질문 목록. 탭 개수(PlanningPanel)와 질문 탭(PlanningQuestions)이 같은 키로 함께 쓴다.
// 질문을 만드는 동안에는 끝날 때까지 목록을 다시 불러온다. 질문 생성이 시작되기 전 문서 재분석 단계에도
// 서버가 '갱신 중'으로 표시한 질문이 있으면 끝날 때까지 따라간다(S15P31A108-396).
export function usePlanningQuestions(projectId: string, generating: boolean) {
  return useInfiniteQuery({
    queryKey: ['projects', projectId, 'planning-questions'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.questions(projectId, { cursor: pageParam, limit: 100 }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    refetchInterval: (query) =>
      generating ||
      query.state.data?.pages.some((page) =>
        page.items.some((item) => item.freshness === 'REFRESHING'),
      )
        ? ACTIVE_POLL_MS
        : false,
  })
}
