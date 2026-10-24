import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { beeApi } from '@/api/services'
import type { Generation } from '@/api/contracts/common'
import { ActionForm } from '@/components/ActionForm'
import { SourceLinks } from '@/components/SourceLinks'
import { Badge } from '@/components/ui/badge'

export function BibiPanel({ projectId }: { projectId: string }) {
  const [messages, setMessages] = useState<
    { question: string; answer: Generation }[]
  >([])
  const mutation = useMutation({
    mutationFn: (question: string) => beeApi.ask(projectId, { question }),
  })
  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex items-center gap-4">
        <span className="grid size-12 place-items-center rounded-2xl bg-primary/15">
          <Sparkles className="size-6 text-primary" />
        </span>
        <div>
          <h3 className="text-xl font-bold">AI 팀장 비비</h3>
          <p className="mt-1 text-xs text-muted-foreground">
            승인 설계에서 근거를 찾아 함께 생각해요.
          </p>
        </div>
      </div>
      <p className="text-sm leading-7 text-muted-foreground">
        설계의 의도, 요구사항의 근거, 변경 영향에 대해 질문하세요. 근거가
        부족하면 확인 질문으로 남기며, 최종 설계는 인간 팀장이 승인해요. 대화는
        이 화면을 연 동안만 표시돼요.
      </p>
      <div role="log" aria-label="비비 설계 질의응답" className="grid gap-5">
        {messages.map((message, index) => (
          <div key={index} className="space-y-3">
            <div className="ml-10 rounded-2xl bg-muted p-4 text-sm whitespace-pre-wrap">
              {message.question}
            </div>
            <article className="rounded-2xl border bg-primary/5 p-5">
              <div className="flex items-center justify-between">
                <strong className="text-sm">비비</strong>
                <Badge variant="outline">
                  {message.answer.origin === 'MODEL'
                    ? '모델 생성'
                    : '템플릿 응답'}
                </Badge>
              </div>
              <p className="mt-4 text-sm leading-7 whitespace-pre-wrap">
                {message.answer.text}
              </p>
              <SourceLinks sources={message.answer.sources} />
              {message.answer.unresolved.length > 0 && (
                <div className="mt-4 rounded-lg border border-primary/20 p-3">
                  <strong className="text-xs">사람의 확인이 필요해요</strong>
                  <ul className="mt-2 list-inside list-disc text-xs leading-6">
                    {message.answer.unresolved.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                </div>
              )}
            </article>
          </div>
        ))}
      </div>
      <ActionForm
        fields={[
          {
            name: 'question',
            label: '설계 질문',
            type: 'textarea',
            required: true,
            max: 2000,
            placeholder: '이 요구사항을 이렇게 정한 이유가 무엇인가요?',
          },
        ]}
        submitLabel="비비에게 질문하기"
        onSubmit={async (values) => {
          const answer = await mutation.mutateAsync(values.question)
          setMessages((previous) => [
            ...previous,
            { question: values.question, answer },
          ])
        }}
      />
    </div>
  )
}
