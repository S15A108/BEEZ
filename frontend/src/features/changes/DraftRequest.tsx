import { useId, useState } from 'react'
import { LoaderCircle, Sparkles } from 'lucide-react'
import type { Generation } from '@/api/contracts/common'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

const MAX_INSTRUCTION = 2000

// CHG-A07 오류. 다시 시도해도 되는 것(ai_unavailable·ai_timeout)과 기준을 새로 확인해야 하는 것(version_conflict)을 나눠 알린다.
const draftErrors: Record<string, string> = {
  ai_unavailable:
    '비비가 지금 초안을 만들 수 없어요. 잠시 뒤 같은 요청으로 다시 시도해 주세요.',
  ai_timeout:
    '초안을 만드는 데 시간이 오래 걸려 멈췄어요. 문서가 길면 더 걸릴 수 있어요. 잠시 뒤 다시 시도해 주세요.',
  version_conflict:
    '그사이 문서나 관련 설계 항목이 바뀌었어요. 기준 원문을 확인한 뒤 다시 만들어 주세요.',
  baseline_missing: '기준 설계가 아직 없어서 초안을 만들 수 없어요.',
  analysis_not_completed:
    '이 문서의 승인본은 아직 원문 준비가 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요.',
}

interface Draft {
  after_content: string
  generation: Generation
}

interface Props {
  /** 기준 원문과 버전을 받았고 최신일 때만 요청할 수 있다. */
  ready: boolean
  /** ready 가 아닐 때 이유 */
  notReadyReason?: string
  request: (instruction: string) => Promise<Draft>
  /** '고친 글'이 비었거나 기준 원문 그대로인지. 손댄 글은 묻지 않고 덮어쓰지 않는다. */
  isUntouched: () => boolean
  onApply: (text: string) => void
  titleOf: (documentId: string) => string
}

// 비비에게 문서 수정 초안을 받는다(CHG-A07). 초안은 승인된 설계가 아니고 저장되지 않는다.
// 받은 글은 '고친 글'에 채울 뿐이라 사람이 읽고 고친 뒤 제안을 올려야 효력이 생긴다.
export function DraftRequest({
  ready,
  notReadyReason,
  request,
  isUntouched,
  onApply,
  titleOf,
}: Props) {
  const inputId = useId()
  const [open, setOpen] = useState(false)
  const [instruction, setInstruction] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<(Draft & { applied: boolean }) | null>(
    null,
  )
  const submit = async () => {
    const text = instruction.trim()
    if (!text) {
      setError('어떻게 고칠지 입력해 주세요.')
      return
    }
    if (text.length > MAX_INSTRUCTION) {
      setError(
        `수정 요청은 ${MAX_INSTRUCTION.toLocaleString()}자까지 쓸 수 있어요.`,
      )
      return
    }
    setBusy(true)
    setError('')
    setResult(null)
    try {
      const draft = await request(text)
      const applied = isUntouched()
      if (applied) onApply(draft.after_content)
      setResult({ ...draft, applied })
    } catch (cause) {
      setError(
        draftErrors[getApiErrorCode(cause) ?? ''] ?? getApiErrorMessage(cause),
      )
    } finally {
      setBusy(false)
    }
  }
  if (!open)
    return (
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="xs"
          variant="outline"
          disabled={!ready}
          onClick={() => setOpen(true)}
        >
          <Sparkles />
          비비에게 초안 받기
        </Button>
        {!ready && notReadyReason && (
          <span className="text-xs text-muted-foreground">
            {notReadyReason}
          </span>
        )}
      </div>
    )
  return (
    <div
      role="group"
      aria-label="비비에게 초안 받기"
      className="grid gap-2 rounded-xl border p-3"
    >
      <Label htmlFor={inputId}>어떻게 고칠지 알려 주세요</Label>
      <Textarea
        id={inputId}
        rows={3}
        value={instruction}
        disabled={busy}
        maxLength={MAX_INSTRUCTION}
        placeholder="예: 오류 응답 필드 설명을 보완해 주세요."
        onChange={(event) => setInstruction(event.target.value)}
      />
      <p className="text-xs text-muted-foreground">
        비비가 쓴 초안은 승인된 설계가 아니에요. 고친 글에 채워 줄 테니 읽어
        보고 고친 뒤 제안을 올려 주세요.
      </p>
      {busy && (
        <p
          role="status"
          className="flex items-center gap-2 text-sm text-muted-foreground"
        >
          <LoaderCircle className="size-4 animate-spin" />
          비비가 초안을 쓰는 중이에요. 문서가 길면 시간이 더 걸려요.
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {result && (
        <div className="grid gap-2 text-sm">
          {result.applied ? (
            <p role="status" className="rounded-lg bg-muted/50 p-3 leading-6">
              초안을 고친 글에 넣었어요. 꼭 읽어 보고 고쳐 주세요.
            </p>
          ) : (
            <div
              role="status"
              className="grid gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3 leading-6"
            >
              <p>
                지금 쓰고 있는 글이 있어서 바꾸지 않았어요. 초안으로 바꾸면 지금
                쓴 글은 사라져요.
              </p>
              <div className="flex gap-2">
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  onClick={() => {
                    onApply(result.after_content)
                    setResult({ ...result, applied: true })
                  }}
                >
                  초안으로 바꾸기
                </Button>
                <Button
                  type="button"
                  size="xs"
                  variant="ghost"
                  onClick={() => setResult(null)}
                >
                  버리기
                </Button>
              </div>
            </div>
          )}
          {result.generation.unresolved.length > 0 && (
            <div className="rounded-lg bg-muted/50 p-3 leading-6">
              <p className="font-semibold">일부는 반영하지 못했어요</p>
              <ul className="list-disc pl-5">
                {result.generation.unresolved.map((text) => (
                  <li key={text}>{text}</li>
                ))}
              </ul>
            </div>
          )}
          {result.generation.sources.length > 0 && (
            <div className="rounded-lg bg-muted/50 p-3 leading-6">
              <p className="font-semibold">참고한 곳</p>
              <ul className="list-disc pl-5">
                {result.generation.sources.map((source, index) => (
                  <li key={`${source.document_version_id}:${index}`}>
                    {titleOf(source.document_id)} v{source.version_no} ·{' '}
                    {source.location}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() => setOpen(false)}
        >
          닫기
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={busy || !instruction.trim()}
          onClick={() => void submit()}
        >
          초안 만들기
        </Button>
      </div>
    </div>
  )
}
