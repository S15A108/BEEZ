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
    openPane(
      item.target_type === 'CHANGE_PROPOSAL'
        ? 'changes'
        : item.target_type === 'MEETING'
          ? 'meetings'
          : item.target_type === 'BASELINE'
            ? 'documents'
            : 'tasks',
      item.target_id,
    )
  }
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-xl font-bold">알림함</h3>
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
              <div className="grid gap-3">
                {items.map((item) => (
                  <article
                    key={item.id}
                    className={`flex items-start gap-4 rounded-xl border p-4 ${item.read_at ? '' : 'bg-primary/5'}`}
                  >
                    <span
                      className={`mt-2 size-2 shrink-0 rounded-full ${item.read_at ? 'bg-muted' : 'bg-primary'}`}
                    />
                    <div className="flex-1">
                      <button
                        type="button"
                        className="text-left text-sm font-semibold hover:underline"
                        onClick={() => open(item)}
                      >
                        {item.title}
                      </button>
                      <p className="mt-2 text-xs leading-6 text-muted-foreground">
                        {item.body}
                      </p>
                      <time className="mt-2 block text-xs text-muted-foreground">
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
                  </article>
                ))}
              </div>
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
