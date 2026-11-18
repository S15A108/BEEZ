import { z } from 'zod'
import { idSchema } from '@/api/contracts/common'

// 이 정보는 pane focus에만 보관한다. workspaceStore는 pane/focusId를 영구 저장하지 않는다.
const targetSchema = z
  .object({
    document_id: idSchema,
    document_version_id: idSchema,
    start_line: z.number().int().positive(),
    end_line: z.number().int().positive(),
    // AI 계약의 1000자는 UTF-16 코드 단위가 아닌 유니코드 코드포인트다.
    quote: z
      .string()
      .min(1)
      .max(2000)
      .refine((value) => [...value].length <= 1000)
      .refine((value) => value.trim().length > 0 && value.trim() === value),
  })
  .refine((value) => value.end_line >= value.start_line)

export type CitationTarget = z.infer<typeof targetSchema>

export function citationFocus(target: CitationTarget): string {
  return `citation:${JSON.stringify(targetSchema.parse(target))}`
}

export function parseCitationFocus(focus: string): CitationTarget | undefined {
  if (!focus.startsWith('citation:')) return undefined
  try {
    const result = targetSchema.safeParse(JSON.parse(focus.slice(9)))
    return result.success ? result.data : undefined
  } catch {
    return undefined
  }
}

/** 현재 버전의 실제 본문에서 주어진 행 범위의 유일한 원문 일치만 표시한다. */
export function findCitation(content: string, target: CitationTarget) {
  const normalized = content.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
  const lines = normalized.split('\n')
  if (target.end_line > lines.length) return undefined
  const window = lines.slice(target.start_line - 1, target.end_line).join('\n')
  const position = window.indexOf(target.quote)
  if (position < 0 || window.lastIndexOf(target.quote) !== position)
    return undefined
  const rangeBefore = window.slice(0, position)
  const firstLine = target.start_line + (rangeBefore.match(/\n/g)?.length ?? 0)
  const offset =
    lines
      .slice(0, target.start_line - 1)
      .reduce((n, line) => n + line.length + 1, 0) + position
  const lastLine = firstLine + (target.quote.match(/\n/g)?.length ?? 0)
  return {
    before: normalized.slice(0, offset),
    quote: target.quote,
    after: normalized.slice(offset + target.quote.length),
    firstLine,
    lastLine,
  }
}
