import type { ReactNode } from 'react'
import type { MeetingSummary } from '@/api/contracts/common'
import { DocumentChanges } from './DocumentChanges'

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
      <DocumentChanges
        changes={summary.document_changes}
        documentTitleOf={documentTitleOf}
        onShowEvidence={onShowEvidence}
      />
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
      {summary.generation.unresolved.length > 0 && (
        <section
          aria-label="반영하지 못한 내용"
          className="grid gap-1 rounded-lg bg-[#fff8e0] p-3"
        >
          <h5 className="text-[13px] font-bold">일부는 반영하지 못했어요</h5>
          <ul className="list-disc pl-5 text-[13px] leading-5">
            {summary.generation.unresolved.map((text, index) => (
              <li key={index}>{text}</li>
            ))}
          </ul>
        </section>
      )}
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
