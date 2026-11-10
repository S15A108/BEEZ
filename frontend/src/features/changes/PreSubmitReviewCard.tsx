import type {
  PreSubmitReview,
  SubmittedReviewSuggestion,
} from '@/api/contracts/common'

// 제안 상세 개요의 '올리기 전 비비 검토'(S15P31A108-218 수정안, CHG-A01 pre_submit_review). 지금 리비전을 올릴 때의 상태다.
// 팀장은 작성 중 제안과 그 검토를 읽지 못해 넘긴 의견과 처리하지 않은 수정안을 여기서 본다. 초안 없이 올린 리비전에는 없다.
const statusText = {
  NOT_REQUESTED: '비비 검토를 받지 않고 올렸어요.',
  COMPLETED: '비비 검토를 마치고 올렸어요.',
  FAILED: '비비 검토가 실패한 채로 올렸어요.',
  CANCELLED: '비비 검토가 끝나기 전에 올려 검토가 취소됐어요.',
} as const

export function PreSubmitReviewCard({
  review,
  className,
}: {
  review?: PreSubmitReview
  className?: string
}) {
  return (
    <section aria-label="올리기 전 비비 검토" className={className}>
      <h4 className="text-[15px] font-bold">올리기 전 비비 검토</h4>
      {!review ? (
        <p className="text-[13px] text-muted-foreground">올리기 전 검토 없음</p>
      ) : (
        <div className="grid gap-1.5 text-[13px] leading-[19px]">
          <p>
            {statusText[review.status]}
            {review.edited_after_review && ' 검토 뒤 글을 고쳤어요.'}
            {review.acknowledged && ' 제안자가 이 상태를 확인하고 올렸어요.'}
          </p>
          <Suggestions
            title="처리하지 않은 수정안"
            items={review.pending_suggestions}
          />
          <Suggestions
            title="넘긴 수정안·의견"
            items={review.skipped_suggestions}
          />
        </div>
      )}
    </section>
  )
}

function Suggestions({
  title,
  items,
}: {
  title: string
  items: SubmittedReviewSuggestion[]
}) {
  if (!items.length) return null
  return (
    <div className="grid gap-1">
      <p className="text-xs font-semibold text-muted-foreground">
        {title} {items.length}
      </p>
      <ul className="grid gap-1">
        {items.map((item, index) => (
          <li key={index} className="rounded-lg bg-muted/40 px-2.5 py-1.5">
            {item.design_item_key && (
              <strong className="mr-1">{item.design_item_key}</strong>
            )}
            {item.explanation}
            {item.type === 'PATCH' && item.replacement_text && (
              <span className="mt-0.5 block text-xs text-muted-foreground">
                비비 수정안: {item.replacement_text}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
