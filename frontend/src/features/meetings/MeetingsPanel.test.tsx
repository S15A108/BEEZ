/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { beforeEach, afterEach, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
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
import { MeetingsPanel } from './MeetingsPanel'

let restore: () => void
let client: QueryClient
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  useWorkspaceStore.getState().openPane('meetings')
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})
afterEach(() => {
  client.clear()
  restore()
})

it('creates a meeting, records text and produces clearly marked template minutes', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MeetingsPanel projectId="101" isLeader />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await screen.findByRole('button', { name: /초대 안내 문구 검토/ })
  await user.click(screen.getByRole('button', { name: '회의 만들기' }))
  const dialog = screen.getByRole('dialog')
  await user.type(within(dialog).getByLabelText(/회의 제목/), '첫 화면 검토')
  await user.click(within(dialog).getByRole('button', { name: '회의 만들기' }))
  const text = await screen.findByLabelText(/회의에 남길 내용/)
  await user.type(text, '기존 계약을 유지하고 설명을 보완해요.')
  await user.click(screen.getByRole('button', { name: '기록 남기기' }))
  await screen.findByText('기존 계약을 유지하고 설명을 보완해요.')
  await user.click(screen.getByRole('button', { name: '회의록 만들기' }))
  expect(
    await screen.findByText('예시 템플릿 · 실제 AI 호출 없음'),
  ).toBeInTheDocument()
  expect(
    await screen.findByRole('button', { name: '문서 수정 초안' }),
  ).toBeEnabled()
})
