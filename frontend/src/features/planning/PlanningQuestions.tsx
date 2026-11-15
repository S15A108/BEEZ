import { SLOW_GENERATION_MS } from '@/lib/pollIntervals'
import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  CircleCheck,
  CircleMinus,
  FileText,
  Lock,
  RefreshCw,
} from 'lucide-react'
import type { PlanningQuestion } from '@/api/contracts/common'
import { getErrorCodeMessage } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { Tag } from '@/features/changes/Tag'
import { operationTone } from '@/features/changes/tagTones'
import { cn } from '@/lib/utils'
import { Notice } from './Notice'
import { QuestionAnswerForm } from './QuestionAnswerForm'
import type { GenerationStatus } from './questionGeneration'
import type { AnswerQuestionInput } from './schemas'
import { SPLIT_HEIGHT, TAB_BODY, listRowClass } from './splitLayout'
import { SplitView } from './SplitView'
import { usePlanningQuestions } from './usePlanningQuestions'
import { useShowMore } from './useShowMore'

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
const isDismissed = (question: PlanningQuestion) =>
  question.status === 'DISMISSED'
const isAnswered = (question: PlanningQuestion) =>
  !!question.answer && !isDismissed(question)
const isOpen = (question: PlanningQuestion) =>
  !isAnswered(question) && !isDismissed(question)

type Filter = 'open' | 'answered' | 'all'
const filters: [Filter, string][] = [
  ['open', '답할 질문'],
  ['answered', '답함'],
  ['all', '전체'],
]
const matches: Record<Filter, (question: PlanningQuestion) => boolean> = {
  open: isOpen,
  answered: isAnswered,
  all: () => true,
}
// 목록은 칸 안에서 스크롤하므로 한 번에 넉넉히 보인다.
const QUESTION_STEP = 20

export interface QuestionGenerationView {
  /** 질문 생성이 도는 중이거나 실패한 문서 하나(문서 버전마다 따로 온다) */
  versionId: string
  label: string
  status: GenerationStatus
  errorCode?: string
  retryable?: boolean
  onRetry: () => void
}

interface AnswerProps {
  isLeader: boolean
  busy: boolean
  /** 기준 설계가 발행되면 서버가 답변을 막는다(409 design_already_published) */
  answerable: boolean
  published: boolean
  onAnswer: (id: string, input: AnswerQuestionInput) => Promise<unknown>
}

export function PlanningQuestions({
  projectId,
  generations,
  ...answer
}: AnswerProps & {
  projectId: string
  generations: QuestionGenerationView[]
}) {
  const { isLeader, busy } = answer
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
  const questions = usePlanningQuestions(projectId, generating)
  return (
    <div className={TAB_BODY}>
      {failed.map((item) => (
        <Notice
          key={item.versionId}
          tone="fail"
          action={
            isLeader &&
            item.retryable !== false && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={item.onRetry}
              >
                <RefreshCw />
                질문 다시 만들기
              </Button>
            )
          }
        >
          <p>
            {item.label} 보완 질문을 만들지 못했어요.{' '}
            {getErrorCodeMessage(item.errorCode) ?? ''}
          </p>
        </Notice>
      ))}
      {generating && (
        <Notice
          tone="run"
          action={
            slow && (
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
            )
          }
        >
          <p>
            비비가 보완 질문을 만들고 있어요. 만드는 중인 문서:{' '}
            {activeLabels.join(', ')}
          </p>
          {slow && (
            <p className="text-xs">
              평소보다 오래 걸리고 있어요. 계속 같은 상태면 상태를 다시
              확인하거나 팀장에게 알려 주세요.
            </p>
          )}
        </Notice>
      )}
      <AsyncState query={questions}>
        {(data) => (
          <QuestionBoard
            {...answer}
            entries={data.pages.flatMap((page) => page.items)}
            total={data.pages[0]?.total_count}
            hasNextPage={questions.hasNextPage}
            loadingNext={questions.isFetchingNextPage}
            loadNext={() => void questions.fetchNextPage()}
          />
        )}
      </AsyncState>
    </div>
  )
}

