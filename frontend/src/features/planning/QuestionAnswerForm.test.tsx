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

// ---- 질문을 넘겨 가며 답하기 (S15P31A108-382) ----
import { fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'

const short = (id: string, status: PlanningQuestion['status'] = 'OPEN') =>
  ({
    id,
    number: Number(id),
    question: `질문 ${id}`,
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER', max_length: 200 },
    status,
  }) as PlanningQuestion

// 부모(PlanningQuestions)처럼 현재 질문 id 를 들고 있는 작은 틀
function Harness({
  list,
  onSubmit,
  onClose = vi.fn(),
  start = '1',
}: {
  list: PlanningQuestion[]
  onSubmit: (input: unknown) => Promise<unknown>
  onClose?: () => void
  start?: string
}) {
  const [id, setId] = useState(start)
  const current = list.find((item) => item.id === id)!
  return (
    <QuestionAnswerForm
      question={current}
      siblings={list}
      onNavigate={setId}
      onSubmit={onSubmit}
      onClose={onClose}
    />
  )
}

it('저장하고 다음을 누르면 저장한 뒤 다음 질문으로 넘어가고 마지막은 저장하고 닫는다', async () => {
  const user = userEvent.setup()
  const submit = vi.fn().mockResolvedValue(undefined)
  const close = vi.fn()
  render(
    <Harness
      list={[short('1'), short('2')]}
      onSubmit={submit}
      onClose={close}
    />,
  )
  expect(screen.getByText('질문 1 / 2')).toBeInTheDocument()
  await user.type(screen.getByLabelText('답변'), '첫 답')
  await user.click(screen.getByRole('button', { name: '저장하고 다음' }))
  await waitFor(() =>
    expect(screen.getByText('질문 2 / 2')).toBeInTheDocument(),
  )
  expect(submit).toHaveBeenCalledWith({
    answer_payload: { type: 'SHORT_ANSWER', text: '첫 답' },
  })
  expect(close).not.toHaveBeenCalled()
  // 마지막 질문은 '저장'이고 저장하면 닫는다
  await user.type(screen.getByLabelText('답변'), '둘째 답')
  await user.click(screen.getByRole('button', { name: '저장' }))
  await waitFor(() => expect(close).toHaveBeenCalledTimes(1))
})

it('저장하지 않고 이전·다음으로 넘겨도 쓰던 답이 사라지지 않는다', async () => {
  const user = userEvent.setup()
  render(<Harness list={[short('1'), short('2')]} onSubmit={vi.fn()} />)
  await user.type(screen.getByLabelText('답변'), '쓰던 글')
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(screen.getByLabelText('답변')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: /^이전/ }))
  expect(screen.getByLabelText('답변')).toHaveValue('쓰던 글')
})

it('첫 질문은 이전이, 마지막 질문은 다음이 막혀 있다', async () => {
  const user = userEvent.setup()
  render(<Harness list={[short('1'), short('2')]} onSubmit={vi.fn()} />)
  expect(screen.getByRole('button', { name: /^이전/ })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(screen.getByRole('button', { name: /^다음/ })).toBeDisabled()
})

it('남은 질문만 넘기기가 기본이라 이미 답한 질문은 건너뛰고, 끄면 모두 넘긴다', async () => {
  const user = userEvent.setup()
  render(
    <Harness
      list={[short('1'), short('2', 'ANSWERED'), short('3')]}
      onSubmit={vi.fn()}
    />,
  )
  expect(screen.getByText('질문 1 / 3')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  // 답한 2번은 건너뛰고 3번으로 가며, 번호는 전체 목록 기준이다
  expect(screen.getByText('질문 3 / 3')).toBeInTheDocument()
  await user.click(screen.getByRole('checkbox', { name: '남은 질문만 넘기기' }))
  expect(screen.getByText('질문 3 / 3')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /^이전/ }))
  expect(screen.getByText('질문 2')).toBeInTheDocument()
})

it('번호를 누르면 그 질문으로 바로 가고 상태를 함께 알려 준다', async () => {
  const user = userEvent.setup()
  render(
    <Harness
      list={[short('1'), short('2', 'ANSWERED'), short('3', 'DISMISSED')]}
      onSubmit={vi.fn()}
    />,
  )
  expect(screen.getByText('답변 1 · 닫음 1 · 남음 1')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '질문 3 닫음' }))
  expect(screen.getByRole('button', { name: '질문 3 닫음' })).toHaveAttribute(
    'aria-current',
    'step',
  )
})

it('Ctrl+Enter 로 저장하고 다음으로 넘어간다', async () => {
  const submit = vi.fn().mockResolvedValue(undefined)
  render(<Harness list={[short('1'), short('2')]} onSubmit={submit} />)
  fireEvent.change(screen.getByLabelText('답변'), {
    target: { value: '빠른 답' },
  })
  fireEvent.keyDown(screen.getByLabelText('답변'), {
    key: 'Enter',
    ctrlKey: true,
  })
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1))
  await waitFor(() =>
    expect(screen.getByText('질문 2 / 2')).toBeInTheDocument(),
  )
})

it('저장이 실패하면 넘어가지 않고 이유를 보인다', async () => {
  const user = userEvent.setup()
  render(
    <Harness
      list={[short('1'), short('2')]}
      onSubmit={vi.fn().mockRejectedValue(new Error('x'))}
    />,
  )
  await user.type(screen.getByLabelText('답변'), '답')
  await user.click(screen.getByRole('button', { name: '저장하고 다음' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.getByText('질문 1 / 2')).toBeInTheDocument()
})

it('목록 없이 하나만 열면 이전·다음·번호 없이 예전처럼 저장하고 닫는다', async () => {
  const user = userEvent.setup()
  const submit = vi.fn().mockResolvedValue(undefined)
  const close = vi.fn()
  render(
    <QuestionAnswerForm
      question={short('1')}
      onSubmit={submit}
      onClose={close}
    />,
  )
  expect(
    screen.queryByRole('list', { name: '질문 목록' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: /^다음/ }),
  ).not.toBeInTheDocument()
  await user.type(screen.getByLabelText('답변'), '답')
  await user.click(screen.getByRole('button', { name: '저장' }))
  await waitFor(() => expect(close).toHaveBeenCalledTimes(1))
})
