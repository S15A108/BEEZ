import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { documentsApi } from '@/api/services/documents'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { REVIEW_TEXT_MAX } from './schemas'

// 최종본 본문 아래 '비비에게 직접 요청하기'(DOC-A23). 비비가 요청을 반영한 새 제안 하나를 만들어 주고,
// 그 제안은 반영하거나 넘겨야 팀장에게 요청할 수 있어 화면은 제안 확인 단계로 돌아간다.
export function DirectRequest({
  projectId,
  documentId,
  reviewId,
  revision,
  onStale,
}: {
  projectId: string
  documentId: string
  reviewId: string
  revision: number
  onStale: () => void
}) {
  const client = useQueryClient()
  const [text, setText] = useState('')
  const request = useMutation({
    mutationFn: (value: string) =>
      documentsApi.requestBeeSuggestion(projectId, documentId, reviewId, {
        expected_revision: revision,
        text: value,
      }),
    onSuccess: () => setText(''),
    onError: (cause) => {
      if (getApiErrorCode(cause) === 'review_stale') onStale()
    },
    onSettled: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId] }),
  })
  const trimmed = text.trim()
  return (
    <form
      aria-label="비비에게 직접 요청하기"
      className="grid gap-2 rounded-xl border p-3 text-sm"
      onSubmit={(event) => {
        event.preventDefault()
        if (trimmed) request.mutate(trimmed)
      }}
    >
      <strong>비비에게 직접 요청하기</strong>
      <Textarea
        aria-label="비비에게 요청할 내용"
        maxLength={REVIEW_TEXT_MAX}
        placeholder="예: 오류 표에 권한 없음(403) 행을 추가해 줘"
        value={text}
        disabled={request.isPending}
        onChange={(event) => setText(event.target.value)}
      />
      <span className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">
          비비가 새 수정 제안을 만들어요. 반영하거나 넘긴 뒤에 팀장에게 요청할
          수 있어요.
        </span>
        <Button
          type="submit"
          size="sm"
          variant="outline"
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
      {request.error && (
        <p role="alert" className="text-xs text-destructive">
          {getApiErrorMessage(request.error)}
        </p>
      )}
    </form>
  )
}
