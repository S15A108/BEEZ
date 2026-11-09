import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'

// 비비 대화 기록. 업무 창은 하나만 떠서, 근거를 눌러 문서함으로 가면 BibiPanel이 내려간다.
// 답은 서버(BEE-A01) 응답이라 창 밖의 QueryClient 캐시에 프로젝트별로 둔다. 창을 다시 열면 그대로 보이고,
// 창이 내려간 뒤 도착한 답도 붙는다. 새로 고침, 로그아웃(queryClient.clear), 프로젝트 나가기에 함께 지워진다.
export function useBibiMessages<T>(projectId: string) {
  const client = useQueryClient()
  const { data } = useQuery<T[]>({
    queryKey: ['projects', projectId, 'bibi-chat'],
    // 다시 받을 서버 값이 없다. 프로젝트 전체를 무효화해도 지금 대화를 그대로 둔다.
    queryFn: () =>
      client.getQueryData<T[]>(['projects', projectId, 'bibi-chat']) ?? [],
    initialData: [],
    staleTime: Infinity,
    gcTime: Infinity,
  })
  const setMessages = useCallback(
    (update: (previous: T[]) => T[]) =>
      client.setQueryData<T[]>(
        ['projects', projectId, 'bibi-chat'],
        (previous = []) => update(previous),
      ),
    [client, projectId],
  )
  return [data, setMessages] as const
}
