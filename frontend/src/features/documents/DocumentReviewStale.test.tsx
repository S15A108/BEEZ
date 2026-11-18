/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { documentsApi } from '@/api/services/documents'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { DocumentReview } from './DocumentReview'

// 참고 승인본이 바뀌어 비비 검토를 더 쓸 수 없을 때의 안내(S15P31A108-362).
// 409 시험이 모듈의 무효 검토 목록에 같은 검토 id를 남기므로, 서버 표시 시험을 먼저 둔다. 요청 글 시험은 DirectRequestDraft.test.tsx.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => {
  vi.restoreAllMocks()
  restore()
})

function mount() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DocumentReview
        projectId="102"
        documentId="221"
        title="기획안"
        kind="PLAN"
        isLeader
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  )
}

function apiError(status: number, code: string, message: string) {
  return new AxiosError(message, String(status), undefined, undefined, {
    status,
    statusText: message,
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message } },
  })
}

// 서버가 작업본 응답에 review_stale을 싣는 것처럼, 검토가 있을 때만 값을 바꿔 돌려준다.
function serverStale(state: { stale: boolean }) {
  const original = documentsApi.workingCopy.bind(documentsApi)
  return vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const copy = await original(...args)
      return { ...copy, review_stale: state.stale && !!copy.latest_review }
    })
}

it('서버가 검토를 쓸 수 없다고 알리면 409를 맞기 전에 무효 안내와 다시 받기만 보이고, 다시 받으면 사라진다', async () => {
  const user = userEvent.setup()
  const state = { stale: true }
  serverStale(state)
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  mount()
  expect(
    await screen.findByText(/이 검토는 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(1)
  // 예전 검토의 제안·팀장 요청은 보이지 않고, 자동으로 다시 검토하지도 않는다.
  expect(screen.queryByText(/비비가 수정 제안/)).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '팀장에게 검토 요청' }),
  ).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '반영하기' })).toBeNull()

  state.stale = false
  await user.click(screen.getByRole('button', { name: '비비 검토 다시 받기' }))
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(screen.queryByText(/이 검토는 더 쓸 수 없어요/)).toBeNull()
  expect(request).toHaveBeenCalledTimes(2)
})

it('review_stale 409는 본문이 바뀌었다고 단정하는 빨간 띠 없이 무효 카드만 보인다', async () => {
  const user = userEvent.setup()
  vi.spyOn(documentsApi, 'decideSuggestion').mockRejectedValue(
    apiError(409, 'review_stale', '바뀌었어요.'),
  )
  mount()
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(screen.getAllByRole('button', { name: '반영하기' })[0])
  expect(
    await screen.findByText(/이 검토는 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  expect(screen.queryByText(/본문이 바뀌어 비비 검토를/)).toBeNull()
  expect(screen.getAllByRole('alert')).toHaveLength(1)
})
