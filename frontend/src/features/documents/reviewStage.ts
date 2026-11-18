import type {
  DocumentBeeReview,
  DocumentWorkingCopy,
} from '@/api/contracts/common'

// 지금 작성 건이 어느 단계인지. 검토가 최신 본문과 맞을 때만 그 검토를 쓴다.
// stale은 본문은 그대로인데 다른 문서가 승인돼 참고 승인본이 바뀐 경우처럼, 서버가 그 검토를 더 쓸 수 없다고 알린 상태다.
// 이때는 자동으로 다시 검토하지 않고(사용자가 눌러야 한다) 1단계로 돌아가 다시 받게 한다.
// failed는 지금 본문의 마지막 비비 검토 요청이 실패한 상태다(서버의 bee_review_request, S15P31A108-382).
// 공용 문서함 카드가 이 상태를 '비비 검토 중'으로 보여 끝나지 않을 검토를 기다리게 했다(S15P31A108-383).
export function reviewStage(copy: DocumentWorkingCopy) {
  const review =
    copy.latest_review?.resolved_revision === copy.revision
      ? copy.latest_review
      : undefined
  const stage = (step: number, label: string, flags: Partial<Flags> = {}) => ({
    step,
    label,
    review,
    stale: false,
    failed: false,
    ...flags,
  })
  if (copy.status === 'APPROVED') return stage(4, '승인 완료')
  if (copy.status === 'SUBMITTED') return stage(3, '팀장 승인 대기')
  if (review && copy.review_stale)
    return stage(1, '다시 검토 필요', { stale: true })
  // 반려 뒤 다시 받던 검토가 실패해도 반려됐다는 사실은 그대로 보인다(실패 표시만 더한다).
  if (!review && copy.bee_review_request?.status === 'FAILED')
    return stage(1, copy.status === 'REJECTED' ? '반려됨' : '검토 실패', {
      failed: true,
    })
  if (!review)
    // 올리거나 고치면 비비 검토가 자동으로 시작된다(DOC-A16).
    return stage(1, copy.status === 'REJECTED' ? '반려됨' : '비비 검토 중')
  if (review.suggestions.some((item) => item.decision === 'PENDING'))
    return stage(2, '수정 제안 확인')
  return stage(3, '팀장 검토 요청 전')
}

type Flags = { stale: boolean; failed: boolean }

// 비비 검토 단계 하나가 어떻게 끝났는지(제안 확인 화면과 팀장 상세가 같은 문구를 쓴다).
export function stepOutcome(step: DocumentBeeReview['steps'][number]) {
  if (step.status === 'COMPLETE') return '검토함'
  return step.reason === 'SAME_KIND'
    ? '같은 문서라 건너뜀'
    : '승인본이 없어 건너뜀'
}
