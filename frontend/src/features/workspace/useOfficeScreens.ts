import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
import { isMissingApi } from '@/api/error'
import { changesApi, dashboardApi, planningApi } from '@/api/services'
import { BACKGROUND_POLL_MS } from '@/lib/pollIntervals'
import {
  designBoard,
  progressRows,
  proposalWall,
  type OfficeScreens,
} from '@/three/office/office-screens'

// 없는 API면 더 부르지 않는다. 그 밖의 실패는 다음 주기에 다시 본다.
const poll = (query: { state: { error: unknown } }) =>
  isMissingApi(query.state.error) ? false : BACKGROUND_POLL_MS

/**
 * 사무실 메모 벽·관계 TV·대시보드에 그릴 프로젝트 상태(D47). 새 API 없이 업무 창이 쓰는 쿼리를 같은 키로 불러 캐시를
 * 나눠 쓴다: 집계는 HUD(['hud', 'changes'], ['hud', 'documents']), 제안 목록은 변경 제안 창의 '전체' 탭(['changes', '']),
 * 기준 설계 항목과 제안 상세는 변경 제안 창(['approved-items'], ['change', id]), 진행 중 제안은 HUD·대시보드(['hud-review']).
 * 업무 창이 열려 있으면(active=false) 사무실 화면이 가려지므로 새로 부르지 않고 받아 둔 값을 그린다.
 */
export function useOfficeScreens(
  projectId: string,
  baselineVersion: number | undefined,
  active: boolean,
): OfficeScreens {
  const started = baselineVersion !== undefined
  const changeCounts = useQuery({
    queryKey: ['projects', projectId, 'hud', 'changes'],
    queryFn: () => dashboardApi.changes(projectId),
    enabled: active,
    refetchInterval: poll,
  })
  const documentCounts = useQuery({
    queryKey: ['projects', projectId, 'hud', 'documents'],
    queryFn: () => dashboardApi.documents(projectId),
    enabled: active,
  })
  const list = useInfiniteQuery({
    queryKey: ['projects', projectId, 'changes', ''],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      changesApi.list(projectId, {
        status: undefined,
        cursor: pageParam,
        limit: 30,
      }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    enabled: active && started,
    refetchInterval: poll,
  })
  const items = useQuery({
    queryKey: ['projects', projectId, 'approved-items'],
    queryFn: () =>
      planningApi.items(projectId, { status: 'APPROVED', limit: 100 }),
    // ponytail: 기준은 승인할 때 바뀌고 승인 성공이 프로젝트 쿼리를 무효화해 다시 읽는다. 다른 브라우저의 승인은 창에 다시 들어올 때 맞춘다.
    enabled: active && started,
  })
  const open = useQuery({
    queryKey: ['projects', projectId, 'hud-review'],
    queryFn: () =>
      changesApi.list(projectId, { group: 'IN_PROGRESS', limit: 20 }),
    enabled: active && started,
    refetchInterval: poll,
  })
  // 진행 중 제안이 바꾸는 설계 항목. 목록에는 항목이 없어 상세를 읽는다(변경 제안 창·HUD와 같은 키).
  const details = useQueries({
    queries: (open.data?.items ?? []).map((proposal) => ({
      queryKey: ['projects', projectId, 'change', proposal.id],
      queryFn: () => changesApi.get(projectId, proposal.id),
      enabled: active,
    })),
  })
  const proposals = list.data?.pages.flatMap((page) => page.items)
  const changed = details.map((query) => query.data?.proposal.items)
  // 렌더마다 새 객체지만 사무실 화면은 그리는 값이 같으면 다시 그리지 않는다(OfficeWorkSurface 서명 비교).
  return {
    version: baselineVersion,
    wall:
      started && proposals
        ? proposalWall(proposals, changeCounts.data?.by_status)
        : undefined,
    design: !started
      ? null
      : items.data && open.data && changed.every(Boolean)
        ? designBoard(
            baselineVersion,
            items.data.items,
            items.data.has_more,
            changed.map((entries) => ({ items: entries ?? [] })),
          )
        : undefined,
    progress: progressRows(changeCounts.data, documentCounts.data),
  }
}
