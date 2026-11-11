import { Fragment, useEffect, useId, useRef, useState } from 'react'
import {
  useMutation,
  useMutationState,
  useQueryClient,
} from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { BibiFace } from '@/components/BibiFace'
import { beeApi } from '@/api/services'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { BeeAnswerEvidence } from './BeeAnswerEvidence'
import type { AskResponse, AskScope } from './schemas'
import { useBibiMessages } from './useBibiMessages'

// 찾는 범위 칩(캔버스 PNL-12-v2-bibi-hotbar). 값은 BEE-A01 scope다.
// soon이 있으면 칩을 보이되 고를 수 없게 막고 그 안내를 붙인다.
const scopes: { value: AskScope; label: string; soon?: string }[] = [
  { value: 'APPROVED', label: '승인된 문서' },
  // ponytail: BEE-A01(PR #252)은 아직 INCLUDE_DRAFTS를 400으로 거절한다. 서버가 받게 되면 soon만 지운다.
  {
    value: 'INCLUDE_DRAFTS',
    label: '초안 포함',
    soon: '초안 포함은 곧 지원해요',
  },
]

// BEE-A01의 409. baseline_changed는 변경 제안 API에서 뜻이 달라 공통 문구(api/error.ts)에 두지 않는다.
const askErrors: Record<string, string> = {
  // 현재 기준 설계가 없거나 그 문서의 색인이 아직 준비되지 않았다.
  index_not_ready:
    '답할 문서가 아직 준비되지 않았어요. 기준 설계가 만들어지고 문서 준비가 끝나면 다시 물어봐 주세요.',
  baseline_changed: '답을 만드는 사이 기준이 바뀌었어요. 다시 질문해 주세요.',
}

const questionSchema = z.object({
  question: z
    .string()
    .trim()
    .min(1, '설계 질문을 입력해 주세요.')
    .max(2000, '2000자 이하로 입력해 주세요.'),
})
type QuestionInput = z.infer<typeof questionSchema>

type Ask = { question: string; scope: AskScope }

function questionErrorMessage(error: unknown) {
  if (isAxiosError(error)) {
    const status = error.response?.status
    if (
      status !== undefined &&
      status >= 500 &&
      status < 600 &&
      status !== 501 &&
      getApiErrorCode(error) === undefined
    )
      return '답변을 받지 못했어요. 다시 보내면 새로 답을 만들어요.'
  }
  return getApiErrorMessage(error)
}

