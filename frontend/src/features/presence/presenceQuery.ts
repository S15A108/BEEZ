import { queryOptions } from '@tanstack/react-query'
import { unlessMissing } from '@/api/error'
import { presenceApi } from '@/api/services/presence'
import { PRESENCE_POLL_MS } from '@/lib/pollIntervals'

// 팀원 접속 현황(MON-A07). 첫 화면 조회에 묶지 않아 실패해도 워크스페이스는 열린다. 서버에 없는 API(404·405·501)면
// null 로 두고 더 부르지 않는다. 있으면 보고 주기와 같은 15초마다 폴링한다(실시간 연결은 후속).
export function presenceQuery(projectId: string) {
  return queryOptions({
    queryKey: ['projects', projectId, 'presence'],
    queryFn: async () =>
      (await unlessMissing(presenceApi.list(projectId)))?.items ?? null,
    refetchInterval: (query) =>
      query.state.data === null ? false : PRESENCE_POLL_MS,
  })
}
