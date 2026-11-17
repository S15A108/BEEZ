import { z } from 'zod'

// 서버(SourceRemapper.lines)와 같은 줄 규칙: LF로 나누고, 마지막 LF 뒤에는 빈 줄을 만들지 않는다.
export function documentLines(content: string): string[] {
  if (!content) return []
  const lines = content.split('\n')
  if (lines.at(-1) === '') lines.pop()
  return lines
}

// 출처 위치 지정 폼(PLN-A11). 줄 수를 알면(원문을 읽었으면) 문서 밖 범위를 미리 막는다.
export function sourceLinesSchema(lineCount: number) {
  const line = z
    .string()
    .trim()
    .regex(/^[1-9][0-9]*$/, '1 이상의 줄 번호를 입력해 주세요.')
  return z
    .object({ start_line: line, end_line: line })
    .superRefine((value, context) => {
      const start = Number(value.start_line)
      const end = Number(value.end_line)
      if (end < start)
        context.addIssue({
          code: 'custom',
          path: ['end_line'],
          message: '끝 줄은 시작 줄보다 앞설 수 없어요.',
        })
      else if (lineCount > 0 && end > lineCount)
        context.addIssue({
          code: 'custom',
          path: ['end_line'],
          message: `문서는 ${lineCount}줄까지 있어요.`,
        })
    })
}
export type SourceLinesForm = z.infer<ReturnType<typeof sourceLinesSchema>>
