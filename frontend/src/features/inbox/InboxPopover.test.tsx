import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { inboxApi } from '@/api/services'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { InboxPopover } from './InboxPopover'

afterEach(() => vi.restoreAllMocks())

const notification = (id: string, read: boolean) => ({
  id,
  project_id: '10',
  type: 'CHANGE_PROPOSAL_SUBMITTED',
  title: `알림 ${id}`,
  body: '',
  target_type: 'CHANGE_PROPOSAL' as const,
  target_id: '1',
  created_at: '2026-11-01T00:00:00Z',
  ...(read ? { read_at: '2026-11-01T01:00:00Z' } : {}),
})

// PR #72 전수 리뷰 TC-6: 최신 20건이 모두 읽은 알림이어도, 그 밖의 안 읽은 알림을 배지와 '읽지 않음' 탭에 보인다.
it('최신 20건 밖의 안 읽은 알림도 배지와 읽지 않음 탭에 보인다', async () => {
  const user = userEvent.setup()
  vi.spyOn(inboxApi, 'list').mockImplementation(async (query) =>
    query?.unread
      ? { items: [notification('99', false)], has_more: false }
      : {
          items: Array.from({ length: 20 }, (_, index) =>
            notification(String(index + 1), true),
          ),
          has_more: true,
          next_cursor: 'next',
        },
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <InboxPopover projectId="10" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: '알림 1건' }))
  await user.click(screen.getByRole('button', { name: '읽지 않음 1' }))
  expect(screen.getByText('알림 99')).toBeInTheDocument()
  expect(screen.queryByText('알림 1')).not.toBeInTheDocument()
})

// PR #72 3차 조사 THIRD-TC-02: '모두 읽음'은 불러온 첫 20건 ID만 보내지 않고 이 프로젝트의 안 읽은 알림 전체를 읽음 처리한다.
it('모두 읽음은 첫 페이지 ID 대신 이 프로젝트 알림 전체 읽음을 보낸다', async () => {
  const user = userEvent.setup()
  vi.spyOn(inboxApi, 'list').mockImplementation(async () => ({
    items: Array.from({ length: 20 }, (_, index) =>
      notification(String(index + 1), false),
    ),
    has_more: true,
    next_cursor: 'next',
  }))
  const markRead = vi
    .spyOn(inboxApi, 'markRead')
    .mockResolvedValue({ updated_count: 21 })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <InboxPopover projectId="10" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: '알림 20+건' }))
  await user.click(screen.getByRole('button', { name: '모두 읽음' }))
  expect(markRead).toHaveBeenCalledWith({ all: true, project_id: '10' })
})

// S15P31A108-296: 문서 알림의 target_id는 문서 버전 ID다. 문서함은 문서 ID로 상세를 열므로 document_id로 연다.
it('문서 알림을 누르면 버전 ID가 아니라 그 문서의 상세를 연다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ pane: null, focusId: null })
  const document = {
    ...notification('30', false),
    type: 'DOCUMENT_APPROVED',
    title: '문서 승인',
    target_type: 'DOCUMENT_VERSION' as const,
    target_id: '42',
    document_id: '7',
  }
  vi.spyOn(inboxApi, 'list').mockResolvedValue({
    items: [document],
    has_more: false,
  })
  vi.spyOn(inboxApi, 'markRead').mockResolvedValue({ updated_count: 1 })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <InboxPopover projectId="10" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: '알림 1건' }))
  expect(screen.getByText(/공용 문서함/)).toBeInTheDocument()
  await user.click(screen.getByText('문서 승인'))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'documents',
    focusId: '7',
  })
})
