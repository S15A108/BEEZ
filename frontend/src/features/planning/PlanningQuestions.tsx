import { ACTIVE_POLL_MS, SLOW_GENERATION_MS } from '@/lib/pollIntervals'
import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Lightbulb, RefreshCw } from 'lucide-react'
import type { PlanningQuestion } from '@/api/contracts/common'
import { planningApi } from '@/api/services'
import { getErrorCodeMessage } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Tag } from '@/features/changes/Tag'
import { operationTone } from '@/features/changes/tagTones'
import { cn } from '@/lib/utils'
import { QuestionAnswerForm } from './QuestionAnswerForm'
import type { GenerationStatus } from './questionGeneration'
import type { AnswerQuestionInput } from './schemas'

const kindLabel = {
  MISSING_CANDIDATE: '누락 후보',
  CLARIFICATION: '모호한 내용',
  CONFLICT: '문서 간 충돌',
} as const
const kindTone = {
  MISSING_CANDIDATE: 'bg-[#e6effa] text-[#1d4f91]',
  CLARIFICATION: undefined,
  CONFLICT: operationTone.RETIRE,
} as const
const typeLabel = {
  CHOICE: '선택형',
  SHORT_ANSWER: '단답형',
  ESSAY: '서술형',
} as const
// S15P31A108-396: 본문이 바뀐 뒤 질문을 새로 만드는 중이거나 그러지 못한 동안의 이전 질문 표시
const freshnessLabel = {
  REFRESHING: '이전 본문 기준 · 갱신 중',
  REFRESH_FAILED: '최신 본문으로 갱신하지 못함',
  AWAITING_RESUBMISSION: '반려된 문서 · 다시 제출 대기',
} as const
const lockedMessage = {
  REFRESHING: '새 본문으로 질문을 다시 만드는 중이라 끝난 뒤 답할 수 있어요.',
  REFRESH_FAILED:
    '이전 본문으로 만든 질문이라 답할 수 없어요. 질문을 새 본문으로 다시 만든 뒤 답해 주세요.',
  AWAITING_RESUBMISSION:
    '문서가 반려돼 이 질문은 답할 수 없어요. 문서를 다시 제출하면 새 본문으로 질문을 만들어요.',
} as const

const isOutdated = (question: PlanningQuestion) =>
  !!question.freshness && question.freshness !== 'CURRENT'
// 서버는 이전 본문으로 만든 미답변 질문의 답변·닫기를 막는다(409 question_outdated). 답한·닫은 질문은 이력이라 고칠 수 있다.
const isLocked = (question: PlanningQuestion) =>
  isOutdated(question) && !question.answer && question.status !== 'DISMISSED'

export interface QuestionGenerationView {
  /** 질문 생성이 도는 중이거나 실패한 문서 하나(문서 버전마다 따로 온다) */
  versionId: string
  label: string
  status: GenerationStatus
  errorCode?: string
  retryable?: boolean
  onRetry: () => void
}

