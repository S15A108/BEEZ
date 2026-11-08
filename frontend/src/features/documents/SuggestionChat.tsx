import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { documentsApi } from '@/api/services/documents'
import type { DocumentReviewSuggestion } from '@/api/contracts/common'
import { PaneSheet } from '@/components/PaneSheet'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { REVIEW_TEXT_MAX } from './schemas'
import { cn } from '@/lib/utils'

// 제안 카드 아래에서 그 제안에 대해 비비와 상의한다(DOC-A22). 대화는 본문을 바꾸지 않고, 비비가 다른 문장을
// 내면 '이 문장으로 반영'으로 DOC-A17 APPLY 에 replacement_text 를 실어 보낸다. COMMENT 제안에도 대화할 수 있다.
export function SuggestionChat({
  projectId,
  documentId,
  reviewId,
  suggestion,
  index,
  className,
  canChat,
  canApply,
  busy,
  onApply,
  onStale,
}: {
  projectId: string
  documentId: string
  reviewId: string
  suggestion: DocumentReviewSuggestion
  /** 제안 번호(0부터). 대화 화면 머리에 '제안 1'처럼 보인다 */
  index: number
  /** 카드 안에서 열기 버튼 자리(오른쪽 끝) */
  className?: string
  /** 올린 사람이고 지금 검토가 최신일 때만 새 말을 보낼 수 있다 */
  canChat: boolean
  /** 아직 처리하지 않은 PATCH 제안일 때만 비비의 다른 문장으로 반영할 수 있다 */
  canApply: boolean
  busy: boolean
  onApply: (replacementText: string) => void
  onStale: () => void
}) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const send = useMutation({
    mutationFn: (value: string) =>
      documentsApi.discussSuggestion(
        projectId,
        documentId,
        reviewId,
        suggestion.id,
        { text: value },
      ),
    onSuccess: () => setText(''),
    onError: (cause) => {
      if (getApiErrorCode(cause) === 'review_stale') onStale()
    },
    onSettled: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId] }),
  })
  const messages = suggestion.messages
  if (!canChat && !messages.length) return null
  const opener = (
    <Button
      size="xs"
      variant="outline"
      className={className}
      onClick={() => setOpen(true)}
    >
      {messages.length
        ? `비비와 나눈 대화 ${messages.length}개`
        : '비비와 이야기하기'}
    </Button>
  )
  if (!open) return opener
  const close = () => setOpen(false)
  const apply = (replacement: string) => {
    onApply(replacement)
    close()
  }
  const thread = (
    <Thread
      messages={messages}
      canChat={canChat}
      canApply={canApply}
      busy={busy}
      text={text}
      setText={setText}
      sending={send.isPending}
      error={send.error ? getApiErrorMessage(send.error) : ''}
      onSend={(value) => send.mutate(value)}
      onApply={apply}
    />
  )
  const title = `${suggestion.type === 'COMMENT' ? '의견' : '제안'} ${index + 1} · 비비와 대화`
  const proposal = <Proposal suggestion={suggestion} index={index} />
  return (
    <>
      {opener}
      <PaneSheet
        back="제안 목록"
        title={title}
        description="대화만으로는 본문이 바뀌지 않아요. 마음에 드는 문장이 나오면 반영하기를 눌러 주세요."
        busy={send.isPending}
        onClose={close}
      >
        {proposal}
        {thread}
      </PaneSheet>
    </>
  )
}

// 대화 화면 맨 위에 고정해 보이는, 지금 이야기하는 제안 한 장.
function Proposal({
  suggestion,
  index,
}: {
  suggestion: DocumentReviewSuggestion
  index: number
}) {
  return (
    <div
      aria-label="대화 중인 제안"
      role="group"
      className="grid gap-1.5 rounded-xl border bg-muted/30 p-3 text-sm"
    >
      <strong>
        {suggestion.type === 'COMMENT' ? '의견' : '제안'} {index + 1} ·{' '}
        {suggestion.explanation}
      </strong>
      {suggestion.type === 'PATCH' && (
        <div className="grid gap-1 font-mono text-xs leading-6 whitespace-pre-wrap select-text">
          <p className="rounded bg-destructive/5 px-2 text-destructive">
            − {suggestion.original_text}
          </p>
          <p className="rounded bg-primary/10 px-2">
            +{' '}
            {suggestion.applied_replacement_text ?? suggestion.replacement_text}
          </p>
        </div>
      )}
    </div>
  )
}

function Thread({
  messages,
  canChat,
  canApply,
  busy,
  text,
  setText,
  sending,
  error,
  onSend,
  onApply,
}: {
  messages: DocumentReviewSuggestion['messages']
  canChat: boolean
  canApply: boolean
  busy: boolean
  text: string
  setText: (value: string) => void
  sending: boolean
  error: string
  onSend: (value: string) => void
  onApply: (replacementText: string) => void
}) {
  const trimmed = text.trim()
  return (
    <>
      {messages.length > 0 && (
        <ol aria-label="대화 내용" className="grid gap-1.5">
          {messages.map((message) => (
            <li
              key={message.id}
              className={cn(
                'grid max-w-[92%] gap-1 rounded-lg px-2.5 py-1.5 text-sm',
                message.author_kind === 'HUMAN'
                  ? 'justify-self-end bg-muted'
                  : 'justify-self-start bg-primary/10',
              )}
            >
              <span className="text-xs font-semibold text-muted-foreground">
                {message.author_kind === 'HUMAN' ? '나' : '비비'}
              </span>
              <span className="leading-6 whitespace-pre-wrap">
                {message.text}
              </span>
              {message.proposed_text !== undefined && (
                <span className="grid gap-1.5">
                  <span className="rounded bg-background px-2 py-1 font-mono text-xs leading-6 whitespace-pre-wrap select-text">
                    + {message.proposed_text}
                  </span>
                  {canApply && (
                    <Button
                      size="xs"
                      className="justify-self-end"
                      disabled={busy}
                      onClick={() => onApply(message.proposed_text!)}
                    >
                      이 문장으로 반영
                    </Button>
                  )}
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
      {canChat ? (
        <form
          className="grid gap-1.5"
          onSubmit={(event) => {
            event.preventDefault()
            if (trimmed) onSend(trimmed)
          }}
        >
          <Textarea
            aria-label="비비에게 보낼 말"
            maxLength={REVIEW_TEXT_MAX}
            placeholder="이 제안에 대해 비비에게 물어보거나 다른 문장을 부탁해요"
            value={text}
            disabled={sending}
            onChange={(event) => setText(event.target.value)}
          />
          <div className="flex flex-wrap items-center justify-end gap-2">
            <Button type="submit" size="xs" disabled={!trimmed || sending}>
              {sending ? (
                <>
                  <LoaderCircle className="animate-spin" />
                  비비가 답을 쓰고 있어요
                </>
              ) : (
                '보내기'
              )}
            </Button>
          </div>
          {error && (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          )}
        </form>
      ) : null}
    </>
  )
}
