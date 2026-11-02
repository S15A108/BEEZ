import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import type { Account } from '@/api/contracts/common'
import { authApi } from '@/api/services'
import AuthPage from './AuthPage'

afterEach(() => vi.restoreAllMocks())

const account = (id: string, email: string): Account => ({
  id,
  email,
  name: email,
  created_at: '2026-10-23T00:00:00Z',
})

function renderLogin(client: QueryClient, from?: string) {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}>
        <Routes>
          <Route path="/login" element={<AuthPage />} />
          <Route path="/" element={<p>도시 화면</p>} />
          <Route path="/account/password" element={<p>비밀번호 변경 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function login(email: string) {
  const user = userEvent.setup()
  await user.clear(screen.getByRole('textbox', { name: /이메일/ }))
  await user.type(screen.getByRole('textbox', { name: /이메일/ }), email)
  const password = screen.getByLabelText(/^비밀번호( \*)?$/)
  await user.clear(password)
  await user.type(password, 'beez-2026!')
  await user.click(screen.getByRole('button', { name: '로그인' }))
}

// PR #72 3차 조사 THIRD-AMP-03: 회원 설정처럼 RequireAccount가 보관한 앱 안 주소로 돌아가고, 다른 사이트 주소는 막는다.
it('로그인 뒤 보관한 회원 설정 주소로 돌아간다', async () => {
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  renderLogin(new QueryClient(), '/account/password')
  await login('b@beez.local')
  expect(await screen.findByText('비밀번호 변경 화면')).toBeInTheDocument()
})

it('로그인 뒤 복귀 주소가 //로 시작하면 도시로 보낸다', async () => {
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  renderLogin(new QueryClient(), '//evil.example/account')
  await login('b@beez.local')
  await waitFor(() => expect(screen.getByText('도시 화면')).toBeInTheDocument())
})
