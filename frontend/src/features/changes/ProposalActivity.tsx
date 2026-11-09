import { useState } from 'react'
import { AlertCircle, Bot, Cpu, Hexagon, User } from 'lucide-react'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import type { Activity } from '@/api/contracts/common'
import { activityChip } from '@/features/dashboard/labels'
import { cn } from '@/lib/utils'
import { useProposalActivities } from './useProposalActivities'
import type { ActorFilter } from './useProposalActivities'

const typeLabels: Record<string, string> = {
  CHANGE_PROPOSAL_SUBMITTED: '제안을 올렸어요',
  CHANGE_PROPOSAL_WITHDRAWN: '제안을 거뒀어요',
  CHANGE_PROPOSAL_REVIEW_STARTED: '검토를 시작했어요',
  CHANGE_PROPOSAL_REVIEW_REQUESTED: '검토를 요청했어요',
  CHANGE_PROPOSAL_COMMENTED: '의견을 남겼어요',
  CHANGE_PROPOSAL_APPROVED: '제안을 승인했어요',
  CHANGE_PROPOSAL_REJECTED: '제안을 반려했어요',
  CHANGE_PROPOSAL_PUBLISHED: '새 기준으로 발행했어요',
  MEETING_CLOSED: '회의를 끝내고 제안을 만들었어요',
}
const actorKinds = {
  HUMAN: { label: '사람', icon: User },
  AGENT: { label: '에이전트', icon: Bot },
  BEE: { label: '비비', icon: Hexagon },
  SYSTEM: { label: '시스템', icon: Cpu },
} as const
const filters: [ActorFilter, string][] = [
  ['', '전체'],
  ['HUMAN', '사람'],
  ['AGENT', '에이전트'],
  ['BEE', '비비'],
  ['SYSTEM', '시스템'],
]
const when = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))

const tones = {
  HUMAN: 'bg-muted text-muted-foreground',
  AGENT: 'bg-violet-100 text-violet-700',
  BEE: 'bg-amber-100 text-amber-700',
  SYSTEM: 'bg-sky-100 text-sky-700',
} as const

function actorName(item: Activity) {
  if (item.actor_type === 'BEE') return '비비'
  if (item.actor_type === 'SYSTEM') return '시스템'
  return item.actor_name ?? '이름을 알 수 없는 사용자'
}

// 변경 제안 하나의 활동 이력(S15P31A108-303). 제안이 바뀌면 부모가 key 로 새로 그려 필터·더 보기가 초기화된다.
export function ProposalActivity({
  projectId,
  proposalId,
  onShowComments,
}: {
  projectId: string
  proposalId: string
  onShowComments: () => void
}) {
  const [actor, setActor] = useState<ActorFilter>('')
  const query = useProposalActivities(projectId, proposalId, actor)
  // 다음 페이지 실패는 이미 받은 목록 아래에서만 알린다. 처음 불러오기 실패와 섞지 않는다.
  const asyncQuery = {
    data: query.data,
    isPending: query.isPending,
    error: query.isFetchNextPageError ? null : query.error,
    refetch: query.refetch,
  }

  return (
    <section aria-label="활동 이력" className="grid gap-3 text-sm">
      <div
        role="group"
        aria-label="행위자 보기"
        className="flex flex-wrap gap-1.5"
      >
        {filters.map(([value, label]) => (
          <button
            key={value || 'all'}
            type="button"
            aria-pressed={actor === value}
            onClick={() => setActor(value)}
            className={cn(
              'h-7 rounded-full px-3 text-xs font-semibold',
              actor === value
                ? 'bg-foreground text-background'
                : 'bg-muted text-muted-foreground',
            )}
          >
            {label}
          </button>
        ))}
      </div>
      <AsyncState query={asyncQuery}>
        {(data) => {
          const items = data.pages.flatMap((page) => page.items)
          if (!items.length)
            return (
              <EmptyState>
                {actor
                  ? '이 조건에 맞는 활동이 없어요.'
                  : '기록된 활동이 없습니다.'}
                {!actor && (
                  <span className="text-xs">
                    활동 기록이 연결되기 전의 제안은 남아 있지 않을 수 있어요.
                  </span>
                )}
              </EmptyState>
            )
          return (
            <>
              <ol className="rounded-2xl border bg-card px-4">
                {items.map((item) => {
                  const kind = actorKinds[item.actor_type]
                  const Icon = kind.icon
                  return (
                    <li
                      key={item.id}
                      className="flex items-start gap-3 border-t py-3 first:border-t-0"
                    >
                      <span
                        aria-hidden
                        className={cn(
                          'mt-0.5 grid size-7 shrink-0 place-items-center rounded-full',
                          tones[item.actor_type],
                        )}
                      >
                        <Icon className="size-3.5" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="leading-[19px]">
                          <strong className="font-semibold">
                            {actorName(item)}
                          </strong>{' '}
                          {item.actor_type === 'AGENT' && (
                            <span
                              className={cn(
                                'rounded-full px-2 py-0.5 text-[11px] font-semibold',
                                tones[item.actor_type],
                              )}
                            >
                              {kind.label}
                            </span>
                          )}
                        </p>
                        <p className="leading-[19px]">
                          {typeLabels[item.type] ?? item.type}
                        </p>
                        <time
                          dateTime={item.created_at}
                          className="text-xs text-muted-foreground"
                        >
                          {when(item.created_at)}
                        </time>
                      </div>
                      <span
                        className={cn(
                          'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold',
                          activityChip(item).className,
                        )}
                      >
                        {activityChip(item).label}
                      </span>
                    </li>
                  )
                })}
              </ol>
              {query.isFetchNextPageError && (
                <div
                  role="alert"
                  className="flex flex-wrap items-center gap-3 rounded-xl border border-destructive/20 bg-destructive/5 p-3"
                >
                  <AlertCircle className="size-4 text-destructive" />
                  <span className="mr-auto">
                    다음 활동을 불러오지 못했어요.
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => void query.fetchNextPage()}
                  >
                    다시 시도
                  </Button>
                </div>
              )}
              {query.hasNextPage && !query.isFetchNextPageError && (
                <Button
                  variant="outline"
                  size="sm"
                  className="justify-self-start"
                  disabled={query.isFetchingNextPage}
                  onClick={() => void query.fetchNextPage()}
                >
                  {query.isFetchingNextPage ? '불러오는 중…' : '더 보기'}
                </Button>
              )}
            </>
          )
        }}
      </AsyncState>
      <p className="text-xs text-muted-foreground">
        의견 내용과 반려 사유는 활동에 담기지 않아요.
        <Button
          variant="link"
          className="h-auto px-1.5 py-0 text-xs"
          onClick={onShowComments}
        >
          의견 탭에서 보기
        </Button>
      </p>
    </section>
  )
}
