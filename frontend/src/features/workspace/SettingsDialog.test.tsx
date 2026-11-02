/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { act, render, screen } from '@testing-library/react'
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
