import type { MeetingDocumentChange } from '@/api/contracts/common'
import { cn } from '@/lib/utils'

const line = 'border-[#e4e0d4]'

// 비비 정리의 바뀔 문서(MTG-A11). 회의를 끝내면(MTG-A13) 이 변경이 그대로 변경 제안에 들어간다.
// 비비 정리 단계에서는 '문서 · 몇 행'과 근거만 한 줄씩(compact), 수정 초안 단계에서는 지금 문장과 바뀐 뒤 문장을 나란히 보인다.
export function DocumentChanges({
  changes,
  compact,
  documentTitleOf,
  onShowEvidence,
}: {
  changes: MeetingDocumentChange[]
  compact?: boolean
  documentTitleOf: (documentId: string) => string
  onShowEvidence?: (entryId: string) => void
}) {
  return (
    <section aria-label="바뀔 문서" className="grid gap-2">
      <h5 className="text-[13px] font-bold">바뀔 문서</h5>
      {changes.length ? (
        <ul
          className={
            compact
              ? cn('grid rounded-xl border bg-white', line)
              : 'grid gap-2.5'
          }
        >
          {changes.map((change, index) => {
            const { start_line: start, end_line: end } = change.locator
            const where = `${documentTitleOf(change.document_id)} · ${start === end ? `${start}행` : `${start}~${end}행`}`
            const evidence = change.evidence_entry_ids.length > 0 && (
              <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                근거 발언
                {change.evidence_entry_ids.map((entryId, number) => (
                  <button
                    key={entryId}
                    type="button"
                    className="rounded-full border border-[#d6d1c4] bg-white px-2 py-0.5 text-xs font-semibold text-[#3f3a2e] hover:bg-[#fbfaf6]"
                    onClick={() => onShowEvidence?.(entryId)}
                  >
                    대화 {number + 1}
                  </button>
                ))}
              </span>
            )
            return compact ? (
              <li
                key={`${change.document_id}:${start}:${index}`}
                className={cn(
                  'flex flex-wrap items-center gap-x-2.5 gap-y-1 border-t px-3.5 py-2.5 text-[13px] first:border-t-0',
                  line,
                )}
              >
                <span className="rounded-full bg-[#fdf0c8] px-2 py-0.5 text-xs font-semibold text-[#6e5200]">
                  문서
                </span>
                <span className="mr-auto font-semibold">{where}</span>
                {evidence}
              </li>
            ) : (
              <li
                key={`${change.document_id}:${start}:${index}`}
                className={cn(
                  'grid gap-2 rounded-xl border bg-white p-3',
                  line,
                )}
              >
                <p className="text-xs font-semibold">{where}</p>
                <div className="grid gap-2 sm:grid-cols-2">
                  <Side label="지금" text={change.before} />
                  <Side label="바뀐 뒤" text={change.after} changed />
                </div>
                {evidence}
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="text-[13px] text-muted-foreground">
          바뀔 문서가 없어요. 회의를 끝내도 변경 제안은 만들어지지 않아요.
        </p>
      )}
    </section>
  )
}

function Side({
  label,
  text,
  changed,
}: {
  label: string
  text: string
  changed?: boolean
}) {
  return (
    <div
      className={cn(
        'min-w-0 rounded-lg px-2.5 py-2',
        changed ? 'bg-[#fff8e0]' : 'bg-[#f7f5ef]',
      )}
    >
      <p className="mb-0.5 text-xs font-semibold text-muted-foreground">
        {label}
      </p>
      <p className="text-[13px] leading-5 break-words whitespace-pre-wrap">
        {text.replace(/\n$/, '') || '(비어 있음)'}
      </p>
    </div>
  )
}
