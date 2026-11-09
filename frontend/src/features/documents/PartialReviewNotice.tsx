import type { DocumentBeeReview } from '@/api/contracts/common'
import { statusLabel } from '@/lib/display'
import { stepOutcome } from './reviewStage'

// 승인된 비교 문서가 모자라 일부 단계를 비교하지 못한 검토(PARTIAL)를 팀장이 알아보게 한다.
// 이 검토는 비비의 완료일 뿐 팀장 승인이 아니고, 승인 여부는 팀장이 정한다.
export function PartialReviewNotice({ review }: { review: DocumentBeeReview }) {
  if (review.review_status !== 'PARTIAL') return null
  const skipped = review.steps.filter(
    (step) => step.status !== 'COMPLETE' && step.reason !== 'SAME_KIND',
  )
  return (
    <div
      role="note"
      className="grid gap-1 rounded-lg border border-[#c99e1e]/45 bg-primary/10 px-3 py-2 text-sm"
    >
      <strong>일부 문서와는 비교하지 못한 검토예요</strong>
      <ul aria-label="비교하지 못한 단계" className="grid gap-0.5 text-xs">
        {skipped.map((step) => (
          <li key={step.step}>
            {statusLabel(step.step)} · {stepOutcome(step)}
          </li>
        ))}
      </ul>
      <span className="text-xs text-muted-foreground">
        비비 검토가 끝났다는 뜻이고, 승인은 팀장이 직접 정해요.
      </span>
    </div>
  )
}
