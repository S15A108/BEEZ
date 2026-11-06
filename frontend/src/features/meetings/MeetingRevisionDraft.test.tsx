import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { changesApi, documentsApi, meetingsApi } from '@/api/services'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { MeetingRevisionDraft } from './MeetingRevisionDraft'

vi.mock('@/api/services', () => ({
  meetingsApi: {
    latestSummary: vi.fn(),
    latestRevisionDraft: vi.fn(),
    summary: vi.fn(),
    revisionDraft: vi.fn(),
  },
  documentsApi: { list: vi.fn() },
  changesApi: { scopedBlocks: vi.fn() },
}))

beforeEach(() => {
  vi.resetAllMocks()
  const time = '2026-11-06T00:00:00Z'
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
    id: '9',
    meeting_id: '2',
    through_entry_id: '3',
    entry: {
      id: '4',
      meeting_id: '2',
      kind: 'SUMMARY',
      text: '',
      created_at: time,
    },
    conflicts: [],
    decisions: [{ text: '팀장이 관리한다' }],
    document_changes: [],
    action_items: [],
    created_at: time,
  })
  vi.mocked(meetingsApi.latestRevisionDraft).mockRejectedValue({
    isAxiosError: true,
    response: { status: 404 },
  })
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [
      {
        id: '5',
        project_id: '1',
        kind: 'PLAN',
        title: '계획서',
        approved_version: {
          id: '6',
          document_id: '5',
          version_no: 1,
          status: 'APPROVED',
          file_name: 'plan.md',
          media_type: 'text/markdown',
          content_hash: 'abc',
          created_at: time,
        },
      },
    ],
    has_more: false,
  })
  vi.mocked(changesApi.scopedBlocks).mockResolvedValue({
    document_id: '5',
    base_document_version_id: '6',
    text_hash: 'abc',
    parser_profile: 'plain',
    parser_version: '1',
    blocks: [
      {
        block_id: 'block',
        block_type: 'paragraph',
        locator: { type: 'text_lines', start_line: 1, end_line: 1 },
        text: '기존 내용',
      },
    ],
  })
  vi.mocked(meetingsApi.revisionDraft).mockResolvedValue({
    draft_id: '10',
    summary_id: '9',
    document_id: '5',
    base_document_version_id: '6',
    meeting: {
      project_id: '1',
      meeting_id: '2',
      document_id: '5',
      document_version_id: '6',
      entries: [{ id: '3', meeting_id: '2' }],
      decisions: [
        { id: '9:0', text: '팀장이 관리한다', evidence_entry_ids: ['3'] },
      ],
    },
    draft: {
      document_id: '5',
      base_document_version_id: '6',
      after_content: '수정 내용',
      changes: [
        {
          block_id: 'block',
          before: '기존 내용',
          after: '수정 내용',
          reason: '결정 반영',
          evidence: [],
        },
      ],
      unresolved: [],
      generation: { origin: 'MODEL' },
    },
  })
})

it('결정과 근거를 확인해야 선택 범위를 저장한다', async () => {
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '3',
            meeting_id: '2',
            kind: 'TEXT',
            text: '팀장이 맡겠습니다',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )

  await user.click(
    await screen.findByRole('checkbox', { name: '팀장이 관리한다' }),
  )
  await user.selectOptions(
    await screen.findByLabelText('수정할 승인 문서'),
    '5',
  )
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  await user.click(await screen.findByRole('checkbox', { name: '기존 내용' }))
  await user.type(screen.getByLabelText('수정 요청'), '담당자 변경')
  const save = screen.getByRole('button', { name: '수정 초안 저장' })
  expect(save).toBeDisabled()
  await user.click(screen.getByRole('checkbox', { name: '팀장이 맡겠습니다' }))
  expect(save).toBeDisabled()
  await user.click(
    screen.getByRole('checkbox', { name: /실제 회의에서 확인한 결정/ }),
  )
  await user.click(save)
  expect(await screen.findByText('저장된 초안 · 제출 전')).toBeInTheDocument()
  expect(meetingsApi.revisionDraft).toHaveBeenCalledWith('1', '2', {
    summary_id: '9',
    document_id: '5',
    base_document_version_id: '6',
    selected_block_ids: ['block'],
    instruction: '담당자 변경',
    confirmed: true,
    decisions: [{ decision_index: 0, evidence_entry_ids: ['3'] }],
  })
  await user.click(screen.getByText('전체 수정 초안 보기'))
  await user.click(screen.getByRole('button', { name: '전체 초안 복사' }))
  expect(await navigator.clipboard.readText()).toBe('수정 내용')
  await user.click(
    screen.getByRole('button', { name: '변경 제안 작성으로 이동' }),
  )
  expect(useWorkspaceStore.getState().focusId).toBe('doc:5')
})

it('정리 이후 새 발언이 있으면 다시 정리하도록 알리고 해당 발언을 근거로 고를 수 없다', async () => {
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '3',
            meeting_id: '2',
            kind: 'TEXT',
            text: '팀장이 맡겠습니다',
            created_at: '2026-11-06T00:00:00Z',
          },
          {
            id: '5',
            meeting_id: '2',
            kind: 'TEXT',
            text: '정리 후 발언',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  await user.click(
    await screen.findByRole('checkbox', { name: '팀장이 관리한다' }),
  )
  expect(
    screen.getByText(/회의 정리 이후 새 기록이 있어요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('checkbox', { name: '정리 후 발언' }),
  ).not.toBeInTheDocument()
})

it('모든 기록을 불러온 뒤 마지막 TEXT 또는 CONFLICT_BRIEF까지 정리한다', async () => {
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const entries = [
    {
      id: '3',
      meeting_id: '2',
      kind: 'TEXT' as const,
      text: '발언',
      created_at: '2026-11-06T00:00:00Z',
    },
    {
      id: '7',
      meeting_id: '2',
      kind: 'CONFLICT_BRIEF' as const,
      text: '충돌 기록',
      created_at: '2026-11-06T00:00:00Z',
    },
  ]
  const loadMore = vi.fn()
  const component = (hasMore: boolean) => (
    <QueryClientProvider client={queryClient}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={entries}
        hasMore={hasMore}
        loadingMore={false}
        loadMore={loadMore}
      />
    </QueryClientProvider>
  )
  const view = render(component(true))
  const button = await screen.findByRole('button', {
    name: '최신 대화로 다시 정리',
  })
  expect(button).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '회의 기록 더 보기' }))
  expect(loadMore).toHaveBeenCalledOnce()
  view.rerender(component(false))
  vi.mocked(meetingsApi.summary).mockResolvedValue(
    await meetingsApi.latestSummary('1', '2'),
  )
  await user.click(button)
  expect(meetingsApi.summary).toHaveBeenCalledWith('1', '2', '7')
})
