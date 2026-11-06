import { useState } from 'react'
import type { FormEvent } from 'react'
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
const TEXT_MAX = 2000

function initialChoice(question: PlanningQuestion) {
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

// 질문 유형(선택·단답·서술)에 맞는 답변 입력. 서버는 `answer_format`대로 답을 검사하므로 화면도 같은 규칙으로 먼저 막는다.
export function QuestionAnswerForm({
  question,
  onSubmit,
  onClose,
}: {
  question: PlanningQuestion
  onSubmit: (input: AnswerQuestionInput) => Promise<unknown>
  onClose: () => void
}) {
  const type = question.question_type
  const format = question.answer_format
  const multiple = format?.multiple === true
  const [choice, setChoice] = useState(() => initialChoice(question))
  const [text, setText] = useState(() => initialText(question))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const maxLength = format?.max_length ?? TEXT_MAX

  const run = async (input: AnswerQuestionInput) => {
    setError('')
    setBusy(true)
    try {
      await onSubmit(input)
      onClose()
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

  return (
    <PaneSheet
      title="기획 보완 질문에 답변"
      description={question.question}
      busy={busy}
      onClose={onClose}
    >
      <form noValidate className="flex flex-col gap-5" onSubmit={submit}>
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
        <SheetSubmit disabled={busy}>저장</SheetSubmit>
      </form>
    </PaneSheet>
  )
}
