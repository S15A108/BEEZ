import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { PlanningQuestion } from '@/api/contracts/common'
import { QuestionAnswerForm } from './QuestionAnswerForm'
import { AUTOSAVE_DELAY_MS } from './useAnswerAutosave'

// 자동 저장은 입력을 멈추고 1초 뒤에 보낸다. 시간을 직접 넘긴다.
beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
})
afterEach(() => {
  vi.useRealTimers()
})
const setup = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
const pass = (ms = AUTOSAVE_DELAY_MS) =>
  act(() => vi.advanceTimersByTimeAsync(ms))

const show = (question: Partial<PlanningQuestion>, onSave = vi.fn()) =>
  render(
    <QuestionAnswerForm
      question={{ id: '1', question: '질문', ...question }}
      onSave={onSave}
    />,
  )

const short = (id: string, status: PlanningQuestion['status'] = 'OPEN') =>
  ({
    id,
    number: Number(id),
    question: `질문 ${id}`,
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER', max_length: 200 },
    status,
  }) as PlanningQuestion

// 부모(PlanningQuestions)처럼 지금 질문 id 를 들고 있는 작은 틀. 목록에서 다른 질문을 여는 버튼도 둔다.
function Harness({
  list,
  onSave,
}: {
  list: PlanningQuestion[]
  onSave: (id: string, input: unknown) => Promise<unknown>
}) {
  const [id, setId] = useState(list[0].id)
  return (
    <>
      {list.map((item) => (
        <button key={item.id} type="button" onClick={() => setId(item.id)}>
          목록에서 {item.id} 열기
        </button>
      ))}
      <QuestionAnswerForm
        question={list.find((item) => item.id === id)!}
        siblings={list}
        onNavigate={setId}
        onSave={onSave}
      />
    </>
  )
}

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

it('선택형은 고르는 즉시 저장하고 저장됨을 보인다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  show(
    {
      question_type: 'CHOICE',
      answer_format: {
        type: 'CHOICE',
        options: ['팀장만', '모두'],
        multiple: false,
        allow_other: true,
      },
    },
    onSave,
  )
  await user.click(screen.getByLabelText('팀장만'))
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: { type: 'CHOICE', selected: ['팀장만'] },
  })
  expect(await screen.findByText('저장됨')).toBeInTheDocument()
  // '기타' 글은 글 답변처럼 입력을 멈춘 뒤 저장한다. 고르기만 하고 비어 있으면 저장하지 않는다.
  onSave.mockClear()
  await user.click(screen.getByLabelText('기타'))
  expect(onSave).not.toHaveBeenCalled()
  expect(screen.getByText('빈 답은 저장하지 않아요')).toBeInTheDocument()
  await user.type(screen.getByLabelText('기타 답변'), '팀장이 정한 사람')
  expect(onSave).not.toHaveBeenCalled()
  await pass()
  expect(onSave).toHaveBeenCalledTimes(1)
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: {
      type: 'CHOICE',
      selected: [],
      other_text: '팀장이 정한 사람',
    },
  })
})

it('글 답변은 입력을 멈추고 1초 뒤에 한 번 저장한다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  show(short('1'), onSave)
  await user.type(screen.getByLabelText('답변'), '팀장만')
  expect(screen.getByText('저장 중…')).toBeInTheDocument()
  await pass(AUTOSAVE_DELAY_MS - 100)
  expect(onSave).not.toHaveBeenCalled()
  await pass(100)
  expect(onSave).toHaveBeenCalledTimes(1)
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: { type: 'SHORT_ANSWER', text: '팀장만' },
  })
  expect(await screen.findByText('저장됨')).toBeInTheDocument()
})

it('칸을 벗어나면 기다리지 않고 바로 저장한다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  show(short('1'), onSave)
  await user.type(screen.getByLabelText('답변'), '바로')
  await user.tab()
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: { type: 'SHORT_ANSWER', text: '바로' },
  })
})

