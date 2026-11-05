import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { beeApi } from '@/api/services'
import type { StatusResponse } from '@/features/bee/schemas'
import { beeStatusQuery } from '@/features/bee/beeStatusQuery'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { BibiStatusRow } from './BibiStatusRow'

beforeEach(() => useWorkspaceStore.setState({ pane: null, focusId: null }))
afterEach(() => vi.restoreAllMocks())

// 상태를 먼저 받아 두고 그린다. 그린 뒤 다시 읽어도 같은 값이다.
async function renderRow(status: Promise<StatusResponse>) {
  vi.spyOn(beeApi, 'status').mockReturnValue(status)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  await client.prefetchQuery(beeStatusQuery('101'))
  render(
    <QueryClientProvider client={client}>
      <ul aria-label="팀 목록">
        <BibiStatusRow projectId="101" />
      </ul>
    </QueryClientProvider>,
  )
}

it('비비가 문서를 검토 중이면 문구를 보이고, 누르면 공용 문서함에서 그 문서를 연다', async () => {
  await renderRow(
    Promise.resolve({
      activity: 'DOCUMENT_REVIEW',
      target_type: 'DOCUMENT',
      target_id: '212',
      label: '알림 API 명세서 검토 중',
    }),
  )
  await userEvent.click(
    screen.getByRole('button', { name: /알림 API 명세서 검토 중/ }),
  )
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'documents',
    focusId: '212',
  })
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
