import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getApiErrorMessage } from '@/api/error'
import type { PlanningQuestion } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import type { AnswerQuestionInput } from './schemas'
import { DETAIL_FOOTER } from './splitLayout'

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
// 질문마다 쓰던 답. 이전·다음으로 넘겨도 저장하기 전까지 사라지지 않는다.
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

// 질문 유형(선택·단답·서술)에 맞는 답변 입력. 서버는 `answer_format`대로 답을 검사하므로 화면도 같은 규칙으로 먼저 막는다.
// 기획 분석 질문 탭의 오른쪽 상세에 바로 놓인다. 목록(siblings)을 받으면 '저장하고 다음'과 이전·다음으로 질문을 넘길 수 있다.
// 어떤 질문을 넘길지는 왼쪽 목록의 필터(답할 질문·답함·전체)가 정한다.
export function QuestionAnswerForm({
  question,
  siblings,
  total,
  onNavigate,
  onSubmit,
  onClose,
  children,
}: {
  question: PlanningQuestion
  /** 같은 목록의 질문들(화면 순서). 있으면 질문을 넘겨 가며 답할 수 있다 */
  siblings?: PlanningQuestion[]
  /** 전체 질문 수. 없으면 siblings 수로 센다 */
  total?: number
  /** 다른 질문으로 옮긴다(siblings 와 함께 준다) */
  onNavigate?: (id: string) => void
  onSubmit: (input: AnswerQuestionInput) => Promise<unknown>
  /** 마지막 질문을 저장한 뒤 */
  onClose?: () => void
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
  const update = (next: Partial<Draft>) =>
    setDrafts((all) => ({
      ...all,
      [question.id]: {
        ...(all[question.id] ?? initialDraft(question)),
        ...next,
      },
    }))
  const setChoice = (change: (state: ChoiceDraft) => ChoiceDraft) =>
    update({ choice: change(choice) })
  const setText = (value: string) => update({ text: value })
  // 오류는 그 질문에만 보인다. 목록에서 다른 질문을 열면 앞 질문의 오류를 끌고 오지 않는다.
  const [failure, setFailure] = useState<{ id: string; message: string }>()
  const error = failure?.id === question.id ? failure.message : ''
  const setError = (message: string) =>
    setFailure(message ? { id: question.id, message } : undefined)
  // 저장하는 동안 목록에서 다른 질문을 열었으면 저장이 끝나도 그 질문에 그대로 둔다.
  const shownId = useRef(question.id)
  useEffect(() => {
    shownId.current = question.id
  }, [question.id])
  const [busy, setBusy] = useState(false)
  const limit = type === 'CHOICE' || !type ? undefined : TEXT_LIMITS[type]
  const maxLength = limit
    ? Math.min(format?.max_length ?? limit.fallback, limit.hard)
    : (format?.max_length ?? LEGACY_TEXT_MAX)
  // 닫으면 서버가 이전 답변을 지우므로, 이미 답한 질문에서는 닫기를 보이지 않는다.
  const canDismiss = !question.answer && question.status !== 'DISMISSED'

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

  const go = (id: string) => {
    setError('')
    onNavigate?.(id)
  }

  const run = async (input: AnswerQuestionInput) => {
    setError('')
    setBusy(true)
    // 저장한 뒤 갈 곳은 저장하기 전의 목록으로 정한다(저장하면 목록이 새로 읽혀 순서 계산이 바뀐다).
    const after = next
    const saving = question.id
    try {
      await onSubmit(input)
      if (shownId.current !== saving) return
      if (after) go(after.id)
      else onClose?.()
    } catch (cause) {
      setError(getApiErrorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (type === 'CHOICE') {
      const other = choice.otherOn ? choice.other.trim() : ''
      if (!choice.selected.length && !other) {
        setError('답변을 하나 이상 골라 주세요.')
        return
      }
      void run({
        answer_payload: {
          type: 'CHOICE',
          selected: choice.selected,
          ...(other ? { other_text: other } : {}),
        },
      })
      return
    }
    const value = text.trim()
    if (!value) {
      setError('답변을 입력해 주세요.')
      return
    }
    void run(
      type
        ? { answer_payload: { type, text: value } }
        : // 유형이 없는 예전 질문은 문장으로만 보낸다.
          { answer: value },
    )
  }

  // Ctrl+Enter(Mac은 ⌘+Enter)로 저장하고 다음으로. 글을 쓰다가 손을 떼지 않아도 된다.
  const onKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey) || busy)
      return
    event.preventDefault()
    event.currentTarget.requestSubmit()
  }

  const toggle = (option: string) =>
    setChoice((state) => ({
      ...state,
      selected: multiple
        ? state.selected.includes(option)
          ? state.selected.filter((item) => item !== option)
          : [...state.selected, option]
        : [option],
      // 하나만 고르는 질문은 선택지를 고르면 기타 입력을 끈다.
      otherOn: multiple ? state.otherOn : false,
    }))

  const optionClass = (on: boolean) =>
    cn(
      'flex cursor-pointer items-center gap-3 rounded-lg border px-4 py-3 text-[15px] has-focus-visible:ring-2 has-focus-visible:ring-ring',
      on ? 'border-primary bg-primary/10 font-medium' : 'hover:bg-muted/50',
    )

  const nav = onNavigate && ordered.length > 1
  return (
    <form
      noValidate
      className="flex flex-1 flex-col"
      onSubmit={submit}
      onKeyDown={onKeyDown}
    >
      <div className="px-8 pt-5 pb-6">
        <div className="mb-3 flex min-h-7 items-center gap-1 text-xs text-muted-foreground">
          <span aria-live="polite" className="mr-auto font-semibold">
            질문 {number} / {total ?? ordered.length}
          </span>
          {nav && (
            <>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={busy || !previous}
                onClick={() => previous && go(previous.id)}
              >
                <ChevronLeft />
                이전
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={busy || !next}
                onClick={() => next && go(next.id)}
              >
                다음
                <ChevronRight />
              </Button>
            </>
          )}
        </div>
        {children}
        <div className="mt-6 grid gap-4 border-t pt-6">
          {type === 'CHOICE' ? (
            <fieldset className="grid gap-2" disabled={busy}>
              <legend className="mb-2 text-sm font-medium">
                {multiple ? '답변 (여러 개 선택 가능)' : '답변'}
              </legend>
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
                        setChoice((state) => ({
                          ...state,
                          otherOn: multiple ? !state.otherOn : true,
                          selected: multiple ? state.selected : [],
                        }))
                      }
                    />
                    기타
                  </label>
                  {choice.otherOn && (
                    <Input
                      aria-label="기타 답변"
                      maxLength={OTHER_MAX}
                      placeholder="직접 입력해 주세요."
                      value={choice.other}
                      onChange={(event) =>
                        setChoice((state) => ({
                          ...state,
                          other: event.target.value,
                        }))
                      }
                    />
                  )}
                </>
              )}
            </fieldset>
          ) : (
            <div className="grid gap-2">
              <div className="flex items-center justify-between gap-2">
                <Label htmlFor="planning-answer">답변</Label>
                <span className="text-xs text-muted-foreground">
                  {[...text].length} / {maxLength}
                </span>
              </div>
              {type === 'SHORT_ANSWER' ? (
                <Input
                  id="planning-answer"
                  maxLength={maxLength}
                  disabled={busy}
                  value={text}
                  onChange={(event) => setText(event.target.value)}
                />
              ) : (
                <Textarea
                  id="planning-answer"
                  rows={type === 'ESSAY' ? 6 : 4}
                  maxLength={maxLength}
                  disabled={busy}
                  value={text}
                  onChange={(event) => setText(event.target.value)}
                />
              )}
            </div>
          )}
          {error && (
            <p
              role="alert"
              className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
            >
              {error}
            </p>
          )}
        </div>
      </div>
      <div className={DETAIL_FOOTER}>
        {canDismiss && (
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            title="닫아도 나중에 다시 답할 수 있어요."
            onClick={() => void run({ status: 'DISMISSED' })}
          >
            답변하지 않고 닫기
          </Button>
        )}
        <Button type="submit" className="ml-auto" disabled={busy}>
          {next ? '저장하고 다음' : '저장'}
        </Button>
      </div>
    </form>
  )
}
