import type { ReactNode } from 'react'
import type { MeetingSummary } from '@/api/contracts/common'
import { cn } from '@/lib/utils'

const line = 'border-[#e4e0d4]'

// MTG-A11·A12 비비 회의 정리(충돌 · 결정 · 바뀔 문서 · 할 일). 문서와 설계는 직접 바뀌지 않고, 회의를 끝내면(MTG-A13)
// 이 정리로 변경 제안이 만들어진다. 바뀔 문서는 정리가 정한 줄의 지금 문장과 제안에 들어갈 문장을 나란히 보인다.
export function SummaryCard({
  summary,
  documentTitleOf,
  nameOfMember,
  onShowEvidence,
}: {
  summary: MeetingSummary
  documentTitleOf: (documentId: string) => string
  nameOfMember: (memberId: string) => string
  onShowEvidence?: (entryId: string) => void
}) {
  return (
    <div className="grid gap-3 text-sm">
      {summary.previous_summary_id && (
        <p className="text-xs text-muted-foreground">
          <strong className="mr-1.5 rounded-full bg-[#f0eee8] px-2 py-0.5 text-[#57534e]">
            수정본
          </strong>
          {summary.feedback
            ? `고칠 점: ${summary.feedback}`
            : '고칠 점을 반영해 다시 정리했어요.'}
        </p>
      )}
      <Section
        title="충돌"
        empty="비비가 찾은 충돌이 없어요."
        items={summary.conflicts}
      >
        {(item) => item}
      </Section>
      <Section
        title="결정"
        empty="정해진 내용이 없어요."
        items={summary.decisions}
      >
        {(item) => (
          <>
            {item.text}
            {item.decided_by_member_id && (
              <span className="ml-1.5 text-xs text-muted-foreground">
                {nameOfMember(item.decided_by_member_id)}이 정함
              </span>
            )}
          </>
        )}
      </Section>
      <section aria-label="바뀔 문서" className="grid gap-2">
        <h5 className="text-[13px] font-bold">바뀔 문서</h5>
        {summary.document_changes.length ? (
          <ul className="grid gap-2.5">
            {summary.document_changes.map((change, index) => {
              const { start_line: start, end_line: end } = change.locator
              return (
                <li
                  key={`${change.document_id}:${start}:${index}`}
                  className={cn(
                    'grid gap-2 rounded-xl border bg-white p-3',
                    line,
                  )}
                >
                  <p className="text-xs font-semibold">
                    {documentTitleOf(change.document_id)} ·{' '}
                    {start === end ? `${start}행` : `${start}~${end}행`}
                  </p>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <Side label="지금" text={change.before} />
                    <Side label="바뀐 뒤" text={change.after} changed />
                  </div>
                  {change.evidence_entry_ids.length > 0 && (
                    <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
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
                    </p>
                  )}
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
      <Section
        title="할 일"
        empty="정해진 할 일이 없어요."
        items={summary.action_items}
      >
        {(item) => (
          <>
            {item.text}
            {item.member_id && (
              <span className="ml-1.5 text-xs text-muted-foreground">
                {nameOfMember(item.member_id)}
              </span>
            )}
          </>
        )}
      </Section>
    </div>
  )
}

function Section<T>({
  title,
  empty,
  items,
  children,
}: {
  title: string
  empty: string
  items: T[]
  children: (item: T) => ReactNode
}) {
  return (
    <section aria-label={title} className="grid gap-1.5">
      <h5 className="text-[13px] font-bold">{title}</h5>
      {items.length ? (
        <ul className="grid list-disc gap-1 pl-[18px] leading-6">
          {items.map((item, index) => (
            <li key={index}>{children(item)}</li>
          ))}
        </ul>
      ) : (
        <p className="text-[13px] text-muted-foreground">{empty}</p>
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