// 왼쪽은 질문 문장만 보이는 목록, 오른쪽은 고른 질문의 종류·근거와 답변 입력이다.
function QuestionBoard({
  entries,
  total,
  hasNextPage,
  loadingNext,
  loadNext,
  isLeader,
  answerable,
  published,
  onAnswer,
}: AnswerProps & {
  entries: PlanningQuestion[]
  total: number | undefined
  hasNextPage: boolean
  loadingNext: boolean
  loadNext: () => void
}) {
  const [picked, setPicked] = useState<Filter>()
  // 답하는 중인 질문. 목록이 새로 읽혀도 최신 질문을 보이고, 이전·다음으로 질문을 바꿔 넘긴다.
  // 새 본문으로 질문이 다시 만들어져 그 질문이 목록에서 빠져도 연 때의 질문을 그대로 보인다 — 쓰던 답이 사라지지 않고,
  // 저장하면 서버가 409(question_superseded 등)로 이유를 알려 준다(S15P31A108-396).
  const [opened, setOpened] = useState<PlanningQuestion>()
  const counts: Record<Filter, number> = {
    open: entries.filter(isOpen).length,
    answered: entries.filter(isAnswered).length,
    all: total ?? entries.length,
  }
  // 답할 질문이 남아 있으면 그것부터 보인다.
  const filter = picked ?? (counts.open ? 'open' : 'all')
  const filtered = entries.filter(matches[filter])
  const list = useShowMore(filtered, QUESTION_STEP)
  const current = opened
    ? (entries.find((item) => item.id === opened.id) ?? opened)
    : filtered[0]
  if (!entries.length)
    return (
      <EmptyState className={SPLIT_HEIGHT}>
        현재 등록된 보완 질문이 없어요.
      </EmptyState>
    )
  const canAnswer = (question: PlanningQuestion) =>
    isLeader && answerable && !isLocked(question)
  const header = (
    <div
      role="group"
      aria-label="질문 고르기"
      className="flex flex-1 gap-0.5 rounded-[10px] bg-[#e6e9ee] p-[3px]"
    >
      {filters.map(([value, label]) => (
        <button
          key={value}
          type="button"
          aria-pressed={filter === value}
          onClick={() => {
            setPicked(value)
            setOpened(undefined)
            list.reset()
          }}
          className={cn(
            'h-[30px] flex-1 rounded-lg px-1 text-[13px] whitespace-nowrap',
            filter === value
              ? 'bg-card font-bold shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
              : 'font-medium text-[#57534e]',
          )}
        >
          {label}{' '}
          <span className="font-normal text-muted-foreground tabular-nums">
            {counts[value]}
          </span>
        </button>
      ))}
    </div>
  )
  const rows = (
    <>
      {filtered.length === 0 && (
        <p className="px-4 py-8 text-center text-sm text-muted-foreground">
          {filter === 'open'
            ? '답할 질문이 남아 있지 않아요.'
            : '질문이 없어요.'}
        </p>
      )}
      <ul>
        {list.shown.map((question, index) => (
          <li key={question.id}>
            <QuestionRow
              question={question}
              number={question.number ?? index + 1}
              active={question.id === current?.id}
              onClick={() => setOpened(question)}
            />
          </li>
        ))}
      </ul>
      {(list.hidden > 0 || hasNextPage) && (
        <div className="p-3">
          <Button
            variant="ghost"
            size="sm"
            className="w-full text-muted-foreground"
            disabled={loadingNext}
            onClick={() => {
              list.more()
              if (list.hidden === 0 && hasNextPage) loadNext()
            }}
          >
            질문 더 보기
            {list.hidden > 0 ? ` (${list.hidden}개 남음)` : ''}
          </Button>
        </div>
      )}
    </>
  )
  const head = current && <QuestionHead question={current} />
  const detail = !current ? null : canAnswer(current) ? (
    <QuestionAnswerForm
      question={current}
      siblings={filtered.filter(
        (item) => item.id === current.id || !isLocked(item),
      )}
      total={total}
      onNavigate={(id) =>
        setOpened(entries.find((item) => item.id === id) ?? undefined)
      }
      onSubmit={(input) => onAnswer(current.id, input)}
    >
      {head}
    </QuestionAnswerForm>
  ) : (
    <div className="px-8 pt-5 pb-6">
      <p className="mb-3 text-xs font-semibold text-muted-foreground">
        질문 {current.number ?? '-'} / {total ?? entries.length}
      </p>
      {head}
      {isAnswered(current) && (
        <div className="mt-6 border-t pt-6">
          <p className="mb-1.5 text-xs font-semibold text-muted-foreground">
            답변
          </p>
          <p className="rounded-lg bg-muted/60 px-4 py-3 text-[15px] leading-7 whitespace-pre-wrap">
            {current.answer}
          </p>
        </div>
      )}
      {isLeader && published && (
        <p className="mt-4 text-sm text-muted-foreground">
          기준 설계가 발행되어 답변을 바꿀 수 없어요.
        </p>
      )}
      {isLeader &&
        !published &&
        isLocked(current) &&
        current.freshness &&
        current.freshness !== 'CURRENT' && (
          <p className="mt-6 flex gap-2 rounded-lg bg-muted px-4 py-3 text-sm leading-6">
            <Lock className="mt-1 size-4 shrink-0 text-muted-foreground" />
            {lockedMessage[current.freshness]}
          </p>
        )}
    </div>
  )
  return <SplitView header={header} list={rows} detail={detail} />
}

