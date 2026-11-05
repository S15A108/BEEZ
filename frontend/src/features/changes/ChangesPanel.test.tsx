/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { authApi, changesApi, documentsApi } from '@/api/services'
import { getApiErrorCode } from '@/api/error'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ChangesPanel } from './ChangesPanel'
import { getResponseSchema } from './schemas'

let restore: () => void
let client: QueryClient
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  useWorkspaceStore.getState().openPane('changes')
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})
afterEach(() => {
  client.clear()
  restore()
  vi.restoreAllMocks()
})

function renderPanel() {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it('알 수 없는 발행 후 상태는 상세를 유지하고 확인 필요로 표시한다', async () => {
  const base = await changesApi.get('101', '71')
  const payload = {
    ...base,
    proposal: { ...base.proposal, status: 'PUBLISHED' },
    published_analysis: { status: 'PENDING', guidance: 'FUTURE_GUIDANCE' },
  }
  const response = getResponseSchema.parse(payload)
  expect(response.published_analysis).toEqual({
    status: 'UNKNOWN',
    guidance: 'CHECK_RESULT',
  })
  expect(
    getResponseSchema.safeParse({ ...payload, proposal: null }).success,
  ).toBe(false)
  vi.spyOn(changesApi, 'get').mockResolvedValue(response)
  renderPanel()
  expect(await screen.findByText('상태 확인 필요')).toBeInTheDocument()
  expect(screen.queryByText('처리 대기 중')).not.toBeInTheDocument()
})

it('발행된 제안은 상세 응답의 후속 분석 실패를 표시하고 발행 전 재분석 버튼을 숨긴다', async () => {
  const user = userEvent.setup()
  const base = await changesApi.get('101', '71')
  const response = getResponseSchema.parse({
    ...base,
    proposal: { ...base.proposal, status: 'PUBLISHED' },
    published_analysis: {
      status: 'FAILED',
      guidance: 'CONTACT_OPERATOR',
      attempt: 4,
      requested_at: '2026-11-04T00:00:00Z',
    },
  })
  expect(response.published_analysis?.status).toBe('FAILED')
  vi.spyOn(changesApi, 'get').mockResolvedValue(response)
  vi.spyOn(changesApi, 'impact').mockResolvedValue({
    explicit: [],
    candidates: [],
    analysis_status: 'NOT_EVALUATED',
    error_code: 'analysis_not_started',
    is_current: true,
  })
  const retry = vi.spyOn(changesApi, 'retryImpact')

  renderPanel()
  expect(await screen.findByText('처리 실패')).toBeInTheDocument()
  expect(screen.getByText(/발행 후 분석을 마치지 못했어요/)).toBeInTheDocument()
  await user.click(await screen.findByRole('tab', { name: /^영향/ }))
  expect(screen.getByText(/발행 전 영향 분석 결과예요/)).toBeInTheDocument()
  expect(
    screen.getByText('발행 전 영향 분석이 진행되지 않았어요.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '다시 분석' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '영향 분석 시작' }),
  ).not.toBeInTheDocument()
  expect(retry).not.toHaveBeenCalled()
})

it('영향 분석이 실패했으면 개요와 영향 탭에서 0건으로 대신 표시하지 않는다', async () => {
  const user = userEvent.setup()
  vi.spyOn(changesApi, 'impact').mockResolvedValue({
    explicit: [],
    candidates: [],
    analysis_status: 'FAILED',
    is_current: true,
  })
  renderPanel()
  expect(await screen.findByText('분석하지 못했어요.')).toBeInTheDocument()
  expect(screen.queryByText(/확인된 관계 0/)).not.toBeInTheDocument()
  expect(screen.getByRole('tab', { name: '영향' })).toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: '영향' }))
  expect(
    screen.getByText('확인된 관계를 아직 판단할 수 없어요.'),
  ).toBeInTheDocument()
})

