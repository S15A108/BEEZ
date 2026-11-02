import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import { isMissingApi } from '@/api/error'
import { inboxApi } from '@/api/services'
import type { MarkReadInput } from './schemas'
import type { Notification } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { targetLabel, targetPane } from './notificationTarget'

// 벨 아래 팝오버. 알림을 누르면 그 창으로 가고 그 알림만 읽음 처리한다.
export function InboxPopover({ projectId }: { projectId: string }) {
  const [open, setOpen] = useState(false)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['notifications', projectId, 'popover'],
    queryFn: () => inboxApi.list({ project_id: projectId, limit: 20 }),
  })
  // 안 읽은 알림은 서버에서 unread=true로 따로 받는다. 최신 20건만 걸러 세면 그 밖의 안 읽은 알림이 빠진다
  // (PR #72 전수 리뷰 TC-6). 키와 조건이 HUD 배지와 같아 같은 응답을 나눠 쓴다.
  const unreadQuery = useQuery({
    queryKey: ['notifications', projectId, 'unread'],
    queryFn: () =>
      inboxApi.list({ project_id: projectId, unread: true, limit: 20 }),
  })
  const read = useMutation({
    mutationFn: (input: MarkReadInput) => inboxApi.markRead(input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })
  const items = query.data?.items ?? []
  const unread = unreadQuery.data?.items ?? []
  // 다음 페이지가 더 있으면 '20+'처럼 더 있다고 보인다.
  const unreadCount = unread.length
    ? `${unread.length}${unreadQuery.data?.has_more ? '+' : ''}`
    : ''
  const active = unreadOnly ? unreadQuery : query
  const shown = unreadOnly ? unread : items
  const go = (item: Notification) => {
    if (!item.read_at) read.mutate({ ids: [item.id] })
    setOpen(false)
    // 정할 것: 대상이 지워졌거나 권한이 없을 때의 안내
    openPane(targetPane[item.target_type], item.target_id)
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          size="icon"
          variant="secondary"
          className="relative"
          aria-label={unreadCount ? `알림 ${unreadCount}건` : '알림'}
        >
          <Bell />
          {unread.length > 0 && (
            <span className="absolute -top-1 -right-1 grid h-4.5 min-w-4.5 place-items-center rounded-full bg-destructive px-1 text-[11px] font-bold text-white">
              {unreadCount}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96 p-0">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <h2 className="font-semibold">알림</h2>
          <Button
            variant="ghost"
            size="sm"
            disabled={!unread.length || read.isPending}
            // 불러온 첫 페이지 ID만 보내지 않고 이 프로젝트의 안 읽은 알림 전체를 읽음 처리한다(PR #72 3차 조사 THIRD-TC-02).
            onClick={() => read.mutate({ all: true, project_id: projectId })}
          >
            모두 읽음
          </Button>
        </div>
        <div className="flex gap-1 px-4 pt-3 text-xs" role="group">
          {[
            [false, '전체'],
            [true, `읽지 않음 ${unreadCount}`.trim()],
          ].map(([value, label]) => (
            <button
              key={String(value)}
              type="button"
              aria-pressed={unreadOnly === value}
              onClick={() => setUnreadOnly(value as boolean)}
              className={cn(
                'rounded-full px-3 py-1',
                unreadOnly === value
                  ? 'bg-foreground text-background'
                  : 'bg-muted',
              )}
            >
              {label}
            </button>
          ))}
        </div>
        <ul className="max-h-96 overflow-y-auto p-2">
          {active.isPending && (
            <li role="status" className="p-4 text-sm text-muted-foreground">
              불러오는 중…
            </li>
          )}
          {active.error &&
            (isMissingApi(active.error) ? (
              <li role="status" className="p-4 text-sm text-muted-foreground">
                알림은 서버에서 아직 준비 중이에요.
              </li>
            ) : (
              <li role="alert" className="p-4 text-sm text-destructive">
                알림을 불러오지 못했어요.
              </li>
            ))}
          {active.data && !shown.length && (
            <li className="p-4 text-sm text-muted-foreground">
              새 알림이 없어요. 변경 제안 검토 요청, 회의, 작업 검증 결과가 오면
              여기에 떠요.
            </li>
          )}
          {shown.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => go(item)}
                className={cn(
                  'flex w-full gap-3 rounded-lg p-3 text-left hover:bg-muted',
                  !item.read_at && 'bg-primary/5',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'mt-1.5 size-2 shrink-0 rounded-full',
                    item.read_at ? 'bg-muted' : 'bg-primary',
                  )}
                />
                <span className="grid gap-1">
                  <strong className="text-sm font-medium">{item.title}</strong>
                  <span className="text-xs text-muted-foreground">
                    {targetLabel[item.target_type]} ·{' '}
                    {item.read_at ? '읽음' : formatDate(item.created_at)}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
        <div className="border-t p-2">
          <Button
            variant="ghost"
            size="sm"
            className="w-full"
            onClick={() => {
              setOpen(false)
              openPane('inbox')
            }}
          >
            지난 알림 더 보기
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
