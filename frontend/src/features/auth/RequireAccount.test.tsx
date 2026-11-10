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

// S15P31A108-343: 배포 중 /me가 502여도 받아 둔 계정이 있으면 보호 화면을 오류로 바꾸지 않는다(열린 창·입력 유지).
it('계정 캐시가 있으면 /me가 5xx로 실패해도 보호 화면을 그대로 둔다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['account'], {
    id: '11',
    email: 'demo@beez.local',
    name: '이팀장',
    created_at: '2026-10-23T00:00:00Z',
  })
  const me = vi.spyOn(authApi, 'me').mockRejectedValue(
    new AxiosError('bad gateway', '502', undefined, undefined, {
      status: 502,
      statusText: 'Bad Gateway',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: '',
    }),
  )
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/101']}>
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
  await waitFor(() => expect(me).toHaveBeenCalled())
  await waitFor(() =>
    expect(client.getQueryState(['account'])?.status).toBe('error'),
  )
  expect(screen.getByText('보호 화면')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