it('상태를 새로고침하면 같은 제안의 변경된 발행 후 처리 결과를 보여 준다', async () => {
  const user = userEvent.setup()
  const base = await changesApi.get('101', '71')
  const published = {
    ...base,
    proposal: { ...base.proposal, status: 'PUBLISHED' as const },
  }
  vi.spyOn(changesApi, 'get')
    .mockResolvedValueOnce({
      ...published,
      published_analysis: { status: 'PENDING', guidance: 'WAIT' },
    })
    .mockResolvedValue({
      ...published,
      published_analysis: { status: 'FAILED', guidance: 'CONTACT_OPERATOR' },
    })

  renderPanel()
  expect(await screen.findByText('처리 대기 중')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '상태 새로고침' }))
  expect(await screen.findByText('처리 실패')).toBeInTheDocument()
  expect(screen.queryByText('처리 대기 중')).not.toBeInTheDocument()
})

it('상태 재조회가 실패하면 이전 결과를 최신 상태로 계속 보여 주지 않는다', async () => {
  const user = userEvent.setup()
  const base = await changesApi.get('101', '71')
  vi.spyOn(changesApi, 'get')
    .mockResolvedValueOnce({
      ...base,
      proposal: { ...base.proposal, status: 'PUBLISHED' },
      published_analysis: {
        status: 'COMPLETED',
        evaluation_status: 'COMPLETED',
        guidance: 'NONE',
      },
    })
    .mockRejectedValue(new Error('unavailable'))

  renderPanel()
  expect(await screen.findByText('분석 완료')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '상태 새로고침' }))
  expect(
    await screen.findByText(/정보를 불러오지 못했어요/),
  ).toBeInTheDocument()
  expect(screen.queryByText('분석 완료')).not.toBeInTheDocument()
})

it('팀장은 탭으로 제안을 살펴보고 창 안에서 확인한 뒤 기준 v2를 발행한다', async () => {
  const user = userEvent.setup()
  renderPanel()
  await screen.findByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ })
  await user.click(screen.getByRole('tab', { name: '바뀌는 내용' }))
  await user.click(await screen.findByText('REQ-001'))
  expect(screen.getByText(/\+ .*초대를 기다리라는 안내/)).toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  expect(
    screen.getByRole('heading', { name: '기준 v2 발행을 승인할까요?' }),
  ).toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  expect(await screen.findByRole('status')).toHaveTextContent(
    '기준 v2 발행을 마쳤어요.',
  )
  expect(await screen.findByText(/새 기준을 발행했어요/)).toBeInTheDocument()
})

