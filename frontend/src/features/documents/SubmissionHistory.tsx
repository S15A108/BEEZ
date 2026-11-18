import type { DocumentReviewSubmission } from '@/api/contracts/common'
import { formatDate } from '@/lib/formatDate'

const LABELS: Record<DocumentReviewSubmission['status'], string> = {
  SUBMITTED: '승인 대기',
  APPROVED: '승인',
  REJECTED: '반려',
  // 취소는 팀장 결정이 아니라 올린 사람이 거둔 것이다. 승인·반려와 섞어 쓰지 않는다.
  WITHDRAWN: '취소됨',
}

// 이 문서의 지난 검토 요청. 아직 결정 전인 요청은 위 단계 표시가 이미 보여 주므로 끝난 요청만 적는다.
export function SubmissionHistory({
  submissions,
  nameOf,
}: {
  submissions: DocumentReviewSubmission[]
  nameOf: (accountId?: string) => string
}) {
  const finished = submissions.filter((item) => item.status !== 'SUBMITTED')
  if (!finished.length) return null
  return (
    <div className="grid gap-1 text-xs text-muted-foreground">
      <strong className="text-foreground">지난 검토 요청</strong>
      <ol aria-label="지난 검토 요청" className="grid gap-0.5">
        {finished.map((item) => (
          <li key={item.id}>
            {formatDate(item.submitted_at)} · {nameOf(item.submitted_by)} 요청 ·{' '}
            <strong className="text-foreground">{LABELS[item.status]}</strong>
          </li>
        ))}
      </ol>
    </div>
  )
}
