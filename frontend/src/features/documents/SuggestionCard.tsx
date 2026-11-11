import type { DocumentReviewSuggestion } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { SuggestionChat } from './SuggestionChat'
import { SuggestionRequest } from './SuggestionRequest'

const CATEGORY = {
  OMISSION: '누락',
  CONSISTENCY: '일관성',
  READABILITY: '가독성',
  REJECTION_FIX: '반려 반영',
} as const

function Tag({
  tone = 'muted',
  children,
}: {
  tone?: 'muted' | 'amber' | 'blue'
  children: string
}) {
  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
        {
          muted: 'bg-muted text-muted-foreground',
          amber: 'bg-amber-100 text-amber-800',
          blue: 'bg-blue-100 text-blue-800',
        }[tone],
      )}
    >
      {children}
    </span>
  )
}

// 수정 제안·의견 한 장. 분류·중요도·출처 칩, 참고 승인본 근거, 반영 결과, 비비와의 대화를 담는다.
export function SuggestionCard({
  projectId,
  documentId,
  reviewId,
  revision,
  suggestion,
  index,
  owner,
  stale,
  busy,
  onDecide,
  onStale,
}: {
  projectId: string
  documentId: string
  reviewId: string
  /** 지금 작성 리비전. '비비에게 요청하기'가 이 리비전으로 요청한다 */
  revision: number
  suggestion: DocumentReviewSuggestion
  index: number
  owner: boolean
  /** 이 검토가 낡아 대화를 새로 시작할 수 없음 */
  stale: boolean
  busy: boolean
  onDecide: (
    suggestionId: string,
    decision: 'APPLY' | 'SKIP',
    replacementText?: string,
  ) => void
  onStale: () => void
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const pending = suggestion.decision === 'PENDING'
  const applied =
    suggestion.decision === 'APPLIED' &&
    suggestion.applied_replacement_text !== undefined
  return (
    <li className="grid gap-2 rounded-xl border p-3 text-sm">
      {(suggestion.category ||
        suggestion.severity ||
        suggestion.origin === 'DIRECT_REQUEST' ||
        (suggestion.type === 'PATCH' && !pending)) && (
        <span className="flex flex-wrap gap-1.5">
          {suggestion.category && <Tag>{CATEGORY[suggestion.category]}</Tag>}
          {suggestion.severity && (
            <Tag tone={suggestion.severity === 'MAJOR' ? 'amber' : 'muted'}>
              {suggestion.severity === 'MAJOR' ? '중요' : '가벼움'}
            </Tag>
          )}
          {suggestion.origin === 'DIRECT_REQUEST' && (
            <Tag tone="blue">직접 요청</Tag>
          )}
          {suggestion.type === 'PATCH' && !pending && (
            <Tag>{suggestion.decision === 'APPLIED' ? '반영함' : '넘김'}</Tag>
          )}
        </span>
      )}
      <div className="flex items-start justify-between gap-2">
        <strong>
          {suggestion.type === 'COMMENT' ? '의견' : '제안'} {index + 1} ·{' '}
          {suggestion.explanation}
        </strong>
        <SuggestionChat
          projectId={projectId}
          documentId={documentId}
          reviewId={reviewId}
          suggestion={suggestion}
          index={index}
          className="shrink-0"
          canChat={owner && !stale}
          canApply={owner && !stale && pending && suggestion.type === 'PATCH'}
          busy={busy}
          onApply={(replacementText) =>
            onDecide(suggestion.id, 'APPLY', replacementText)
          }
          onStale={onStale}
        />
      </div>
      {/* 의견(COMMENT)은 읽기만 하고, 반영·넘기기 없이 제출을 막지 않는다. */}
      {suggestion.type === 'PATCH' && (
        <div className="grid gap-1 font-mono text-xs leading-6 whitespace-pre-wrap select-text">
          <p className="rounded bg-destructive/5 px-2 text-destructive">
            − {suggestion.original_text}
          </p>
          <p className="rounded bg-primary/10 px-2">
            +{' '}
            {applied
              ? suggestion.applied_replacement_text
              : suggestion.replacement_text}
          </p>
        </div>
      )}
      {applied && (
        <p className="text-xs text-muted-foreground">
          비비가 대화에서 낸 문장으로 반영했어요.
        </p>
      )}
      {suggestion.sources.length > 0 && (
        <ul aria-label="근거" className="grid gap-1">
          {suggestion.sources.map((source) => (
            <li
              key={`${source.reference_id}:${source.quote ?? ''}`}
              className="grid gap-0.5 text-xs"
            >
              <Button
                size="xs"
                variant="outline"
                className="justify-self-start"
                onClick={() => openPane('documents', source.document_id)}
              >
                근거 · {statusLabel(source.kind)} v{source.version_no}
              </Button>
              {source.quote && (
                <span className="leading-5 text-muted-foreground">
                  “{source.quote}”
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {/* 이 의견으로 본문을 고친 새 제안을 바로 요청한다. 대화(비비와 이야기하기)와 달리 요청을 보낸다(DOC-A23). */}
      {owner && !stale && (suggestion.type === 'COMMENT' || pending) && (
        <SuggestionRequest
          projectId={projectId}
          documentId={documentId}
          reviewId={reviewId}
          revision={revision}
          suggestion={suggestion}
          disabled={busy}
          onStale={onStale}
        />
      )}
      {owner && pending && (
        <span className="flex justify-end gap-2">
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => onDecide(suggestion.id, 'SKIP')}
          >
            넘기기
          </Button>
          <Button
            size="sm"
            disabled={busy}
            onClick={() => onDecide(suggestion.id, 'APPLY')}
          >
            반영하기
          </Button>
        </span>
      )}
    </li>
  )
}
