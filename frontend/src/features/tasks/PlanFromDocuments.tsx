import { Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { documentTypeLabel, draftFieldLabel, type DraftFill } from './planDraft'
import type { PlanDraft } from './schemas'

// 작업 계획 문서 초안(TSK-A08, S15P31A108-385). 승인된 문서에서 계획 칸을 채우고 연결 후보를 보여 준다.
// 저장하지 않는다. 폼에 채우는 것까지만 하고, 등록은 사람이 확인한 뒤 기존 흐름으로 한다.

/** 창 머리줄 오른쪽에 두는 버튼. 받는 중이거나 같은 입력으로 이미 받았으면 비활성이다. */
export function DocumentDraftButton({
  canRequest,
  sameInput,
  disabled,
  onClick,
}: {
  canRequest: boolean
  sameInput: boolean
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <Button
      type="button"
      disabled={!canRequest || disabled}
      title={
        sameInput
          ? '같은 제목과 목표로 이미 받았어요. 바꾸면 다시 받을 수 있어요.'
          : undefined
      }
      onClick={onClick}
    >
      <Sparkles />
      문서로 초안 받기
    </Button>
  )
}

/** 초안을 받은 뒤 폼 위에 보이는 안내와 근거·연결 후보. 실패 안내도 여기서 보인다. */
export function DocumentDraftPanel({
  draft,
  fill,
  error,
  note,
  linkedIds,
  hasRefs,
  unlinkedTexts,
  disabled,
  onRetry,
  onUndo,
  onToggleLink,
  onAddUnlinked,
  onOpenDocument,
}: {
  draft: PlanDraft | null
  fill: DraftFill | null
  error?: { message: string; retry: boolean }
  /** 되돌린 뒤 알릴 말(직접 고친 칸을 그대로 둔 경우) */
  note?: string
  /** 지금 계획에 연결해 둔 설계 항목 */
  linkedIds: Set<string>
  hasRefs: boolean
  /** 이미 '연결하지 못한 내용'에 있는 문장 */
  unlinkedTexts: Set<string>
  disabled?: boolean
  onRetry: () => void
  onUndo: () => void
  onToggleLink: (
    candidate: PlanDraft['design_ref_candidates'][number],
    checked: boolean,
  ) => void
  onAddUnlinked: (text: string) => void
  /** 문서함에서 그 문서를 연다(창이 바뀌므로 작성 중인 내용은 사라진다) */
  onOpenDocument: (documentId: string) => void
}) {
  if (!draft && !error) return null
  const filled = fill?.fields.length ?? 0
  const missing = [
    ...(draft?.new_design_item_candidates.map((item) => ({
      text:
        typeof item.content.description === 'string'
          ? item.content.description
          : item.rationale,
      detail: item.rationale,
      source: item.evidence[0],
    })) ?? []),
    ...(draft?.unlinked.map((item) => ({
      text: item.text,
      detail: undefined,
      source: undefined,
    })) ?? []),
  ].filter(
    (entry, index, all) =>
      all.findIndex((other) => other.text === entry.text) === index,
  )
  return (
    <section aria-label="문서 초안" className="grid gap-3 md:col-span-2">
      {error && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          <span>{error.message}</span>
          {error.retry && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="shrink-0 text-foreground"
              disabled={disabled}
              onClick={onRetry}
            >
              다시 받기
            </Button>
          )}
        </div>
      )}
      {draft && (
        <>
          <div className="flex items-center justify-between gap-3 rounded-xl border border-primary/40 bg-primary/10 p-3 text-sm">
            <span>
              {draft.status === 'INSUFFICIENT'
                ? '문서에서 이 작업에 맞는 근거를 찾지 못했어요. 직접 적어 주세요.'
                : filled > 0
                  ? `문서 초안으로 빈 칸 ${filled}개를 채웠어요. 노란 칸을 확인하고 고쳐서 등록하세요. 등록하기 전에는 저장되지 않아요.`
                  : '이미 적은 칸은 그대로 두었어요. 아래 근거와 연결 후보를 참고하세요.'}
            </span>
            {filled > 0 && (
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="shrink-0"
                disabled={disabled}
                onClick={onUndo}
              >
                되돌리기
              </Button>
            )}
          </div>
          {note && filled === 0 && (
            <p className="text-xs text-muted-foreground">{note}</p>
          )}
          <div className="grid gap-3 md:grid-cols-3 md:items-start">
            <div className="grid gap-2 rounded-xl border bg-card p-4 text-sm">
              <h4 className="font-medium">근거 문서</h4>
              {draft.evidence.length === 0 && (
                <p className="text-xs text-muted-foreground">
                  원문에서 확인된 근거가 없어요.
                </p>
              )}
              {draft.evidence.map((item, index) => (
                <div
                  key={`${item.document_version_id}-${index}`}
                  className="border-l-[3px] border-primary pl-2.5"
                >
                  <p className="font-medium">
                    {documentTypeLabel[item.document_type]} v{item.version_no} ·{' '}
                    {item.location}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    “{item.quote}”
                  </p>
                  <p className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                    <span>{draftFieldLabelOf(item.field)} 근거</span>
                    <button
                      type="button"
                      className="underline underline-offset-2 hover:text-foreground"
                      onClick={() => onOpenDocument(item.document_id)}
                    >
                      문서함에서 보기
                    </button>
                  </p>
                </div>
              ))}
              {draft.evidence.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  문서함으로 가면 작성 중인 내용은 사라져요.
                </p>
              )}
              {draft.unsupported_fields.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  {draft.unsupported_fields.map(draftFieldLabelOf).join(' · ')}
                  은 원문 근거 없이 정리한 글이에요. 확인해 주세요.
                </p>
              )}
            </div>
            <div className="grid gap-2 rounded-xl border bg-card p-4 text-sm">
              <h4 className="font-medium">연결할 설계 항목 후보</h4>
              {draft.design_ref_candidates.length === 0 && (
                <p className="text-xs text-muted-foreground">
                  연결할 승인된 설계 항목이 없어요.
                </p>
              )}
              {draft.design_ref_candidates.map((candidate) => (
                <label
                  key={candidate.design_item_id}
                  className="flex items-start gap-2.5"
                >
                  <input
                    type="checkbox"
                    className="mt-1 size-4 accent-primary"
                    disabled={disabled}
                    checked={linkedIds.has(candidate.design_item_id)}
                    onChange={(event) =>
                      onToggleLink(candidate, event.target.checked)
                    }
                  />
                  <span>
                    <span className="font-medium">
                      {candidate.key} · {candidate.title}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {candidate.reason}
                    </span>
                  </span>
                </label>
              ))}
              {!hasRefs && (
                <p className="rounded-lg border border-amber-300 bg-amber-50 p-2.5 text-xs text-amber-900">
                  연결된 설계 항목이 없으면 이 작업은 충돌 감지에 잡히지 않아요.
                  {draft.design_ref_candidates.length > 0
                    ? ' 후보를 확인해 연결해 보세요.'
                    : ''}
                </p>
              )}
            </div>
            <div className="grid gap-2 rounded-xl border bg-card p-4 text-sm">
              <h4 className="font-medium">설계에 아직 없는 내용</h4>
              {missing.length === 0 && (
                <p className="text-xs text-muted-foreground">
                  설계에 없는 내용은 찾지 못했어요.
                </p>
              )}
              {missing.map((entry) => (
                <div
                  key={entry.text}
                  className="grid gap-1.5 rounded-lg border border-dashed p-2.5"
                >
                  <p className="font-medium">{entry.text}</p>
                  {entry.detail && (
                    <p className="text-xs text-muted-foreground">
                      {entry.detail}
                    </p>
                  )}
                  {entry.source && (
                    <p className="text-xs text-muted-foreground">
                      {documentTypeLabel[entry.source.document_type]} v
                      {entry.source.version_no} · {entry.source.location}
                    </p>
                  )}
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={disabled || unlinkedTexts.has(entry.text)}
                      onClick={() => onAddUnlinked(entry.text)}
                    >
                      {unlinkedTexts.has(entry.text)
                        ? '추가했어요'
                        : '연결하지 못한 내용에 추가'}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </section>
  )
}

const draftFieldLabelOf = (field: keyof typeof draftFieldLabel) =>
  draftFieldLabel[field]
