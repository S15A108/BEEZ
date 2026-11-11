/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import CityPage from './CityPage'

// jsdom에는 WebGL이 없어 도시 3D는 그리지 않는다.
vi.mock('@/three/CityScene', () => ({ default: () => null }))

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

async function openCreate() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<CityPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const user = userEvent.setup()
  await user.click(
    (await screen.findAllByRole('button', { name: /프로젝트 만들기/ }))[0]!,
  )
  return user
}
const createDialog = () =>
  screen.queryByRole('dialog', { name: '프로젝트 만들기' })

// 11-10 QA(S15P31A108-371): 이름·소개를 쓰던 중 Esc를 누르면 확인 없이 닫혀 쓴 내용이 사라졌다.
it('프로젝트 만들기 창에 쓴 내용이 있으면 Esc로 바로 닫지 않고 확인한 뒤 닫는다', async () => {
  const user = await openCreate()
  await user.type(screen.getByLabelText(/^프로젝트 이름/), '새 도시')
  await user.keyboard('{Escape}')

  const confirm = await screen.findByRole('dialog', {
    name: '만들던 프로젝트를 닫을까요?',
  })
  await user.click(within(confirm).getByRole('button', { name: '계속 쓰기' }))
  expect(screen.getByLabelText(/^프로젝트 이름/)).toHaveValue('새 도시')

  await user.keyboard('{Escape}')
  await user.click(
    within(
      await screen.findByRole('dialog', {
        name: '만들던 프로젝트를 닫을까요?',
      }),
    ).getByRole('button', { name: '버리고 닫기' }),
  )
  expect(createDialog()).not.toBeInTheDocument()
})

it('프로젝트 만들기 창에 아무것도 쓰지 않았으면 Esc로 바로 닫는다', async () => {
  const user = await openCreate()
  expect(createDialog()).toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(createDialog()).not.toBeInTheDocument()
})
