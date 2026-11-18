/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { documentsApi } from '@/api/services/documents'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { DocumentsPanel } from './DocumentsPanel'

// 비비 검토가 실패하면 단계 표시줄의 '파일 등록'으로 처음 등록 화면에 돌아가, 지금 등록된 파일이 선택된 채로
// 새 파일을 올린다(S15P31A108-382). 올리면 비비 검토 화면으로 돌아가 처음부터 다시 검토한다.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  useWorkspaceStore.setState({ pane: 'documents', focusId: 'review:221' })
})
afterEach(() => {
  localStorage.removeItem('beez.mock.reviewRun')
  cleanup()
  restore()
})

function mount() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DocumentsPanel projectId="102" isLeader />
    </QueryClientProvider>,
  )
}

async function openReupload(user: ReturnType<typeof userEvent.setup>) {
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount()
  await screen.findByText('비비 검토 시간이 초과됐어요')
  await user.click(
    screen.getByRole('button', { name: '이전 단계로 돌아가 파일 다시 올리기' }),
  )
}

it('처음 등록 화면으로 돌아가 지금 등록된 파일이 선택된 채로 열리고 제목은 바꿀 수 없다', async () => {
  const user = userEvent.setup()
  await openReupload(user)
  expect(
    await screen.findByRole('heading', { name: /파일 다시 올리기/ }),
  ).toBeInTheDocument()
  expect(screen.getByText('지금 등록된 파일')).toBeInTheDocument()
  expect(screen.getByLabelText('문서 제목 *')).toBeDisabled()
  // 같은 파일 그대로는 다시 올릴 수 없다
  expect(
    screen.getByRole('button', { name: '다시 올리고 비비 검토 받기' }),
  ).toBeDisabled()
})

it('새 파일을 골라 올리면 파일을 교체하고 검토 화면으로 돌아가 비비가 다시 검토한다', async () => {
  const user = userEvent.setup()
  const reupload = vi.spyOn(documentsApi, 'reuploadWorkingCopy')
  await openReupload(user)
  await screen.findByRole('heading', { name: /파일 다시 올리기/ })
  localStorage.removeItem('beez.mock.reviewRun')
  const next = new File(['# 줄인 계획\n로그인한다.\n'], 'plan-short.md', {
    type: 'text/markdown',
  })
  await user.upload(screen.getByLabelText('원본 파일 *'), next)
  expect(await screen.findByText('선택됨')).toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '다시 올리고 비비 검토 받기' }),
  )
  await waitFor(() => expect(reupload).toHaveBeenCalledTimes(1))
  expect(reupload.mock.calls[0][0]).toBe('102')
  expect(reupload.mock.calls[0][1]).toBe('221')
  expect(reupload.mock.calls[0][2]).toEqual({
    expected_revision: expect.any(Number),
    file: next,
  })
  // 검토 화면으로 돌아와 새 검토를 받는다
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(screen.getByText('파일을 다시 올렸어요')).toBeInTheDocument()
  reupload.mockRestore()
})

it('취소하면 검토 화면으로 돌아가고 파일은 바뀌지 않는다', async () => {
  const user = userEvent.setup()
  const reupload = vi.spyOn(documentsApi, 'reuploadWorkingCopy')
  await openReupload(user)
  await screen.findByRole('heading', { name: /파일 다시 올리기/ })
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(
    await screen.findByText('비비 검토 시간이 초과됐어요'),
  ).toBeInTheDocument()
  expect(reupload).not.toHaveBeenCalled()
  reupload.mockRestore()
})