// 사용자 결정(10-30, PR #42 CHG-A04): 올린 제안은 잠기고, 반려된 제안만 같은 번호로 고쳐 다시 올린다.
it('제안자는 검토 중인 제안을 고칠 수 없고, 반려되면 같은 제안을 고쳐 다시 올린다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const user = userEvent.setup()
  const view = (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>
  )
  const { unmount } = render(view)
  expect(
    await screen.findByText('이팀장님이 검토하고 있어요'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '제안 거두기' })).toBeEnabled()
  expect(
    screen.queryByRole('button', { name: '제안 고치기' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: /승인하고 기준/ }),
  ).not.toBeInTheDocument()
  unmount()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  const { proposal } = await changesApi.get('101', '71')
  await changesApi.reject('101', '71', {
    reason: '받은 초대를 먼저 보여 주는 안으로 고쳐 주세요.',
    expected_revision: proposal.revision,
    expected_snapshot_hash: proposal.snapshot_hash,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  client.clear()
  render(view)
  await user.click(
    await screen.findByRole('button', { name: '고쳐서 다시 올리기' }),
  )
  expect(
    screen.getByRole('heading', { name: /고쳐서 다시 올리기/ }),
  ).toBeInTheDocument()
  expect(screen.getByText(/받은 초대를 먼저 보여 주는 안/)).toBeInTheDocument()
  expect(screen.getByRole('region', { name: '받은 의견' })).toBeInTheDocument()
  // 사람이 쓴 제안 폼에는 설계 항목 입력이 없고, 이 제안의 항목 변경은 보기만 하며 그대로 다시 올라간다.
  expect(
    screen.getByRole('region', { name: '함께 올라가는 설계 항목 변경' }),
  ).toHaveTextContent('고치기 · REQ-001')
  await user.click(screen.getByRole('button', { name: '다시 올리기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '고친 내용을 올렸어요. 다시 검토 대기예요.',
  )
  expect((await changesApi.get('101', '71')).proposal.status).toBe('PROPOSED')
})

// 공용 문서함 '고치기'(PNL-02 수정안 10-29): 그 문서가 골라진 새 제안 폼으로 들어와 고친 글만으로도 올린다.
it('문서함에서 고치기로 들어오면 그 문서를 고른 새 제안으로 고친 글을 올린다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.getState().openPane('changes', 'doc:203')
  renderPanel()
  await waitFor(() =>
    expect(
      (screen.getByLabelText('고친 글') as HTMLTextAreaElement).value,
    ).toContain('REQ-001'),
  )
  const text = screen.getByLabelText('고친 글')
  expect(screen.getByLabelText(/고칠 문서/)).toHaveValue('203')
  expect(
    screen.queryByRole('group', { name: '설계 항목 1' }),
  ).not.toBeInTheDocument()
  await user.type(screen.getByLabelText('제목 *'), '빈 목록 안내 추가')
  await user.type(
    screen.getByLabelText('바꾸려는 이유 *'),
    '참여한 프로젝트가 없으면 안내가 필요해요.',
  )
  await user.type(text, '\nREQ-006 참여한 프로젝트가 없으면 만들기를 안내한다.')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '변경 제안을 올렸어요.',
  )
  expect(
    await screen.findByRole('heading', { name: /빈 목록 안내 추가/ }),
  ).toBeInTheDocument()
})

