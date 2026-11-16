import { useState } from 'react'
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { inboxApi } from '@/api/services'
import type { Notification } from '@/api/contracts/common'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/formatDate'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { targetFocus, targetPane } from './notificationTarget'

export function InboxPanel({ projectId }: { projectId: string }) {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const queryClient = useQueryClient()
  const query = useInfiniteQuery({
    queryKey: ['notifications', projectId, unreadOnly],
    queryFn: ({ pageParam }) =>
      inboxApi.list({
        project_id: projectId,
        unread: unreadOnly,
        cursor: pageParam,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const read = useMutation({
    mutationFn: (ids: string[]) => inboxApi.markRead({ ids }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })
  const open = (item: Notification) => {
    if (!item.read_at) read.mutate([item.id])
    openPane(targetPane[item.target_type], targetFocus(item))
  }
  return (
    <div className="space-y-5">
      {/* 제목 '알림함'은 창 머리에 있어 여기서 다시 쓰지 않는다(D48). */}
      <div className="flex justify-end">
        <Button
          variant="outline"
          aria-pressed={unreadOnly}
          onClick={() => setUnreadOnly((value) => !value)}
        >
          {unreadOnly ? '전체 보기' : '읽지 않은 알림만'}
        </Button>
      </div>
      <AsyncState query={query}>
        {(data) => {
          const items = data.pages.flatMap((page) => page.items)
          return (
            <>
              {/* 알림은 줄로 나눈 목록이다. 안 읽은 알림은 점 하나와 굵은 제목으로만 알린다(D48). */}
              {items.length > 0 && (
                <ul className="divide-y border-y">
                  {items.map((item) => (
                    <li key={item.id} className="flex items-start gap-4 py-4">
                      <span
                        aria-hidden="true"
                        className={`mt-2 size-2 shrink-0 rounded-full ${item.read_at ? '' : 'bg-foreground'}`}
                      />
                      <div className="min-w-0 flex-1">
                        <button
                          type="button"
                          className={`text-left text-sm hover:underline ${item.read_at ? 'font-medium' : 'font-semibold'}`}
                          onClick={() => open(item)}
                        >
                          {item.title}
                        </button>
                        <p className="mt-1 text-sm text-muted-foreground">
                          {item.body}
                        </p>
                        <time className="mt-1 block text-caption text-muted-foreground">
                          {formatDate(item.created_at)}
                        </time>
                      </div>
                      {!item.read_at && (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={read.isPending}
                          onClick={() => read.mutate([item.id])}
                        >
                          읽음
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {!items.length && (
                <EmptyState>새 소식을 모두 확인했어요.</EmptyState>
              )}
              {query.hasNextPage && (
                <Button
                  variant="outline"
                  onClick={() => void query.fetchNextPage()}
                >
                  알림 더 보기
                </Button>
              )}
              {read.error && (
                <p role="alert" className="text-sm text-destructive">
                  읽음 처리를 하지 못했어요. 다시 시도해 주세요.
                </p>
              )}
            </>
          )
        }}
      </AsyncState>
    </div>
  )
}