function QuestionRow({
  question,
  number,
  active,
  onClick,
}: {
  question: PlanningQuestion
  number: number
  active: boolean
  onClick: () => void
}) {
  const locked = isLocked(question)
  const dismissed = isDismissed(question)
  return (
    <button
      type="button"
      aria-current={active || undefined}
      onClick={onClick}
      className={listRowClass(active)}
    >
      <span
        className={cn(
          'w-7 shrink-0 pt-0.5 text-xs font-semibold tabular-nums',
          active ? 'text-foreground' : 'text-muted-foreground',
        )}
      >
        Q{number}
      </span>
      <span
        className={cn(
          'line-clamp-2 min-w-0 flex-1 text-sm leading-[1.5]',
          (locked || dismissed) && 'text-muted-foreground',
        )}
      >
        {question.question}
      </span>
      <span className="shrink-0 pt-0.5">
        {locked ? (
          <Lock
            role="img"
            aria-label="지금은 답할 수 없음"
            className="size-4 text-muted-foreground"
          />
        ) : isAnswered(question) ? (
          <CircleCheck
            role="img"
            aria-label="답변 완료"
            className="size-4 text-[#00753a]"
          />
        ) : dismissed ? (
          <CircleMinus
            role="img"
            aria-label="답변 없이 닫음"
            className="size-4 text-muted-foreground/70"
          />
        ) : null}
      </span>
    </button>
  )
}

// 오른쪽 상세의 질문 머리: 종류·유형·상태 칩, 질문, 설명, 근거 위치
function QuestionHead({ question }: { question: PlanningQuestion }) {
  return (
    <>
      <div className="flex flex-wrap items-center gap-1.5">
        {question.kind && (
          <Tag tone={kindTone[question.kind]}>{kindLabel[question.kind]}</Tag>
        )}
        {question.question_type && (
          <span className="mx-1 text-xs text-muted-foreground">
            {typeLabel[question.question_type]}
          </span>
        )}
        {question.evidence_status === 'UNVERIFIED' && (
          <Tag tone={operationTone.UPDATE}>근거 미확인</Tag>
        )}
        {isDismissed(question) && <Tag>답변 없이 닫음</Tag>}
        {isAnswered(question) && (
          <Tag tone={operationTone.CREATE}>답변 완료</Tag>
        )}
        {question.freshness && question.freshness !== 'CURRENT' && (
          <Tag tone={operationTone.UPDATE}>
            {freshnessLabel[question.freshness]}
          </Tag>
        )}
      </div>
      <h3 className="mt-3 text-xl leading-8 font-semibold tracking-tight break-words">
        {question.question}
      </h3>
      {question.summary && (
        <p className="mt-2 text-[15px] leading-7 text-muted-foreground">
          {question.summary}
        </p>
      )}
      {question.source && (
        <p className="mt-3 flex items-center gap-1.5 text-[13px] text-muted-foreground">
          <FileText className="size-4 shrink-0" />
          원문 v{question.source.version_no} · {question.source.location}
        </p>
      )}
    </>
  )
}
