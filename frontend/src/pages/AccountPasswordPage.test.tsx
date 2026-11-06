/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi } from '@/api/services'
import type { MeResponse } from '@/features/auth/schemas'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import AccountPasswordPage from './AccountPasswordPage'

// 비밀번호 변경(ACC-A06)과 임시 비밀번호(ACC-A07)는 목 어댑터로 실제 경로를 탄다.
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
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/account/password']}>
        <Routes>
          <Route path="/account/password" element={<AccountPasswordPage />} />
          <Route path="/account" element={<p>회원 설정 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

async function change(current: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('현재 비밀번호'), current)
  await user.type(screen.getByLabelText('새 비밀번호'), 'beez-next-2026!')
  await user.type(screen.getByLabelText('새 비밀번호 확인'), 'beez-next-2026!')
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
}

it('현재 비밀번호가 틀리면 현재 비밀번호가 맞지 않는다고 알려 준다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  renderPage()
  await change('wrong-pass-1')

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '현재 비밀번호가 맞지 않아요.',
  )
})

it('임시 비밀번호로 로그인한 뒤 그 비밀번호를 현재 비밀번호로 바꾸면 변경 안내 상태가 풀린다', async () => {
  const sent = await authApi.forgotPassword({
    email: 'member@beez.local',
    name: '김동료',
  })
  const temporary = sent.message.match(/임시 비밀번호: (\S+)/)![1]
  await authApi.login({ email: 'member@beez.local', password: temporary })
  const client = renderPage()
  await waitFor(() =>
    expect(
      client.getQueryData<MeResponse>(['account'])?.must_change_password,
    ).toBe(true),
  )
  await change(temporary)

  expect(await screen.findByText('회원 설정 화면')).toBeInTheDocument()
  expect(
    client.getQueryData<MeResponse>(['account'])?.must_change_password,
  ).toBe(false)
})
