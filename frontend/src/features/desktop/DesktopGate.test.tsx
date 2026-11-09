import { act, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { authApi } from '@/api/services'
import { mockViewport } from '@/test/matchMedia'
import DesktopGate from './DesktopGate'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <DesktopGate />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
const notice = () =>
  screen.queryByRole('heading', { name: 'PC에서 이용해 주세요' })
const app = () => screen.queryByRole('heading', { name: 'BEEZ' })

it.each([
  '/',
  '/projects/1',
  '/account',
  '/account/password',
  '/account/withdraw',
  '/없는-주소',
])('폭 1023px 에서 %s 는 안내를 보이고 /me 를 부르지 않는다', (path) => {
  mockViewport(1023)
  const me = vi.spyOn(authApi, 'me')
  renderAt(path)
  expect(notice()).toBeInTheDocument()
  expect(app()).not.toBeInTheDocument()
  expect(me).not.toHaveBeenCalled()
})

it.each(['/login', '/register', '/invite', '/login/'])(
  '폭 1023px 에서도 %s 는 앱이 열린다',
  (path) => {
    mockViewport(1023)
    renderAt(path)
    expect(notice()).not.toBeInTheDocument()
    expect(app()).toBeInTheDocument()
  },
)

it.each(['/', '/account/password'])(
  '폭 1024px 에서 %s 는 기존 화면이다',
  (path) => {
    mockViewport(1024)
    renderAt(path)
    expect(notice()).not.toBeInTheDocument()
    expect(app()).toBeInTheDocument()
  },
)

it('폭이 바뀌면 같은 화면에서 안내와 앱이 바뀐다', () => {
  const viewport = mockViewport(390)
  renderAt('/')
  expect(notice()).toBeInTheDocument()
  act(() => viewport.resize(1280))
  expect(notice()).not.toBeInTheDocument()
  expect(app()).toBeInTheDocument()
  act(() => viewport.resize(800))
  expect(notice()).toBeInTheDocument()
})

it('폭 1024px 에서 워크스페이스 주소도 안내 없이 열린다', () => {
  mockViewport(1024)
  renderAt('/projects/1')
  expect(notice()).not.toBeInTheDocument()
})
