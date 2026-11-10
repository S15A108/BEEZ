import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { changesApi, documentsApi, meetingsApi } from '@/api/services'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { getApiErrorMessage } from '@/api/error'
import { MeetingRevisionDraft } from './MeetingRevisionDraft'
import { revisionDraftInputSchema } from './schemas'
import { rememberSummary } from './summaryCache'

vi.mock('@/api/services', () => ({
  meetingsApi: {
    latestSummary: vi.fn(),
    latestRevisionDraft: vi.fn(),
    revisionDraft: vi.fn(),
    entries: vi.fn(),
  },
  documentsApi: { list: vi.fn(), baseline: vi.fn() },
  changesApi: { scopedBlocks: vi.fn() },
}))

beforeEach(() => {
  vi.resetAllMocks()
  useWorkspaceStore.getState().openPane(null)
  const time = '2026-11-06T00:00:00Z'
  vi.mocked(meetingsApi.entries).mockResolvedValue({
    items: [],
    has_more: false,
  })
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
    // 서버의 정리 응답에는 항상 생성 근거(generation)가 있다.
    generation: { origin: 'MODEL', text: '', sources: [], unresolved: [] },
    created_at: time,
  })
  vi.mocked(meetingsApi.latestRevisionDraft).mockRejectedValue({
    isAxiosError: true,
    response: { status: 404 },
  })
  const document = {
    id: '5',
    project_id: '1',
    kind: 'PLAN' as const,
    title: '계획서',
    approved_version: {
      id: '7',
      document_id: '5',
      version_no: 1,
      status: 'APPROVED' as const,
      file_name: 'plan.md',
      media_type: 'text/markdown',
      content_hash: 'abc',
      created_at: time,
    },
  }
  vi.mocked(documentsApi.baseline).mockResolvedValue({
    id: '1',
    project_id: '1',
    version_no: 1,
    documents: [{ document_id: '5', document_version_id: '6' }],
    items: [],
    approved_by: '1',
    created_at: time,
  })
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [document],
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
  await screen.findByRole('option', { name: '계획서' })
  await user.selectOptions(
    await screen.findByLabelText('수정할 승인 문서'),
    '5',
  )
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  expect(changesApi.scopedBlocks).toHaveBeenCalledWith('1', {
    document_id: '5',
    base_document_version_id: '6',
  })
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
  await vi.waitFor(() =>
    expect(useWorkspaceStore.getState().focusId).toBe('doc:5'),
  )
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

it('남은 기록이 있으면 더 보기를 누를 수 있고 모두 불러오면 오래된 정리 안내를 판단한다', async () => {
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
  await screen.findByRole('checkbox', { name: '팀장이 관리한다' })
  // 기록을 다 읽기 전에는 정리가 최신인지 알 수 없어 안내하지 않는다.
  expect(screen.queryByText(/회의 정리 이후 새 기록이 있어요/)).toBeNull()
  await user.click(screen.getByRole('button', { name: '회의 기록 더 보기' }))
  expect(loadMore).toHaveBeenCalledOnce()
  // 모두 읽었더니 마지막 기록(7)이 정리가 쓴 기록(3)보다 뒤라면 위에서 다시 정리하도록 안내한다.
  view.rerender(component(false))
  expect(
    await screen.findByText(/회의 정리 이후 새 기록이 있어요/),
  ).toBeInTheDocument()
  // 정리는 여기서 만들지 않는다. 위의 '비비에게 정리 요청'을 쓴다.
  expect(
    screen.queryByRole('button', { name: /다시 정리|회의 정리 만들기/ }),
  ).toBeNull()
})

it.each([
  [
    '오래된 순',
    ['2', '9007199254740992', '9007199254740993', '99999999999999999'],
  ],
  [
    '최신 순',
    ['99999999999999999', '9007199254740993', '9007199254740992', '2'],
  ],
  [
    '섞인 순서',
    ['9007199254740993', '2', '99999999999999999', '9007199254740992'],
  ],
])(
  '%s로 전달돼도 정리 가능한 최대 ID로 오래된 정리를 판단한다',
  async (_, order) => {
    const latest = '9007199254740993'
    const summary = await meetingsApi.latestSummary('1', '2')
    vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
      ...summary,
      through_entry_id: latest,
    })
    const values = [
      { id: '2', kind: 'TEXT' as const, text: '이전 발언' },
      { id: '9007199254740992', kind: 'TEXT' as const, text: '이전 큰 ID' },
      { id: latest, kind: 'CONFLICT_BRIEF' as const, text: '최신 충돌' },
      { id: '99999999999999999', kind: 'SUMMARY' as const, text: '이전 정리' },
    ]
    const entries = order.map((id) => ({
      ...values.find((entry) => entry.id === id)!,
      meeting_id: '2',
      created_at: '2026-11-06T00:00:00Z',
    }))
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <MeetingRevisionDraft
          projectId="1"
          meetingId="2"
          entries={entries}
          hasMore={false}
          loadingMore={false}
          loadMore={() => {}}
        />
      </QueryClientProvider>,
    )
    await screen.findByRole('checkbox', { name: '팀장이 관리한다' })
    expect(screen.queryByText(/회의 정리 이후 새 기록이 있어요/)).toBeNull()
  },
)

