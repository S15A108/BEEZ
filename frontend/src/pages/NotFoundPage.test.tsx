/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import NotFoundPage from './NotFoundPage'

let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => restore())

function renderPage() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// PR #316 리뷰: 유효하지 않은 초대 링크는 보통 로그아웃 상태로 열린다. 나의 도시는 로그인으로 보내므로 버튼도 그 목적지를 말한다.
it('로그아웃 상태면 로그인하러 가는 버튼을 보인다', async () => {
  renderPage()
  expect(
    await screen.findByRole('link', { name: '로그인하러 가기' }),
  ).toHaveAttribute('href', '/login')
})

it('로그인 상태면 나의 도시로 가는 버튼을 보인다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  renderPage()
  expect(
    await screen.findByRole('link', { name: '나의 도시로' }),
  ).toHaveAttribute('href', '/')
})
