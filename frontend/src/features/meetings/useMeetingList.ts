import { useInfiniteQuery } from '@tanstack/react-query'
import { meetingsApi } from '@/api/services'
import type { Meeting } from '@/api/contracts/common'

// 회의 목록. 회의실 창과 사무실 할 일이 같은 키로 캐시를 나눠 쓴다.
export function useMeetingList(projectId: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: ['projects', projectId, 'meetings'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      meetingsApi.list(projectId, { cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    enabled,
  })
}

// 사무실 할 일의 회의 줄: 내가 참여자(거절하지 않음)인 진행 중 회의. status가 없으면 진행 중으로 본다.
export function myOpenMeetings(meetings: Meeting[], memberId?: string) {
  return meetings.filter(
    (meeting) =>
      meeting.status !== 'CLOSED' &&
      meeting.participants.some(
        (item) => item.member_id === memberId && item.status !== 'DECLINED',
      ),
  )
}