it('역순 기록에서 정리 이후의 큰 ID 발언을 오래된 정리로 감지한다', async () => {
  const summary = await meetingsApi.latestSummary('1', '2')
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
    ...summary,
    through_entry_id: '9007199254740992',
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '9007199254740993',
            meeting_id: '2',
            kind: 'TEXT',
            text: '새 발언',
            created_at: '2026-11-06T00:00:00Z',
          },
          {
            id: '9007199254740992',
            meeting_id: '2',
            kind: 'TEXT',
            text: '이전 발언',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/회의 정리 이후 새 기록이 있어요/),
  ).toBeInTheDocument()
  await userEvent.click(
    screen.getByRole('checkbox', { name: '팀장이 관리한다' }),
  )
  expect(screen.queryByRole('checkbox', { name: '새 발언' })).toBeNull()
})

it('정리 가능한 기록이 없으면 대화를 남기라고 안내한다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '9',
            meeting_id: '2',
            kind: 'SUMMARY',
            text: '정리',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText('먼저 회의 대화를 남겨 주세요.'),
  ).toBeInTheDocument()
})

// 정리는 위의 '비비에게 정리 요청'으로만 만든다. 정리가 아직 없으면 거기서 먼저 하라고 안내하고 여기에 정리 버튼을 두지 않는다.
it('정리가 아직 없으면 위의 정리 요청을 안내하고 정리 버튼은 두지 않는다', async () => {
  vi.mocked(meetingsApi.latestSummary).mockRejectedValue(
    Object.assign(new Error('missing'), {
      isAxiosError: true,
      response: { status: 404 },
    }),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '3',
            meeting_id: '2',
            kind: 'TEXT',
            text: '발언',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/위의 '비비에게 정리 요청'/),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: /정리 만들기|다시 정리/ }),
  ).toBeNull()
})

// 위에서 정리를 다시 하면(같은 정리 캐시가 바뀌면) 이 화면이 새 정리를 보고, 이전 정리 기준의 선택을 비운다.
it('위에서 정리를 다시 하면 새 정리를 보이고 이전 선택을 비운다', async () => {
  const first = await meetingsApi.latestSummary('1', '2')
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '3',
            meeting_id: '2',
            kind: 'TEXT',
            text: '발언',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  const decision = await screen.findByRole('checkbox', {
    name: '팀장이 관리한다',
  })
  await userEvent.click(decision)
  expect(decision).toBeChecked()
  act(() =>
    rememberSummary(client, '1', '2', {
      ...first,
      id: '10',
      decisions: [{ text: '팀원이 관리한다' }],
    }),
  )
  expect(
    await screen.findByRole('checkbox', { name: '팀원이 관리한다' }),
  ).not.toBeChecked()
  expect(screen.queryByRole('checkbox', { name: '팀장이 관리한다' })).toBeNull()
})

async function showSavedDraft(hasMore = false) {
  const draft = await meetingsApi.revisionDraft('1', '2', {
    summary_id: '9',
    document_id: '5',
    base_document_version_id: '6',
    selected_block_ids: ['block'],
    instruction: '수정',
    confirmed: true,
    decisions: [{ decision_index: 0, evidence_entry_ids: ['3'] }],
  })
  vi.mocked(meetingsApi.latestRevisionDraft).mockResolvedValue(draft)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const component = (more: boolean) => (
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[
          {
            id: '3',
            meeting_id: '2',
            kind: 'TEXT',
            text: '발언',
            created_at: '2026-11-06T00:00:00Z',
          },
        ]}
        hasMore={more}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>
  )
  const view = render(component(hasMore))
  await screen.findByText('저장된 초안 · 제출 전')
  await userEvent.click(screen.getByText('전체 수정 초안 보기'))
  return { view, component }
}

