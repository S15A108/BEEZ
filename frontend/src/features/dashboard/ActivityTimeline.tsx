import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { dashboardApi } from '@/api/services/dashboard'
import type { Activity, AgentWork } from '@/api/contracts/common'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ActorAvatar } from './ActorAvatar'
import { activityChip, agentLabel } from './labels'
import { monthDayText, seoulClock, seoulDay } from './period'

// 한국시간 날짜로 묶는다: '오늘 · 10월 27일', '어제 · 10월 26일', '10월 23일', 올해가 아니면 '2025년 10월 27일'
function groupByDay<T extends { created_at: string }>(items: T[]) {
  const today = seoulDay(Date.now())
  const yesterday = seoulDay(Date.now() - 86_400_000)
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const day = seoulDay(item.created_at)
    groups.set(day, [...(groups.get(day) ?? []), item])
  }
  return [...groups].map(([day, dayItems]) => {
    const year = day.slice(0, 4)
    const prefix =
      day === today
        ? '오늘 · '
        : day === yesterday
          ? '어제 · '
          : year === today.slice(0, 4)
            ? ''
            : `${year}년 `
    return { day, name: `${prefix}${monthDayText(day)}`, items: dayItems }
  })
}

// 활동 타임라인(MON-A04). 보기를 바꾸면 처음부터 다시 받고, '이전 활동 더 보기'는 아래에 이어 붙인다.
export function ActivityTimeline({
  projectId,
  agents,
  memberName,
  actorName,
}: {
  projectId: string
  // ponytail: 작업 현황 한 쪽(20개) 안의 세션만 칩이 된다. 세션이 더 많은 프로젝트가 생기면 다 받아 온다.
  agents: AgentWork[]
  memberName: (memberId: string) => string
  actorName: (item: Activity) => string
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const sessions = agents.filter((agent) => agent.agent_session_id)
  const views = [
    { key: 'all', label: '전체', query: {} },
    { key: 'human', label: '사람', query: { actor_type: 'HUMAN' as const } },
    ...sessions.map((agent) => ({
      key: agent.agent_session_id!,
      label: agentLabel(agent, sessions, memberName),
      query: { agent_session_id: agent.agent_session_id },
    })),
  ]
  const [viewKey, setViewKey] = useState('all')
  const view = views.find((entry) => entry.key === viewKey) ?? views[0]
  const timeline = useInfiniteQuery({
    queryKey: ['projects', projectId, 'activities', 'timeline', view.query],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      dashboardApi.activities(projectId, {
        ...view.query,
        cursor: pageParam,
        limit: 20,
      }),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor : undefined),
  })
  return (
    <div id="dashboard-timeline" className="grid gap-3.5">
      <div
        role="group"
        aria-label="보기"
        className="flex flex-wrap items-center gap-1 text-xs"
      >
        <span aria-hidden="true" className="mr-1 text-muted-foreground">
          보기
        </span>
        {views.map((entry) => (
          <button
            key={entry.key}
            type="button"
            aria-pressed={entry.key === view.key}
            onClick={() => setViewKey(entry.key)}
            className={cn(
              'rounded-full px-2.5 py-0.5 font-semibold',
              entry.key === view.key
                ? 'bg-foreground text-background'
                : 'bg-muted',
            )}
          >
            {entry.label}
          </button>
        ))}
      </div>
      {/* 다음 쪽만 실패하면 받아 둔 목록은 그대로 두고 버튼 옆에 알린다. */}
      <AsyncState
        query={{
          data: timeline.data,
          isPending: timeline.isPending,
          error: timeline.isFetchNextPageError ? null : timeline.error,
          refetch: () => timeline.refetch(),
        }}
      >
        {(data) => {
          const items = data.pages.flatMap((page) => page.items)
          if (!items.length)
            return (
              <EmptyState>
                {view.key === 'all'
                  ? '아직 기록된 프로젝트 활동이 없어요.'
                  : '이 보기에 맞는 활동이 없어요.'}
              </EmptyState>
            )
          return (
            <>
              <div className="grid gap-3">
                {groupByDay(items).map(({ day, name, items: dayItems }) => (
                  <section
                    key={day}
                    aria-label={name}
                    className="rounded-2xl border bg-card px-4 py-2"
                  >
                    <h4 className="py-1.5 text-[11.5px] text-muted-foreground">
                      {name}
                    </h4>
                    <ol>
                      {dayItems.map((item) => (
                        <li
                          key={item.id}
                          className="flex items-center gap-3 border-t py-2.5 text-[13px]"
                        >
                          <time
                            className="w-11 shrink-0 text-xs text-muted-foreground tabular-nums"
                            dateTime={item.created_at}
                          >
                            {seoulClock(item.created_at)}
                          </time>
                          <ActorAvatar
                            agent={item.actor_type === 'AGENT'}
                            label={
                              item.actor_type === 'AGENT'
                                ? 'AI'
                                : actorName(item).slice(0, 1)
                            }
                          />
                          <span className="min-w-0 flex-1">
                            <strong className="font-semibold">
                              {actorName(item)}
                            </strong>{' '}
                            {statusLabel(item.type)}
                            {item.task_id && (
                              <Button
                                variant="link"
                                className="ml-2 h-auto p-0 text-xs"
                                onClick={() => openPane('tasks', item.task_id)}
                              >
                                작업 #{item.task_id}
                              </Button>
                            )}
                            {item.change_proposal_id && (
                              <Button
                                variant="link"
                                className="ml-2 h-auto p-0 text-xs"
                                onClick={() =>
                                  openPane('changes', item.change_proposal_id)
                                }
                              >
                                변경 요청 #{item.change_proposal_id}
                              </Button>
                            )}
                            {item.baseline_id && (
                              <Button
                                variant="link"
                                className="ml-2 h-auto p-0 text-xs"
                                onClick={() => openPane('documents')}
                              >
                                기준 설계 #{item.baseline_id}
                              </Button>
                            )}
                          </span>
                          <span
                            className={cn(
                              'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold',
                              activityChip(item).className,
                            )}
                          >
                            {activityChip(item).label}
                          </span>
                        </li>
                      ))}
                    </ol>
                  </section>
                ))}
              </div>
              {timeline.isFetchNextPageError && (
                <p role="alert" className="mt-4 text-xs text-destructive">
                  이전 활동을 불러오지 못했어요. 다시 눌러 주세요.
                </p>
              )}
              {timeline.hasNextPage && (
                <div className="mt-4 flex justify-center">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={timeline.isFetchingNextPage}
                    onClick={() => void timeline.fetchNextPage()}
                  >
                    {timeline.isFetchingNextPage
                      ? '불러오는 중…'
                      : '이전 활동 더 보기'}
                  </Button>
                </div>
              )}
            </>
          )
        }}
      </AsyncState>
    </div>
  )
}