// PR #46 0df13f0: 서버가 정규화한 본문이 기준과 같으면 거절하므로 화면에서 미리 막는다.
it('고친 글이 기준 문서와 같으면 올리지 않고 칸 아래에 알린다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.getState().openPane('changes', 'doc:203')
  renderPanel()
  await waitFor(() =>
    expect(
      (screen.getByLabelText('고친 글') as HTMLTextAreaElement).value,
    ).toContain('REQ-001'),
  )
  await user.type(screen.getByLabelText('제목 *'), '그대로 둔 문서')
  await user.type(screen.getByLabelText('바꾸려는 이유 *'), '확인용')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  expect(
    await screen.findByText('기준 문서와 달라진 내용이 없어요.'),
  ).toBeInTheDocument()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('목은 BOM과 CRLF만 다른 문서를 기준과 같다고 보고 400 validation_failed로 거절한다', async () => {
  const baseline = await documentsApi.baseline('101')
  const { document } = await documentsApi.get('101', '203')
  const content = document.approved_version!.content ?? ''
  const same = await changesApi
    .create('101', {
      base_baseline_id: baseline.id,
      title: '줄바꿈만 바꿈',
      reason: '확인용',
      items: [],
      documents: [
        {
          document_id: '203',
          base_document_version_id: document.approved_version!.id,
          after_content: `\uFEFF${content.replace(/\n/g, '\r\n')}`,
        },
      ],
    })
    .catch((cause: unknown) => cause)
  expect(getApiErrorCode(same)).toBe('validation_failed')
})

// PR #46 CHG-A15: 제안자는 올린 제안을 거두고, 거둔 제안은 새 제안(previous_proposal_id)으로 다시 올린다.
it('제안자는 제안을 거두고, 거둔 제안을 새 제안으로 다시 올린다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: '제안 거두기' }))
  await user.click(screen.getByRole('button', { name: '거두기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '제안을 거뒀어요.',
  )
  expect((await changesApi.get('101', '71')).proposal.status).toBe('WITHDRAWN')
  await user.click(
    await screen.findByRole('button', { name: '새 제안으로 다시 올리기' }),
  )
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  const heading = await screen.findAllByRole('heading', {
    name: /프로젝트 목록 빈 화면 안내/,
  })
  expect(heading.length).toBeGreaterThan(0)
  const list = await changesApi.list('101')
  const next = list.items.find((item) => item.id !== '71')!
  expect((await changesApi.get('101', next.id)).proposal).toMatchObject({
    previous_proposal_id: '71',
    revision: 1,
    status: 'PROPOSED',
  })
})

it('조회한 리비전과 해시가 지금과 다르면 409 version_conflict', async () => {
  const { proposal } = await changesApi.get('101', '71')
  const stale = await changesApi
    .reject('101', '71', {
      reason: '다시 봐 주세요.',
      expected_revision: proposal.revision + 1,
      expected_snapshot_hash: proposal.snapshot_hash,
    })
    .catch((cause: unknown) => cause)
  expect(getApiErrorCode(stale)).toBe('version_conflict')
})

// '지금 할 일' 미리 보기용 항목별 −/+ 문장(item_texts, 195 미구현). 목은 채운다.
it('목 상세는 항목별 바뀌기 전·뒤 문장을 item_texts로 준다', async () => {
  const detail = await changesApi.get('101', '71')
  expect(detail.item_texts?.[0]).toMatchObject({
    before_text: expect.stringContaining('참여 프로젝트를 조회한다'),
    after_text: expect.stringContaining('초대를 기다리라는 안내'),
  })
})

// PR #72 리뷰 2: 문서 목록 응답에는 본문이 없어 고른 문서의 기준 원문을 따로 받는다. 못 받으면 칸 위에 알린다.
it('고칠 문서의 기준 원문을 받지 못하면 고친 글 칸 위에 오류와 다시 불러오기를 보인다', async () => {
  vi.spyOn(documentsApi, 'get').mockRejectedValue(new Error('network'))
  useWorkspaceStore.getState().openPane('changes', 'doc:203')
  renderPanel()
  expect(
    await screen.findByText(/기준 원문을 불러오지 못했어요/),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 불러오기' })).toBeEnabled()
  expect((screen.getByLabelText('고친 글') as HTMLTextAreaElement).value).toBe(
    '',
  )
  vi.restoreAllMocks()
})

// 사용자 결정 11-02: 변경 제안 창은 올라온 제안을 모아 보는 곳이고, 제안은 공용 문서함의 문서 '고치기'로 만든다.
it('변경 제안 창에는 새 제안 버튼 대신 공용 문서함으로 가는 버튼이 있다', async () => {
  const user = userEvent.setup()
  renderPanel()
  await screen.findByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ })
  expect(
    screen.queryByRole('button', { name: '새 변경 제안' }),
  ).not.toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '공용 문서함에서 제안하기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('documents')
})

// PR #72 리뷰 4: 다시 낼 때 설계 항목 변경은 승인본으로 다시 만들지 않고 원래 proposed_content 그대로 보낸다.
it('반려된 제안을 다시 내면 설계 항목 변경을 원래 그대로 보낸다', async () => {
  const { proposal } = await changesApi.get('101', '71')
  await changesApi.reject('101', '71', {
    reason: '다시 봐 주세요.',
    expected_revision: proposal.revision,
    expected_snapshot_hash: proposal.snapshot_hash,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const update = vi.spyOn(changesApi, 'update')
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.click(
    await screen.findByRole('button', { name: '고쳐서 다시 올리기' }),
  )
  await user.click(screen.getByRole('button', { name: '다시 올리기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '고친 내용을 올렸어요.',
  )
  expect(update.mock.calls[0][2].items).toEqual(proposal.items)
  vi.restoreAllMocks()
})

// PR #72 mock/live 전수 대조 DOC-LIVE-5: 검토 요청은 대상 팀원이 있어야 한다(서버 400).
it('검토 요청을 고르고 대상 팀원을 고르지 않으면 보내지 않고 알린다', async () => {
  const user = userEvent.setup()
  const comment = vi.spyOn(changesApi, 'comment')
  renderPanel()
  await screen.findByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ })
  await user.click(screen.getByRole('tab', { name: /^의견/ }))
  await user.selectOptions(
    await screen.findByLabelText('남길 종류'),
    'REVIEW_REQUEST',
  )
  await user.type(screen.getByLabelText(/^내용/), '이 문장도 봐 주세요.')
  await user.click(screen.getByRole('button', { name: '남기기' }))
  expect(
    await screen.findByText('검토를 요청할 팀원을 골라 주세요.'),
  ).toBeInTheDocument()
  expect(comment).not.toHaveBeenCalled()
  await expect(
    changesApi.comment('101', '71', {
      body: '봐 주세요',
      kind: 'REVIEW_REQUEST',
    }),
  ).rejects.toMatchObject({ response: { status: 400 } })
})

// PR #72 mock/live 전수 대조 DOC-LIVE-6: 발행·철회한 제안에는 의견을 남길 수 없다(서버 409 invalid_transition).
it('철회한 제안에는 의견 폼 대신 끝난 제안이라는 안내를 보인다', async () => {
  const user = userEvent.setup()
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const { proposal } = await changesApi.get('101', '71')
  await changesApi.withdraw('101', '71', {
    expected_revision: proposal.revision,
    expected_snapshot_hash: proposal.snapshot_hash,
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('tab', { name: /^의견/ }))
  expect(
    await screen.findByText('끝난 제안이라 댓글을 달 수 없어요.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '남기기' }),
  ).not.toBeInTheDocument()
  await expect(
    changesApi.comment('101', '71', { body: '늦은 의견', kind: 'COMMENT' }),
  ).rejects.toMatchObject({ response: { status: 409 } })
})

// PR #72 3차 조사 THIRD-DOC-03: 철회된 제안의 초안이 v2를 차지했으면 새 제안의 초안은 v3이다. 승인본+1로 짐작하지 않고
// 제안이 만든 문서 버전의 번호를 보이며, 모르면 '새 버전'이라고만 한다.
it('승인 확인창은 문서 새 버전 번호를 제안이 만든 버전에서 읽고, 모르면 새 버전이라고 한다', async () => {
  const user = userEvent.setup()
  const approved = (await documentsApi.list('101', { limit: 100 })).items
    .filter((item) => item.approved_version)
    .slice(0, 2)
  expect(approved).toHaveLength(2)
  const detail = await changesApi.get('101', '71')
  vi.spyOn(changesApi, 'get').mockResolvedValue({
    ...detail,
    proposal: {
      ...detail.proposal,
      documents: approved.map((item, index) => ({
        document_id: item.id,
        base_document_version_id: item.approved_version!.id,
        proposed_document_version_id: `900${index}`,
        after_content: '고친 글',
        content_hash: `sha256:${'0'.repeat(64)}`,
      })),
    },
  })
  vi.spyOn(documentsApi, 'versions').mockImplementation(
    async (_project, documentId) =>
      ({
        items:
          documentId === approved[0].id
            ? [{ id: '9000', version_no: 3 }]
            : [{ id: '8999', version_no: 2 }],
        has_more: false,
      }) as never,
  )
  renderPanel()
  await user.click(
    await screen.findByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  const [first, second] = approved
  expect(
    await screen.findByText(
      `${first.title} v${first.approved_version!.version_no} → v3`,
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByText(
      `${second.title} v${second.approved_version!.version_no} → 새 버전`,
    ),
  ).toBeInTheDocument()
})

// PR #72 4차 조사 FOURTH-DOC-02: 제목만 고쳐 다시 올려도 관련 작업이 그대로면 원래 검증 연결(validation_id)을 보낸다.
// mock도 서버 PUT처럼 빠진 필드를 비우므로, 빠뜨리면 연결이 끊긴다.
it('제목만 고쳐 다시 올려도 관련 작업이 그대로면 검증 연결을 지킨다', async () => {
  const { proposal } = await changesApi.get('101', '71')
  expect(proposal).toMatchObject({ task_id: '501', validation_id: '601' })
  await changesApi.reject('101', '71', {
    reason: '다시 봐 주세요.',
    expected_revision: proposal.revision,
    expected_snapshot_hash: proposal.snapshot_hash,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChangesPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.click(
    await screen.findByRole('button', { name: '고쳐서 다시 올리기' }),
  )
  await user.type(screen.getByLabelText('제목 *'), ' (보완)')
  await user.click(screen.getByRole('button', { name: '다시 올리기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '고친 내용을 올렸어요.',
  )
  expect((await changesApi.get('101', '71')).proposal).toMatchObject({
    title: `${proposal.title} (보완)`,
    task_id: '501',
    validation_id: '601',
  })
})

// PR #72 5차 조사 FIFTH-DOC-02: 서버는 작성자면 거두게 한다. 팀장도 본인 제안은 거둘 수 있다.
it('팀장도 본인이 올린 제안을 거둔다', async () => {
  const user = userEvent.setup()
  const baselineId = (await documentsApi.baseline('101')).id
  const document = (await documentsApi.list('101', { limit: 100 })).items.find(
    (item) => item.approved_version,
  )!
  const created = await changesApi.create('101', {
    base_baseline_id: baselineId,
    title: '팀장이 올린 제안',
    reason: '직접 고칠 곳이 있어요.',
    items: [],
    documents: [
      {
        document_id: document.id,
        base_document_version_id: document.approved_version!.id,
        after_content: `${document.approved_version!.content ?? ''}
고친 줄`,
      },
    ],
  })
  renderPanel()
  await user.click(await screen.findByText('팀장이 올린 제안'))
  await user.click(await screen.findByRole('button', { name: '제안 거두기' }))
  await user.click(screen.getByRole('button', { name: '거두기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '제안을 거뒀어요.',
  )
  expect((await changesApi.get('101', created.id)).proposal.status).toBe(
    'WITHDRAWN',
  )
})

// 사용자 요청(11-02): 업무 창 목록의 선택 표시는 작업 목록과 같은 노란 테두리·바탕으로 통일한다.
it('고른 제안은 작업 목록과 같은 노란 선택 표시로 보인다', async () => {
  renderPanel()
  await screen.findByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ })
  const selected = screen
    .getAllByRole('button')
    .find((button) => button.getAttribute('aria-current') === 'true')!
  expect(selected).toHaveClass('border-primary/30', 'bg-primary/5')
  expect(selected).not.toHaveClass('border-foreground')
})

// S15P31A108-241: 영향 분석이 완료·최신이 아니면 서버가 409 impact_not_confirmed 로 승인을 막는다.
// 팀장은 사유를 남겨 다시 승인하고, 사유는 impact_override_reason 으로 나간다.
function conflict(code: string) {
  return new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: code } },
  })
}

it('영향 분석이 확인되지 않아 막히면 사유 입력을 열고 사유를 담아 다시 승인한다', async () => {
  const user = userEvent.setup()
  const detail = await changesApi.get('101', '71')
  const approve = vi
    .spyOn(changesApi, 'approve')
    .mockRejectedValueOnce(conflict('impact_not_confirmed'))
    .mockResolvedValueOnce({
      proposal: detail.proposal,
      baseline: await documentsApi.baseline('101'),
    })
  renderPanel()
  await user.click(
    await screen.findByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  expect(approve.mock.calls[0][2]).not.toHaveProperty('impact_override_reason')
  const reason = await screen.findByLabelText('영향 분석 없이 승인하는 사유')
  // 사유가 필요한 것은 실패가 아니라 정상 단계라 일반 오류 띠가 같이 뜨면 안 된다.
  expect(screen.queryByText(/요청을 처리하지 못했어요/)).not.toBeInTheDocument()
  const retry = screen.getByRole('button', {
    name: '사유 남기고 기준 v2 발행',
  })
  expect(retry).toBeDisabled()
  await user.type(reason, '  분석 서버 장애로 직접 확인함  ')
  await user.click(retry)
  await waitFor(() => expect(approve).toHaveBeenCalledTimes(2))
  expect(approve.mock.calls[1][2]).toMatchObject({
    impact_override_reason: '분석 서버 장애로 직접 확인함',
  })
})

it('영향 분석이 진행 중이면 사유 입력 없이 끝난 뒤 다시 승인하라고 안내한다', async () => {
  const user = userEvent.setup()
  vi.spyOn(changesApi, 'approve').mockRejectedValue(
    conflict('analysis_running'),
  )
  renderPanel()
  await user.click(
    await screen.findByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  expect(
    await screen.findByText(/영향 분석이 진행 중이에요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByLabelText('영향 분석 없이 승인하는 사유'),
  ).not.toBeInTheDocument()
  expect(screen.queryByText(/요청을 처리하지 못했어요/)).not.toBeInTheDocument()
})

// 분석 이력이 없는 제안은 서버가 오류 없이 NOT_EVALUATED(analysis_not_started)를 준다.
// 팀장은 웹에서 분석을 시작하고, 완료된 뒤에는 사유 없이 승인한다(사유 우회에만 기대지 않는다).
it('분석한 적 없는 제안은 영향 탭에서 분석을 시작하고 완료되면 사유 없이 승인한다', async () => {
  const user = userEvent.setup()
  const detail = await changesApi.get('101', '71')
  const done = await changesApi.impact('101', '71')
  let started = false
  vi.spyOn(changesApi, 'impact').mockImplementation(async () =>
    started
      ? done
      : {
          explicit: [],
          candidates: [],
          analysis_status: 'NOT_EVALUATED',
          error_code: 'analysis_not_started',
          is_current: true,
        },
  )
  const retryImpact = vi
    .spyOn(changesApi, 'retryImpact')
    .mockImplementation(async () => {
      started = true
      return { ...done, analysis_status: 'RUNNING' }
    })
  const approve = vi.spyOn(changesApi, 'approve').mockResolvedValue({
    proposal: detail.proposal,
    baseline: await documentsApi.baseline('101'),
  })
  renderPanel()
  await user.click(await screen.findByRole('tab', { name: /^영향/ }))
  expect(
    await screen.findByText(/아직 영향 분석을 하지 않았어요/),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '영향 분석 시작' }))
  expect(retryImpact).toHaveBeenCalledTimes(1)
  expect(
    await screen.findByRole('button', { name: '다시 분석' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText(/아직 영향 분석을 하지 않았어요/),
  ).not.toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  await waitFor(() => expect(approve).toHaveBeenCalledTimes(1))
  expect(approve.mock.calls[0][2]).not.toHaveProperty('impact_override_reason')
})

it('근거 부족·오래됨·재분석 중 상태도 오류 없이 상태별 안내와 버튼을 보인다', async () => {
  const user = userEvent.setup()
  const base = { explicit: [], candidates: [], is_current: true }
  const impact = vi.spyOn(changesApi, 'impact')
  impact.mockResolvedValue({
    ...base,
    analysis_status: 'INSUFFICIENT_EVIDENCE',
  })
  renderPanel()
  await user.click(await screen.findByRole('tab', { name: /^영향/ }))
  expect(await screen.findByText(/근거가 부족해서/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 분석' })).toBeEnabled()
  await client.invalidateQueries({ queryKey: ['projects', '101', 'impact'] })
  impact.mockResolvedValue({
    ...base,
    analysis_status: 'COMPLETED',
    reanalysis: { run_id: '9' },
  })
  await client.invalidateQueries({ queryKey: ['projects', '101', 'impact'] })
  expect(await screen.findByText(/다시 분석하는 중이에요/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 분석' })).toBeDisabled()
  impact.mockResolvedValue({
    ...base,
    analysis_status: 'STALE',
    is_current: false,
  })
  await client.invalidateQueries({ queryKey: ['projects', '101', 'impact'] })
  expect(await screen.findByText(/이전 분석이 오래됐어요/)).toBeInTheDocument()
})

it('영향받는 작업에 담당자가 없으면 미배정으로 보여 준다', async () => {
  const user = userEvent.setup()
  const done = await changesApi.impact('101', '71')
  vi.spyOn(changesApi, 'impact').mockResolvedValue({
    ...done,
    explicit: [{ task_id: '501', design_item_id: '211' }],
  })
  renderPanel()
  await user.click(await screen.findByRole('tab', { name: /^영향/ }))
  expect(await screen.findByText(/미배정/)).toBeInTheDocument()
  expect(screen.queryByText(/담당자 확인 필요/)).not.toBeInTheDocument()
})

it('사유를 남긴 예외 승인은 승인 이력과 발행 안내에 사유를 보여 준다', async () => {
  const user = userEvent.setup()
  const detail = (await changesApi.get('101', '71')).proposal
  await changesApi.approve('101', '71', {
    expected_base_baseline_id: detail.base_baseline_id,
    expected_revision: detail.revision,
    expected_snapshot_hash: detail.snapshot_hash,
    impact_override_reason: '분석 서버 장애로 직접 확인함',
  })
  renderPanel()
  expect(await screen.findByText(/새 기준을 발행했어요/)).toBeInTheDocument()
  expect(
    screen.getByText(/영향 분석 없이 승인한 사유/).parentElement,
  ).toHaveTextContent('분석 서버 장애로 직접 확인함')
  await user.click(screen.getByRole('button', { name: '승인 이력' }))
  expect(
    await screen.findByRole('heading', { name: '승인 이력' }),
  ).toBeInTheDocument()
  expect(
    (await screen.findByText(/영향 분석 없이 승인한 사유/)).parentElement,
  ).toHaveTextContent('분석 서버 장애로 직접 확인함')
})

it('사유 없이 승인한 이력에는 예외 사유 문구가 없다', async () => {
  const user = userEvent.setup()
  const detail = (await changesApi.get('101', '71')).proposal
  await changesApi.approve('101', '71', {
    expected_base_baseline_id: detail.base_baseline_id,
    expected_revision: detail.revision,
    expected_snapshot_hash: detail.snapshot_hash,
  })
  renderPanel()
  expect(await screen.findByText(/새 기준을 발행했어요/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '승인 이력' }))
  expect(
    await screen.findByRole('heading', { name: '승인 이력' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText(/영향 분석 없이 승인한 사유/),
  ).not.toBeInTheDocument()
})

it('승인 요청의 사유는 승인 이력에 남고 공백뿐인 사유는 요청 전에 거절한다', async () => {
  const detail = (await changesApi.get('101', '71')).proposal
  const base = {
    expected_base_baseline_id: detail.base_baseline_id,
    expected_revision: detail.revision,
    expected_snapshot_hash: detail.snapshot_hash,
  }
  expect(() =>
    changesApi.approve('101', '71', { ...base, impact_override_reason: ' ' }),
  ).toThrow()
  await changesApi.approve('101', '71', {
    ...base,
    impact_override_reason: '직접 확인함',
  })
  expect((await changesApi.approval('101', '71')).impact_override_reason).toBe(
    '직접 확인함',
  )
})

// PR #88 리뷰: '공용 문서함에서 제안하기'는 창을 바꾸므로, 새 제안·승인·반려·이력 화면에서는 숨겨 쓰던 내용을 지킨다.
it('승인 확인 화면에서는 공용 문서함에서 제안하기 버튼을 숨기고, 상세로 돌아오면 다시 보인다', async () => {
  const user = userEvent.setup()
  renderPanel()
  await screen.findByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ })
  expect(
    screen.getByRole('button', { name: '공용 문서함에서 제안하기' }),
  ).toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '승인하고 기준 v2 발행' }),
  )
  expect(
    screen.getByRole('heading', { name: '기준 v2 발행을 승인할까요?' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '공용 문서함에서 제안하기' }),
  ).toBeNull()
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(
    await screen.findByRole('button', { name: '공용 문서함에서 제안하기' }),
  ).toBeInTheDocument()
})
