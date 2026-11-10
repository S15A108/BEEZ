import type { Draft, DraftReview, DraftReviewStep } from './schemas'

// 올리기 전 비비 검토(CHG-A19)의 상태 판단. 화면 문구와 올리기 전 확인(CHG-A01 review_acknowledged)이 같은 기준을 쓴다.

export const stepLabel: Record<DraftReviewStep['key'], string> = {
  READ_CHANGES: '바뀐 곳 읽기',
  FIND_RELATED: '관련 작업·검증 찾기',
  AI_CANDIDATES: 'AI 영향 후보 찾기',
  GAPS_CONFLICTS: '빠진 부분·충돌 정리',
}

export const pendingPatches = (review?: DraftReview) =>
  review?.suggestions.filter(
    (item) => item.type === 'PATCH' && item.decision === 'PENDING',
  ) ?? []

// 검토 뒤 글을 고쳤는지. 수정안 반영은 resolved_revision도 같이 올라가므로 고친 것으로 보지 않는다.
export const editedAfterReview = (draft: Draft) =>
  !!draft.latest_review &&
  draft.latest_review.resolved_revision !== draft.revision

// 지금 글에 맞는 검토가 있어 새로 요청하지 않아도 되는지(진행 중이거나 고치지 않은 완료·실패).
export const hasCurrentReview = (draft: Draft) =>
  !!draft.latest_review &&
  draft.latest_review.status !== 'CANCELLED' &&
  (draft.latest_review.status === 'RUNNING' || !editedAfterReview(draft))

export const failureText = (review: DraftReview) =>
  review.status === 'CANCELLED'
    ? '검토가 끝나기 전에 취소됐어요.'
    : review.error_code === 'review_timeout'
      ? '10분 안에 끝나지 않아 검토를 멈췄어요.'
      : '검토하는 중에 문제가 생겼어요.'

// 올리기 전에 사용자가 확인해야 할 상태. 비어 있으면 확인 없이 올린다(CHG-A01 처리 규칙).
export function acknowledgementReasons(draft: Draft): string[] {
  const review = draft.latest_review
  if (!review) return ['비비 검토를 받지 않았어요.']
  const reasons: string[] = []
  if (review.status === 'RUNNING')
    reasons.push('비비가 아직 검토하고 있어요. 올리면 이 검토는 취소돼요.')
  else if (review.status !== 'COMPLETED')
    reasons.push(`최신 비비 검토가 끝나지 못했어요. ${failureText(review)}`)
  if (editedAfterReview(draft)) reasons.push('검토를 받은 뒤 글을 고쳤어요.')
  const pending = pendingPatches(review).length
  if (pending) reasons.push(`처리하지 않은 수정안이 ${pending}개 있어요.`)
  return reasons
}

// 검토 단계 오른쪽에 보이는 결과. 평가하지 못한 단계를 0건으로 보이지 않는다.
export function stepOutcome(step: DraftReviewStep) {
  if (step.status === 'NOT_EVALUATED') return '확인하지 못함'
  if (step.status === 'FAILED') return '실패'
  if (step.status !== 'DONE') return ''
  if (step.count === undefined) return '마침'
  return {
    READ_CHANGES: `바뀐 곳 ${step.count}개`,
    FIND_RELATED: `관련 ${step.count}건`,
    AI_CANDIDATES: `후보 ${step.count}건`,
    GAPS_CONFLICTS: `수정안·의견 ${step.count}개`,
  }[step.key]
}