it('목록에 없는 초안 문서도 기준 설계에서 확인하고 버전이 바뀌면 사용을 막는다', async () => {
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [],
    has_more: true,
    next_cursor: 'next',
  })
  const current = await documentsApi.baseline('1')
  vi.mocked(documentsApi.baseline).mockResolvedValue({
    ...current,
    documents: [{ document_id: '5', document_version_id: '7' }],
  })
  await showSavedDraft()
  expect(
    await screen.findByText(/이전 회의 정리 또는 문서 버전/),
  ).toBeInTheDocument()
  expect(documentsApi.baseline).toHaveBeenCalledWith('1')
  expect(screen.getByRole('button', { name: '전체 초안 복사' })).toBeDisabled()
  expect(
    screen.getByRole('button', { name: '변경 제안 작성으로 이동' }),
  ).toBeDisabled()
})

it('문서 조회 실패와 남은 회의 기록은 미확인으로 처리하고 확인 후 사용할 수 있다', async () => {
  vi.mocked(documentsApi.baseline).mockRejectedValueOnce(new Error('network'))
  const { view, component } = await showSavedDraft(true)
  await screen.findByRole('button', { name: '기준 문서 다시 확인' })
  const copy = screen.getByRole('button', { name: '전체 초안 복사' })
  expect(copy).toBeDisabled()
  await userEvent.click(
    screen.getByRole('button', { name: '기준 문서 다시 확인' }),
  )
  expect(copy).toBeDisabled()
  view.rerender(component(false))
  await vi.waitFor(() => expect(copy).toBeEnabled())
})

it('초안이 열린 후 다른 정리가 저장되면 이동 전에 차단한다', async () => {
  await showSavedDraft()
  const open = screen.getByRole('button', { name: '변경 제안 작성으로 이동' })
  await vi.waitFor(() => expect(open).toBeEnabled())
  const old = await meetingsApi.latestSummary('1', '2')
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({ ...old, id: '20' })
  await userEvent.click(open)
  expect(
    await screen.findByText(/회의 정리 또는 기준 문서가 바뀌었어요/),
  ).toBeInTheDocument()
  expect(useWorkspaceStore.getState().focusId).toBeNull()
})

it('다른 세션에서 기준 문서가 바뀌면 복사 전에 차단한다', async () => {
  await showSavedDraft()
  const copy = screen.getByRole('button', { name: '전체 초안 복사' })
  await vi.waitFor(() => expect(copy).toBeEnabled())
  const old = await documentsApi.baseline('1')
  vi.mocked(documentsApi.baseline).mockResolvedValue({
    ...old,
    documents: [{ document_id: '5', document_version_id: '7' }],
  })
  const write = vi.spyOn(navigator.clipboard, 'writeText')
  await userEvent.click(copy)
  expect(
    await screen.findByText(/회의 정리 또는 기준 문서가 바뀌었어요/),
  ).toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
  write.mockRestore()
})

it('정리 후 추가된 요약을 건너뛰고 다음 페이지의 새 발언까지 확인한다', async () => {
  await showSavedDraft()
  const open = screen.getByRole('button', { name: '변경 제안 작성으로 이동' })
  await vi.waitFor(() => expect(open).toBeEnabled())
  const base = { meeting_id: '2', text: '', created_at: '2026-11-06T00:00:00Z' }
  vi.mocked(meetingsApi.entries)
    .mockResolvedValueOnce({
      items: [{ ...base, id: '4', kind: 'SUMMARY' }],
      has_more: true,
      next_cursor: '4',
    })
    .mockResolvedValueOnce({
      items: [{ ...base, id: '5', kind: 'TEXT' }],
      has_more: false,
    })
  await userEvent.click(open)
  expect(
    await screen.findByText(/회의 정리 이후 새 기록이 있어요/),
  ).toBeInTheDocument()
  expect(meetingsApi.entries).toHaveBeenNthCalledWith(1, '1', '2', {
    cursor: '3',
    limit: 100,
  })
  expect(meetingsApi.entries).toHaveBeenNthCalledWith(2, '1', '2', {
    cursor: '4',
    limit: 100,
  })
  expect(useWorkspaceStore.getState().focusId).toBeNull()
})

it('사용 직전의 최신 상태 조회가 실패하면 이동을 막는다', async () => {
  await showSavedDraft()
  const open = screen.getByRole('button', { name: '변경 제안 작성으로 이동' })
  await vi.waitFor(() => expect(open).toBeEnabled())
  vi.mocked(meetingsApi.entries).mockRejectedValue(new Error('network'))
  await userEvent.click(open)
  expect(
    await screen.findByText(/최신 상태를 확인하지 못했어요/),
  ).toBeInTheDocument()
  expect(useWorkspaceStore.getState().focusId).toBeNull()
})