it('빈 칸은 저장하지 않는다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  show(short('1'), onSave)
  await user.type(screen.getByLabelText('답변'), '   ')
  await pass()
  await user.tab()
  expect(onSave).not.toHaveBeenCalled()
  expect(screen.getByText('빈 답은 저장하지 않아요')).toBeInTheDocument()
})

it('저장이 실패하면 쓴 글을 그대로 두고 다시 시도할 수 있다', async () => {
  const user = setup()
  const onSave = vi
    .fn()
    .mockRejectedValueOnce(new Error('x'))
    .mockResolvedValue(undefined)
  show(short('1'), onSave)
  await user.type(screen.getByLabelText('답변'), '지키는 글')
  await pass()
  expect(await screen.findByText('저장 못 했어요')).toBeInTheDocument()
  expect(screen.getByRole('alert')).toBeInTheDocument()
  expect(screen.getByLabelText('답변')).toHaveValue('지키는 글')
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(onSave).toHaveBeenCalledTimes(2)
  expect(await screen.findByText('저장됨')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).toBeNull()
})

it('같은 질문에 저장이 겹치면 보내는 중에 바뀐 마지막 값만 이어서 보낸다', async () => {
  const user = setup()
  let finish = () => {}
  const onSave = vi.fn().mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve
      }),
  )
  show(short('1'), onSave)
  const box = screen.getByLabelText('답변')
  await user.type(box, '가')
  await pass()
  expect(onSave).toHaveBeenCalledTimes(1)
  // 첫 저장이 끝나기 전에 두 번 더 바꾼다.
  await user.type(box, '나')
  await pass()
  await user.type(box, '다')
  await pass()
  expect(onSave).toHaveBeenCalledTimes(1)
  await act(async () => finish())
  expect(onSave).toHaveBeenCalledTimes(2)
  expect(onSave).toHaveBeenLastCalledWith('1', {
    answer_payload: { type: 'SHORT_ANSWER', text: '가나다' },
  })
})

it('목록이나 다음으로 다른 질문으로 옮기면 저장 안 된 글을 바로 저장한다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  render(
    <Harness list={[short('1'), short('2'), short('3')]} onSave={onSave} />,
  )
  await user.type(screen.getByLabelText('답변'), '첫 답')
  await user.click(screen.getByRole('button', { name: '목록에서 2 열기' }))
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: { type: 'SHORT_ANSWER', text: '첫 답' },
  })
  expect(screen.getByText('질문 2 / 3')).toBeInTheDocument()
  // 키보드로 옮길 때도 같다(다음 버튼).
  fireEvent.change(screen.getByLabelText('답변'), {
    target: { value: '둘째 답' },
  })
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(onSave).toHaveBeenCalledWith('2', {
    answer_payload: { type: 'SHORT_ANSWER', text: '둘째 답' },
  })
  expect(screen.getByText('질문 3 / 3')).toBeInTheDocument()
})

it('저장 결과와 상관없이 이전·다음으로 넘겨도 쓰던 답이 사라지지 않는다', async () => {
  const user = setup()
  render(
    <Harness
      list={[short('1'), short('2')]}
      onSave={vi.fn().mockRejectedValue(new Error('x'))}
    />,
  )
  await user.type(screen.getByLabelText('답변'), '쓰던 글')
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(screen.getByLabelText('답변')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: /^이전/ }))
  expect(screen.getByLabelText('답변')).toHaveValue('쓰던 글')
})

