import { queryOptions } from '@tanstack/react-query'
import { unlessMissing } from '@/api/error'
import { beeApi } from '@/api/services'

// 비비 현재 상태(BEE-A04). HUD 팀 목록의 비비 줄이 30초마다 다시 읽는다.
// 키가 ['projects', id] 아래라 문서 검토(DOC-A16)가 끝나거나 회의를 만들 때의 프로젝트 무효화로도 새로 읽는다.
// 서버에 아직 없으면(404) null로 두어 비비 줄을 그리지 않는다. 일시 오류면 TanStack이 마지막 값을 그대로 둔다.
export const beeStatusQuery = (projectId: string) =>
  queryOptions({
    queryKey: ['projects', projectId, 'bee-status'],
    queryFn: () =>
      unlessMissing(beeApi.status(projectId)).then((status) => status ?? null),
    refetchInterval: 30_000,
  })
