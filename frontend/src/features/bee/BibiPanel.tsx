import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { BibiFace } from '@/components/BibiFace'
import { beeApi } from '@/api/services'
import type { Generation } from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
import { ActionForm } from '@/components/ActionForm'
import { SourceLinks } from '@/components/SourceLinks'
import { Badge } from '@/components/ui/badge'

// 단축 바 가운데 비비 버튼(B안)으로 여는 대화. 대화는 창을 연 동안만 보인다.
export function BibiPanel({ projectId }: { projectId: string }) {
  const [messages, setMessages] = useState<
    { question: string; answer: Generation }[]
  >([])
  const mutation = useMutation({
    mutationFn: (question: string) => beeApi.ask(projectId, { question }),
    onSuccess: (answer, question) =>
      setMessages((previous) => [...previous, { question, answer }]),
  })
  const last = messages.at(-1)?.answer
  return (
    // 창 높이를 다 쓰고, 대화가 짧아도 질문 입력줄은 아래에 둔다.
    <div className="grid min-h-full gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
      <div className="flex min-w-0 flex-col gap-4">
        <div className="flex items-center gap-3">
          <BibiFace className="size-12 shrink-0" />
          <div>
            <h3 className="font-bold">
              비비{' '}
              <span className="text-sm font-medium text-muted-foreground">
                공용 문서에서 찾아 답해요
              </span>
            </h3>
            <p className="text-xs text-muted-foreground">
              답마다 찾은 문서를 붙여요. 최종 설계는 인간 팀장이 승인해요.
              {/* API 없음: 찾는 범위(승인된 문서 / 초안 포함) 파라미터가 생기면 여기에 고르는 칩을 둔다. */}
            </p>
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
          {mutation.isPending && (
            <p role="status" className="text-xs text-muted-foreground">
              비비가 문서를 찾는 중…
            </p>
          )}
          {mutation.error && (
            <p role="alert" className="text-sm text-destructive">
              {getApiErrorMessage(mutation.error)}
            </p>
          )}
        </div>
        <ActionForm
          key={messages.length}
          fields={[
            {
              name: 'question',
              label: '설계 질문',
              required: true,
              max: 2000,
              placeholder: '공용 문서에 대해 물어보세요',
            },
          ]}
          submitLabel="비비에게 질문하기"
          onSubmit={(values) =>
            mutation.mutateAsync(values.question).catch(() => undefined)
          }
        />
        <p className="text-xs text-muted-foreground">
          대화는 이 창을 연 동안만 보여요.
        </p>
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
            {last?.sources.length ?? 0}
          </span>
        </h3>
        {/* API 없음: 근거마다 문단 위치·인용 문장을 주면 번호와 칠한 문장으로 바꾼다. */}
        {last?.sources.length ? (
          <SourceLinks sources={last.sources} />
        ) : (
          <p className="mt-3 text-xs text-muted-foreground">
            질문하면 비비가 찾은 문서가 여기에 떠요. 근거를 못 찾으면 짐작하지
            않고 못 찾았다고 답해요.
          </p>
        )}
      </aside>
    </div>
  )
}
