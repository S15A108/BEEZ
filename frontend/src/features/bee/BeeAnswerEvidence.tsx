import { SourceLinks } from '@/components/SourceLinks'
import type { AskResponse, BeeCitation } from './schemas'

function citationSource(citation: BeeCitation) {
  const { start_line, end_line } = citation.locator
  const section = citation.section_title ? `${citation.section_title} · ` : ''
  return {
    document_id: citation.document_id,
    document_version_id: citation.document_version_id,
    version_no: citation.version_no,
    location: `${section}${start_line}~${end_line}행`,
  }
}

/** 검증 수준은 모델의 확인 방식이며, 사람의 최종 검토를 대신하지 않는다. */
function verificationText(answer: AskResponse) {
  if (answer.grounded === false) return '이 답변에서 확인된 근거가 없어요.'
  if (answer.verification?.level === 'ENTAILMENT')
    return '모델이 근거와 답변의 관계를 확인했어요. 중요한 내용은 직접 확인해 주세요.'
  if (answer.verification?.level === 'TOKENS')
    return '인용과 표기를 확인했어요. 답변의 의미는 직접 확인해 주세요.'
  return '근거 확인 수준은 제공되지 않았어요.'
}

export function BeeAnswerEvidence({ answer }: { answer: AskResponse }) {
  // 인용에서 이미 표시한 출처와 별개로 설계 항목에 연결된 출처도 보여 준다.
  const citedSources = new Set(
    (answer.citations ?? []).map((citation) =>
      JSON.stringify([
        citation.document_id,
        citation.document_version_id,
        citation.section_title
          ? `${citation.section_title} / L${citation.locator.start_line}-L${citation.locator.end_line}`
          : `L${citation.locator.start_line}-L${citation.locator.end_line}`,
      ]),
    ),
  )
  const otherSources = answer.sources.filter(
    (source) =>
      !citedSources.has(
        JSON.stringify([
          source.document_id,
          source.document_version_id,
          source.location,
        ]),
      ),
  )
  return (
    <div className="mt-3 grid gap-3 text-sm">
      <p className="text-xs text-muted-foreground">
        {verificationText(answer)}
      </p>
      {answer.citations?.map((citation) => (
        <section
          key={citation.n}
          aria-label={`인용 ${citation.n}번`}
          className="rounded-lg border bg-background p-3"
        >
          <p className="font-medium">
            [{citation.n}] {citation.title} v{citation.version_no}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {citation.section_title && `${citation.section_title} · `}
            {citation.locator.start_line}~{citation.locator.end_line}행
          </p>
          <blockquote className="mt-2 border-l-2 pl-2 whitespace-pre-wrap">
            {citation.quote}
          </blockquote>
          {citation.claim && (
            <p className="mt-2 text-xs text-muted-foreground">
              {citation.claim}
            </p>
          )}
          <SourceLinks sources={[citationSource(citation)]} />
        </section>
      ))}
      {!!answer.design_refs?.length && (
        <div>
          <p className="font-medium">참고한 설계 항목</p>
          <ul className="mt-1 list-inside list-disc text-xs">
            {answer.design_refs.map((item) => (
              <li key={item.design_item_version_id}>
                {item.key} · v{item.version_no}
              </li>
            ))}
          </ul>
        </div>
      )}
      {!!answer.task_refs?.length && (
        <div>
          <p className="font-medium">참고한 작업</p>
          <ul className="mt-1 list-inside list-disc text-xs">
            {answer.task_refs.map((task) => (
              <li key={task.task_id}>{task.key}</li>
            ))}
          </ul>
        </div>
      )}
      {!!otherSources.length && (
        <div>
          <p className="font-medium">참고 문서</p>
          <SourceLinks sources={otherSources} />
        </div>
      )}
      {!!answer.citations?.length && (
        <p className="text-xs text-muted-foreground">
          문서를 열면 인용한 버전으로 이동해요. 표시된 행은 문서에서 직접 확인해
          주세요.
        </p>
      )}
    </div>
  )
}
