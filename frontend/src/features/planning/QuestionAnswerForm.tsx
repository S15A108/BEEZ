import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode } from 'react'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react'
import { getApiErrorMessage } from '@/api/error'
import type { PlanningQuestion } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import type { AnswerQuestionInput } from './schemas'
import { DETAIL_FOOTER } from './splitLayout'
import { useAnswerAutosave } from './useAnswerAutosave'
import type { SaveState } from './useAnswerAutosave'

const OTHER_MAX = 200
// 서버(PlanningAnswers)의 글자 수 기본값과 상한. 질문에 max_length 가 없거나 더 크면 이 값을 쓴다.
const TEXT_LIMITS = {
  SHORT_ANSWER: { fallback: 200, hard: 500 },
  ESSAY: { fallback: 2000, hard: 4000 },
} as const
const LEGACY_TEXT_MAX = 2000

interface ChoiceDraft {
  selected: string[]
  other: string
  otherOn: boolean
}
// 질문마다 쓰던 답. 이전·다음으로 넘겨도, 저장에 실패해도 사라지지 않는다.
interface Draft {
  choice: ChoiceDraft
  text: string
}

function initialChoice(question: PlanningQuestion): ChoiceDraft {
  const payload =
    question.answer_payload?.type === 'CHOICE'
      ? question.answer_payload
      : undefined
  return {
    selected: payload?.selected ?? [],
    other: payload?.other_text ?? '',
    otherOn: !!payload?.other_text,
  }
}

function initialText(question: PlanningQuestion) {
  return question.answer_payload?.type !== 'CHOICE' && question.answer_payload
    ? question.answer_payload.text
    : (question.answer ?? '')
}

const initialDraft = (question: PlanningQuestion): Draft => ({
  choice: initialChoice(question),
  text: initialText(question),
})

// 보낼 답. 고른 것도 글도 없으면 undefined(서버가 빈 답을 받지 않는다).
function inputOf(
  question: PlanningQuestion,
  draft: Draft,
): AnswerQuestionInput | undefined {
  const type = question.question_type
  if (type === 'CHOICE') {
    const other = draft.choice.otherOn ? draft.choice.other.trim() : ''
    if (!draft.choice.selected.length && !other) return undefined
    return {
      answer_payload: {
        type: 'CHOICE',
        selected: draft.choice.selected,
        ...(other ? { other_text: other } : {}),
      },
    }
  }
  const value = draft.text.trim()
  if (!value) return undefined
  return type
    ? { answer_payload: { type, text: value } }
    : // 유형이 없는 예전 질문은 문장으로만 보낸다.
      { answer: value }
}

const statusText: Record<SaveState['state'], string> = {
  saving: '저장 중…',
  saved: '저장됨',
  empty: '빈 답은 저장하지 않아요',
  failed: '저장 못 했어요',
}

