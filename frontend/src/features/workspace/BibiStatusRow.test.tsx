/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { authApi, beeApi } from '@/api/services'
import { DocumentsPanel } from '@/features/documents/DocumentsPanel'
import type { StatusResponse } from '@/features/bee/schemas'
import { beeStatusQuery } from '@/features/bee/beeStatusQuery'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { BibiStatusRow } from './BibiStatusRow'

let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  useWorkspaceStore.setState({ pane: null, focusId: null })
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

// 워크스페이스(WorkspacePage)처럼 고른 창이 문서함이면 공용 문서함을 연다.
function DocumentsPane({ projectId }: { projectId: string }) {
  const pane = useWorkspaceStore((state) => state.pane)
  return pane === 'documents' ? (
    <DocumentsPanel projectId={projectId} isLeader />
  ) : null
}

// 상태를 먼저 받아 두고 그린다. 그린 뒤 다시 읽어도 같은 값이다.
async function renderRow(status: Promise<StatusResponse>, projectId = '101') {
  vi.spyOn(beeApi, 'status').mockReturnValue(status)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  await client.prefetchQuery(beeStatusQuery(projectId))
  render(
    <QueryClientProvider client={client}>
      <ul aria-label="팀 목록">
        <BibiStatusRow projectId={projectId} />
      </ul>
      <DocumentsPane projectId={projectId} />
    </QueryClientProvider>,
  )
}

// 비비 검토(DOC-A16)는 대개 첫 승인 전에 돈다. 승인본이 없는 문서를 상세(DOC-A03)로 열면 오류라 검토 화면으로 연다.
it('비비가 승인 전 문서를 검토 중이면 문구를 보이고, 누르면 공용 문서함에서 그 문서의 검토 화면을 연다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  await renderRow(
    Promise.resolve({
      activity: 'DOCUMENT_REVIEW',
      target_type: 'DOCUMENT',
      target_id: '221',
      label: '기획안 검토 중',
    }),
    '102',
  )
  await userEvent.click(screen.getByRole('button', { name: /기획안 검토 중/ }))
  const review = await screen.findByRole('region', { name: '기획안 검토' })
  expect(
    await within(review).findByRole('list', { name: '검토 단계' }),
  ).toBeInTheDocument()
  expect(screen.queryByText(/정보를 불러오지 못했어요/)).not.toBeInTheDocument()
})

it('비비가 회의 중이면 키보드로 눌러 회의실에서 그 회의를 연다', async () => {
  await renderRow(
    Promise.resolve({
      activity: 'MEETING',
      target_type: 'MEETING',
      target_id: '901',
      label: "'초대 안내 문구 검토' 진행 중",
    }),
  )
  await userEvent.tab()
  expect(
    screen.getByRole('button', { name: /'초대 안내 문구 검토' 진행 중/ }),
  ).toHaveFocus()
  await userEvent.keyboard('{Enter}')
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'meetings',
    focusId: '901',
  })
})

it('비비가 한가하면 비비 줄을 그리지 않는다', async () => {
  await renderRow(Promise.resolve({ activity: 'IDLE' }))
  expect(screen.getByRole('list', { name: '팀 목록' })).toBeEmptyDOMElement()
})

it('서버에 비비 상태 API가 없으면 비비 줄을 그리지 않는다', async () => {
  await renderRow(
    Promise.reject(
      new AxiosError('Not Found', 'ERR_BAD_REQUEST', undefined, undefined, {
        status: 404,
        statusText: 'Not Found',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
      }),
    ),
  )
  expect(screen.getByRole('list', { name: '팀 목록' })).toBeEmptyDOMElement()
})
