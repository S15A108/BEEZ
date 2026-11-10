import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AxiosError, AxiosHeaders } from 'axios'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
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

function httpError(status: number, code?: string) {
  const headers = new AxiosHeaders()
  const config = { headers }
  return new AxiosError('question failed', undefined, config, undefined, {
    status,
    statusText: '',
    headers,
    config,
    data: code === undefined ? undefined : { error: { code } },
  })
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

  it('실패 시 새 입력을 보존하고 같은 질문과 범위일 때만 다시 보내기를 표시한다', async () => {
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
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled()

    await user.clear(input)
    await user.type(input, '먼저 보낸 질문')
    expect(screen.getByRole('button', { name: '다시 보내기' })).toBeEnabled()

    await user.click(screen.getByRole('radio', { name: '초안 포함' }))
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled()
  })

  it('응답 유실과 다른 입력에 사용한 키를 구분하고 자동으로 재호출하지 않는다', async () => {
    ask
      .mockRejectedValueOnce(new AxiosError('response lost', 'ERR_NETWORK'))
      .mockRejectedValueOnce(httpError(409, 'idempotency_conflict'))
      .mockResolvedValueOnce(answer)
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '요청 확인')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('처리됐을 수')
    expect(screen.getByRole('alert')).toHaveTextContent('추가 호출')
    expect(ask).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '다시 보내기' }))
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        '같은 요청이 다른 내용으로 이미 처리됐어요.',
      ),
    )
    expect(ask).toHaveBeenCalledTimes(2)
    await user.click(screen.getByRole('button', { name: '다시 보내기' }))
    expect(await screen.findByText(answer.text)).toBeInTheDocument()
    expect(ask).toHaveBeenCalledTimes(3)
  })

  it.each([
    ['본문 없는 502', httpError(502), '이전 요청이 처리됐을 수 있어요.'],
    ['501', httpError(501), '이 기능은 서버에서 아직 준비 중이에요.'],
    [
      '서버가 알려 준 분석 시간 초과',
      httpError(504, 'ai_timeout'),
      '분석 시간이 초과되었습니다.',
    ],
    [
      '서버가 알려 준 분석 실패',
      httpError(503, 'ai_unavailable'),
      '분석을 완료하지 못했습니다.',
    ],
    [
      '응답 형식 오류',
      new z.ZodError([]),
      '서버 응답 형식이 API 계약과 다릅니다.',
    ],
    [
      '명세에 없는 오류 코드',
      httpError(409, 'draft_search_not_supported'),
      '요청을 완료하지 못했습니다.',
    ],
  ])('%s에 맞는 문구를 표시한다', async (_scenario, error, message) => {
    ask.mockRejectedValueOnce(error)
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '설계 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})
