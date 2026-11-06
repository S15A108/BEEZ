/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { SettingsDialog } from './SettingsDialog'

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

// S15P31A108-285: 메뉴는 '연결 미확인', 본문은 '연결 전'처럼 갈렸고 확인에 실패해도 메뉴는 그대로였다.
it('설정 메뉴와 에이전트 화면이 같은 상태 이름을 보이고, 다른 메뉴에 갔다 와도 유지한다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.getState().openPane('settings', 'agent')
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <SettingsDialog
          projectId="101"
          isLeader
          agentConnected={false}
          memberId="21"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const menu = await screen.findByRole('navigation', { name: '설정 메뉴' })
  const status = await screen.findByRole('status')
  await waitFor(() => expect(status).toHaveTextContent(/^연결 미확인/))
  expect(
    within(menu).getByRole('button', { name: /에이전트/ }),
  ).toHaveTextContent('연결 미확인')
  await user.click(within(status).getByRole('button', { name: '연결 확인' }))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
  expect(
    within(menu).getByRole('button', { name: /에이전트/ }),
  ).toHaveTextContent('연결 실패')
  await user.click(within(menu).getByRole('button', { name: '팀' }))
  await user.click(within(menu).getByRole('button', { name: /에이전트/ }))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
})

// PR #72 재전수 SECOND-AMP-02: 토큰 발급처럼 요청이 처리 중이면 다른 메뉴로 옮기지 않아 한 번만 보이는 토큰을 잃지 않는다.
it('요청이 처리 중이면 설정의 다른 메뉴로 옮기지 못하고 안내를 보인다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  useWorkspaceStore.getState().openPane('settings', 'agent')
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SettingsDialog projectId="101" isLeader agentConnected={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const team = await screen.findByRole('button', { name: '팀' })
  expect(team).toBeEnabled()
  // 발급 요청이 끝나지 않은 상태를 흉내 낸다.
  act(() => {
    void client
      .getMutationCache()
      .build(client, { mutationFn: () => new Promise(() => undefined) })
      .execute(undefined)
  })
  expect(
    await screen.findByText(
      '처리 중이에요. 끝나면 다른 메뉴로 옮길 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '팀' })).toBeDisabled()
  expect(screen.getByRole('link', { name: /회원 설정/ })).toHaveAttribute(
    'aria-disabled',
    'true',
  )
})
