import { useEffect, useMemo, useRef, useState } from 'react'
import { MarkdownView } from '@/components/MarkdownView'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { findCitation, type CitationTarget } from '@/lib/citationTarget'

/** 실제 원문에서 유일하게 확인된 인용만 강조한다. */
export function VerifiedCitation({
  content,
  target,
}: {
  content?: string
  target: CitationTarget
}) {
  const anchor = useRef<HTMLElement>(null)
  const [formatted, setFormatted] = useState(false)
  const found = useMemo(
    () => (content === undefined ? undefined : findCitation(content, target)),
    [content, target],
  )
  useEffect(() => {
    if (!found || formatted) return
    anchor.current?.scrollIntoView?.({ block: 'center' })
    anchor.current?.focus({ preventScroll: true })
  }, [found, formatted])

  if (!found)
    return (
      <>
        <p
          role="alert"
          className="rounded-xl border border-destructive/20 bg-destructive/5 p-4 text-sm"
        >
          이 버전의 지정된 범위에서 인용 문장을 하나로 확인할 수 없어요. 원문을
          직접 확인해 주세요.
        </p>
        {content !== undefined ? (
          <MarkdownView source={content} className="max-h-[32rem]" />
        ) : (
          <EmptyState>
            이 파일은 본문 미리보기를 제공하지 않아요. 원본 파일을 내려받아
            확인해 주세요.
          </EmptyState>
        )}
      </>
    )
  return (
    <section
      aria-label="확인된 인용 원문"
      className="rounded-xl border bg-muted/30 p-4"
    >
      <p className="text-sm font-semibold">
        확인된 인용 원문 · {found.firstLine}~{found.lastLine}행
      </p>
      <Button
        variant="outline"
        size="sm"
        onClick={() => setFormatted((value) => !value)}
      >
        {formatted ? '원문과 인용 보기' : '형식 적용 본문 보기'}
      </Button>
      {formatted ? (
        <MarkdownView source={content!} className="max-h-[32rem]" />
      ) : (
        <pre className="mt-2 max-h-[32rem] overflow-auto text-sm leading-relaxed break-words whitespace-pre-wrap">
          {found.before}
          <mark
            ref={anchor}
            tabIndex={-1}
            className="rounded-sm bg-yellow-200 text-foreground"
          >
            {found.quote}
          </mark>
          {found.after}
        </pre>
      )}
    </section>
  )
}
