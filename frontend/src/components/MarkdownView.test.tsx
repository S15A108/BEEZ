import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { MarkdownView } from './MarkdownView'

it('제목·목록·표·코드·인용을 마크다운대로 그리고 # 기호는 보이지 않는다', () => {
  render(
    <MarkdownView
      source={[
        '# 기획안',
        '## 목표',
        '팀이 **같은** 기준으로 일한다.',
        '- 인간 팀장',
        '- 팀원',
        '| 구분 | 필드 |',
        '| --- | --- |',
        '| 요청 | `email` |',
        '```',
        '{ "email": "string" }',
        '```',
        '> 비밀번호는 담지 않는다.',
      ].join('\n')}
    />,
  )
  expect(
    screen.getByRole('heading', { level: 2, name: '기획안' }),
  ).toBeVisible()
  expect(screen.getByRole('heading', { level: 3, name: '목표' })).toBeVisible()
  expect(screen.getByText('같은').tagName).toBe('STRONG')
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(screen.getByRole('columnheader', { name: '필드' })).toBeVisible()
  expect(screen.getByText('email').tagName).toBe('CODE')
  expect(screen.getByText('{ "email": "string" }').tagName).toBe('PRE')
  expect(screen.getByText('비밀번호는 담지 않는다.').tagName).toBe('BLOCKQUOTE')
  expect(screen.queryByText(/^#/)).not.toBeInTheDocument()
})
