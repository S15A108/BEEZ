import { useEffect, useReducer, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, LoaderCircle } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { documentsApi } from '@/api/services/documents'
import type { DocumentBeeReview } from '@/api/contracts/common'
import { ActionForm } from '@/components/ActionForm'
import { MarkdownView } from '@/components/MarkdownView'
import { AsyncState } from '@/components/AsyncState'
import { Toast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { statusLabel } from '@/lib/display'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { reviewStage } from './reviewStage'
import { membersQuery } from '@/features/members/membersQuery'

const STEPS = ['파일 등록', '비비 검토', '수정 제안 반영', '팀장 승인'] as const
const REVIEW_STEPS = [
  '문서 읽기',
  '승인된 API 명세서와 비교',
  'ERD와 맞춰 보기',
  '요구사항과 비교',
  '수정 제안 정리',
]

// 다른 문서가 승인돼 참고 승인본이 바뀌면 반영·넘기기·검토 요청이 409 review_stale로 막힌다. 응답에 낡은 검토라는
// 표시가 없어, 한 번 받은 검토 id를 기억해 창을 다시 열어도 무효 안내와 '비비 검토 다시 받기'를 바로 보인다.
const staleReviews = new Set<string>()

// 공용 문서함 · 문서별 검토 흐름(PNL-02-m): 비비 검토 → 수정 제안 반영 → 최종본 확인 → 팀장 승인·반려.
export function DocumentReview({
  projectId,
  documentId,
  title,
  kind,
  isLeader,
  locked = false,
  onClose,
  onApproved,
}: {
  projectId: string
  documentId: string
  title: string
  kind: string
  isLeader: boolean
  /** 기준 v1이 생겨 이 흐름으로는 고칠 수 없음(change_proposal_required) */
  locked?: boolean
  onClose: () => void
  /** 팀장이 승인하면 부르는 쪽이 목록으로 돌아가고 이 알림을 띄운다(사용자 요청 11-02). */
  onApproved?: (message: string) => void
}) {
  const session = useSession()
  const openPane = useWorkspaceStore((state) => state.openPane)
  const action = useProjectAction(projectId)
  const [editing, setEditing] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const client = useQueryClient()
  // 비비 검토(DOC-A16): 기준 v1 전에는 올리거나 고친 직후 화면이 바로 부른다. 누르는 버튼은 없고 실패했을 때만 다시 검토한다.
  const bee = useMutation({
    mutationFn: (revision: number) =>
      documentsApi.requestBeeReview(projectId, documentId, {
        expected_revision: revision,
      }),
    onSettled: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId] }),
  })
  const running = getApiErrorCode(bee.error) === 'review_in_progress'
  const copy = useQuery({
    queryKey: ['projects', projectId, 'working-copy', documentId],
    queryFn: () => documentsApi.workingCopy(projectId, documentId),
    // 다른 창에서 이미 검토 중이면 끝날 때까지 다시 읽는다. 올린 사람이 아닌 팀원은 검토를 부르지 않아
    // review_in_progress를 받지 못하므로, 작업본이 비비 검토 단계(1단계, 반려 아님)면 끝날 때까지 다시 읽는다(PR #142 리뷰).
    refetchInterval: ({ state: { data } }) =>
      running ||
      (data &&
        data.owner_account_id !== session.data?.id &&
        data.status !== 'REJECTED' &&
        reviewStage(data).step === 1)
        ? 3000
        : false,
  })
  const current = copy.data
  const currentStage = current && reviewStage(current)
  const reviewKey =
    current &&
    !locked &&
    current.owner_account_id === session.data?.id &&
    currentStage?.step === 1 &&
    current.status !== 'SUBMITTED'
      ? `${projectId}:${documentId}:${current.revision}`
      : null
  // 같은 작성 리비전으로 두 번 부르지 않는다(다시 그리기). 창을 다시 열거나 새로고침해
  // 이미 검토 중이면 서버가 review_in_progress로 알려 주고, 끝났으면 1단계가 아니라 부르지 않는다.
  const started = useRef(new Set<string>())
  const startReview = bee.mutate
  useEffect(() => {
    if (!reviewKey || started.current.has(reviewKey)) return
    started.current.add(reviewKey)
    startReview(Number(reviewKey.split(':')[2]))
  }, [reviewKey, startReview])
  const failed = !!bee.error && !running
  const members = useQuery(membersQuery(projectId))
  const pending = copy.data?.submissions.find(
    (item) => item.status === 'SUBMITTED',
  )
  const detail = useQuery({
    queryKey: ['projects', projectId, 'review-submission', pending?.id],
    queryFn: () =>
      documentsApi.reviewSubmission(projectId, documentId, pending!.id),
    enabled: !!pending && isLeader,
  })
  const nameOf = (accountId?: string) =>
    members.data?.items.find((member) => member.account_id === accountId)
      ?.name ?? '팀원'
  const [, rerender] = useReducer((count: number) => count + 1, 0)
  const run = (
    operation: () => Promise<unknown>,
    message: string,
    done?: () => void,
  ) =>
    void action
      .run(operation, message)
      .then(done)
      .catch((cause: unknown) => {
        const reviewId = currentStage?.review?.id
        if (getApiErrorCode(cause) === 'review_stale' && reviewId) {
          staleReviews.add(reviewId)
          rerender()
        }
      })
  const reviewStale =
    !!currentStage?.review &&
    current?.status !== 'SUBMITTED' &&
    staleReviews.has(currentStage.review.id)
  return (
    <section
      aria-label={`${title} 검토`}
      className="grid min-w-0 content-start gap-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onClose}>
          <ArrowLeft />
          목록
        </Button>
        <span className="text-xs text-muted-foreground">
          {statusLabel(kind)}
        </span>
        <h3 className="text-lg font-bold">{title}</h3>
      </div>
      {/* 팀장 승인·반려 알림은 가운데 위(승인 초록, 반려 빨강, PNL-02 수정안 10-29). */}
      {action.message && (
        <Toast
          key={action.message}
          tone={action.message.startsWith('반려') ? 'danger' : 'success'}
        >
          {action.message}
        </Toast>
      )}
      {locked && (
        <div className="grid gap-2 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
          <p>
            기준 설계 생성 이후 문서 수정은 변경 제안을 통해 요청해 주세요. 이
            화면에서는 보기만 할 수 있어요.
          </p>
          <Button
            size="sm"
            variant="outline"
            className="justify-self-start"
            onClick={() => openPane('changes')}
          >
            변경 제안으로 요청
          </Button>
        </div>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      <AsyncState query={copy}>
        {(data) => {
          const stage = reviewStage(data)
          const owner = !locked && data.owner_account_id === session.data?.id
          const rejected = data.submissions.find(
            (item) => item.status === 'REJECTED',
          )
          return (
            <>
              <ol
                aria-label="검토 단계"
                className="flex flex-wrap gap-1.5 text-xs"
              >
                {STEPS.map((step, index) => (
                  <li
                    key={step}
                    aria-current={index === stage.step ? 'step' : undefined}
                    className={cn(
                      'rounded-full border px-2.5 py-1',
                      index < stage.step && 'bg-muted text-muted-foreground',
                      index === stage.step && 'border-foreground font-semibold',
                    )}
                  >
                    {index < stage.step ? '✓ ' : ''}
                    {step}
                  </li>
                ))}
              </ol>
              <p className="text-sm">
                <strong>{stage.label}</strong>
                <span className="text-muted-foreground">
                  {' · '}올린 사람 {nameOf(data.owner_account_id)} · 리비전{' '}
                  {data.revision}
                </span>
              </p>
              {data.status === 'REJECTED' && rejected && (
                <div className="grid gap-1 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
                  <strong>팀장 반려</strong>
                  <p className="leading-6">{rejected.rejection_reason}</p>
                  <span className="text-xs text-muted-foreground">
                    {nameOf(rejected.decided_by)} ·{' '}
                    {formatDate(rejected.decided_at)}
                  </span>
                </div>
              )}
              {stage.step === 1 && (
                <div className="grid gap-3">
                  <MarkdownView source={data.content} className="max-h-72" />
                  {owner && failed ? (
                    <div
                      role="alert"
                      className="grid gap-2 rounded-xl border border-destructive/40 bg-destructive/5 p-4 text-sm"
                    >
                      <strong>비비 검토를 마치지 못했어요</strong>
                      <span>{getApiErrorMessage(bee.error)}</span>
                      <Button
                        variant="outline"
                        className="justify-self-start"
                        disabled={bee.isPending}
                        onClick={() => bee.mutate(data.revision)}
                      >
                        다시 검토하기
                      </Button>
                    </div>
                  ) : owner ? (
                    <div
                      role="status"
                      className="grid gap-2 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm"
                    >
                      <strong className="flex items-center gap-2">
                        <LoaderCircle className="size-4 animate-spin" />
                        {data.status === 'REJECTED'
                          ? '비비가 반려 사유를 보고 다시 검토하고 있어요'
                          : '비비가 문서를 검토하고 있어요'}
                      </strong>
                      {/* API 없음: 단계별 진행 상태(steps)는 검토 결과와 함께 와서 진행 중에는 순서만 보여 준다(DOC-A16, Q19). */}
                      <ol className="list-decimal pl-5 text-xs leading-5 text-muted-foreground">
                        {REVIEW_STEPS.map((step) => (
                          <li key={step}>{step}</li>
                        ))}
                      </ol>
                      <span className="text-xs text-muted-foreground">
                        창을 닫아도 검토는 이어져요.
                      </span>
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      올린 문서는 비비가 바로 검토해요. 검토가 끝나면 여기서
                      수정 제안을 볼 수 있어요.
                    </p>
                  )}
                </div>
              )}
              {reviewStale && (
                <div
                  role="alert"
                  className="grid gap-2 rounded-xl border border-[#c99e1e]/45 bg-primary/10 p-4 text-sm"
                >
                  <strong>
                    참고하던 승인 문서가 바뀌어 이 검토는 더 쓸 수 없어요
                  </strong>
                  <span className="text-muted-foreground">
                    비비 검토를 다시 받으면 지금 승인본을 기준으로 수정 제안을
                    새로 받아요.
                  </span>
                  {bee.error && !running && (
                    <span className="text-destructive">
                      {getApiErrorMessage(bee.error)}
                    </span>
                  )}
                  {owner && (
                    <Button
                      variant="outline"
                      className="justify-self-start"
                      disabled={bee.isPending}
                      onClick={() => bee.mutate(data.revision)}
                    >
                      {bee.isPending
                        ? '다시 검토하는 중…'
                        : '비비 검토 다시 받기'}
                    </Button>
                  )}
                </div>
              )}
              {stage.step === 2 && stage.review && !reviewStale && (
                <Suggestions
                  review={stage.review}
                  owner={owner}
                  busy={action.busy}
                  onDecide={(suggestionId, decision) =>
                    run(
                      () =>
                        documentsApi.decideSuggestion(
                          projectId,
                          documentId,
                          stage.review!.id,
                          suggestionId,
                          { expected_revision: data.revision, decision },
                        ),
                      decision === 'APPLY'
                        ? '제안을 반영했어요.'
                        : '제안을 넘겼어요.',
                    )
                  }
                />
              )}
              {stage.step === 3 &&
                data.status !== 'SUBMITTED' &&
                stage.review &&
                !reviewStale && (
                  <div className="grid gap-3">
                    <h4 className="font-semibold">최종본 확인</h4>
                    <p className="rounded-lg bg-primary/10 px-3 py-2 text-sm">
                      비비 제안을 반영한 최종본이에요. 팀장이 보게 될 화면과
                      같아요.
                    </p>
                    <ReviewNotes review={stage.review} />
                    {editing ? (
                      <ActionForm
                        fields={[
                          {
                            name: 'content',
                            label: '본문',
                            type: 'textarea',
                            required: true,
                            value: data.content,
                          },
                        ]}
                        submitLabel="고친 내용 저장"
                        onSubmit={async (values) => {
                          await action.run(
                            () =>
                              documentsApi.updateWorkingCopy(
                                projectId,
                                documentId,
                                {
                                  expected_revision: data.revision,
                                  content: values.content,
                                  file_name: data.file_name,
                                },
                              ),
                            '고친 내용을 저장했어요. 비비가 다시 검토해요.',
                          )
                          setEditing(false)
                        }}
                      />
                    ) : (
                      <MarkdownView
                        source={data.content}
                        className="max-h-72"
                      />
                    )}
                    {owner && !editing && (
                      <div className="flex flex-wrap gap-2">
                        <Button
                          variant="outline"
                          onClick={() => setEditing(true)}
                        >
                          다시 고치기
                        </Button>
                        <Button
                          disabled={action.busy}
                          onClick={() =>
                            run(
                              () =>
                                documentsApi.submitReview(
                                  projectId,
                                  documentId,
                                  {
                                    expected_revision: data.revision,
                                    review_id: stage.review!.id,
                                  },
                                ),
                              '팀장에게 검토를 요청했어요.',
                            )
                          }
                        >
                          팀장에게 검토 요청
                        </Button>
                      </div>
                    )}
                  </div>
                )}
              {data.status === 'SUBMITTED' &&
                (isLeader && !locked ? (
                  <AsyncState query={detail}>
                    {(submission) => (
                      <div className="grid gap-3">
                        <p className="rounded-lg bg-primary/10 px-3 py-2 text-sm">
                          {nameOf(submission.submission.submitted_by)}님이
                          승인을 요청했어요. ·{' '}
                          {formatDate(submission.submission.submitted_at)}
                        </p>
                        <MarkdownView
                          source={submission.content}
                          className="max-h-72"
                        />
                        <p className="text-xs text-muted-foreground">
                          비비 검토: {submission.review.summary} · 반영{' '}
                          {
                            submission.review.suggestions.filter(
                              (item) => item.decision === 'APPLIED',
                            ).length
                          }
                          개, 넘김{' '}
                          {
                            submission.review.suggestions.filter(
                              (item) => item.decision === 'SKIPPED',
                            ).length
                          }
                          개
                          {submission.review.unavailable_references.length >
                            0 &&
                            ` · 비교하지 못한 문서: ${submission.review.unavailable_references.map(statusLabel).join(', ')}`}
                        </p>
                        {rejecting ? (
                          <ActionForm
                            fields={[
                              {
                                name: 'reason',
                                label: '반려 사유',
                                type: 'textarea',
                                required: true,
                                max: 10000,
                              },
                            ]}
                            submitLabel="반려하기"
                            destructive
                            onSubmit={async (values) => {
                              await action.run(
                                () =>
                                  documentsApi.decideSubmission(
                                    projectId,
                                    documentId,
                                    submission.submission.id,
                                    {
                                      decision: 'REJECT',
                                      rejection_reason: values.reason,
                                    },
                                  ),
                                '반려했어요. 올린 사람이 보완해 다시 요청해요.',
                              )
                              setRejecting(false)
                            }}
                          />
                        ) : (
                          <div className="flex gap-2">
                            <Button
                              variant="outline"
                              onClick={() => setRejecting(true)}
                            >
                              반려
                            </Button>
                            <Button
                              disabled={action.busy}
                              onClick={() =>
                                run(
                                  () =>
                                    documentsApi.decideSubmission(
                                      projectId,
                                      documentId,
                                      submission.submission.id,
                                      { decision: 'APPROVE' },
                                    ),
                                  '승인했어요. 현재 문서로 옮겼어요.',
                                  () =>
                                    onApproved?.(
                                      '승인했어요. 현재 문서로 옮겼어요.',
                                    ),
                                )
                              }
                            >
                              승인
                            </Button>
                          </div>
                        )}
                        {/* API 없음: 승인·반려 결과를 올린 사람에게 알리는 공통 알림 연동(dev TODO) */}
                      </div>
                    )}
                  </AsyncState>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    팀장 승인을 기다리고 있어요. 결과는 이 화면에서 확인할 수
                    있어요.
                  </p>
                ))}
              {data.status === 'APPROVED' && (
                <p className="text-sm">
                  팀장이 승인했어요. 목록의 현재 문서에서 볼 수 있어요.
                </p>
              )}
            </>
          )
        }}
      </AsyncState>
    </section>
  )
}

const unavailableText = (review: DocumentBeeReview) =>
  `승인본이 없어 비교하지 못한 문서: ${review.unavailable_references.map(statusLabel).join(', ')}`

// 처리할 수정 제안이 없으면 최종본 단계로 바로 넘어가므로, 그 단계에서도 비비 의견(COMMENT)과
// 비교하지 못한 문서를 읽기 전용으로 보여 준다. 의견만 온 검토도 여기서 보인다(PR #89 리뷰).
function ReviewNotes({ review }: { review: DocumentBeeReview }) {
  const comments = review.suggestions.filter((item) => item.type === 'COMMENT')
  if (!comments.length && !review.unavailable_references.length) return null
  return (
    <div className="grid gap-1.5 rounded-xl border p-3 text-sm">
      {comments.length > 0 && (
        <>
          <strong>비비 의견 {comments.length}개</strong>
          <ol aria-label="비비 의견" className="grid gap-1">
            {comments.map((comment, index) => (
              <li key={comment.id}>
                의견 {index + 1} · {comment.explanation}
              </li>
            ))}
          </ol>
        </>
      )}
      {review.unavailable_references.length > 0 && (
        <p className="text-xs text-muted-foreground">
          {unavailableText(review)}
        </p>
      )}
    </div>
  )
}

function Suggestions({
  review,
  owner,
  busy,
  onDecide,
}: {
  review: DocumentBeeReview
  owner: boolean
  busy: boolean
  onDecide: (suggestionId: string, decision: 'APPLY' | 'SKIP') => void
}) {
  const comments = review.suggestions.filter((item) => item.type === 'COMMENT')
  return (
    <div className="grid gap-3">
      <div className="rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
        <strong>
          비비가 수정 제안 {review.suggestions.length - comments.length}개
          {comments.length > 0 && `와 의견 ${comments.length}개`}를 남겼어요
        </strong>
        <p className="mt-1 leading-6">{review.summary}</p>
        {review.unavailable_references.length > 0 && (
          <p className="mt-1 text-xs text-muted-foreground">
            {unavailableText(review)}
          </p>
        )}
        {review.steps.length > 0 && (
          <ol
            aria-label="비비 검토 단계"
            className="mt-2 grid gap-0.5 text-xs text-muted-foreground"
          >
            {review.steps.map((step, index) => (
              <li key={step.step}>
                {index + 1}단계 / {review.steps.length}단계 ·{' '}
                {statusLabel(step.step)} ·{' '}
                {step.status === 'COMPLETE'
                  ? '검토함'
                  : step.reason === 'SAME_KIND'
                    ? '같은 문서라 건너뜀'
                    : '승인본이 없어 건너뜀'}
              </li>
            ))}
          </ol>
        )}
      </div>
      <ol className="grid gap-2">
        {review.suggestions.map((suggestion, index) => (
          <li
            key={suggestion.id}
            className="grid gap-2 rounded-xl border p-3 text-sm"
          >
            <p className="flex flex-wrap items-center justify-between gap-2">
              <strong>
                {suggestion.type === 'COMMENT' ? '의견' : '제안'} {index + 1} ·{' '}
                {suggestion.explanation}
              </strong>
              {suggestion.type === 'PATCH' &&
                suggestion.decision !== 'PENDING' && (
                  <span className="rounded-full bg-muted px-2 py-0.5 text-xs">
                    {suggestion.decision === 'APPLIED' ? '반영함' : '넘김'}
                  </span>
                )}
            </p>
            {/* 의견(COMMENT)은 읽기만 하고, 반영·넘기기 없이 제출을 막지 않는다. */}
            {suggestion.type === 'PATCH' && (
              <div className="grid gap-1 font-mono text-xs leading-6 whitespace-pre-wrap select-text">
                <p className="rounded bg-destructive/5 px-2 text-destructive">
                  − {suggestion.original_text}
                </p>
                <p className="rounded bg-primary/10 px-2">
                  + {suggestion.replacement_text}
                </p>
              </div>
            )}
            {owner && suggestion.decision === 'PENDING' && (
              <span className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => onDecide(suggestion.id, 'SKIP')}
                >
                  넘기기
                </Button>
                <Button
                  size="sm"
                  disabled={busy}
                  onClick={() => onDecide(suggestion.id, 'APPLY')}
                >
                  반영하기
                </Button>
              </span>
            )}
          </li>
        ))}
      </ol>
      {!owner && (
        <p className="text-xs text-muted-foreground">
          제안은 올린 사람이 반영하거나 넘겨요.
        </p>
      )}
    </div>
  )
}
