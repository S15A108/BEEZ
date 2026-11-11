import { REVIEW_POLL_MS } from '@/lib/pollIntervals'
import { useEffect, useReducer, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, LoaderCircle } from 'lucide-react'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getErrorCodeMessage,
} from '@/api/error'
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
import { reviewStage, stepOutcome } from './reviewStage'
import { CancelSubmission } from './CancelSubmission'
import { DirectRequest } from './DirectRequest'
import { DiscardDocument } from './DiscardDocument'
import { WorkingCopyEditor } from './WorkingCopyEditor'
import { PartialReviewNotice } from './PartialReviewNotice'
import { SuggestionCard } from './SuggestionCard'
import { SubmissionHistory } from './SubmissionHistory'
import { membersQuery } from '@/features/members/membersQuery'

const STEPS = ['파일 등록', '비비 검토', '수정 제안 반영', '팀장 승인'] as const
const REVIEW_STEPS = [
  '문서 읽기',
  '승인된 API 명세서와 비교',
  'ERD와 맞춰 보기',
  '요구사항과 비교',
  '수정 제안 정리',
]

// 다른 문서가 승인돼 참고 승인본이 바뀌면 반영·넘기기·검토 요청이 409 review_stale로 막힌다. 이 경우는 작업본 응답의
// review_stale(S15P31A108-362)이 처음부터 알려 준다. 그 판정에 없는 경우(앞 제안을 반영해 다음 제안의 원문이 사라짐 등)는
// 409를 받은 검토 id를 기억해 창을 다시 열어도 무효 안내와 '비비 검토 다시 받기'를 보인다.
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
  onDiscarded,
  onReupload,
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
  /** 등록을 취소하면 부르는 쪽이 목록으로 돌아가고 이 알림을 띄운다(DOC-A25). 없으면 onClose 로 닫는다. */
  onDiscarded?: (message: string) => void
  /**
   * 비비 검토가 실패했을 때 '파일 등록' 단계로 돌아가는 길. 부르는 쪽이 처음 등록 화면을 같은 문서의 '파일 다시 올리기'로 열고,
   * 지금 등록된 파일을 선택된 채로 보인다(S15P31A108-382). 없으면 단계 표시줄은 보기만 한다.
   */
  onReupload?: (current: {
    fileName: string
    content: string
    revision: number
  }) => void
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
        reviewStage(data).step === 1 &&
        !reviewStage(data).stale &&
        // 실패한 검토는 기다려도 끝나지 않는다. 올린 사람이 다시 시도하거나 고칠 때까지 조회를 멈춘다(S15P31A108-382).
        data.bee_review_request?.status !== 'FAILED')
        ? REVIEW_POLL_MS
        : false,
  })
  const current = copy.data
  const currentStage = current && reviewStage(current)
  // 서버가 기억하는 이 리비전의 검토 실패(새로고침·다른 팀원 화면에서도 보인다). 고치면 비워진다.
  const serverFailed =
    current?.bee_review_request?.status === 'FAILED'
      ? (current.bee_review_request.error_code ?? 'review_unavailable')
      : undefined
  const reviewKey =
    current &&
    !locked &&
    current.owner_account_id === session.data?.id &&
    currentStage?.step === 1 &&
    !currentStage.stale &&
    // 실패했다면 화면을 열 때마다 자동으로 다시 부르지 않는다(비용). 다시 검토하기를 눌러야 한다.
    !serverFailed &&
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
  // 지금 이 문서의 비비 검토가 실패한 상태인지. 다시 부르는 중이거나 방금 성공했으면 아니다.
  const failed =
    !bee.isPending &&
    !bee.isSuccess &&
    ((!!bee.error && !running) || !!serverFailed)
  const failedCode =
    bee.error && !running ? getApiErrorCode(bee.error) : serverFailed
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
  // 서버가 준 오류 코드별 안내(경합·잠김·AI 장애). 일반 오류 띠 대신 보인다.
  const [failure, setFailure] = useState('')
  // '비비에게 직접 요청하기'에 쓰던 글. 검토가 무효가 돼 입력창이 사라져도 남겨, 새 검토가 나오면 이어서 보낸다.
  const [requestDraft, setRequestDraft] = useState('')
  // 다시 검토를 시작하면 앞선 실패 안내는 지운다(검토가 끝나도 이전 오류가 남지 않게).
  const reReview = (revision: number) => {
    setFailure('')
    bee.mutate(revision)
  }
  const markStale = (reviewId: string) => {
    staleReviews.add(reviewId)
    rerender()
  }
  const run = (
    operation: () => Promise<unknown>,
    message: string,
    done?: () => void,
  ) => {
    setFailure('')
    return action
      .run(operation, message)
      .then(done)
      .catch((cause: unknown) => {
        const code = getApiErrorCode(cause)
        const reviewId = currentStage?.review?.id
        if (code === 'review_stale') {
          // 무효 카드가 안내하므로 일반 오류 띠는 지운다.
          action.reset()
          if (reviewId) {
            staleReviews.add(reviewId)
            rerender()
          }
        }
        // review_stale은 아래 '이 검토는 더 쓸 수 없어요' 카드가 안내한다. 띠로 또 띄우면 원인을 단정하는 문구가 겹친다.
        setFailure(
          code === 'submission_locked'
            ? `${getApiErrorMessage(cause)} 먼저 검토 요청을 취소한 뒤 고쳐 주세요.`
            : code && code !== 'review_stale'
              ? getApiErrorMessage(cause)
              : '',
        )
      })
  }
  const reviewStale =
    !!currentStage?.review &&
    current?.status !== 'SUBMITTED' &&
    (currentStage.stale || staleReviews.has(currentStage.review.id))
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
      {(failure || action.error) && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {failure || action.error}
        </p>
      )}
      <AsyncState query={copy}>
        {(data) => {
          const stage = reviewStage(data)
          const owner = !locked && data.owner_account_id === session.data?.id
          const rejected = data.submissions.find(
            (item) => item.status === 'REJECTED',
          )
          // 올린 사람만, 팀장이 결정하기 전에 거둘 수 있다(DOC-A24).
          const cancelable =
            !locked && !!pending && pending.submitted_by === session.data?.id
          const cancel = pending && (
            <CancelSubmission
              busy={action.busy}
              onConfirm={() =>
                run(
                  () =>
                    documentsApi.cancelSubmission(
                      projectId,
                      documentId,
                      pending.id,
                    ),
                  '검토 요청을 취소했어요. 비비가 다시 검토한 뒤 고치거나 다시 요청할 수 있어요.',
                )
              }
            />
          )
          // 기준 v1 전에 올린 사람만, 검토가 실패한 1단계에서 앞 단계로 돌아가 고칠 수 있다.
          const canGoBack =
            owner && stage.step === 1 && !stage.stale && failed && !!onReupload
          // 등록 취소(DOC-A25)는 올린 사람과 팀장이, 승인된 적 없고 팀장 검토를 요청하지 않은 문서에만 한다.
          const discard = !locked &&
            (owner || isLeader) &&
            data.status !== 'SUBMITTED' &&
            data.status !== 'APPROVED' && (
              <DiscardDocument
                busy={action.busy}
                onConfirm={() =>
                  run(
                    () => documentsApi.discardDocument(projectId, documentId),
                    '문서 등록을 취소했어요. 같은 종류의 문서를 새로 올릴 수 있어요.',
                    () =>
                      (onDiscarded ?? (() => onClose()))(
                        '문서 등록을 취소했어요. 같은 종류의 문서를 새로 올릴 수 있어요.',
                      ),
                  )
                }
              />
            )
          // 앞 단계로 돌아가 고친 내용을 저장한다. 리비전이 오르면 이전 실패는 지우고 비비 검토가 다시 시작된다.
          const saveContent = async (content: string) => {
            await action.run(
              () =>
                documentsApi.updateWorkingCopy(projectId, documentId, {
                  expected_revision: data.revision,
                  content,
                  file_name: data.file_name,
                }),
              '고친 내용을 저장했어요. 비비가 다시 검토해요.',
            )
            bee.reset()
            setEditing(false)
          }
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
                    {index === 0 && canGoBack ? (
                      // 비비 검토가 실패하면 처음 등록 화면으로 돌아가 파일을 다시 올릴 수 있다.
                      <button
                        type="button"
                        aria-label="이전 단계로 돌아가 파일 다시 올리기"
                        className="underline underline-offset-2"
                        onClick={() =>
                          onReupload?.({
                            fileName: data.file_name,
                            content: data.content,
                            revision: data.revision,
                          })
                        }
                      >
                        ← {step}
                      </button>
                    ) : (
                      <>
                        {index < stage.step ? '✓ ' : ''}
                        {step}
                      </>
                    )}
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
              {stage.step === 1 && !stage.stale && (
                <div className="grid gap-3">
                  <MarkdownView source={data.content} className="max-h-72" />
                  {failed ? (
                    <div
                      role="alert"
                      className="grid gap-2 rounded-xl border border-destructive/40 bg-destructive/5 p-4 text-sm"
                    >
                      <strong>
                        {failedCode === 'review_timeout'
                          ? '비비 검토 시간이 초과됐어요'
                          : failedCode === 'review_unavailable'
                            ? '지금은 비비를 부를 수 없어요'
                            : '비비 검토를 마치지 못했어요'}
                      </strong>
                      <span>
                        {bee.error && !running
                          ? getApiErrorMessage(bee.error)
                          : (getErrorCodeMessage(failedCode) ??
                            '비비 검토를 마치지 못했어요. 다시 시도해 주세요.')}
                      </span>
                      {owner ? (
                        <>
                          <span className="text-xs text-muted-foreground">
                            위 단계의 &lsquo;파일 등록&rsquo;을 눌러 파일을 다시
                            올리거나, 그대로 다시 검토하거나, 등록을 취소할 수
                            있어요.
                          </span>
                          <div className="flex flex-wrap gap-2">
                            <Button
                              variant="outline"
                              disabled={bee.isPending || action.busy}
                              onClick={() => reReview(data.revision)}
                            >
                              다시 검토하기
                            </Button>
                            {discard}
                          </div>
                        </>
                      ) : (
                        <>
                          <span className="text-xs text-muted-foreground">
                            올린 사람이 다시 검토하거나 문서를 고쳐야 해요.
                          </span>
                          {discard}
                        </>
                      )}
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
                  <strong>이 검토는 더 쓸 수 없어요</strong>
                  {/* 서버는 같은 review_stale을 본문·참고 승인본이 바뀐 경우, 더 새로운 검토가 생긴 경우,
                      제안한 원문이 본문에서 더 찾아지지 않는 경우(앞 제안을 반영한 뒤)에 모두 준다. 원인을 단정하지 않는다. */}
                  <span className="text-muted-foreground">
                    본문이나 참고하던 승인 문서가 바뀌었거나, 앞서 반영한 제안
                    때문에 제안한 부분이 달라졌을 수 있어요. 비비 검토를 다시
                    받으면 지금 본문과 승인본을 기준으로 수정 제안을 새로
                    받아요.
                  </span>
                  {requestDraft.trim() && (
                    <span className="text-xs text-muted-foreground">
                      비비에게 쓰던 요청 글은 지우지 않았어요. 새 검토가 나오면
                      이어서 보낼 수 있어요.
                    </span>
                  )}
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
                      onClick={() => reReview(data.revision)}
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
                  projectId={projectId}
                  documentId={documentId}
                  review={stage.review}
                  revision={data.revision}
                  owner={owner}
                  busy={action.busy}
                  onStale={() => markStale(stage.review!.id)}
                  onDecide={(suggestionId, decision, replacementText) =>
                    run(
                      () =>
                        documentsApi.decideSuggestion(
                          projectId,
                          documentId,
                          stage.review!.id,
                          suggestionId,
                          {
                            expected_revision: data.revision,
                            decision,
                            ...(replacementText !== undefined
                              ? { replacement_text: replacementText }
                              : {}),
                          },
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
                      {stage.review.suggestions.some(
                        (item) => item.decision === 'APPLIED',
                      )
                        ? '비비 제안을 반영한 최종본이에요. 팀장이 보게 될 화면과 같아요.'
                        : '반영한 제안 없이 지금 본문 그대로예요. 팀장이 보게 될 화면과 같아요.'}
                    </p>
                    <ChangeHistory
                      projectId={projectId}
                      documentId={documentId}
                      review={stage.review}
                      revision={data.revision}
                      onStale={() => markStale(stage.review!.id)}
                    />
                    <ReviewNotes
                      projectId={projectId}
                      documentId={documentId}
                      review={stage.review}
                      revision={data.revision}
                      owner={owner}
                      busy={action.busy}
                      onStale={() => markStale(stage.review!.id)}
                    />
                    {editing ? (
                      <WorkingCopyEditor
                        content={data.content}
                        onSave={saveContent}
                      />
                    ) : (
                      <MarkdownView
                        source={data.content}
                        className="max-h-72"
                      />
                    )}
                    {owner && !editing && (
                      <DirectRequest
                        projectId={projectId}
                        documentId={documentId}
                        reviewId={stage.review.id}
                        revision={data.revision}
                        text={requestDraft}
                        onTextChange={setRequestDraft}
                        onStale={() => markStale(stage.review!.id)}
                      />
                    )}
                    {owner && !editing && (
                      <div className="flex flex-wrap justify-end gap-2">
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
                          {submission.review.review_status !== 'PARTIAL' &&
                            submission.review.unavailable_references.length >
                              0 &&
                            ` · 비교하지 못한 문서: ${submission.review.unavailable_references.map(statusLabel).join(', ')}`}
                        </p>
                        <PartialReviewNotice review={submission.review} />
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
                        {cancelable && cancel}
                      </div>
                    )}
                  </AsyncState>
                ) : (
                  <div className="grid gap-2">
                    <p className="text-sm text-muted-foreground">
                      팀장 승인을 기다리고 있어요. 결과는 이 화면에서 확인할 수
                      있어요.
                    </p>
                    {cancelable && cancel}
                  </div>
                ))}
              <SubmissionHistory
                submissions={data.submissions}
                nameOf={nameOf}
              />
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
// 의견에 대해서도 비비와 대화할 수 있어야 하므로(DOC-A22) 수정 제안 단계와 같은 카드로 보여 준다.
function ReviewNotes({
  projectId,
  documentId,
  review,
  revision,
  owner,
  busy,
  onStale,
}: {
  projectId: string
  documentId: string
  review: DocumentBeeReview
  revision: number
  owner: boolean
  busy: boolean
  onStale: () => void
}) {
  const comments = review.suggestions.filter((item) => item.type === 'COMMENT')
  if (!comments.length && !review.unavailable_references.length) return null
  return (
    <div className="grid gap-2 rounded-xl border p-3 text-sm">
      {comments.length > 0 && (
        <>
          <strong>비비 의견 {comments.length}개</strong>
          <ol aria-label="비비 의견" className="grid gap-2">
            {comments.map((comment, index) => (
              <SuggestionCard
                key={comment.id}
                projectId={projectId}
                documentId={documentId}
                reviewId={review.id}
                revision={revision}
                suggestion={comment}
                index={index}
                owner={owner}
                stale={false}
                busy={busy}
                onDecide={() => undefined}
                onStale={onStale}
              />
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

// 최종본 확인 단계에서도 비비 제안을 어떻게 처리했는지(반영·넘김)와 고친 내용을 계속 볼 수 있게 한다.
// 제안을 모두 처리하면 제안 목록이 사라져 무엇이 어떻게 바뀌었는지 알 수 없었다(S15P31A108-382).
function ChangeHistory({
  projectId,
  documentId,
  review,
  revision,
  onStale,
}: {
  projectId: string
  documentId: string
  review: DocumentBeeReview
  revision: number
  onStale: () => void
}) {
  const patches = review.suggestions.filter((item) => item.type === 'PATCH')
  if (!patches.length) return null
  const applied = patches.filter((item) => item.decision === 'APPLIED').length
  return (
    <details className="rounded-xl border p-3 text-sm">
      <summary className="cursor-pointer font-semibold">
        비비 제안 처리 내역 · 반영 {applied}개 · 넘김 {patches.length - applied}
        개
      </summary>
      <ol aria-label="비비 제안 처리 내역" className="mt-2 grid gap-2">
        {patches.map((suggestion, index) => (
          <SuggestionCard
            key={suggestion.id}
            projectId={projectId}
            documentId={documentId}
            reviewId={review.id}
            revision={revision}
            suggestion={suggestion}
            index={index}
            owner={false}
            stale={false}
            busy={false}
            onDecide={() => undefined}
            onStale={onStale}
          />
        ))}
      </ol>
    </details>
  )
}

function Suggestions({
  projectId,
  documentId,
  review,
  revision,
  owner,
  busy,
  onDecide,
  onStale,
}: {
  projectId: string
  documentId: string
  review: DocumentBeeReview
  revision: number
  owner: boolean
  busy: boolean
  onDecide: (
    suggestionId: string,
    decision: 'APPLY' | 'SKIP',
    replacementText?: string,
  ) => void
  onStale: () => void
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
                {statusLabel(step.step)} · {stepOutcome(step)}
              </li>
            ))}
          </ol>
        )}
      </div>
      <ol className="grid gap-2">
        {review.suggestions.map((suggestion, index) => (
          <SuggestionCard
            key={suggestion.id}
            projectId={projectId}
            documentId={documentId}
            reviewId={review.id}
            revision={revision}
            suggestion={suggestion}
            index={index}
            owner={owner}
            stale={false}
            busy={busy}
            onDecide={onDecide}
            onStale={onStale}
          />
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
