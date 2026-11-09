import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BibiPanel } from './BibiPanel'

const ask = vi.hoisted(() => vi.fn())
vi.mock('@/api/services', () => ({ beeApi: { ask } }))

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <BibiPanel projectId="1" />
    </QueryClientProvider>,
  )
}

const answer = {
  origin: 'MODEL',
  text: '승인된 문서를 확인했어요.',
  sources: [],
  unresolved: [],
}

describe('비비 질문 전송과 재시도', () => {
  beforeEach(() => {
    ask.mockReset()
  })

  it('빠른 연속 제출은 한 번만 전송하고 기다리는 질문을 표시한다', async () => {
    let resolve!: (value: typeof answer) => void
    ask.mockReturnValue(new Promise((done) => (resolve = done)))
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '인증 정책은?')
    const button = screen.getByRole('button', { name: '보내기' })
    const form = button.closest('form')!
    fireEvent.submit(form)
    fireEvent.submit(form)
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('status')).toHaveTextContent('찾는 중')
    expect(screen.getByText('인증 정책은?')).toBeInTheDocument()
    expect(button).toBeDisabled()
    await act(async () => resolve(answer))
    expect(await screen.findByText(answer.text)).toBeInTheDocument()
    expect(ask).toHaveBeenCalledWith('1', {
      question: '인증 정책은?',
      scope: 'APPROVED',
    })
  })

  it('질문이 실패하면 입력을 복원하고 다시 전송할 수 있다', async () => {
    ask
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(answer)
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '다시 보낼 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByLabelText('설계 질문')).toHaveValue('다시 보낼 질문')
    await user.click(screen.getByRole('button', { name: '다시 보내기' }))
    expect(await screen.findByText(answer.text)).toBeInTheDocument()
    expect(ask).toHaveBeenCalledTimes(2)
  })

  it('응답을 기다리며 새로 입력한 내용은 실패한 질문으로 덮어쓰지 않는다', async () => {
    let reject!: (error: Error) => void
    ask.mockReturnValue(new Promise((_resolve, fail) => (reject = fail)))
    renderPanel()
    const user = userEvent.setup()
    const input = screen.getByLabelText('설계 질문')
    await user.type(input, '먼저 보낸 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    await user.type(input, '새 질문 초안')
    await act(async () => reject(new Error('network')))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(input).toHaveValue('새 질문 초안')
  })
})
