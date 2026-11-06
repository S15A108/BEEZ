import { useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
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

export interface QuestionGenerationView {
  /** 분석이 끝난 문서 중 질문 생성이 도는 중이거나 실패한 상태 */
  status: 'PENDING' | 'RUNNING' | 'FAILED'
  errorCode?: string
  retryable?: boolean
  onRetry: () => void
}

export function PlanningQuestions({
  projectId,
  isLeader,
  busy,
  generation,
  onAnswer,
}: {
  projectId: string
  isLeader: boolean
  busy: boolean
  generation?: QuestionGenerationView
  onAnswer: (id: string, input: AnswerQuestionInput) => Promise<unknown>
}) {
  const [answering, setAnswering] = useState<PlanningQuestion | null>(null)
  const generating =
    generation?.status === 'PENDING' || generation?.status === 'RUNNING'
  const questions = useInfiniteQuery({
    queryKey: ['projects', projectId, 'planning-questions'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.questions(projectId, { cursor: pageParam, limit: 100 }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    // 질문을 만드는 동안에는 끝날 때까지 목록을 다시 불러온다.
    refetchInterval: generating ? 1500 : false,
  })
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
        {generation && (
          <div
            role={generation.status === 'FAILED' ? 'alert' : 'status'}
            className={
              generation.status === 'FAILED'
                ? 'mb-3 rounded-lg bg-destructive/5 p-3 text-sm text-destructive'
                : 'mb-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900'
            }
          >
            {generation.status === 'FAILED' ? (
              <>
                <p>
                  보완 질문을 만들지 못했어요.{' '}
                  {getErrorCodeMessage(generation.errorCode) ?? ''}
                </p>
                {isLeader && generation.retryable !== false && (
                  <Button
                    className="mt-2"
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={generation.onRetry}
                  >
                    <RefreshCw />
                    질문 다시 만들기
                  </Button>
                )}
              </>
            ) : (
              <p>비비가 보완 질문을 만들고 있어요. 잠시만 기다려 주세요.</p>
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
                        onAnswer={() => setAnswering(question)}
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
          onClose={() => setAnswering(null)}
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
  onAnswer,
}: {
  question: PlanningQuestion
  isLeader: boolean
  busy: boolean
  onAnswer: () => void
}) {
  const dismissed = question.status === 'DISMISSED'
  const answered = !!question.answer && !dismissed
  const clickable = isLeader && !busy
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
      {clickable && (
        <p className="mt-3 text-xs text-muted-foreground">
          {answered ? '눌러서 답변 수정' : '눌러서 답변하기'}
        </p>
      )}
    </article>
  )
}
