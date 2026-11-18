/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen, waitFor } from '@testing-library/react'
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

// 무효로 막혀도 비비에게 직접 요청하려던 글이 남는다(S15P31A108-362).
// DocumentReview는 409를 받은 검토 id를 모듈에 기억하므로, 다른 시험과 섞이지 않게 파일을 나눴다.
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

it('무효로 막혀도 비비에게 쓰던 요청 글은 남고, 새 검토가 나오면 이어서 보낼 수 있다', async () => {
  const user = userEvent.setup()
  mount()
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await screen.findByRole('heading', { name: '최종본 확인' })
  await user.type(
    screen.getByLabelText('비비에게 요청할 내용'),
    '오류 표에 403 행을 추가해 줘',
  )
  vi.spyOn(documentsApi, 'requestBeeSuggestion').mockRejectedValue(
    apiError(409, 'review_stale', '바뀌었어요.'),
  )
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  expect(
    await screen.findByText(/이 검토는 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  expect(screen.getByText(/쓰던 요청 글은 지우지 않았어요/)).toBeInTheDocument()

  // 새 검토를 받아 다시 최종본까지 오면 쓰던 글이 그대로 있다.
  await user.click(screen.getByRole('button', { name: '비비 검토 다시 받기' }))
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await waitFor(() =>
    expect(screen.getByLabelText('비비에게 요청할 내용')).toHaveValue(
      '오류 표에 403 행을 추가해 줘',
    ),
  )
})
