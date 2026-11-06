import { useQuery } from '@tanstack/react-query'
import { inboxApi } from '@/api/services'
import type { Notification, Page } from '@/api/contracts/common'

export type UnreadCount = { text: string; label: string }

/** 화면에 보일 수. 0이면 없고, 99를 넘으면 99+, 더 있을 수 있으면 벨 팝오버처럼 '3+'다. */
export function unreadCount(
  count: number,
  hasMore: boolean,
): UnreadCount | undefined {
  if (count === 0) return undefined
  const text = count > 99 ? '99+' : hasMore ? `${count}+` : String(count)
  return { text, label: `안 읽은 알림 ${text}건` }
}

export function unreadByProject(
  page?: Pick<Page<Pick<Notification, 'project_id'>>, 'items' | 'has_more'>,
) {
  const counts = new Map<string, number>()
  for (const { project_id } of page?.items ?? [])
    counts.set(project_id, (counts.get(project_id) ?? 0) + 1)
  return (projectId: string) =>
    unreadCount(counts.get(projectId) ?? 0, page?.has_more ?? false)
}

// 도시의 프로젝트마다 알림을 부르지 않고 내 안 읽은 알림을 한 번만 받아 프로젝트별로 센다.
// ponytail: 프로젝트별 집계 API가 없어 최신 100건으로 센다. 100건을 넘으면 수가 모자랄 수 있어 'N+'로 보이고, 최신 100건에
// 하나도 없는 프로젝트는 배지가 빠진다. NTF-A01에 프로젝트별 수가 생기면 그 값으로 바꾼다.
export function useUnreadByProject() {
  const { data } = useQuery({
    queryKey: ['notifications', 'by-project'],
    queryFn: () => inboxApi.list({ unread: true, limit: 100 }),
  })
  return unreadByProject(data)
}