// 단축 바 가운데 비비 버튼(B안)으로 여는 대화.
export function BibiPanel({ projectId }: { projectId: string }) {
  const scopeLabel = useId()
  const errorId = useId()
  const sourcesId = useId()
  const sourcesRef = useRef<HTMLElement>(null)
  const [scope, setScope] = useState<AskScope>('APPROVED')
  const [messages, setMessages] = useBibiMessages<
    Ask & { answer: AskResponse }
  >(projectId)
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const client = useQueryClient()
  const mutationKey = ['projects', projectId, 'bibi-ask'] as const
  // 요청 상태는 창 밖의 캐시에 남아, 근거 문서를 보고 돌아와도 이어진다.
  const attempts = useMutationState({
    filters: { mutationKey, exact: true },
    select: (attempt) => ({
      id: attempt.mutationId,
      status: attempt.state.status,
      input: attempt.state.variables as Ask | undefined,
      error: attempt.state.error,
    }),
  })
  const latest = attempts.at(-1)
  const pending = attempts.findLast(
    (attempt) => attempt.status === 'pending',
  )?.input
  const failed = latest?.status === 'error' ? latest : undefined
  const mutation = useMutation({
    mutationKey,
    gcTime: Infinity,
    retry: false,
    mutationFn: (input: Ask) => beeApi.ask(projectId, input),
    onSuccess: (answer, input) => {
      setMessages((previous) => [...previous, { ...input, answer }])
      setSelectedIndex(null)
    },
  })
  const {
    control,
    register,
    handleSubmit,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<QuestionInput>({
    resolver: zodResolver(questionSchema),
    defaultValues: { question: '' },
  })
  const question = useWatch({ control, name: 'question' })
  const isRetry =
    !!failed?.input &&
    question.trim() === failed.input.question &&
    scope === failed.input.scope
  const restoredFailure = useRef<number | null>(null)
  useEffect(() => {
    if (!failed?.input || restoredFailure.current === failed.id) return
    restoredFailure.current = failed.id
    if (!getValues('question')) setValue('question', failed.input.question)
  }, [failed, getValues, setValue])
  // 보내면 입력칸을 바로 비우고, 실패하면 그사이 새로 쓴 글이 없을 때만 질문을 돌려준다.
  const send = ({ question }: QuestionInput) => {
    // mutateAsync는 캐시의 pending 상태를 동기로 세운다. 연속 제출과 새로 열린 창 모두
    // React 렌더링을 기다리지 않고 실제 대기 요청을 검사한다.
    if (client.isMutating({ mutationKey, exact: true })) return
    setValue('question', '')
    return mutation
      .mutateAsync({ question, scope })
      .then(() => undefined)
      .catch(() => undefined)
  }
  const end = useRef<HTMLDivElement>(null)
  // 새 질문·답이 붙으면 입력줄까지 내려 마지막 대화가 보이게 한다.
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'nearest' })
  }, [messages.length, pending])
  const selected =
    selectedIndex === null ? messages.at(-1) : messages[selectedIndex]
  const referenceCount = selected
    ? selected.answer.citations.length || selected.answer.sources.length
    : 0
  return (
    // 창 높이를 다 쓰고, 대화가 짧아도 질문 입력줄은 아래에 둔다.
    <div className="grid min-h-full gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
      <div className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <BibiFace className="size-12 shrink-0" />
          <div className="min-w-0 flex-1">
            <h3 className="font-bold">
              비비{' '}
              <span className="text-sm font-medium text-muted-foreground">
                공용 문서에서 찾아 답해요
              </span>
            </h3>
            <p className="text-xs text-muted-foreground">
              답마다 찾은 문서를 붙여요. 최종 설계는 인간 팀장이 승인해요.
            </p>
          </div>
          <div
            role="radiogroup"
            aria-labelledby={scopeLabel}
            className="flex items-center gap-1.5"
          >
            <span
              id={scopeLabel}
              className="mr-1 text-xs text-muted-foreground"
            >
              찾는 범위
            </span>
            {scopes.map((option) => (
              <Fragment key={option.value}>
                <label
                  className={cn(
                    'cursor-pointer rounded-full px-3 py-1 text-xs font-semibold has-focus-visible:ring-2 has-focus-visible:ring-ring',
                    scope === option.value
                      ? 'bg-foreground text-background'
                      : 'bg-muted text-muted-foreground hover:text-foreground',
                    option.soon &&
                      'cursor-not-allowed opacity-50 hover:text-muted-foreground',
                  )}
                >
                  <input
                    type="radio"
                    name={scopeLabel}
                    value={option.value}
                    checked={scope === option.value}
                    onChange={() => setScope(option.value)}
                    disabled={!!option.soon}
                    aria-describedby={
                      option.soon ? `${scopeLabel}-soon` : undefined
                    }
                    className="sr-only"
                  />
                  {option.label}
                </label>
                {option.soon && (
                  <span
                    id={`${scopeLabel}-soon`}
                    className="text-xs text-muted-foreground"
                  >
                    {option.soon}
                  </span>
                )}
              </Fragment>
            ))}
          </div>
        </div>
        <div
          role="log"
          aria-label="비비 설계 질의응답"
          className="grid min-h-48 flex-1 content-start gap-3"
        >
          <p className="max-w-xl justify-self-start rounded-2xl rounded-tl-sm bg-muted px-3.5 py-2.5 text-sm">
            공용 문서의 승인된 내용에서 찾아 답해 드릴게요.
          </p>
          {messages.map((message, index) => (
            <div key={index} className="grid gap-3">
              <p className="max-w-xl justify-self-end rounded-2xl rounded-br-sm bg-foreground px-3.5 py-2.5 text-sm whitespace-pre-wrap text-background">
                {message.question}
              </p>
              <article className="max-w-xl justify-self-start rounded-2xl rounded-tl-sm bg-muted px-3.5 py-2.5 text-sm">
                <p className="leading-6 whitespace-pre-wrap">
                  {message.answer.text
                    .split(/(\[\d+\])/g)
                    .map((part, partIndex) => {
                      const citation = message.answer.citations.find(
                        (item) => part === `[${item.n}]`,
                      )
                      return citation ? (
                        <button
                          key={partIndex}
                          type="button"
                          aria-label={`인용 ${citation.n}번 근거 보기`}
                          aria-controls={sourcesId}
                          className="font-semibold underline underline-offset-2"
                          onClick={() => {
                            setSelectedIndex(index)
                            sourcesRef.current?.focus()
                          }}
                        >
                          {part}
                        </button>
                      ) : (
                        part
                      )
                    })}
                </p>
                {message.answer.unresolved.length > 0 && (
                  <ul className="mt-2 list-inside list-disc text-xs text-muted-foreground">
                    {message.answer.unresolved.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
                {!!message.answer.notices?.length && (
                  <ul
                    aria-label="답변 안내"
                    className="mt-2 text-xs text-muted-foreground"
                  >
                    {message.answer.notices.map((notice, index) => (
                      <li key={index}>{notice}</li>
                    ))}
                  </ul>
                )}
                <span className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                  찾은 문서 {message.answer.sources.length}개
                  {/* 초안까지 찾은 답은 승인 전 내용이 섞였을 수 있다(명세: 미승인 검토임을 표시). */}
                  {message.scope === 'INCLUDE_DRAFTS' && (
                    <Badge variant="outline">초안 포함</Badge>
                  )}
                  {/* 모델 답을 쓰지 못한 응답(TEMPLATE)은 본문이 이미 그렇다고 말해 꼬리표를 붙이지 않는다(오너 결정 11-11). */}
                  {message.answer.origin === 'MODEL' && (
                    <Badge variant="outline">모델 생성</Badge>
                  )}
                </span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={`${index + 1}번째 답변의 근거 보기`}
                  aria-controls={sourcesId}
                  onClick={() => {
                    setSelectedIndex(index)
                    sourcesRef.current?.focus()
                  }}
                >
                  근거 보기
                </Button>
              </article>
            </div>
          ))}
          {pending && (
            <>
              <p className="max-w-xl justify-self-end rounded-2xl rounded-br-sm bg-foreground px-3.5 py-2.5 text-sm whitespace-pre-wrap text-background">
                {pending.question}
              </p>
              <p role="status" className="text-xs text-muted-foreground">
                비비가 문서를 찾는 중…
              </p>
            </>
          )}
          {failed && (
            <p role="alert" className="text-sm text-destructive">
              {askErrors[getApiErrorCode(failed.error) ?? ''] ??
                questionErrorMessage(failed.error)}
            </p>
          )}
        </div>
        {/* 대화가 길어져도 입력줄은 창 아래에 붙어 있다. 업무 창의 아래 여백(최대 2rem)으로 흘러내린 대화는 그림자로 가린다. */}
        <div className="sticky bottom-0 grid gap-2 border-t bg-background pt-3 shadow-[0_2rem_0_var(--background)]">
          <form
            noValidate
            onSubmit={(event) => void handleSubmit(send)(event)}
            className="flex gap-2"
          >
            <Input
              aria-label="설계 질문"
              placeholder="공용 문서에 대해 물어보세요"
              autoComplete="off"
              // 창(Radix)은 열 때 첫 포커스 요소인 '찾는 범위' 칩에 포커스를 준다. 비비를 부르고 바로 묻게 입력칸을 먼저 잡는다(S15P31A108-364).
              autoFocus
              aria-invalid={Boolean(errors.question)}
              aria-describedby={errors.question ? errorId : undefined}
              className="h-10"
              {...register('question')}
            />
            <Button type="submit" className="h-10 px-4" disabled={!!pending}>
              {isRetry ? '다시 보내기' : '보내기'}
            </Button>
          </form>
          {errors.question && (
            <p id={errorId} role="alert" className="text-xs text-destructive">
              {errors.question.message}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            대화는 새로 고침하면 지워져요.
          </p>
        </div>
        {/* 입력줄이 본래 놓일 자리의 끝. 붙어 있는 입력줄 안에 두면 늘 보이는 것으로 쳐서 내려가지 않는다. */}
        <div ref={end} aria-hidden className="-mt-4 scroll-mb-8" />
      </div>
      <aside
        id={sourcesId}
        ref={sourcesRef}
        tabIndex={-1}
        aria-label="선택한 답의 근거"
        className="rounded-2xl border bg-muted/30 p-4"
      >
        <h3 className="flex justify-between text-sm font-bold">
          이 답의 근거
          <span className="text-muted-foreground">{referenceCount}</span>
        </h3>
        {selected ? (
          <BeeAnswerEvidence answer={selected.answer} />
        ) : (
          <p className="mt-3 text-xs text-muted-foreground">
            질문하면 비비가 찾은 문서가 여기에 떠요. 근거를 못 찾으면 짐작하지
            않고 못 찾았다고 답해요.
          </p>
        )}
        {selected && (
          <p className="mt-3 text-xs text-muted-foreground">
            {selected.scope === 'APPROVED'
              ? '승인된 문서만 찾았어요.'
              : '초안까지 찾았어요. 초안은 아직 팀장이 승인하지 않은 내용이에요.'}
          </p>
        )}
      </aside>
    </div>
  )
}