it('첫 질문은 이전이, 마지막 질문은 다음이 막혀 있다', async () => {
  const user = setup()
  render(<Harness list={[short('1'), short('2')]} onSave={vi.fn()} />)
  expect(screen.getByRole('button', { name: /^이전/ })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(screen.getByRole('button', { name: /^다음/ })).toBeDisabled()
})

it('Ctrl+Enter 로 다음 질문으로 넘어가며 쓰던 글을 저장한다', async () => {
  const onSave = vi.fn().mockResolvedValue(undefined)
  render(<Harness list={[short('1'), short('2')]} onSave={onSave} />)
  fireEvent.change(screen.getByLabelText('답변'), {
    target: { value: '빠른 답' },
  })
  fireEvent.keyDown(screen.getByLabelText('답변'), {
    key: 'Enter',
    ctrlKey: true,
  })
  expect(screen.getByText('질문 2 / 2')).toBeInTheDocument()
  expect(onSave).toHaveBeenCalledWith('1', {
    answer_payload: { type: 'SHORT_ANSWER', text: '빠른 답' },
  })
})

// 오른쪽 상세에 놓인 폼은 왼쪽 목록으로도 질문이 바뀐다. 앞 질문의 실패를 끌고 오지 않는다.
it('목록에서 다른 질문을 열면 앞 질문의 실패를 보이지 않고, 돌아오면 다시 보인다', async () => {
  const user = setup()
  render(
    <Harness
      list={[short('1'), short('2')]}
      onSave={vi.fn().mockRejectedValue(new Error('x'))}
    />,
  )
  await user.type(screen.getByLabelText('답변'), '답')
  await user.tab()
  expect(await screen.findByText('저장 못 했어요')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '목록에서 2 열기' }))
  expect(screen.queryByText('저장 못 했어요')).toBeNull()
  expect(screen.queryByRole('alert')).toBeNull()
  await user.click(screen.getByRole('button', { name: '목록에서 1 열기' }))
  expect(screen.getByText('저장 못 했어요')).toBeInTheDocument()
})

it('목록 없이 하나만 열면 이전·다음 없이 자동 저장만 한다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  show(short('1'), onSave)
  expect(screen.queryByRole('button', { name: /^다음/ })).toBeNull()
  expect(screen.queryByRole('button', { name: /^저장/ })).toBeNull()
  await user.type(screen.getByLabelText('답변'), '답')
  await pass()
  expect(onSave).toHaveBeenCalledTimes(1)
})

it('답하지 않고 닫으면 기다리던 저장은 보내지 않고 닫기만 보낸 뒤 다음 질문으로 간다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  render(<Harness list={[short('1'), short('2')]} onSave={onSave} />)
  await user.type(screen.getByLabelText('답변'), '버릴 글')
  await user.click(screen.getByRole('button', { name: '답변하지 않고 닫기' }))
  await pass()
  expect(onSave).toHaveBeenCalledTimes(1)
  expect(onSave).toHaveBeenCalledWith('1', { status: 'DISMISSED' })
  expect(screen.getByText('질문 2 / 2')).toBeInTheDocument()
  // 돌아오면 '저장 중…'도, 버린 글도 남아 있지 않다(서버에는 답 없이 닫혔다).
  await user.click(screen.getByRole('button', { name: '목록에서 1 열기' }))
  expect(screen.queryByText('저장 중…')).toBeNull()
  expect(screen.getByLabelText('답변')).toHaveValue('')
})

it('보내는 중인 저장이 있으면 끝난 뒤에 답하지 않고 닫기를 보낸다', async () => {
  const user = setup()
  let finish = () => {}
  const onSave = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValue(undefined)
  render(<Harness list={[short('1'), short('2')]} onSave={onSave} />)
  await user.type(screen.getByLabelText('답변'), '먼저 간 글')
  await user.tab()
  expect(onSave).toHaveBeenCalledTimes(1)
  await user.click(screen.getByRole('button', { name: '답변하지 않고 닫기' }))
  expect(onSave).toHaveBeenCalledTimes(1)
  await act(async () => finish())
  expect(onSave).toHaveBeenLastCalledWith('1', { status: 'DISMISSED' })
  // 보내던 저장이 끝나도 '저장됨'으로 덮지 않는다(서버에는 답 없이 닫혔다).
  await user.click(screen.getByRole('button', { name: '목록에서 1 열기' }))
  expect(screen.queryByText('저장됨')).toBeNull()
  expect(screen.getByLabelText('답변')).toHaveValue('')
})

