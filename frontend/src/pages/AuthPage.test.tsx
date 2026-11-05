import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
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

// PR #72 3차 조사 THIRD-AMP-01: 로그인 전 세션(A)의 /me가 늦게 와도 새로 로그인한 계정(B)을 덮지 않는다.
it('이전 세션의 늦은 /me 응답이 새로 로그인한 계정을 덮지 않는다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  let answerA: (value: Account) => void = () => undefined
  void client
    .fetchQuery({
      queryKey: ['account'],
      queryFn: () => new Promise<Account>((done) => (answerA = done)),
    })
    .catch(() => undefined)
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  renderLogin(client)
  await login('b@beez.local')
  expect(await screen.findByText('도시 화면')).toBeInTheDocument()
  answerA(account('11', 'a@beez.local'))
  await new Promise((done) => setTimeout(done, 0))
  expect(client.getQueryData<Account>(['account'])?.id).toBe('12')
})

// PR #72 3차 조사 THIRD-AMP-03: 회원 설정처럼 RequireAccount가 보관한 앱 안 주소로 돌아가고, 다른 사이트 주소는 막는다.
it('로그인 뒤 보관한 회원 설정 주소로 돌아간다', async () => {
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  renderLogin(new QueryClient(), '/account/password')
  await login('b@beez.local')
  expect(await screen.findByText('비밀번호 변경 화면')).toBeInTheDocument()
})

// PR #134 메일 링크(/projects/{id}?invite=): 로그인·가입을 오가도 초대 토큰과 프로젝트 ID를 함께 넘긴다.
it('초대로 들어온 로그인은 가입 링크와 로그인 뒤 초대 화면에 프로젝트 ID를 함께 넘긴다', async () => {
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter
        initialEntries={['/login?invite=mock-invite-7&project=101']}
      >
        <Routes>
          <Route path="/login" element={<AuthPage />} />
          <Route path="/invite" element={<InviteScreen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(screen.getByRole('link', { name: '회원가입' })).toHaveAttribute(
    'href',
    '/register?invite=mock-invite-7&project=101',
  )
  await login('b@beez.local')
  expect(
    await screen.findByText('초대 화면 ?invite=mock-invite-7&project=101'),
  ).toBeInTheDocument()
})

function InviteScreen() {
  return <p>초대 화면 {useLocation().search}</p>
}

it('로그인 뒤 복귀 주소가 //로 시작하면 도시로 보낸다', async () => {
  vi.spyOn(authApi, 'login').mockResolvedValue(account('12', 'b@beez.local'))
  renderLogin(new QueryClient(), '//evil.example/account')
  await login('b@beez.local')
  await waitFor(() => expect(screen.getByText('도시 화면')).toBeInTheDocument())
})
