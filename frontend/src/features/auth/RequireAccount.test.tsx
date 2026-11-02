import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { authApi } from '@/api/services'
import { RequireAccount } from './RequireAccount'

afterEach(() => vi.restoreAllMocks())

function LoginScreen() {
  const location = useLocation()
  return <p>로그인 화면 {(location.state as { from?: string }).from}</p>
}

// PR #72 재전수 SECOND-AMP-03: 계정 캐시가 남아 있어도 /me가 401이면 캐시를 지우고 로그인으로 보낸다(돌아올 주소 유지).
it('계정 캐시가 있어도 /me가 401이면 로그인으로 보내고 캐시를 지운다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['account'], {
    id: '11',
    email: 'demo@beez.local',
    name: '이팀장',
    created_at: '2026-10-23T00:00:00Z',
  })
  vi.spyOn(authApi, 'me').mockRejectedValue(
    new AxiosError('unauthorized', '401', undefined, undefined, {
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'unauthenticated', message: '로그인 필요' } },
    }),
  )
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/101?pane=tasks']}>
        <Routes>
          <Route
            path="/projects/:id"
            element={
              <RequireAccount>
                <p>보호 화면</p>
              </RequireAccount>
            }
          />
          <Route path="/login" element={<LoginScreen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText('로그인 화면 /projects/101?pane=tasks'),
  ).toBeInTheDocument()
  await waitFor(() => expect(client.getQueryData(['account'])).toBeUndefined())
})
