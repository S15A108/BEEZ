import { useEffect, useId, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { LoaderCircle, Sparkles } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { lineRangeText } from '@/lib/display'
import { draftRequestFormSchema } from './schemas'
import type { DraftRequestFormValues, GenerateDraftResponse } from './schemas'

// CHG-A07 오류. 다시 시도해도 되는 것(ai_unavailable·ai_timeout)과 기준을 새로 확인해야 하는 것(version_conflict)을 나눠 알린다.
const draftErrors: Record<string, string> = {
  ai_unavailable:
    '비비가 지금 초안을 만들 수 없어요. 잠시 뒤 같은 요청으로 다시 시도해 주세요.',
  ai_timeout:
    '초안을 만드는 데 시간이 오래 걸려 멈췄어요. 문서가 길면 더 걸릴 수 있어요. 잠시 뒤 다시 시도해 주세요.',
  version_conflict:
    '그사이 문서나 관련 설계 항목이 바뀌어 기준 원문을 다시 불러왔어요. 새 원문을 확인한 뒤 다시 만들어 주세요.',
  baseline_missing: '기준 설계가 아직 없어서 초안을 만들 수 없어요.',
  analysis_not_completed:
    '이 문서의 승인본은 아직 원문 준비가 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요.',
}

// 초안을 어떻게 다뤘는지(화면 상태). 서버 응답은 mutation.data 에 있고 여기에 복사하지 않는다.
type Outcome = 'applied' | 'asking' | 'stale' | 'dismissed'

interface Props {
  /** 기준 원문과 버전을 받았고 최신일 때만 요청할 수 있다. */
  ready: boolean
  /** ready 가 아닐 때 이유 */
  notReadyReason?: string
  request: (instruction: string) => Promise<GenerateDraftResponse>
  /** 응답이 지금 폼의 문서·기준 버전에 대한 것인지. 요청하는 동안 문서를 바꿨으면 다른 문서의 초안이다. */
  isCurrent: (draft: GenerateDraftResponse) => boolean
  /** '고친 글'이 비었거나 기준 원문 그대로인지. 손댄 글은 묻지 않고 덮어쓰지 않는다. */
  isUntouched: () => boolean
  onApply: (text: string) => void
  /** 기준이 바뀌었다는 409 가 오면 문서 목록·기준 원문을 다시 읽게 한다. */
  onConflict?: () => void
  titleOf: (documentId: string) => string
}

// 비비에게 문서 수정 초안을 받는다(CHG-A07). 초안은 승인된 설계가 아니고 저장되지 않는다.
// 받은 글은 '고친 글'에 채울 뿐이라 사람이 읽고 고친 뒤 제안을 올려야 효력이 생긴다.
// 제안 폼의 <form> 안에 있어서 안쪽에 <form> 을 두지 않고 버튼에서 handleSubmit 을 부른다.
export function DraftRequest({
  ready,
  notReadyReason,
  request,
  isCurrent,
  isUntouched,
  onApply,
  onConflict,
  titleOf,
}: Props) {
  const inputId = useId()
  const [open, setOpen] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const form = useForm<DraftRequestFormValues>({
    resolver: zodResolver(draftRequestFormSchema),
    defaultValues: { instruction: '' },
  })
  const draft = useMutation({ mutationFn: request })
  // 패널을 열면 입력칸으로 포커스를 옮긴다(트리거 버튼은 그대로 남아 있다).
  useEffect(() => {
    if (open) form.setFocus('instruction')
  }, [open, form])
  const submit = form.handleSubmit((values) => {
    setOutcome(null)
    // mutate 에 넘긴 콜백은 이 패널이 사라진 뒤에는 불리지 않아, 다른 문서로 바뀐 폼에 초안이 들어가지 않는다.
    draft.mutate(values.instruction, {
      onSuccess: (result) => {
        if (!isCurrent(result)) return setOutcome('stale')
        if (!isUntouched()) return setOutcome('asking')
        onApply(result.after_content)
        setOutcome('applied')
      },
      onError: (cause) => {
        if (getApiErrorCode(cause) === 'version_conflict') onConflict?.()
      },
    })
  })
  const close = () => {
    setOpen(false)
    trigger.current?.focus()
  }
  const error = draft.error
    ? (draftErrors[getApiErrorCode(draft.error) ?? ''] ??
      getApiErrorMessage(draft.error))
    : ''
  const result = draft.data
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          ref={trigger}
          type="button"
          size="xs"
          variant="outline"
          aria-expanded={open}
          disabled={!ready && !open}
          onClick={() => (open ? close() : setOpen(true))}
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
      {open && (
        <div
          role="group"
          aria-label="비비에게 초안 받기"
          className="grid gap-2 rounded-xl border p-3"
        >
          <Label htmlFor={inputId}>어떻게 고칠지 알려 주세요</Label>
          <Textarea
            id={inputId}
            rows={3}
            disabled={draft.isPending}
            aria-invalid={!!form.formState.errors.instruction}
            placeholder="예: 오류 응답 필드 설명을 보완해 주세요."
            {...form.register('instruction')}
          />
          {form.formState.errors.instruction && (
            <p role="alert" className="text-xs text-destructive">
              {form.formState.errors.instruction.message}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            비비가 쓴 초안은 승인된 설계가 아니에요. 고친 글에 채워 줄 테니 읽어
            보고 고친 뒤 제안을 올려 주세요.
          </p>
          {draft.isPending && (
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
          {result && outcome === 'stale' && (
            <p role="status" className="rounded-lg bg-muted/50 p-3 text-sm">
              기다리는 동안 고칠 문서나 기준 원문이 바뀌어 이 초안은 쓰지
              않았어요. 필요하면 다시 만들어 주세요.
            </p>
          )}
          {result && outcome && outcome !== 'stale' && (
            <div className="grid gap-2 text-sm">
              {outcome === 'applied' && (
                <p
                  role="status"
                  className="rounded-lg bg-muted/50 p-3 leading-6"
                >
                  초안을 고친 글에 넣었어요. 꼭 읽어 보고 고쳐 주세요.
                </p>
              )}
              {outcome === 'asking' && (
                <div
                  role="status"
                  className="grid gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3 leading-6"
                >
                  <p>
                    지금 쓰고 있는 글이 있어서 바꾸지 않았어요. 초안으로 바꾸면
                    지금 쓴 글은 사라져요.
                  </p>
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      size="xs"
                      variant="outline"
                      onClick={() => {
                        onApply(result.after_content)
                        setOutcome('applied')
                      }}
                    >
                      초안으로 바꾸기
                    </Button>
                    <Button
                      type="button"
                      size="xs"
                      variant="ghost"
                      onClick={() => setOutcome('dismissed')}
                    >
                      버리기
                    </Button>
                  </div>
                </div>
              )}
              {outcome !== 'dismissed' &&
                result.generation.unresolved.length > 0 && (
                  <div className="rounded-lg bg-muted/50 p-3 leading-6">
                    <p className="font-semibold">일부는 반영하지 못했어요</p>
                    <ul className="list-disc pl-5">
                      {result.generation.unresolved.map((text) => (
                        <li key={text}>{text}</li>
                      ))}
                    </ul>
                  </div>
                )}
              {outcome !== 'dismissed' &&
                result.generation.sources.length > 0 && (
                  <div className="rounded-lg bg-muted/50 p-3 leading-6">
                    <p className="font-semibold">참고한 곳</p>
                    <ul className="list-disc pl-5">
                      {result.generation.sources.map((source, index) => (
                        <li key={`${source.document_version_id}:${index}`}>
                          {titleOf(source.document_id)} v{source.version_no} ·{' '}
                          {lineRangeText(source.location)}
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
              disabled={draft.isPending}
              onClick={close}
            >
              닫기
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={draft.isPending || !ready}
              onClick={() => void submit()}
            >
              초안 만들기
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