export function PlanningQuestions({
  projectId,
  isLeader,
  busy,
  generations,
  answerable,
  published,
  onAnswer,
}: {
  projectId: string
  isLeader: boolean
  busy: boolean
  generations: QuestionGenerationView[]
  /** 기준 설계가 발행되면 서버가 답변을 막는다(409 design_already_published) */
  answerable: boolean
  published: boolean
  onAnswer: (id: string, input: AnswerQuestionInput) => Promise<unknown>
}) {
  // 답하는 중인 질문. 목록이 새로 읽혀도 최신 질문을 보이고, 이전·다음으로 질문을 바꿔 넘긴다.
  // 새 본문으로 질문이 다시 만들어져 그 질문이 목록에서 빠져도 창을 닫지 않고 연 때의 질문을 보인다 — 쓰던 답이 사라지지 않고,
  // 저장하면 서버가 409(question_superseded 등)로 이유를 알려 준다(S15P31A108-396).
  const [opened, setOpened] = useState<PlanningQuestion | null>(null)
  const failed = generations.filter((item) => item.status === 'FAILED')
  const activeIds = generations
    .filter((item) => item.status !== 'FAILED')
    .map((item) => item.versionId)
  const generating = activeIds.length > 0
  const activeLabels = generations
    .filter((item) => item.status !== 'FAILED')
    .map((item) => item.label)
  const queryClient = useQueryClient()
  // 같은 문서들이 오래 만드는 중으로 남으면 끝없이 기다리지 않게 알리고 상태를 직접 새로 읽을 길을 준다(S15P31A108-359).
  // 문서가 끝나거나 새로 시작해 대상이 바뀌면 다시 센다.
  const [slowFor, setSlowFor] = useState('')
  const slowKey = activeIds.join(',')
  useEffect(() => {
    if (!slowKey) return
    const timer = setTimeout(() => setSlowFor(slowKey), SLOW_GENERATION_MS)
    return () => clearTimeout(timer)
  }, [slowKey])
  const slow = !!slowKey && slowFor === slowKey
  // 문서 하나라도 질문 생성이 끝나는 순간에는 마지막 목록이 완료 직전 값일 수 있어, 그때 한 번 더 읽는다.
  const previousActive = useRef<string[]>([])
  const activeKey = activeIds.join(',')
  useEffect(() => {
    const now = activeKey ? activeKey.split(',') : []
    if (previousActive.current.some((id) => !now.includes(id)))
      void queryClient.invalidateQueries({
        queryKey: ['projects', projectId, 'planning-questions'],
      })
    previousActive.current = now
  }, [activeKey, projectId, queryClient])
  const questions = useInfiniteQuery({
    queryKey: ['projects', projectId, 'planning-questions'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.questions(projectId, { cursor: pageParam, limit: 100 }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    // 질문을 만드는 동안에는 끝날 때까지 목록을 다시 불러온다. 질문 생성이 시작되기 전 문서 재분석 단계에도
    // 서버가 '갱신 중'으로 표시한 질문이 있으면 끝날 때까지 따라간다(S15P31A108-396).
    refetchInterval: (query) =>
      generating ||
      query.state.data?.pages.some((page) =>
        page.items.some((item) => item.freshness === 'REFRESHING'),
      )
        ? ACTIVE_POLL_MS
        : false,
  })
  const allQuestions = questions.data?.pages.flatMap((page) => page.items) ?? []
  const answering = opened
    ? (allQuestions.find((item) => item.id === opened.id) ?? opened)
    : undefined
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Lightbulb className="size-4 text-primary" />
          아직 정하지 않은 내용
        </CardTitle>
        <CardDescription>
          답변은 초안 생성의 참고 자료이며 설계 승인과 구분해요.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {failed.map((item) => (
          <div
            key={item.versionId}
            role="alert"
            className="mb-3 rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
          >
            <p>
              {item.label} 보완 질문을 만들지 못했어요.{' '}
              {getErrorCodeMessage(item.errorCode) ?? ''}
            </p>
            {isLeader && item.retryable !== false && (
              <Button
                className="mt-2"
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={item.onRetry}
              >
                <RefreshCw />
                질문 다시 만들기
              </Button>
            )}
          </div>
        ))}
        {generating && (
          <div
            role="status"
            className="mb-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
          >
            <p>비비가 보완 질문을 만들고 있어요. 잠시만 기다려 주세요.</p>
            <p className="mt-1 text-xs">
              만드는 중인 문서: {activeLabels.join(', ')}
            </p>
            {slow && (
              <div className="mt-2 grid justify-items-start gap-2">
                <p>
                  평소보다 오래 걸리고 있어요. 계속 같은 상태면 상태를 다시
                  확인하거나 팀장에게 알려 주세요.
                </p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    void queryClient.invalidateQueries({
                      queryKey: ['projects', projectId, 'analysis'],
                    })
                  }
                >
                  <RefreshCw />
                  상태 다시 확인
                </Button>
              </div>
            )}
          </div>
        )}
        <AsyncState query={questions}>
          {(data) => {
            const entries = data.pages.flatMap((page) => page.items)
            const total = data.pages[0]?.total_count
            return (
              <div className="space-y-3">
                {entries.length ? (
                  <>
                    {total !== undefined && (
                      <p className="text-xs text-muted-foreground">
                        전체 질문 {total}개
                        {entries.length < total &&
                          ` · ${entries.length}개 표시`}
                      </p>
                    )}
                    {entries.map((question) => (
                      <QuestionCard
                        key={question.id}
                        question={question}
                        isLeader={isLeader}
                        busy={busy}
                        answerable={answerable}
                        published={published}
                        onAnswer={() => setOpened(question)}
                      />
                    ))}
                  </>
                ) : (
                  <EmptyState>현재 등록된 보완 질문이 없어요.</EmptyState>
                )}
                {questions.hasNextPage && (
                  <Button
                    variant="outline"
                    onClick={() => void questions.fetchNextPage()}
                    disabled={questions.isFetchingNextPage}
                  >
                    질문 더 보기
                  </Button>
                )}
              </div>
            )
          }}
        </AsyncState>
      </CardContent>
      {answering && (
        <QuestionAnswerForm
          question={answering}
          siblings={allQuestions.filter(
            (item) => item.id === answering.id || !isLocked(item),
          )}
          onNavigate={(id) =>
            setOpened(allQuestions.find((item) => item.id === id) ?? null)
          }
          onClose={() => setOpened(null)}
          onSubmit={(input) => onAnswer(answering.id, input)}
        />
      )}
    </Card>
  )
}

