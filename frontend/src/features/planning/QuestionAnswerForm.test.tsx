import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import type { PlanningQuestion } from '@/api/contracts/common'
import { QuestionAnswerForm } from './QuestionAnswerForm'

const show = (question: Partial<PlanningQuestion>) =>
  render(
    <QuestionAnswerForm
      question={{ id: '1', question: '질문', ...question }}
      onSubmit={vi.fn()}
      onClose={vi.fn()}
    />,
  )

// 서버(PlanningAnswers): 단답 기본 200·상한 500, 서술 기본 2000·상한 4000.
it('단답은 max_length 가 없으면 200자, 상한을 넘으면 500자로 막는다', () => {
  const { unmount } = show({
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER' },
  })
  expect(screen.getByText('0 / 200')).toBeInTheDocument()
  unmount()
  show({
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER', max_length: 900 },
  })
  expect(screen.getByText('0 / 500')).toBeInTheDocument()
})

it('서술은 max_length 가 없으면 2000자, 상한을 넘으면 4000자로 막는다', () => {
  const { unmount } = show({
    question_type: 'ESSAY',
    answer_format: { type: 'ESSAY' },
  })
  expect(screen.getByText('0 / 2000')).toBeInTheDocument()
  unmount()
  show({
    question_type: 'ESSAY',
    answer_format: { type: 'ESSAY', max_length: 9000 },
  })
  expect(screen.getByText('0 / 4000')).toBeInTheDocument()
})
