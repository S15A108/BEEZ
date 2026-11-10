import { Fragment, useEffect, useId, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { BibiFace } from '@/components/BibiFace'
import { useBibiMessages } from './useBibiMessages'
import { beeApi } from '@/api/services'
import type { Generation } from '@/api/contracts/common'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { SourceLinks } from '@/components/SourceLinks'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import type { AskScope } from './schemas'

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

// 단축 바 가운데 비비 버튼(B안)으로 여는 대화. 대화는 창을 연 동안만 보인다.
export function BibiPanel({ projectId }: { projectId: string }) {
  const scopeLabel = useId()
  const errorId = useId()
  const [scope, setScope] = useState<AskScope>('APPROVED')
  const [messages, setMessages] = useBibiMessages<Ask & { answer: Generation }>(
    projectId,
  )
  const mutation = useMutation({
    mutationFn: (input: Ask) => beeApi.ask(projectId, input),
    onSuccess: (answer, input) =>
      setMessages((previous) => [...previous, { ...input, answer }]),
  })
  const {
    register,
    handleSubmit,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<QuestionInput>({
    resolver: zodResolver(questionSchema),
    defaultValues: { question: '' },
  })
  // 보내면 입력칸을 바로 비우고, 실패하면 그사이 새로 쓴 글이 없을 때만 질문을 돌려준다.
  const send = handleSubmit(({ question }) => {
    setValue('question', '')
    return mutation.mutateAsync({ question, scope }).catch(() => {
      if (!getValues('question')) setValue('question', question)
    })
  })
  const end = useRef<HTMLDivElement>(null)
  // 새 질문·답이 붙으면 입력줄까지 내려 마지막 대화가 보이게 한다.
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'nearest' })
  }, [messages.length, mutation.isPending])
  const pending = mutation.isPending ? mutation.variables : undefined
  const last = messages.at(-1)
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
                  {message.answer.text}
                </p>
                {message.answer.unresolved.length > 0 && (
                  <ul className="mt-2 list-inside list-disc text-xs text-muted-foreground">
                    {message.answer.unresolved.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
                <span className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                  찾은 문서 {message.answer.sources.length}개
                  {/* 초안까지 찾은 답은 승인 전 내용이 섞였을 수 있다(명세: 미승인 검토임을 표시). */}
                  {message.scope === 'INCLUDE_DRAFTS' && (
                    <Badge variant="outline">초안 포함</Badge>
                  )}
                  <Badge variant="outline">
                    {message.answer.origin === 'MODEL'
                      ? '모델 생성'
                      : '템플릿 응답'}
                  </Badge>
                  {/* API 없음: '도움이 됐어요 / 근거가 틀려요' 피드백 저장 */}
                </span>
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
          {mutation.error && (
            <p role="alert" className="text-sm text-destructive">
              {askErrors[getApiErrorCode(mutation.error) ?? ''] ??
                getApiErrorMessage(mutation.error)}
            </p>
          )}
        </div>
        {/* 대화가 길어져도 입력줄은 창 아래에 붙어 있다. 업무 창의 아래 여백(최대 2rem)으로 흘러내린 대화는 그림자로 가린다. */}
        <div className="sticky bottom-0 grid gap-2 border-t bg-background pt-3 shadow-[0_2rem_0_var(--background)]">
          <form noValidate onSubmit={send} className="flex gap-2">
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
              보내기
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
        aria-labelledby="bibi-sources"
        className="rounded-2xl border bg-muted/30 p-4"
      >
        <h3
          id="bibi-sources"
          className="flex justify-between text-sm font-bold"
        >
          이 답의 근거
          <span className="text-muted-foreground">
            {last?.answer.sources.length ?? 0}
          </span>
        </h3>
        {/* API 없음: 근거마다 문단 위치·인용 문장을 주면 번호와 칠한 문장으로 바꾼다. */}
        {last?.answer.sources.length ? (
          <SourceLinks sources={last.answer.sources} />
        ) : (
          <p className="mt-3 text-xs text-muted-foreground">
            질문하면 비비가 찾은 문서가 여기에 떠요. 근거를 못 찾으면 짐작하지
            않고 못 찾았다고 답해요.
          </p>
        )}
        {last && (
          <p className="mt-3 text-xs text-muted-foreground">
            {last.scope === 'APPROVED'
              ? '승인된 문서만 찾았어요.'
              : '초안까지 찾았어요. 초안은 아직 팀장이 승인하지 않은 내용이에요.'}
          </p>
        )}
      </aside>
    </div>
  )
}
