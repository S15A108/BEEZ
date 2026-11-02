/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { PlanningPanel } from './PlanningPanel'

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

// PR #72 6차 조사 SIXTH-DOC-02: 서버는 문서 4종이 모두 팀장 승인을 받아야 기준을 만든다(409 document_not_approved).
// 승인 전에는 발행 버튼을 끄고 이유와 공용 문서함으로 가는 길을 보인다.
it('문서 4종이 모두 승인되기 전에는 발행을 끄고 공용 문서함으로 보낸다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PlanningPanel projectId="102" isLeader />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/문서 4종이 모두 팀장 승인을 받아야/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '검토한 설계 승인·발행' }),
  ).toBeDisabled()
  await user.click(
    screen.getByRole('button', { name: '공용 문서함에서 승인하기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('documents')
})
