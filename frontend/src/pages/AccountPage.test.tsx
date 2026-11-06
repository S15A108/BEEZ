/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
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
import AccountPage from './AccountPage'

// 프로필 저장(ACC-A05)은 목 어댑터로 실제 경로를 탄다. 데모 계정(이팀장)의 아바타는 예전 값 bee-yellow라 화면에는 '벌'로 보인다.
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

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/account']}>
        <Routes>
          <Route path="/account" element={<AccountPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return {
    user: userEvent.setup(),
    save: await screen.findByRole('button', { name: '프로필 저장' }),
    name: screen.getByLabelText('표시 이름'),
  }
}

it('바꾼 것이 없으면 프로필 저장 버튼이 꺼져 있고 앞뒤 공백만 더해도 그대로다', async () => {
  const { user, save, name } = await renderPage()
  expect(save).toBeDisabled()
  await user.type(name, '  ')
  expect(save).toBeDisabled()
})

it('이름을 바꿔 저장하면 한 번만 보내고 저장했다고 알린 뒤 버튼을 다시 끈다', async () => {
  const update = vi.spyOn(authApi, 'updateMe')
  const { user, save, name } = await renderPage()
  await user.clear(name)
  await user.type(name, '새 이름')
  expect(save).toBeEnabled()
  await user.click(save)
  expect(await screen.findByRole('status')).toHaveTextContent('저장했어요')
  expect(update).toHaveBeenCalledTimes(1)
  expect(update.mock.calls[0][0]).toEqual({ name: '새 이름', avatar: 'bee' })
  await waitFor(() => expect(save).toBeDisabled())

  await user.type(name, '요')
  expect(save).toBeEnabled()
  expect(screen.queryByText('저장했어요')).not.toBeInTheDocument()
})

it('캐릭터를 바꾸면 버튼이 켜지고 원래 캐릭터로 되돌리면 다시 꺼진다', async () => {
  const { user, save } = await renderPage()
  await user.click(screen.getByRole('radio', { name: '로봇' }))
  expect(save).toBeEnabled()
  await user.click(screen.getByRole('radio', { name: '벌' }))
  expect(save).toBeDisabled()
})

it('저장이 실패하면 이유를 보이고 다시 누를 수 있다', async () => {
  vi.spyOn(authApi, 'updateMe').mockRejectedValueOnce(new Error('서버 오류'))
  const { user, save } = await renderPage()
  await user.click(screen.getByRole('radio', { name: '로봇' }))
  await user.click(save)
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.queryByText('저장했어요')).not.toBeInTheDocument()
  expect(save).toBeEnabled()

  await user.click(save)
  expect(await screen.findByRole('status')).toHaveTextContent('저장했어요')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
