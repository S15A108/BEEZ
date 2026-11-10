import { Check, LoaderCircle, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { BibiFace } from '@/components/BibiFace'
import { cn } from '@/lib/utils'
import {
  editedAfterReview,
  failureText,
  pendingPatches,
  stepLabel,
  stepOutcome,
} from './reviewState'
import type { Draft, DraftReview as Review, DraftSuggestion } from './schemas'
import { Tag } from './Tag'
import { operationTone } from './tagTones'

// 문서 수정 제안 ③ 비비 검토(캔버스 PNL-04-v2-propose-running·review, CHG-A19·A20). 권장 단계라 결과와 상관없이 다음으로 갈 수 있다.
// 영향 작업·검증은 검토한 리비전(input_revision) 기준의 예상이고, 올린 뒤의 판단은 팀장 쪽 영향 분석(CHG-A05)을 따른다.

const categoryLabel = { OMISSION: '빠진 부분', CONSISTENCY: '충돌' } as const
const categoryTone = {
  OMISSION: operationTone.UPDATE,
  CONSISTENCY: operationTone.RETIRE,
} as const

export function DraftReview({
  draft,
  busy,
  onRequest,
  onDecide,
}: {
  draft: Draft
  busy: boolean
  onRequest: () => void
  onDecide: (
    suggestion: DraftSuggestion,
    decision: 'APPLIED' | 'SKIPPED' | 'PENDING',
  ) => void
}) {
  const review = draft.latest_review
  if (!review)
    return (
      <div className="grid justify-items-center gap-3 py-10 text-center">
        <BibiFace className="size-16" />
        <p className="text-sm text-muted-foreground">
          아직 비비 검토를 받지 않았어요.
        </p>
        <Button disabled={busy} onClick={onRequest}>
          비비 검토 받기
        </Button>
      </div>
    )
  if (review.status === 'RUNNING') return <Running review={review} />
  if (review.status !== 'COMPLETED')
    return (
      <div className="grid gap-4">
        <div
          role="alert"
          className="grid justify-items-center gap-2 py-4 text-center"
        >
          <BibiFace className="size-16" />
          <h3 className="text-lg font-bold">비비 검토가 끝나지 못했어요</h3>
          <p className="text-sm text-muted-foreground">
            {failureText(review)} 의견이 없다는 뜻이 아니에요. 다시 검토를
            받거나, 이 상태를 확인하고 올릴 수 있어요.
          </p>
          <Button disabled={busy} onClick={onRequest}>
            다시 검토 받기
          </Button>
        </div>
        <StepList review={review} />
      </div>
    )
  return (
    <Result
      draft={draft}
      review={review}
      busy={busy}
      onRequest={onRequest}
      onDecide={onDecide}
    />
  )
}

function Running({ review }: { review: Review }) {
  const done = review.steps.filter(
    (step) => !['PENDING', 'RUNNING'].includes(step.status),
  ).length
  const percent = Math.round((done / review.steps.length) * 100)
  return (
    <div className="mx-auto grid w-full max-w-160 gap-4 py-2">
      <div className="grid justify-items-center gap-2 text-center">
        <BibiFace className="size-20 ring-4 ring-primary/40" />
        <h3 className="text-2xl font-bold">비비가 바뀐 곳을 검토하고 있어요</h3>
        <p className="text-[13px] text-muted-foreground">
          아직 팀장에게 가지 않았어요. 올리기 전에 검토해요.
        </p>
      </div>
      <div className="grid gap-1.5">
        <p className="flex justify-between text-[13px] font-bold">
          <span>
            {Math.min(done + 1, review.steps.length)}단계 /{' '}
            {review.steps.length}단계
          </span>
          <span className="text-muted-foreground">{percent}%</span>
        </p>
        <div
          role="progressbar"
          aria-label="검토 진행"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          className="h-1.5 overflow-hidden rounded-full bg-[#f3ead0]"
        >
          <div
            className="h-full rounded-full bg-primary transition-[width]"
            style={{ width: `${percent}%` }}
          />
        </div>
      </div>
      <StepList review={review} />
    </div>
  )
}

function StepList({ review }: { review: Review }) {
  return (
    <ol aria-label="비비 검토 단계" className="grid">
      {review.steps.map((step) => (
        <li
          key={step.key}
          className="flex items-center gap-3 border-b py-3 text-[15px] last:border-b-0"
        >
          <span
            className={cn(
              'grid size-9 shrink-0 place-items-center rounded-full',
              step.status === 'DONE' && 'bg-primary text-[#322710]',
              step.status === 'RUNNING' && 'text-primary',
              step.status === 'PENDING' && 'border border-[#e5e2d9]',
              ['FAILED', 'NOT_EVALUATED'].includes(step.status) &&
                'bg-muted text-muted-foreground',
            )}
          >
            {step.status === 'DONE' ? (
              <Check aria-hidden="true" className="size-4" />
            ) : step.status === 'RUNNING' ? (
              <LoaderCircle
                aria-hidden="true"
                className="size-6 animate-spin"
              />
            ) : step.status === 'PENDING' ? null : (
              <X aria-hidden="true" className="size-4" />
            )}
          </span>
          <span
            className={cn(
              'font-semibold',
              step.status === 'PENDING' && 'text-[#a8a59c]',
            )}
          >
            {stepLabel[step.key]}
          </span>
          <span className="ml-auto text-[13px] font-semibold text-muted-foreground">
            {step.status === 'RUNNING' ? '진행 중' : stepOutcome(step)}
          </span>
        </li>
      ))}
    </ol>
  )
}

function Result({
  draft,
  review,
  busy,
  onRequest,
  onDecide,
}: {
  draft: Draft
  review: Review
  busy: boolean
  onRequest: () => void
  onDecide: (
    suggestion: DraftSuggestion,
    decision: 'APPLIED' | 'SKIPPED' | 'PENDING',
  ) => void
}) {
  const related =
    review.steps.find((step) => step.key === 'FIND_RELATED')?.status === 'DONE'
  const aiStep = review.steps.find((step) => step.key === 'AI_CANDIDATES')
  const patches = review.suggestions.filter((item) => item.type === 'PATCH')
  const count = (category: DraftSuggestion['category']) =>
    review.suggestions.filter((item) => item.category === category).length
  const stale = editedAfterReview(draft)
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <BibiFace className="size-9" />
        <h3 className="text-xl font-bold">비비가 검토를 마쳤어요</h3>
        <Tag>
          {related
            ? `영향 작업 ${review.tasks_to_wait.length}`
            : '영향 확인 못함'}
        </Tag>
        <Tag tone={categoryTone.OMISSION}>빠진 부분 {count('OMISSION')}</Tag>
        <Tag tone={categoryTone.CONSISTENCY}>충돌 {count('CONSISTENCY')}</Tag>
        <span className="text-[13px] text-muted-foreground">
          올리기 전 검토라 팀장에게는 아직 가지 않았어요.
        </span>
      </div>
      {stale && (
        <div
          role="status"
          className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary/40 bg-primary/10 px-4 py-3 text-sm"
        >
          <p>
            검토를 받은 뒤 글을 고쳤어요. 아래 결과는 고치기 전 글 기준이에요.
            지금 글로 다시 검토를 받을 수 있어요.
          </p>
          <Button size="sm" disabled={busy} onClick={onRequest}>
            다시 검토 받기
          </Button>
        </div>
      )}
      <div className="grid items-start gap-5 lg:grid-cols-2">
        <section aria-label="영향받는 작업·검증" className="grid gap-2.5">
          <h4 className="text-sm font-bold">
            영향받는 작업·검증{' '}
            <span className="font-normal text-muted-foreground">
              검토 당시 예상
            </span>
          </h4>
          {!related ? (
            <p className="rounded-xl border px-3.5 py-3 text-[13px]">
              관련 작업·검증을 확인하지 못했어요.
            </p>
          ) : review.tasks_to_wait.length +
            review.validations_to_stale.length ? (
            <ul className="grid gap-2">
              {review.tasks_to_wait.map((task) => (
                <li
                  key={`task-${task.task_id}`}
                  className="grid gap-0.5 rounded-xl border px-3.5 py-3 text-[13px]"
                >
                  <strong>{task.title}</strong>
                  <span className="text-muted-foreground">
                    승인되면 대기·재검증으로 바뀌어요.
                  </span>
                </li>
              ))}
              {review.validations_to_stale.map((validation) => (
                <li
                  key={`validation-${validation.validation_id}`}
                  className="grid gap-0.5 rounded-xl border px-3.5 py-3 text-[13px]"
                >
                  <strong>{validation.task_title} 검증</strong>
                  <span className="text-muted-foreground">
                    승인되면 다시 검증 필요로 바뀌어요.
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="rounded-xl border px-3.5 py-3 text-[13px]">
              확인된 관계로는 영향받는 작업·검증이 없어요.
            </p>
          )}
          <div className="grid gap-1 rounded-xl border border-dashed bg-[#fbf8ef] px-3.5 py-3 text-[13px]">
            <strong>AI 영향 후보</strong>
            {review.ai_candidates == null ? (
              <p>
                확인하지 못했어요.{' '}
                {aiStep?.status === 'NOT_EVALUATED'
                  ? '문서 변경은 아직 AI가 평가하지 않아요.'
                  : aiStep?.status === 'FAILED'
                    ? 'AI 호출이 실패했어요.'
                    : ''}
              </p>
            ) : review.ai_candidates.length ? (
              <ul className="grid gap-1">
                {review.ai_candidates.map((candidate) => (
                  <li key={candidate.task_id}>
                    <strong>{candidate.title}</strong> · {candidate.reason}
                  </li>
                ))}
              </ul>
            ) : (
              <p>후보가 없어요.</p>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            검토한 글 기준의 예상이에요. 올리면 팀장 쪽 영향 분석을 새로 해요.
          </p>
        </section>
        <section aria-label="빠진 부분·충돌" className="grid gap-2.5">
          <h4 className="text-sm font-bold">
            빠진 부분·충돌{' '}
            <span className="font-normal text-muted-foreground">
              수정안 {patches.length}개 중 {pendingPatches(review).length}개
              남음
            </span>
          </h4>
          {review.suggestions.length ? (
            <ul className="grid gap-2">
              {review.suggestions.map((suggestion) => (
                <SuggestionCard
                  key={suggestion.id}
                  suggestion={suggestion}
                  disabled={busy || stale}
                  onDecide={onDecide}
                />
              ))}
            </ul>
          ) : (
            <p className="rounded-xl border px-3.5 py-3 text-[13px]">
              빠진 부분이나 충돌을 찾지 못했어요.
            </p>
          )}
          {review.summary && (
            <p className="text-xs text-muted-foreground">{review.summary}</p>
          )}
        </section>
      </div>
    </div>
  )
}

function SuggestionCard({
  suggestion,
  disabled,
  onDecide,
}: {
  suggestion: DraftSuggestion
  disabled: boolean
  onDecide: (
    suggestion: DraftSuggestion,
    decision: 'APPLIED' | 'SKIPPED' | 'PENDING',
  ) => void
}) {
  const pending = suggestion.decision === 'PENDING'
  return (
    <li
      className={cn(
        'grid gap-2 rounded-xl border px-3.5 py-3 text-[13px]',
        pending && 'border-primary/60 shadow-[0_0_0_3px_#fdf3d3]',
        !pending && 'bg-[#fbfaf6]',
      )}
    >
      <p className="flex items-center gap-2">
        {suggestion.category && (
          <Tag tone={categoryTone[suggestion.category]}>
            {categoryLabel[suggestion.category]}
          </Tag>
        )}
        {suggestion.design_item_key && (
          <strong>{suggestion.design_item_key}</strong>
        )}
        {suggestion.type === 'PATCH' && !pending && (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            {suggestion.decision === 'APPLIED' ? '반영함' : '넘김'} ·
            <button
              type="button"
              disabled={disabled}
              className="font-semibold text-[#8a6a00] disabled:opacity-50"
              onClick={() => onDecide(suggestion, 'PENDING')}
            >
              되돌리기
            </button>
          </span>
        )}
      </p>
      <p className="leading-5">{suggestion.explanation}</p>
      {suggestion.type === 'COMMENT' ? (
        <p className="text-xs text-muted-foreground">
          의견이라 고를 것은 없어요.
        </p>
      ) : (
        pending && (
          <>
            <div className="rounded-lg bg-[#fdf3d3] px-3 py-2">
              <p className="text-xs font-semibold text-[#8a6a00]">
                비비 수정안
              </p>
              <p className="leading-5">{suggestion.replacement_text}</p>
            </div>
            <div className="flex justify-end gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={disabled}
                onClick={() => onDecide(suggestion, 'SKIPPED')}
              >
                넘기기
              </Button>
              <Button
                size="sm"
                disabled={disabled}
                onClick={() => onDecide(suggestion, 'APPLIED')}
              >
                반영하기
              </Button>
            </div>
          </>
        )
      )}
    </li>
  )
}
