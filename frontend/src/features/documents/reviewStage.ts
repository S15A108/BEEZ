import type {
  DocumentBeeReview,
  DocumentWorkingCopy,
} from '@/api/contracts/common'

// 지금 작성 건이 어느 단계인지. 검토가 최신 본문과 맞을 때만 그 검토를 쓴다.
// stale은 본문은 그대로인데 다른 문서가 승인돼 참고 승인본이 바뀐 경우처럼, 서버가 그 검토를 더 쓸 수 없다고 알린 상태다.
// 이때는 자동으로 다시 검토하지 않고(사용자가 눌러야 한다) 1단계로 돌아가 다시 받게 한다.
export function reviewStage(copy: DocumentWorkingCopy) {
  const review =
    copy.latest_review?.resolved_revision === copy.revision
      ? copy.latest_review
      : undefined
  if (copy.status === 'APPROVED')
    return { step: 4, label: '승인 완료', review, stale: false }
  if (copy.status === 'SUBMITTED')
    return { step: 3, label: '팀장 승인 대기', review, stale: false }
  if (review && copy.review_stale)
    return { step: 1, label: '다시 검토 필요', review, stale: true }
  if (!review)
    return {
      step: 1,
      // 올리거나 고치면 비비 검토가 자동으로 시작된다(DOC-A16).
      label: copy.status === 'REJECTED' ? '반려됨' : '비비 검토 중',
      review,
      stale: false,
    }
  if (review.suggestions.some((item) => item.decision === 'PENDING'))
    return { step: 2, label: '수정 제안 확인', review, stale: false }
  return { step: 3, label: '팀장 검토 요청 전', review, stale: false }
}

// 비비 검토 단계 하나가 어떻게 끝났는지(제안 확인 화면과 팀장 상세가 같은 문구를 쓴다).
export function stepOutcome(step: DocumentBeeReview['steps'][number]) {
  if (step.status === 'COMPLETE') return '검토함'
  return step.reason === 'SAME_KIND'
    ? '같은 문서라 건너뜀'
    : '승인본이 없어 건너뜀'
}