// 질문 유형(선택·단답·서술)에 맞는 답변 입력. 서버는 `answer_format`대로 답을 검사하므로 화면도 같은 규칙으로 먼저 막는다.
// 기획 분석 질문 탭의 오른쪽 상세에 바로 놓인다. 답은 자동으로 저장한다: 선택은 고르는 즉시, 글은 입력을 멈추고 1초 뒤.
// 칸을 벗어나거나 이전·다음·목록으로 다른 질문으로 옮길 때도 저장하지 않은 글이 있으면 바로 저장한다.
// 어떤 질문을 넘길지는 왼쪽 목록의 필터(답할 질문·답함·전체)가 정한다.
export function QuestionAnswerForm({
  question,
  siblings,
  total,
  onNavigate,
  onSave,
  onEdit,
  locked,
  children,
}: {
  question: PlanningQuestion
  /** 같은 목록의 질문들(화면 순서). 있으면 이전·다음으로 질문을 넘길 수 있다 */
  siblings?: PlanningQuestion[]
  /** 전체 질문 수. 없으면 siblings 수로 센다 */
  total?: number
  /** 다른 질문으로 옮긴다(siblings 와 함께 준다) */
  onNavigate?: (id: string) => void
  /** 답 저장(PLN-A04). 답하지 않고 닫기도 이것으로 보낸다 */
  onSave: (id: string, input: AnswerQuestionInput) => Promise<unknown>
  /** 지금 보는 질문의 답을 바꾸기 시작했다(목록이 이 질문을 붙잡아 두게) */
  onEdit?: (id: string) => void
  /** 지금은 답할 수 없는 질문의 안내. 있으면 입력 대신 이것을 보인다.
   *  폼은 그대로 두어 다른 질문에 쓰던 답과 저장 상태가 사라지지 않는다 */
  locked?: ReactNode
  /** 입력 위에 둘 질문 머리(종류·질문·근거) */
  children?: ReactNode
}) {
  const type = question.question_type
  const format = question.answer_format
  const multiple = format?.multiple === true
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const draft = drafts[question.id] ?? initialDraft(question)
  const choice = draft.choice
  const text = draft.text
  const autosave = useAnswerAutosave(onSave)
  const saveState = autosave.states[question.id]
  // 답하지 않고 닫는 중인 질문. 닫기 요청이 끝날 때까지 그 질문의 입력과 자동 저장을 막는다.
  // 막지 않으면 닫는 동안 쓴 글이 닫기가 끝난 뒤 저장되어 서버가 닫은 질문을 다시 '답함'으로 바꾼다(PR #303 리뷰).
  const [closingId, setClosingId] = useState<string>()
  const closing = closingId === question.id
  // 바꾼 답을 기록하고 저장을 맡긴다. 선택은 바로, 글은 입력을 멈춘 뒤 저장한다.
  const change = (next: Partial<Draft>, now: boolean) => {
    if (closing) return
    const value = { ...draft, ...next }
    setDrafts((all) => ({ ...all, [question.id]: value }))
    onEdit?.(question.id)
    const input = inputOf(question, value)
    if (now) autosave.saveNow(question.id, input)
    else autosave.schedule(question.id, input)
  }
  // 다른 질문으로 옮기거나 입력이 사라지면 저장하지 않은 글을 바로 저장한다.
  const { flush } = autosave
  useEffect(() => {
    const id = question.id
    return () => flush(id)
  }, [question.id, flush])

  // 답하지 않고 닫기의 실패. 그 질문에만 보인다.
  const [failure, setFailure] = useState<{ id: string; message: string }>()
  const error = failure?.id === question.id ? failure.message : ''
  // 닫는 동안 목록에서 다른 질문을 열었으면 닫기가 끝나도 그 질문에 그대로 둔다.
  const shownId = useRef(question.id)
  useEffect(() => {
    shownId.current = question.id
  }, [question.id])
  const limit = type === 'CHOICE' || !type ? undefined : TEXT_LIMITS[type]
  const maxLength = limit
    ? Math.min(format?.max_length ?? limit.fallback, limit.hard)
    : (format?.max_length ?? LEGACY_TEXT_MAX)
  // 닫으면 서버가 이전 답변을 지우므로, 이미 답한 질문에서는 닫기를 보이지 않는다.
  const canDismiss =
    !locked &&
    !question.answer &&
    question.status !== 'DISMISSED' &&
    saveState?.state !== 'saved'

  // 키보드로 '답변하지 않고 닫기'에 와 있는데 답이 저장돼 버튼이 사라지면, 포커스가 화면 밖(body)으로 떨어지지 않게
  // 다음 버튼(없으면 이전 버튼, 그것도 없으면 답변 칸)으로 옮긴다. 버튼이 떼어질 때 아직 포커스가 있었는지 기록해 둔다.
  const dismissHadFocus = useRef(false)
  const formRef = useRef<HTMLFormElement>(null)
  const previousRef = useRef<HTMLButtonElement>(null)
  const nextRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (canDismiss || !dismissHadFocus.current) return
    dismissHadFocus.current = false
    const target =
      [nextRef.current, previousRef.current].find(
        (button) => button && !button.disabled,
      ) ??
      formRef.current?.querySelector<HTMLElement>(
        '#planning-answer, input, textarea',
      )
    target?.focus()
  }, [canDismiss])

  // 넘길 수 있는 질문들. 서버는 대체된 질문을 목록에 주지 않는다.
  const ordered = siblings ?? [question]
  const index = ordered.findIndex((item) => item.id === question.id)
  const previous = onNavigate && index > 0 ? ordered[index - 1] : undefined
  const next =
    onNavigate && index >= 0 && index < ordered.length - 1
      ? ordered[index + 1]
      : undefined
  // 몇 번째인지는 질문 번호로 센다. 남은 질문 목록의 순서로 세면 답할 때마다 번호가 줄어 진행이 거꾸로 보인다.
  const number = question.number ?? index + 1

  const dismiss = async () => {
    const closing = question.id
    setFailure(undefined)
    setClosingId(closing)
    const after = next
    try {
      await autosave.cancel(closing)
      await onSave(closing, { status: 'DISMISSED' })
      // 닫으면 서버에 답이 없으므로, 다시 열었을 때 쓰다 만 글을 보이지 않는다.
      setDrafts((all) =>
        Object.fromEntries(
          Object.entries(all).filter(([key]) => key !== closing),
        ),
      )
      if (shownId.current === closing && after) onNavigate?.(after.id)
    } catch (cause) {
      setFailure({ id: closing, message: getApiErrorMessage(cause) })
    } finally {
      setClosingId((id) => (id === closing ? undefined : id))
    }
  }

  // Enter(단답)는 기다리지 않고 지금 글을 저장한다.
  const submit = (event: FormEvent) => {
    event.preventDefault()
    autosave.flush(question.id)
  }

  // Ctrl+Enter(Mac은 ⌘+Enter)로 다음 질문으로. 옮기면서 쓰던 글을 저장한다.
  const onKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey)) return
    event.preventDefault()
    if (next) onNavigate?.(next.id)
    else autosave.flush(question.id)
  }

  const toggle = (option: string) =>
    change(
      {
        choice: {
          ...choice,
          selected: multiple
            ? choice.selected.includes(option)
              ? choice.selected.filter((item) => item !== option)
              : [...choice.selected, option]
            : [option],
          // 하나만 고르는 질문은 선택지를 고르면 기타 입력을 끈다.
          otherOn: multiple ? choice.otherOn : false,
        },
      },
      true,
    )

  const optionClass = (on: boolean) =>
    cn(
      'flex cursor-pointer items-center gap-3 rounded-lg border px-4 py-3 text-[15px] has-focus-visible:ring-2 has-focus-visible:ring-ring',
      on ? 'border-primary bg-primary/10 font-medium' : 'hover:bg-muted/50',
    )

  // 답변 칸 옆의 저장 상태 한 줄
  const status = saveState && (
    <span
      aria-live="polite"
      className={cn(
        'flex items-center gap-1.5 text-xs',
        saveState.state === 'failed'
          ? 'text-destructive'
          : 'text-muted-foreground',
      )}
    >
      {statusText[saveState.state]}
      {saveState.state === 'failed' && (
        <Button
          type="button"
          size="xs"
          variant="outline"
          className="text-foreground"
          onClick={() =>
            autosave.saveNow(question.id, inputOf(question, draft))
          }
        >
          <RefreshCw />
          다시 시도
        </Button>
      )}
    </span>
  )
  const problem =
    saveState?.state === 'failed' ? saveState.message : error || undefined

  const nav = onNavigate && ordered.length > 1
  return (
    <form
      ref={formRef}
      noValidate
      className="flex flex-1 flex-col"
      onSubmit={submit}
      onKeyDown={onKeyDown}
    >
      <div className="px-8 pt-5 pb-6">
        <p
          aria-live="polite"
          className="mb-3 text-xs font-semibold text-muted-foreground"
        >
          질문 {number} / {total ?? ordered.length}
        </p>
        {children}
        <div className="mt-6 grid gap-4 border-t pt-6">
          {locked ? (
            locked
          ) : type === 'CHOICE' ? (
            <fieldset className="relative grid gap-2" disabled={closing}>
              <legend className="mb-2 text-sm font-medium">
                {multiple ? '답변 (여러 개 선택 가능)' : '답변'}
              </legend>
              <div className="absolute top-0 right-0">{status}</div>
              {(format?.options ?? []).map((option) => (
                <label
                  key={option}
                  className={optionClass(choice.selected.includes(option))}
                >
                  <input
                    type={multiple ? 'checkbox' : 'radio'}
                    name="choice"
                    checked={choice.selected.includes(option)}
                    onChange={() => toggle(option)}
                  />
                  {option}
                </label>
              ))}
              {format?.allow_other && (
                <>
                  <label className={optionClass(choice.otherOn)}>
                    <input
                      type={multiple ? 'checkbox' : 'radio'}
                      name="choice"
                      checked={choice.otherOn}
                      onChange={() =>
                        change(
                          {
                            choice: {
                              ...choice,
                              otherOn: multiple ? !choice.otherOn : true,
                              selected: multiple ? choice.selected : [],
                            },
                          },
                          true,
                        )
                      }
                    />
                    기타
                  </label>
                  {choice.otherOn && (
                    // 기타 글은 글 답변처럼 입력을 멈추거나 칸을 벗어나면 저장한다.
                    <Input
                      aria-label="기타 답변"
                      maxLength={OTHER_MAX}
                      placeholder="직접 입력해 주세요."
                      readOnly={closing}
                      value={choice.other}
                      onChange={(event) =>
                        change(
                          { choice: { ...choice, other: event.target.value } },
                          false,
                        )
                      }
                      onBlur={() => autosave.flush(question.id)}
                    />
                  )}
                </>
              )}
            </fieldset>
          ) : (
            <div className="grid gap-2">
              <div className="flex items-center justify-between gap-2">
                <Label htmlFor="planning-answer">답변</Label>
                <span className="flex items-center gap-3">
                  {status}
                  <span className="text-xs text-muted-foreground">
                    {[...text].length} / {maxLength}
                  </span>
                </span>
              </div>
              {type === 'SHORT_ANSWER' ? (
                <Input
                  id="planning-answer"
                  maxLength={maxLength}
                  readOnly={closing}
                  value={text}
                  onChange={(event) =>
                    change({ text: event.target.value }, false)
                  }
                  onBlur={() => autosave.flush(question.id)}
                />
              ) : (
                <Textarea
                  id="planning-answer"
                  rows={type === 'ESSAY' ? 6 : 4}
                  maxLength={maxLength}
                  readOnly={closing}
                  value={text}
                  onChange={(event) =>
                    change({ text: event.target.value }, false)
                  }
                  onBlur={() => autosave.flush(question.id)}
                />
              )}
            </div>
          )}
          {!locked && problem && (
            <p
              role="alert"
              className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
            >
              {problem}
            </p>
          )}
        </div>
      </div>
      <div className={DETAIL_FOOTER}>
        {canDismiss && (
          <Button
            type="button"
            variant="ghost"
            ref={(node) => () => {
              if (node && document.activeElement === node)
                dismissHadFocus.current = true
            }}
            disabled={closingId !== undefined}
            title="닫아도 나중에 다시 답할 수 있어요."
            // 누를 때 입력 칸을 벗어나며 버릴 글을 저장하지 않게 포커스를 옮기지 않는다.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => void dismiss()}
          >
            답변하지 않고 닫기
          </Button>
        )}
        {nav && (
          <>
            <Button
              ref={previousRef}
              type="button"
              variant="outline"
              className="ml-auto"
              disabled={!previous}
              onClick={() => previous && onNavigate?.(previous.id)}
            >
              <ChevronLeft />
              이전
            </Button>
            <Button
              ref={nextRef}
              type="button"
              disabled={!next}
              onClick={() => next && onNavigate?.(next.id)}
            >
              다음
              <ChevronRight />
            </Button>
          </>
        )}
      </div>
    </form>
  )
}
