import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { documentsApi } from '@/api/services/documents'
import type { DocumentReviewSuggestion } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { REVIEW_TEXT_MAX, noChangeReason } from './schemas'

// 대상 문장은 비비가 본문에서 고칠 자리를 정확히 찾도록 함께 보낸다. 너무 길면 앞부분만 쓴다.
const QUOTE_MAX = 600

// 이 의견을 반영해 본문을 고쳐 달라는 요청 글의 초안. 사용자가 고쳐 쓸 수 있다.
function requestTextFor(suggestion: DocumentReviewSuggestion) {
  const quote =
    suggestion.type === 'PATCH' && suggestion.original_text
      ? `\n\n고칠 대상 문장:\n${suggestion.original_text.slice(0, QUOTE_MAX)}`
      : ''
  return `다음 비비 의견을 반영해 본문을 수정해 주세요.\n의견: ${suggestion.explanation}${quote}`.slice(
    0,
    REVIEW_TEXT_MAX,
  )
}

// 의견·제안 카드 안의 '비비에게 요청하기'. '비비와 이야기하기'(DOC-A22)는 대화만 하고 본문을 바꾸지 않는다.
// 이 버튼은 그 의견을 바탕으로 한 요청을 DOC-A23 으로 바로 보내 수정된 새 제안을 받는다. 직접 요청란에 다시 쓰지 않아도 된다.
export function SuggestionRequest({
  projectId,
  documentId,
  reviewId,
  revision,
  suggestion,
  disabled,
  onStale,
}: {
  projectId: string
  documentId: string
  reviewId: string
  revision: number
  suggestion: DocumentReviewSuggestion
  disabled: boolean
  onStale: () => void
}) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const request = useMutation({
    mutationFn: (value: string) =>
      documentsApi.requestBeeSuggestion(projectId, documentId, reviewId, {
        expected_revision: revision,
        text: value,
      }),
    // 고칠 곳이 없다는 답이면 닫지 않고 이유를 보여, 요청을 고쳐 다시 보낼 수 있게 한다.
    onSuccess: (result) => {
      if (!noChangeReason(result)) setOpen(false)
    },
    onError: (cause) => {
      if (getApiErrorCode(cause) === 'review_stale') onStale()
    },
    onSettled: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId] }),
  })
  const trimmed = text.trim()
  const reason = noChangeReason(request.data)
  if (!open)
    return (
      <Button
        size="xs"
        variant="outline"
        className="justify-self-start"
        disabled={disabled}
        onClick={() => {
          request.reset()
          setText(requestTextFor(suggestion))
          setOpen(true)
        }}
      >
        비비에게 요청하기
      </Button>
    )
  return (
    <form
      aria-label={`${suggestion.explanation} 비비에게 요청하기`}
      className="grid gap-2 rounded-lg border bg-muted/30 p-2"
      onSubmit={(event) => {
        event.preventDefault()
        if (trimmed) request.mutate(trimmed)
      }}
    >
      <Textarea
        aria-label="비비에게 요청할 내용"
        maxLength={REVIEW_TEXT_MAX}
        value={text}
        disabled={request.isPending}
        onChange={(event) => setText(event.target.value)}
      />
      <span className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">
          이 의견으로 비비가 본문을 고친 새 제안을 만들어요. 내용을 고쳐 써도
          돼요.
        </span>
        <span className="flex gap-2">
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={request.isPending}
            onClick={() => setOpen(false)}
          >
            닫기
          </Button>
          <Button
            type="submit"
            size="sm"
            disabled={!trimmed || request.isPending}
          >
            {request.isPending ? (
              <>
                <LoaderCircle className="animate-spin" />
                비비가 제안을 만들고 있어요
              </>
            ) : (
              '요청 보내기'
            )}
          </Button>
        </span>
      </span>
      {reason && (
        <p role="status" className="text-xs text-muted-foreground">
          비비가 고칠 곳을 찾지 못했어요: {reason}
        </p>
      )}
      {request.error && (
        <p role="alert" className="text-xs text-destructive">
          {getApiErrorMessage(request.error)}
        </p>
      )}
    </form>
  )
}
