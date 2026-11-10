/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getApiErrorCode } from '@/api/error'
import { authApi, beeApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { BibiPanel } from './BibiPanel'

// 질문은 브라우저 예시 API(BEE-A01)로 보낸다. 예시 프로젝트 101의 기획안은 승인 v1과 초안 v2가 있다.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <BibiPanel projectId="101" />
    </QueryClientProvider>,
  )
}

async function send(question: string) {
  await userEvent.type(screen.getByLabelText('설계 질문'), question)
  await userEvent.click(screen.getByRole('button', { name: '보내기' }))
}

it('찾는 범위를 그대로 두면 승인된 문서에서 찾고 대화가 쌓인다', async () => {
  const ask = vi.spyOn(beeApi, 'ask')
  renderPanel()
  expect(screen.getByRole('radio', { name: '승인된 문서' })).toBeChecked()

  await send('로그인 실패는 어떻게 처리하나요?')
  expect(await screen.findByText(/기획안의 승인 원문/)).toBeInTheDocument()
  expect(ask).toHaveBeenLastCalledWith('101', {
    question: '로그인 실패는 어떻게 처리하나요?',
    scope: 'APPROVED',
  })
  expect(screen.getByLabelText('설계 질문')).toHaveValue('')

  await send('초대 만료 안내는 정해졌나요?')
  expect(await screen.findAllByText(/기획안의 승인 원문/)).toHaveLength(2)
  expect(ask).toHaveBeenLastCalledWith('101', {
    question: '초대 만료 안내는 정해졌나요?',
    scope: 'APPROVED',
  })
  const log = screen.getByRole('log', { name: '비비 설계 질의응답' })
  expect(
    within(log).getByText('로그인 실패는 어떻게 처리하나요?'),
  ).toBeInTheDocument()
  expect(
    within(log).getByText('초대 만료 안내는 정해졌나요?'),
  ).toBeInTheDocument()
  // 근거 칸은 마지막 답의 근거(승인 v1)를 보여 준다.
  expect(
    within(screen.getByRole('complementary')).getByRole('button', {
      name: /문서 201 v1/,
    }),
  ).toBeInTheDocument()
})

it('보내기에 실패하면 오류를 알리고 질문을 입력칸에 돌려준다', async () => {
  vi.spyOn(beeApi, 'ask').mockRejectedValue(new Error('network'))
  renderPanel()
  await send('초대 링크는 며칠 동안 쓸 수 있나요?')
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.getByLabelText('설계 질문')).toHaveValue(
    '초대 링크는 며칠 동안 쓸 수 있나요?',
  )
  expect(
    within(screen.getByRole('log')).queryByText(
      '초대 링크는 며칠 동안 쓸 수 있나요?',
    ),
  ).not.toBeInTheDocument()
})

it('빈 질문은 보내지 않고 입력하라고 알린다', async () => {
  const ask = vi.spyOn(beeApi, 'ask')
  renderPanel()
  await userEvent.type(screen.getByLabelText('설계 질문'), '   ')
  await userEvent.click(screen.getByRole('button', { name: '보내기' }))
  expect(
    await screen.findByText('설계 질문을 입력해 주세요.'),
  ).toBeInTheDocument()
  expect(ask).not.toHaveBeenCalled()
})

// S15P31A108-361: BEE-A01(PR #252)은 scope=APPROVED만 받고 INCLUDE_DRAFTS는 400이다.
it('초안 포함은 곧 지원한다는 안내와 함께 고를 수 없고, 질문은 승인된 문서에서 찾는다', async () => {
  const ask = vi.spyOn(beeApi, 'ask')
  renderPanel()
  const drafts = screen.getByRole('radio', { name: '초안 포함' })
  expect(drafts).toBeDisabled()
  expect(drafts).toHaveAccessibleDescription('초안 포함은 곧 지원해요')

  await userEvent.click(drafts)
  expect(screen.getByRole('radio', { name: '승인된 문서' })).toBeChecked()
  await send('초대 만료 안내는 정해졌나요?')
  expect(await screen.findByText(/기획안의 승인 원문/)).toBeInTheDocument()
  expect(ask).toHaveBeenLastCalledWith('101', {
    question: '초대 만료 안내는 정해졌나요?',
    scope: 'APPROVED',
  })
})

const conflict = (code: string) =>
  new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: 'server text' } },
  })

it.each([
  [
    'index_not_ready',
    '답할 문서가 아직 준비되지 않았어요. 기준 설계가 만들어지고 문서 준비가 끝나면 다시 물어봐 주세요.',
  ],
  [
    'baseline_changed',
    '답을 만드는 사이 기준이 바뀌었어요. 다시 질문해 주세요.',
  ],
])(
  '질문이 409 %s로 거절되면 그 이유를 알리고 질문을 입력칸에 돌려준다',
  async (code, message) => {
    vi.spyOn(beeApi, 'ask').mockRejectedValue(conflict(code))
    renderPanel()
    await send('초대 링크는 며칠 동안 쓸 수 있나요?')
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByLabelText('설계 질문')).toHaveValue(
      '초대 링크는 며칠 동안 쓸 수 있나요?',
    )
  },
)

it('예시 API도 서버처럼 초안 포함은 400, 기준 설계가 없는 프로젝트는 409 index_not_ready로 거절한다', async () => {
  const question = '로그인 실패는 어떻게 처리하나요?'
  expect(
    await beeApi
      .ask('101', { question, scope: 'INCLUDE_DRAFTS' })
      .catch(getApiErrorCode),
  ).toBe('validation_failed')
  // 예시 프로젝트 102는 기준 설계가 없다.
  expect(
    await beeApi
      .ask('102', { question, scope: 'APPROVED' })
      .catch(getApiErrorCode),
  ).toBe('index_not_ready')
})