it('같은 내용의 결정은 하나만 고를 수 있고 해제하면 다시 고를 수 있다', async () => {
  const summary = await meetingsApi.latestSummary('1', '2')
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
    ...summary,
    decisions: [{ text: '같은 결정' }, { text: '같은 결정' }],
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  const [first, second] = await screen.findAllByRole('checkbox', {
    name: '같은 결정',
  })
  await userEvent.click(first)
  expect(second).toBeDisabled()
  await userEvent.click(first)
  expect(second).toBeEnabled()
})

it('결정을 30개까지 선택하고 해제하면 다른 결정을 선택할 수 있다', async () => {
  const summary = await meetingsApi.latestSummary('1', '2')
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
    ...summary,
    decisions: Array.from({ length: 31 }, (_, i) => ({ text: `결정 ${i}` })),
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={[]}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  await screen.findByRole('checkbox', { name: '결정 0' })
  for (let i = 0; i < 30; i++)
    fireEvent.click(screen.getByRole('checkbox', { name: `결정 ${i}` }))
  const next = screen.getByRole('checkbox', { name: '결정 30' })
  expect(next).toBeDisabled()
  fireEvent.click(screen.getByRole('checkbox', { name: '결정 0' }))
  expect(next).toBeEnabled()
})

it('한 결정의 근거를 100개까지 선택하고 해제할 수 있다', async () => {
  const summary = await meetingsApi.latestSummary('1', '2')
  vi.mocked(meetingsApi.latestSummary).mockResolvedValue({
    ...summary,
    through_entry_id: '101',
  })
  const entries = Array.from({ length: 101 }, (_, i) => ({
    id: String(i + 1),
    meeting_id: '2',
    kind: 'TEXT' as const,
    text: `발언 ${i + 1}`,
    created_at: '2026-11-06T00:00:00Z',
  }))
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MeetingRevisionDraft
        projectId="1"
        meetingId="2"
        entries={entries}
        hasMore={false}
        loadingMore={false}
        loadMore={() => {}}
      />
    </QueryClientProvider>,
  )
  fireEvent.click(
    await screen.findByRole('checkbox', { name: '팀장이 관리한다' }),
  )
  const evidence = Array.from({ length: 101 }, (_, i) =>
    screen.getByRole('checkbox', { name: `발언 ${i + 1}` }),
  )
  for (const item of evidence.slice(0, 100)) fireEvent.click(item)
  expect(evidence[100]).toBeDisabled()
  fireEvent.click(evidence[0])
  expect(evidence[100]).toBeEnabled()
}, 20000)

it('서버의 결정과 근거 수 제한 및 중복을 요청 전에 검증한다', () => {
  const input = {
    summary_id: '9',
    document_id: '5',
    base_document_version_id: '6',
    selected_block_ids: ['block'],
    instruction: '수정',
    confirmed: true as const,
  }
  const refs = (start: number, count: number) =>
    Array.from({ length: count }, (_, i) => String(start + i))
  const valid = [
    { decision_index: 0, evidence_entry_ids: refs(1, 100) },
    { decision_index: 1, evidence_entry_ids: refs(101, 100) },
    { decision_index: 2, evidence_entry_ids: ['1'] },
  ]
  expect(
    revisionDraftInputSchema.safeParse({ ...input, decisions: valid }).success,
  ).toBe(true)
  expect(
    revisionDraftInputSchema.safeParse({
      ...input,
      decisions: [...valid, { decision_index: 3, evidence_entry_ids: ['201'] }],
    }).success,
  ).toBe(false)
  expect(
    revisionDraftInputSchema.safeParse({
      ...input,
      decisions: [{ decision_index: 0, evidence_entry_ids: refs(1, 101) }],
    }).success,
  ).toBe(false)
  expect(
    revisionDraftInputSchema.safeParse({
      ...input,
      decisions: Array.from({ length: 31 }, (_, i) => ({
        decision_index: i,
        evidence_entry_ids: ['1'],
      })),
    }).success,
  ).toBe(false)
  expect(
    revisionDraftInputSchema.safeParse({
      ...input,
      decisions: [{ decision_index: 0, evidence_entry_ids: ['1', '1'] }],
    }).success,
  ).toBe(false)
})

it('오래된 회의 정리 오류에 다시 확인할 내용을 표시한다', () => {
  expect(
    getApiErrorMessage({
      isAxiosError: true,
      response: { status: 409, data: { error: { code: 'summary_outdated' } } },
    }),
  ).toContain('회의 정리 또는 기준 문서')
})