function QuestionCard({
  question,
  isLeader,
  busy,
  answerable,
  published,
  onAnswer,
}: {
  question: PlanningQuestion
  isLeader: boolean
  busy: boolean
  answerable: boolean
  published: boolean
  onAnswer: () => void
}) {
  const dismissed = question.status === 'DISMISSED'
  const answered = !!question.answer && !dismissed
  const locked = isLocked(question)
  const clickable = isLeader && !busy && answerable && !locked
  return (
    <article
      className={cn(
        'rounded-lg border p-4',
        clickable &&
          'cursor-pointer transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
      )}
      {...(clickable
        ? {
            role: 'button',
            tabIndex: 0,
            onClick: onAnswer,
            onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
              if (event.target !== event.currentTarget) return
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onAnswer()
              }
            },
          }
        : {})}
    >
      <div className="mb-2 flex flex-wrap items-center gap-1.5">
        {question.number !== undefined && (
          <span className="mr-1 text-xs font-semibold text-muted-foreground">
            질문 {question.number}
          </span>
        )}
        {question.kind && (
          <Tag tone={kindTone[question.kind]}>{kindLabel[question.kind]}</Tag>
        )}
        {question.question_type && (
          <Tag>{typeLabel[question.question_type]}</Tag>
        )}
        {question.evidence_status === 'UNVERIFIED' && (
          <Tag tone={operationTone.UPDATE}>근거 미확인</Tag>
        )}
        {dismissed && <Tag>답변 없이 닫음</Tag>}
        {answered && <Tag tone={operationTone.CREATE}>답변 완료</Tag>}
        {question.freshness && question.freshness !== 'CURRENT' && (
          <Tag tone={operationTone.UPDATE}>
            {freshnessLabel[question.freshness]}
          </Tag>
        )}
      </div>
      <p className="text-sm leading-6">{question.question}</p>
      {question.summary && (
        <p className="mt-1 text-xs leading-5 text-muted-foreground">
          {question.summary}
        </p>
      )}
      {question.source && (
        <p className="mt-2 text-xs text-muted-foreground">
          원문 v{question.source.version_no} · {question.source.location}
        </p>
      )}
      {answered && (
        <p className="mt-3 rounded-md bg-muted/50 p-3 text-sm whitespace-pre-wrap">
          {question.answer}
        </p>
      )}
      {isLeader && published && (
        <p className="mt-3 text-xs text-muted-foreground">
          기준 설계가 발행되어 답변을 바꿀 수 없어요.
        </p>
      )}
      {isLeader &&
        !published &&
        locked &&
        question.freshness &&
        question.freshness !== 'CURRENT' && (
          <p className="mt-3 text-xs text-muted-foreground">
            {lockedMessage[question.freshness]}
          </p>
        )}
      {clickable && (
        <p className="mt-3 text-xs text-muted-foreground">
          {answered ? '눌러서 답변 수정' : '눌러서 답변하기'}
        </p>
      )}
    </article>
  )
}
