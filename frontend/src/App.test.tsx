import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import type { MeResponse } from '@/features/auth/schemas'
import App from './App'

afterEach(() => vi.restoreAllMocks())

it('기존 앱 진입점에서 BEEZ 브랜드를 보여주고 목데이터 안내는 없다', () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(screen.getByRole('heading', { name: 'BEEZ' })).toBeInTheDocument()
  expect(
    screen.queryByText('목데이터로 미리 보기 · 실제 서버 호출 없음'),
  ).not.toBeInTheDocument()
})

const account = (must_change_password: boolean): MeResponse => ({
  id: '12',
  email: 'member@beez.local',
  name: '김동료',
  created_at: '2026-10-23T00:00:00Z',
  must_change_password,
})

function renderApp(path: string, me: MeResponse) {
  vi.spyOn(authApi, 'me').mockResolvedValue(me)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<App />}>
            <Route index element={<p>도시 화면</p>} />
            <Route
              path="account/password"
              element={<p>비밀번호 변경 화면</p>}
            />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// S15P31A108-283: 임시 비밀번호(ACC-A07)로 로그인하면 회원 설정 › 비밀번호 변경으로 안내만 하고 강제로 옮기지 않는다(Q04).
it('임시 비밀번호로 로그인했으면 비밀번호 변경 화면으로 가는 안내를 보여 준다', async () => {
  renderApp('/', account(true))

  expect(
    await screen.findByRole('link', { name: '비밀번호 변경' }),
  ).toHaveAttribute('href', '/account/password')
  expect(screen.getByText('도시 화면')).toBeInTheDocument()
})

it('비밀번호를 바꿀 필요가 없으면 안내를 보여 주지 않는다', async () => {
  renderApp('/', account(false))
  expect(await screen.findByText('김동료')).toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: '비밀번호 변경' }),
  ).not.toBeInTheDocument()
})

it('비밀번호 변경 화면에서는 안내를 다시 보여 주지 않는다', async () => {
  renderApp('/account/password', account(true))
  expect(await screen.findByText('김동료')).toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: '비밀번호 변경' }),
  ).not.toBeInTheDocument()
})
