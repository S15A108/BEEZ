import { useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getApiErrorMessage } from '@/api/error'
import type { PlanningQuestion } from '@/api/contracts/common'
import { PaneSheet, SheetSubmit } from '@/components/PaneSheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import type { AnswerQuestionInput } from './schemas'

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

const STATUS_TEXT = {
  OPEN: '남음',
  ANSWERED: '답변 완료',
  DISMISSED: '닫음',
} as const

// 질문 유형(선택·단답·서술)에 맞는 답변 입력. 서버는 `answer_format`대로 답을 검사하므로 화면도 같은 규칙으로 먼저 막는다.
// 목록(siblings)을 받으면 '저장하고 다음'과 이전·다음, 번호로 질문을 넘길 수 있다. 하나씩 저장하고 뒤로 가서 다시 여는 일을 줄인다.
export function QuestionAnswerForm({
  question,
  siblings,
  onNavigate,
  onSubmit,
  onClose,
}: {
  question: PlanningQuestion
  /** 같은 목록의 질문들(화면 순서). 있으면 질문을 넘겨 가며 답할 수 있다 */
  siblings?: PlanningQuestion[]
  /** 다른 질문으로 옮긴다(siblings 와 함께 준다) */
  onNavigate?: (id: string) => void
  onSubmit: (input: AnswerQuestionInput) => Promise<unknown>
  onClose: () => void
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
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  // 이미 답한 질문까지 다 넘기면 길어서, 기본은 남은 질문만 넘긴다.
  const [onlyOpen, setOnlyOpen] = useState(true)
  const limit = type === 'CHOICE' || !type ? undefined : TEXT_LIMITS[type]
  const maxLength = limit
    ? Math.min(format?.max_length ?? limit.fallback, limit.hard)
    : (format?.max_length ?? LEGACY_TEXT_MAX)
  // 닫으면 서버가 이전 답변을 지우므로, 이미 답한 질문에서는 닫기를 보이지 않는다.
  const canDismiss = !question.answer && question.status !== 'DISMISSED'

  // 넘길 수 있는 질문들. 서버는 대체된 질문을 목록에 주지 않는다.
  const ordered = siblings ?? [question]
  const visible = onlyOpen
    ? ordered.filter(
        (item) => item.status === 'OPEN' || item.id === question.id,
      )
    : ordered
  const index = visible.findIndex((item) => item.id === question.id)
  // 몇 번째인지는 전체 목록 기준이다. 남은 질문만 세면 답할 때마다 번호가 줄어 진행이 거꾸로 보인다.
  const position = ordered.findIndex((item) => item.id === question.id)
  const previous = onNavigate && index > 0 ? visible[index - 1] : undefined
  const next =
    onNavigate && index >= 0 && index < visible.length - 1
      ? visible[index + 1]
      : undefined
  const count = (status: keyof typeof STATUS_TEXT) =>
    ordered.filter((item) => item.status === status).length

  const go = (id: string) => {
    setError('')
    onNavigate?.(id)
  }

  const run = async (input: AnswerQuestionInput) => {
    setError('')
    setBusy(true)
    // 저장한 뒤 갈 곳은 저장하기 전의 목록으로 정한다(저장하면 목록이 새로 읽혀 순서 계산이 바뀐다).
    const after = next
    try {
      await onSubmit(input)
      if (after) go(after.id)
      else onClose()
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
      'flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm has-focus-visible:ring-2 has-focus-visible:ring-ring',
      on ? 'border-foreground font-semibold' : 'hover:bg-muted/50',
    )

  const nav = onNavigate && ordered.length > 1
  return (
    <PaneSheet
      title="기획 보완 질문에 답변"
      description={question.question}
      busy={busy}
      onClose={onClose}
    >
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={submit}
        onKeyDown={onKeyDown}
      >
        {nav && (
          <div className="grid gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
              <span aria-live="polite">
                질문 {position >= 0 ? position + 1 : '-'} / {ordered.length}
              </span>
              <span>
                답변 {count('ANSWERED')} · 닫음 {count('DISMISSED')} · 남음{' '}
                {count('OPEN')}
              </span>
            </div>
            <ol aria-label="질문 목록" className="flex flex-wrap gap-1.5">
              {ordered.map((item, position) => {
                const label = item.number ?? position + 1
                const status =
                  STATUS_TEXT[item.status as keyof typeof STATUS_TEXT]
                return (
                  <li key={item.id}>
                    <button
                      type="button"
                      disabled={busy}
                      aria-label={`질문 ${label}${status ? ` ${status}` : ''}`}
                      aria-current={
                        item.id === question.id ? 'step' : undefined
                      }
                      onClick={() => go(item.id)}
                      className={cn(
                        'size-7 rounded-full border text-xs font-medium transition-colors hover:bg-muted disabled:opacity-50',
                        item.status === 'ANSWERED' &&
                          'border-green-300 bg-green-100 text-green-800',
                        item.status === 'DISMISSED' &&
                          'bg-muted text-muted-foreground line-through',
                        item.id === question.id &&
                          'font-bold ring-2 ring-foreground ring-offset-1',
                      )}
                    >
                      {label}
                    </button>
                  </li>
                )
              })}
            </ol>
            <label className="flex items-center gap-2 text-xs text-muted-foreground">
              <input
                type="checkbox"
                checked={onlyOpen}
                onChange={(event) => setOnlyOpen(event.target.checked)}
              />
              남은 질문만 넘기기
            </label>
          </div>
        )}
        {question.summary && (
          <p className="rounded-lg bg-muted/50 p-3 text-sm text-muted-foreground">
            {question.summary}
          </p>
        )}
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
        {canDismiss && (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => void run({ status: 'DISMISSED' })}
            >
              답변하지 않고 닫기
            </Button>
            <p className="text-xs text-muted-foreground">
              닫아도 나중에 다시 답할 수 있어요.
            </p>
          </div>
        )}
        <SheetSubmit
          disabled={busy}
          leading={
            nav && (
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
            )
          }
        >
          {next ? '저장하고 다음' : '저장'}
        </SheetSubmit>
      </form>
    </PaneSheet>
  )
}