// 리뷰 표 3: 여러 개 고르는 질문에서 고른 직후 해제
it('여러 개 고르는 질문에서 고른 직후 바로 해제하면 보내던 저장이 끝나도 저장됨으로 덮지 않는다', async () => {
  const user = setup()
  let finish = () => {}
  const onSave = vi.fn().mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve
      }),
  )
  show(
    {
      question_type: 'CHOICE',
      answer_format: {
        type: 'CHOICE',
        options: ['앱 안 알림', '이메일'],
        multiple: true,
        allow_other: false,
      },
    },
    onSave,
  )
  await user.click(screen.getByLabelText('앱 안 알림'))
  expect(onSave).toHaveBeenCalledTimes(1)
  await user.click(screen.getByLabelText('앱 안 알림'))
  expect(screen.getByText('빈 답은 저장하지 않아요')).toBeInTheDocument()
  await act(async () => finish())
  expect(onSave).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('저장됨')).toBeNull()
  expect(screen.getByText('빈 답은 저장하지 않아요')).toBeInTheDocument()
})

// PR #303 재검토 1: '입력하고 Tab 으로 다음'으로 가다 닫기 버튼에 와 있을 때 저장되어 버튼이 사라져도 포커스를 잃지 않는다.
it('키보드로 답을 쓰고 Tab 으로 닫기 버튼에 왔을 때 저장돼 버튼이 사라지면 포커스를 다음 버튼으로 옮긴다', async () => {
  const user = setup()
  const onSave = vi.fn().mockResolvedValue(undefined)
  render(<Harness list={[short('1'), short('2')]} onSave={onSave} />)
  await user.type(screen.getByLabelText('답변'), '답')
  await user.tab()
  // 칸을 벗어나며 바로 저장하고, 저장되면 '답변하지 않고 닫기'가 사라진다.
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '답변하지 않고 닫기' }),
    ).toBeNull(),
  )
  expect(screen.getByRole('button', { name: /^다음/ })).toHaveFocus()
})

// PR #303 리뷰(인라인): 닫기 응답을 기다리는 동안 같은 질문에 글을 써도 새 자동 저장이 생기지 않는다.
// 막지 않으면 DISMISSED → 입력칸 비워짐 → 1초 뒤 answer_payload 순으로 보내져 서버가 닫은 질문을 다시 '답함'으로 바꾼다.
it('답하지 않고 닫는 동안에는 그 질문의 입력과 자동 저장을 막아, 닫은 뒤 답이 다시 보내지지 않는다', async () => {
  const user = setup()
  let finish = () => {}
  const onSave = vi
    .fn()
    .mockImplementation((_id: string, input: { status?: string }) =>
      input.status === 'DISMISSED'
        ? new Promise<void>((resolve) => {
            finish = resolve
          })
        : Promise.resolve(),
    )
  render(<Harness list={[short('1'), short('2')]} onSave={onSave} />)
  const box = screen.getByLabelText('답변')
  await user.type(box, '버릴 글')
  await user.click(screen.getByRole('button', { name: '답변하지 않고 닫기' }))
  await waitFor(() =>
    expect(onSave).toHaveBeenCalledWith('1', { status: 'DISMISSED' }),
  )
  // 닫기 응답을 기다리는 동안 더 써 보지만 입력이 막혀 있다.
  await user.type(box, ' 더')
  expect(box).toHaveValue('버릴 글')
  await pass(AUTOSAVE_DELAY_MS * 2)
  await act(async () => finish())
  await pass(AUTOSAVE_DELAY_MS * 2)
  expect(onSave).toHaveBeenCalledTimes(1)
  expect(onSave).toHaveBeenLastCalledWith('1', { status: 'DISMISSED' })
})
