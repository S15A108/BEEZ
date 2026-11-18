import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

// 공용 문서 본문 뷰어(PNL-02 수정안 v367 면형): 연회색 면, 본문 15px·줄간격 1.7·폭 680px.
// 문서는 .md만 올라오므로 제목·목록·표·코드·인용·굵게만 그린다. HTML은 넣지 않고 React 요소로만 만든다.
// ponytail: 줄 단위로 읽는 간단한 해석기다. 중첩 목록·링크·이미지가 필요해지면 라이브러리를 검토한다.
const soft = 'bg-[#eceef2]'

function inline(text: string): ReactNode[] {
  return text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).map((part, index) =>
    part.startsWith('`') && part.endsWith('`') && part.length > 1 ? (
      <code
        key={index}
        className={cn('rounded-[5px] px-1.5 py-px font-mono text-[13px]', soft)}
      >
        {part.slice(1, -1)}
      </code>
    ) : part.startsWith('**') && part.endsWith('**') && part.length > 3 ? (
      <strong key={index}>{part.slice(2, -2)}</strong>
    ) : (
      part
    ),
  )
}

const cells = (line: string) =>
  line
    .trim()
    .replace(/^\||\|$/g, '')
    .split('|')
    .map((cell) => cell.trim())

export function MarkdownView({
  source,
  className,
}: {
  source: string
  className?: string
}) {
  const lines = source.replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let index = 0
  while (index < lines.length) {
    const line = lines[index]
    const key = blocks.length
    if (!line.trim()) {
      index++
      continue
    }
    if (line.startsWith('```')) {
      const body: string[] = []
      index++
      while (index < lines.length && !lines[index].startsWith('```'))
        body.push(lines[index++])
      index++
      blocks.push(
        <pre
          key={key}
          className={cn(
            'mb-4 overflow-auto rounded-[10px] px-4 py-3.5 font-mono text-[13px] leading-[1.6] text-[#111827]',
            soft,
          )}
        >
          {body.join('\n')}
        </pre>,
      )
      continue
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const text = inline(heading[2])
      blocks.push(
        level === 1 ? (
          <h2
            key={key}
            className="mb-1.5 text-[26px] leading-[1.35] font-bold tracking-[-0.01em] text-[#111827]"
          >
            {text}
          </h2>
        ) : level === 2 ? (
          <h3
            key={key}
            className="mt-9 mb-3 border-b border-[#e5e7eb] pb-2 text-xl leading-[1.4] font-bold text-[#111827]"
          >
            {text}
          </h3>
        ) : (
          <h4
            key={key}
            className="mt-6 mb-2 text-[16.5px] leading-normal font-semibold text-[#111827]"
          >
            {text}
          </h4>
        ),
      )
      index++
      continue
    }
    if (
      line.trim().startsWith('|') &&
      /^\s*\|?[\s:-]+\|/.test(lines[index + 1] ?? '')
    ) {
      const head = cells(line)
      const rows: string[][] = []
      index += 2
      while (index < lines.length && lines[index].trim().startsWith('|'))
        rows.push(cells(lines[index++]))
      blocks.push(
        <table
          key={key}
          className="mt-1 mb-4 w-full border-collapse text-sm leading-[1.6]"
        >
          <thead>
            <tr>
              {head.map((cell, column) => (
                <th
                  key={column}
                  scope="col"
                  className={cn(
                    'border-b border-[#d1d5db] px-3 py-[9px] text-left font-semibold text-[#111827]',
                    soft,
                  )}
                >
                  {inline(cell)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((cell, column) => (
                  <td
                    key={column}
                    className="border-b border-[#e5e7eb] px-3 py-[9px]"
                  >
                    {inline(cell)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>,
      )
      continue
    }
    if (line.startsWith('>')) {
      const quote: string[] = []
      while (index < lines.length && lines[index].startsWith('>'))
        quote.push(lines[index++].replace(/^>\s?/, ''))
      blocks.push(
        <blockquote
          key={key}
          className="mb-3 border-l-[3px] border-[#e0b400] px-3.5 py-1.5 text-[#4b5563]"
        >
          {inline(quote.join(' '))}
        </blockquote>,
      )
      continue
    }
    const listItem = /^\s*(?:[-*+]|\d+\.)\s+/
    if (listItem.test(line)) {
      const ordered = /^\s*\d+\./.test(line)
      const items: string[] = []
      while (index < lines.length && listItem.test(lines[index]))
        items.push(lines[index++].replace(listItem, ''))
      const List = ordered ? 'ol' : 'ul'
      blocks.push(
        <List
          key={key}
          className={cn(
            'mb-3 pl-[22px]',
            ordered ? 'list-decimal' : 'list-disc',
          )}
        >
          {items.map((item, itemIndex) => (
            <li key={itemIndex}>{inline(item)}</li>
          ))}
        </List>,
      )
      continue
    }
    const paragraph: string[] = []
    while (
      index < lines.length &&
      lines[index].trim() &&
      !/^(#{1,6}\s|```|>|\s*(?:[-*+]|\d+\.)\s|\s*\|)/.test(lines[index])
    )
      paragraph.push(lines[index++])
    if (!paragraph.length) paragraph.push(lines[index++])
    blocks.push(
      // 문단 안 줄바꿈은 그대로 둔다(줄마다 쓴 문서가 한 줄로 붙지 않게).
      <p key={key} className="mb-3 whitespace-pre-line">
        {inline(paragraph.join('\n'))}
      </p>,
    )
  }
  return (
    <article
      className={cn(
        'overflow-auto rounded-[14px] bg-[#f7f8fa] px-10 pt-8 pb-9 text-[15px] leading-[1.7] text-[#1f2937] select-text',
        className,
      )}
    >
      <div className="max-w-[680px]">{blocks}</div>
    </article>
  )
}
