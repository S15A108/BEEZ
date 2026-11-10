/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
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

it('찾는 범위를 그대로 두면 승인된 문서에서, 초안 포함을 고르면 초안까지 찾고 대화가 쌓인다', async () => {
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

  await userEvent.click(screen.getByRole('radio', { name: '초안 포함' }))
  await send('초대 만료 안내는 정해졌나요?')
  expect(await screen.findByText(/기획안의 초안 v2/)).toBeInTheDocument()
  expect(ask).toHaveBeenLastCalledWith('101', {
    question: '초대 만료 안내는 정해졌나요?',
    scope: 'INCLUDE_DRAFTS',
  })
  const log = screen.getByRole('log', { name: '비비 설계 질의응답' })
  expect(
    within(log).getByText('로그인 실패는 어떻게 처리하나요?'),
  ).toBeInTheDocument()
  expect(
    within(log).getByText('초대 만료 안내는 정해졌나요?'),
  ).toBeInTheDocument()
  // 근거 칸은 마지막 답의 근거(초안 v2)를 보여 준다.
  expect(
    within(screen.getByRole('complementary')).getByRole('button', {
      name: /문서 201 v2/,
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
