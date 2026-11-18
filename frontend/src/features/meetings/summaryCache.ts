import type { InfiniteData, QueryClient } from '@tanstack/react-query'
import type { MeetingSummary } from '@/api/contracts/common'
import type { EntriesResponse } from './schemas'

// 회의의 '최신 비비 정리'를 보는 모든 화면(정리 카드·끝내기 확인·회의 수정 초안)이 같은 캐시를 읽는다.
// 정리는 회의실의 '비비에게 정리 요청'·'수정 요청'으로만 만든다.
export const latestSummaryKey = (projectId: string, meetingId: string) =>
  ['projects', projectId, 'meeting', meetingId, 'summary', 'latest'] as const

/**
 * 새 정리를 받았을 때 정리 캐시와 기록 목록 캐시에 함께 넣는다. 기록 목록만 재조회 전이면 정리 카드가 잠깐 글로 바뀌고
 * 입력줄이 깜빡이며, 정리 캐시만 옛것이면 다른 화면이 옛 정리를 기준으로 판단한다.
 */
export function rememberSummary(
  client: QueryClient,
  projectId: string,
  meetingId: string,
  next: MeetingSummary,
) {
  client.setQueryData(latestSummaryKey(projectId, meetingId), next)
  client.setQueryData<InfiniteData<EntriesResponse>>(
    ['projects', projectId, 'meeting-entries', meetingId],
    (data) => {
      if (
        !data ||
        data.pages.some((page) =>
          page.items.some((item) => item.id === next.entry.id),
        )
      )
        return data
      const pages = data.pages.slice()
      const last = pages.length - 1
      pages[last] = {
        ...pages[last],
        items: [...pages[last].items, next.entry],
      }
      return { ...data, pages }
    },
  )
}
