import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { documentsApi } from '@/api/services/documents'
import { planningApi } from '@/api/services/planning'
import { authApi, membersApi } from '@/api/services'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { DocumentVersion, Source } from '@/api/contracts/common'
import { SourceLinks } from '@/components/SourceLinks'
import { DocumentsPanel } from './DocumentsPanel'

vi.mock('@/api/services/documents', () => ({
  documentsApi: {
    list: vi.fn(),
    get: vi.fn(),
    versions: vi.fn(),
    upload: vi.fn(),
    uploadDraft: vi.fn(),
    baseline: vi.fn(),
    baselineVersion: vi.fn(),
    workingCopy: vi.fn(),
    search: vi.fn(),
  },
}))
const version: DocumentVersion = {
  id: '21',
  document_id: '2',
  version_no: 1,
  status: 'DRAFT',
  file_name: 'requirements.md',
  media_type: 'text/markdown',
  content_hash: 'hash',
  content: '선택한 문서의 본문',
  created_at: '2026-10-23T00:00:00Z',
}
const listedDocument = {
  id: '1',
  project_id: '10',
  kind: 'REQUIREMENTS' as const,
  title: '같은 제목',
}
const focusedDocument = { ...listedDocument, id: '2' }

beforeEach(() => {
  vi.clearAllMocks()
  useWorkspaceStore.setState({ pane: 'documents', focusId: null })
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [listedDocument],
    has_more: false,
  })
  vi.mocked(documentsApi.get).mockResolvedValue({
    document: focusedDocument,
    version,
  })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [version],
    has_more: false,
  })
  vi.mocked(documentsApi.baseline).mockRejectedValue(new Error('404'))
  vi.mocked(documentsApi.workingCopy).mockRejectedValue(new Error('404'))
})

function mount(isLeader = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <DocumentsPanel projectId="10" isLeader={isLeader} />
    </QueryClientProvider>,
  )
}

it('근거에서 이동한 문서가 첫 목록에 없어도 해당 문서를 연다', async () => {
  useWorkspaceStore.setState({ focusId: '2' })
  mount()
  expect(await screen.findByText('선택한 문서의 본문')).toBeInTheDocument()
  expect(documentsApi.get).toHaveBeenCalledWith('10', '2', {
    version_no: undefined,
  })
})

// PR #218 리뷰: 비비·회의·기획 답의 근거는 문서 ID만이 아니라 인용한 버전으로 문서함을 연다.
async function openFromSource(source: Source) {
  const links = render(<SourceLinks sources={[source]} />)
  await userEvent.click(screen.getByRole('button', { name: /문서 2 v/ }))
  links.unmount()
  return mount()
}

it('근거의 초안을 누르면 승인본이 아니라 그 초안 버전을 연다', async () => {
  const approved = { ...version, status: 'APPROVED' as const }
  const draft = { ...version, id: '22', version_no: 2, content: '초안 본문' }
  vi.mocked(documentsApi.get).mockImplementation(
    async (_project, _document, options) => ({
      document: { ...focusedDocument, approved_version: approved },
      version: options?.version_no === 2 ? draft : approved,
    }),
  )
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [draft, approved],
    has_more: false,
  })
  await openFromSource({
    document_id: '2',
    document_version_id: '22',
    version_no: 2,
    location: '기획안',
  })
  expect(await screen.findByText('초안 본문')).toBeInTheDocument()
  expect(documentsApi.get).toHaveBeenCalledWith('10', '2', { version_no: 2 })
  expect(documentsApi.get).not.toHaveBeenCalledWith('10', '2', {
    version_no: undefined,
  })
})

it('승인본 없이 초안만 있는 문서도 근거에서 그 초안으로 연다', async () => {
  // 버전을 지정하지 않으면 서버는 승인본을 찾다가 404를 준다.
  vi.mocked(documentsApi.get).mockImplementation(
    async (_project, _document, options) => {
      if (options?.version_no !== 1)
        throw new Error('approved_version_not_found')
      return { document: focusedDocument, version }
    },
  )
  await openFromSource({
    document_id: '2',
    document_version_id: '21',
    version_no: 1,
    location: '같은 제목',
  })
  expect(await screen.findByText('선택한 문서의 본문')).toBeInTheDocument()
  expect(screen.getByText(/승인 전/)).toBeInTheDocument()
})

it('승인본 근거는 지금처럼 그 승인본으로 연다', async () => {
  const approved = { ...version, status: 'APPROVED' as const }
  vi.mocked(documentsApi.get).mockResolvedValue({
    document: { ...focusedDocument, approved_version: approved },
    version: approved,
  })
  await openFromSource({
    document_id: '2',
    document_version_id: '21',
    version_no: 1,
    location: '같은 제목',
  })
  expect(await screen.findByText('선택한 문서의 본문')).toBeInTheDocument()
  expect(screen.getByText(/현재 기준 v1/)).toBeInTheDocument()
  expect(screen.queryByText(/를 보고 있어요/)).not.toBeInTheDocument()
})

it('인용을 누르면 지정 버전의 확인된 원문을 강조하고 그 위치로 이동한다', async () => {
  const cited = {
    ...version,
    content: '첫 줄\n두 번째 줄\n정확한 인용 문장\n마지막 줄',
  }
  const approved = {
    ...version,
    id: '22',
    version_no: 2,
    status: 'APPROVED' as const,
    content: '현재 승인본',
  }
  vi.mocked(documentsApi.get).mockImplementation(
    async (_project, _document, options) => ({
      document: { ...focusedDocument, approved_version: approved },
      version: options?.version_no === 1 ? cited : approved,
    }),
  )
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [approved, cited],
    has_more: false,
  })
  const original = HTMLElement.prototype.scrollIntoView
  const scroll = vi.fn()
  HTMLElement.prototype.scrollIntoView = scroll
  try {
    const links = render(
      <SourceLinks
        sources={[
          {
            document_id: '2',
            document_version_id: '21',
            version_no: 1,
            location: '2~4행',
          },
        ]}
        citation={{
          document_id: '2',
          document_version_id: '21',
          start_line: 2,
          end_line: 4,
          quote: '정확한 인용 문장',
        }}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: /문서 2 v1/ }))
    links.unmount()
    mount()
    const excerpt = await screen.findByRole('region', {
      name: '확인된 인용 원문',
    })
    expect(within(excerpt).getByText('정확한 인용 문장').tagName).toBe('MARK')
    expect(excerpt).toHaveTextContent('3~3행')
    expect(excerpt).toHaveTextContent('첫 줄')
    expect(excerpt).toHaveTextContent('마지막 줄')
    expect(scroll).toHaveBeenCalledWith({ block: 'center' })
    await userEvent.click(
      within(excerpt).getByRole('button', { name: '형식 적용 본문 보기' }),
    )
    expect(
      within(excerpt).queryByText('정확한 인용 문장', { selector: 'mark' }),
    ).not.toBeInTheDocument()
    await userEvent.click(
      within(excerpt).getByRole('button', { name: '원문과 인용 보기' }),
    )
    expect(within(excerpt).getByText('정확한 인용 문장').tagName).toBe('MARK')
    expect(documentsApi.get).toHaveBeenCalledWith('10', '2', { version_no: 1 })
    await userEvent.selectOptions(screen.getByLabelText('열람할 버전'), '2')
    expect(await screen.findByText('현재 승인본')).toBeInTheDocument()
    expect(
      screen.queryByRole('region', { name: '확인된 인용 원문' }),
    ).not.toBeInTheDocument()
  } finally {
    HTMLElement.prototype.scrollIntoView = original
  }
})

it('인용 범위 안에 같은 문장이 둘이면 위치를 추측하거나 강조하지 않는다', async () => {
  const ambiguous = { ...version, content: '첫 줄\n같은 문장\n같은 문장' }
  vi.mocked(documentsApi.get).mockResolvedValue({
    document: focusedDocument,
    version: ambiguous,
  })
  const links = render(
    <SourceLinks
      sources={[
        {
          document_id: '2',
          document_version_id: '21',
          version_no: 1,
          location: '2~3행',
        },
      ]}
      citation={{
        document_id: '2',
        document_version_id: '21',
        start_line: 2,
        end_line: 3,
        quote: '같은 문장',
      }}
    />,
  )
  await userEvent.click(screen.getByRole('button', { name: /문서 2 v1/ }))
  links.unmount()
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '하나로 확인할 수 없어요',
  )
  expect(
    screen.queryByRole('region', { name: '확인된 인용 원문' }),
  ).not.toBeInTheDocument()
})

it('인용과 다른 문서 버전으로 연결되는 버튼은 열 수 없다', () => {
  render(
    <SourceLinks
      sources={[
        {
          document_id: '2',
          document_version_id: '22',
          version_no: 2,
          location: '1행',
        },
      ]}
      citation={{
        document_id: '2',
        document_version_id: '21',
        start_line: 1,
        end_line: 1,
        quote: '원문',
      }}
    />,
  )
  expect(screen.getByRole('button', { name: /문서 2 v2/ })).toBeDisabled()
})

// PR #218 리뷰: 인용한 버전 번호를 찾기 전에는 문서를 부르지 않는다(승인본으로 넘어가지 않는다).
it('근거로 열 때 버전 이력 조회가 실패하면 오류와 다시 불러오기를 보이고 승인본을 부르지 않는다', async () => {
  const draft = { ...version, id: '22', version_no: 2, content: '초안 본문' }
  vi.mocked(documentsApi.get).mockImplementation(
    async (_project, _document, options) => ({
      document: focusedDocument,
      version: options?.version_no === 2 ? draft : version,
    }),
  )
  vi.mocked(documentsApi.versions)
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValue({ items: [draft, version], has_more: false })
  await openFromSource({
    document_id: '2',
    document_version_id: '22',
    version_no: 2,
    location: '기획안',
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '정보를 불러오지 못했어요',
  )
  expect(documentsApi.get).not.toHaveBeenCalled()

  await userEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('초안 본문')).toBeInTheDocument()
  expect(documentsApi.get).toHaveBeenCalledTimes(1)
  expect(documentsApi.get).toHaveBeenCalledWith('10', '2', { version_no: 2 })
})

it('인용한 버전이 이력에 없으면 찾을 수 없다고 알리고 승인본을 부르지 않는다', async () => {
  // 지워졌거나 볼 수 없는 버전. 이력에는 v1만 있다.
  await openFromSource({
    document_id: '2',
    document_version_id: '29',
    version_no: 9,
    location: '기획안',
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '열려던 버전을 버전 이력에서 찾을 수 없어요',
  )
  expect(documentsApi.get).not.toHaveBeenCalled()
})

// 수정안(PNL-02-m): 문서 상세는 보기 전용. '수정 초안 작성' 대신 새 .md를 올려 비비 검토·팀장 승인을 다시 거친다.
it('문서 상세는 보기 전용이고 수정 초안 작성 버튼이 없다', async () => {
  useWorkspaceStore.setState({ focusId: '2' })
  mount()
  expect(
    await screen.findByText(/승인된 버전은 바뀌지 않아요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '수정 초안 작성' }),
  ).not.toBeInTheDocument()
})

it('과거 버전을 보다가 현재 승인본으로 돌아간다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  const approved = {
    ...version,
    id: '22',
    version_no: 2,
    status: 'APPROVED' as const,
  }
  vi.mocked(documentsApi.get).mockImplementation(
    async (_project, _document, options) => ({
      document: { ...focusedDocument, approved_version: approved },
      version: options?.version_no === 2 ? approved : version,
    }),
  )
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [approved, version],
    has_more: false,
  })
  mount()
  await user.click(
    await screen.findByRole('button', { name: '기준 v2로 돌아가기' }),
  )
  await waitFor(() =>
    expect(documentsApi.get).toHaveBeenLastCalledWith(
      '10',
      '2',
      expect.objectContaining({ version_no: 2 }),
    ),
  )
  expect(
    screen.queryByRole('button', { name: '기준 v2로 돌아가기' }),
  ).not.toBeInTheDocument()
})

it('등록 중 닫기를 막고 같은 제목의 기존 문서 대신 응답 문서로 이동한다', async () => {
  const user = userEvent.setup()
  let resolveUpload: (
    value: Awaited<ReturnType<typeof documentsApi.upload>>,
  ) => void = () => undefined
  vi.mocked(documentsApi.upload).mockImplementation(
    () =>
      new Promise((resolve) => {
        resolveUpload = resolve
      }),
  )
  mount()
  // 수정안(PNL-02 모달안): 등록은 겹친 창이 아니라 같은 창 안의 등록 화면이다.
  // 시작 전에는 '문서 등록' 버튼 없이 필수 문서 빈 칸으로 등록 화면을 연다(사용자 승인으로 누르는 곳을 바꿈).
  await user.click(await screen.findByRole('button', { name: '기획안 올리기' }))
  await user.clear(screen.getByLabelText(/문서 제목/))
  await user.type(screen.getByLabelText(/문서 제목/), '같은 제목')
  await user.upload(
    screen.getByLabelText(/원본 파일/),
    new File(['등록할 원문'], 'requirements.md', { type: 'text/markdown' }),
  )
  await user.click(
    screen.getByRole('button', { name: '등록하고 비비 검토 받기' }),
  )
  await waitFor(() => expect(documentsApi.upload).toHaveBeenCalledTimes(1))
  expect(documentsApi.upload).toHaveBeenCalledWith(
    '10',
    expect.objectContaining({ kind: 'PLAN', title: '같은 제목' }),
  )
  expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  expect(
    screen.getByRole('button', { name: '이전 화면으로 돌아가기' }),
  ).toBeDisabled()
  await act(async () =>
    resolveUpload({ document: focusedDocument, analysis_status: 'PENDING' }),
  )
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '등록하고 비비 검토 받기' }),
    ).not.toBeInTheDocument(),
  )
  expect(useWorkspaceStore.getState().focusId).toBe('2')
})

// 10-29 보드: 시작 전에는 필수 문서 4종 칸에 승인 수를 보여 주고, 4종이 승인되기 전에는 '프로젝트 시작'을 잠근다.
it('시작 전에는 필수 문서 4종 칸과 잠긴 프로젝트 시작을 보여 준다', async () => {
  const user = userEvent.setup()
  const approved = { ...version, status: 'APPROVED' as const }
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [
      { ...listedDocument, kind: 'REQUIREMENTS', approved_version: approved },
      {
        ...listedDocument,
        id: '3',
        kind: 'ERD',
        title: 'ERD',
        approved_version: approved,
      },
    ],
    has_more: false,
  })
  mount()
  expect(await screen.findByText('팀장 승인 2 / 4')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '프로젝트 시작' })).toBeDisabled()
  expect(screen.getAllByText('승인 v1')).toHaveLength(2)
  await user.click(screen.getByRole('button', { name: '기획안 올리기' }))
  // 유형은 고르지 않고 누른 칸의 종류로 고정해 보인다(사용자 승인으로 라디오 확인을 바꿈).
  expect(
    screen.getByRole('heading', { name: '기획안 등록' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText('공용 문서함에서 누른 칸의 종류라 바꿀 수 없어요'),
  ).toBeInTheDocument()
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
})

it('시작 전에는 문서 등록 버튼이 없고, 형식이 다르면 파일 줄에 알리고 등록 버튼을 끈다', async () => {
  const user = userEvent.setup({ applyAccept: false })
  mount()
  await screen.findByText('팀장 승인 0 / 4')
  expect(
    screen.queryByRole('button', { name: '문서 등록' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '기획안 올리기' }))
  await user.upload(
    screen.getByLabelText(/원본 파일/),
    new File(['png'], 'erd_diagram.png', { type: 'image/png' }),
  )
  expect(
    await screen.findByText(
      '지원하지 않는 형식이에요. Markdown(.md) 파일만 올릴 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '등록하고 비비 검토 받기' }),
  ).toBeDisabled()
  expect(documentsApi.upload).not.toHaveBeenCalled()
})

it('서버가 거절하면 원인별로 알리고 입력값을 남긴다', async () => {
  const user = userEvent.setup()
  const rejectWith = (status: number, code: string) =>
    new AxiosError('rejected', 'ERR_BAD_REQUEST', undefined, undefined, {
      status,
      data: { error: { code } },
    } as never)
  vi.mocked(documentsApi.upload)
    .mockRejectedValueOnce(rejectWith(409, 'document_kind_exists'))
    .mockRejectedValueOnce(rejectWith(409, 'change_proposal_required'))
  mount()
  await user.click(await screen.findByRole('button', { name: '기획안 올리기' }))
  await user.upload(
    screen.getByLabelText(/원본 파일/),
    new File(['# 기획안'], 'plan.md', { type: 'text/markdown' }),
  )
  await user.click(
    screen.getByRole('button', { name: '등록하고 비비 검토 받기' }),
  )
  expect(
    await screen.findByText(/등록하지 못했어요 · 이 종류의 문서는 이미 있어요/),
  ).toBeInTheDocument()
  expect(screen.getByLabelText(/문서 제목/)).toHaveValue('기획안')
  await user.click(
    screen.getByRole('button', { name: '등록하고 비비 검토 받기' }),
  )
  expect(
    await screen.findByText(/기준 v1이 생긴 뒤에는 문서를 새로 올리지 않아요/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '변경 제안 쓰기' }),
  ).toBeInTheDocument()
})

// PR #72 전수 리뷰 AMP-01: 서버는 문서 승인·색인 뒤 설계 초안을 만들지 않는다.
// 4종이 승인됐는데 초안이 0개면 '프로젝트 시작'을 끈 채 기획 분석 창(초안 만들기·직접 추가)으로 보낸다.
it('4종이 승인됐어도 설계 초안이 없으면 기획 분석으로 보내고 시작을 막는다', async () => {
  const user = userEvent.setup()
  const approved = { ...version, status: 'APPROVED' as const }
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: (['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
      (kind, index) => ({
        ...listedDocument,
        id: String(index + 1),
        kind,
        approved_version: approved,
      }),
    ),
    has_more: false,
  })
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items: [],
    has_more: false,
    draft_snapshot_hash: 'sha256:empty',
  })
  mount()
  await user.click(
    await screen.findByRole('button', { name: '기획 분석에서 초안 만들기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('planning')
  expect(screen.getByRole('button', { name: '프로젝트 시작' })).toBeDisabled()
})

// S15P31A108-389: 설계 초안이 하나라도 있어도 채택하지 않은 후보가 남았으면 기획 분석으로 돌아갈 길이 남는다.
it('설계 초안이 있어도 채택하지 않은 후보가 있으면 후보 검토 버튼을 보이고 시작은 막지 않는다', async () => {
  const user = userEvent.setup()
  const approved = { ...version, status: 'APPROVED' as const }
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: (['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
      (kind, index) => ({
        ...listedDocument,
        id: String(index + 1),
        kind,
        approved_version: approved,
      }),
    ),
    has_more: false,
  })
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items: [{ id: '901' } as never],
    has_more: false,
    draft_snapshot_hash: 'sha256:one',
  })
  vi.spyOn(planningApi, 'drafts').mockResolvedValue({
    jobs: [
      {
        id: '1',
        document_version_id: approved.id,
        status: 'COMPLETED',
        mode: 'DOCUMENT_ONLY',
        candidates: [
          { id: '21', status: 'PENDING' },
          { id: '22', status: 'ADOPTED' },
        ],
      },
    ],
  } as never)
  mount()
  expect(
    await screen.findByText(/채택하지 않은 후보 1개이 남아 있어요/),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '후보 1개 검토하기' }))
  expect(useWorkspaceStore.getState().pane).toBe('planning')
  expect(
    screen.queryByRole('button', { name: '기획 분석에서 초안 만들기' }),
  ).toBeNull()
  expect(screen.getByRole('button', { name: '프로젝트 시작' })).toBeEnabled()
})

// PR #72 재전수 ROUND2-DOC-03: 버전 이력은 다음 페이지까지 읽어 가장 오래된 v1도 고를 수 있다.
it('버전 이력은 다음 페이지까지 읽어 v1까지 보인다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  const newer = Array.from({ length: 20 }, (_, index) => ({
    ...version,
    id: String(100 + index),
    version_no: 21 - index,
  }))
  vi.mocked(documentsApi.versions)
    .mockResolvedValueOnce({ items: newer, has_more: true, next_cursor: 'c2' })
    .mockResolvedValueOnce({ items: [version], has_more: false })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  expect(await screen.findByText('v1')).toBeInTheDocument()
  expect(documentsApi.versions).toHaveBeenLastCalledWith('10', '2', {
    cursor: 'c2',
    limit: 100,
  })
})

// PR #72 재전수 ROUND2-DOC-02: 발행한 제안의 '기준 vN 보기'는 그 기준의 문서 버전을 연다(지금 승인본이 아니라).
it('기준 vN 문서에서 문서를 누르면 그 기준의 버전으로 연다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: 'baseline:2' })
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: [focusedDocument],
    has_more: false,
  })
  vi.mocked(documentsApi.baselineVersion).mockResolvedValue({
    id: '402',
    project_id: '10',
    version_no: 2,
    documents: [{ document_id: '2', document_version_id: '21' }],
    items: [],
    approved_by: '11',
    created_at: '2026-10-23T00:00:00Z',
  } as never)
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [{ ...version, version_no: 3, id: '23' }, version],
    has_more: false,
  })
  mount()
  expect(
    await screen.findByRole('heading', { name: '기준 v2 문서' }),
  ).toBeInTheDocument()
  await user.click(await screen.findByRole('button', { name: /같은 제목/ }))
  await waitFor(() =>
    expect(documentsApi.get).toHaveBeenCalledWith('10', '2', {
      version_no: 1,
    }),
  )
  expect(documentsApi.get).not.toHaveBeenCalledWith('10', '2', {
    version_no: undefined,
  })
})

// PR #72 3차 조사 THIRD-DOC-02: 검색은 첫 페이지만 쓰지 않고, has_more면 그 묶음 아래 '더 보기'로 다음 페이지를 잇는다.
it('검색 결과가 더 있으면 더 보기로 다음 페이지를 불러온다', async () => {
  const user = userEvent.setup()
  const hit = (index: number) => ({
    id: String(500 + index),
    type: 'DESIGN_ITEM' as const,
    title: `설계 항목 ${index}`,
    snippet: '로그인',
    score: 1,
  })
  vi.mocked(documentsApi.search).mockImplementation(async (_project, query) =>
    query.mode === 'SEMANTIC'
      ? { items: [], has_more: false, elapsed_ms: 0 }
      : query.cursor
        ? { items: [hit(21)], has_more: false, elapsed_ms: 0 }
        : {
            items: Array.from({ length: 20 }, (_, index) => hit(index + 1)),
            has_more: true,
            next_cursor: 'c2',
            elapsed_ms: 0,
          },
  )
  mount()
  await user.type(await screen.findByLabelText('검색어'), '로그인')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText(/검색 결과 20건 이상/)).toBeInTheDocument()
  expect(screen.queryByText('설계 항목 21')).not.toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '단어가 들어간 문서 더 보기' }),
  )
  expect(await screen.findByText('설계 항목 21')).toBeInTheDocument()
  expect(screen.getByText(/검색 결과 21건/)).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '단어가 들어간 문서 더 보기' }),
  ).not.toBeInTheDocument()
  expect(documentsApi.search).toHaveBeenCalledWith('10', {
    q: '로그인',
    mode: 'KEYWORD',
    cursor: 'c2',
  })
})

// PR #72 4차 조사 FOURTH-DOC-01: 서버는 문서를 등록한 사람만 분류를 고치게 한다. 등록한 사람에게만 버튼을 보이고,
// 팀장 검토를 요청한 동안(SUBMITTED)은 끈다.
it('분류 수정은 문서를 등록한 사람에게만 보이고 검토 요청 중에는 꺼진다', async () => {
  useWorkspaceStore.setState({ focusId: '2' })
  vi.spyOn(authApi, 'me').mockResolvedValue({
    id: '11',
    email: 'owner@beez.local',
    name: '등록한 사람',
    created_at: '2026-10-23T00:00:00Z',
  })
  const copy = (owner: string, status: 'EDITING' | 'SUBMITTED') => ({
    document_id: '2',
    owner_account_id: owner,
    revision: 1,
    status,
    content: '',
    file_name: 'requirements.md',
    submissions: [],
  })
  vi.mocked(documentsApi.workingCopy).mockResolvedValue(copy('12', 'EDITING'))
  const first = mount()
  await screen.findByRole('button', { name: /원본 다운로드/ })
  await waitFor(() => expect(documentsApi.workingCopy).toHaveBeenCalled())
  expect(
    screen.queryByRole('button', { name: '분류 수정' }),
  ).not.toBeInTheDocument()
  first.unmount()
  vi.mocked(documentsApi.workingCopy).mockResolvedValue(copy('11', 'EDITING'))
  const second = mount()
  expect(await screen.findByRole('button', { name: '분류 수정' })).toBeEnabled()
  second.unmount()
  vi.mocked(documentsApi.workingCopy).mockResolvedValue(copy('11', 'SUBMITTED'))
  mount()
  expect(
    await screen.findByRole('button', { name: '분류 수정' }),
  ).toBeDisabled()
})

it('서버가 비었거나 UTF-8이 아닌 파일을 거절하면 파일 줄 아래에 안내한다', async () => {
  const user = userEvent.setup()
  vi.mocked(documentsApi.upload).mockRejectedValueOnce(
    new AxiosError('rejected', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 400,
      data: { error: { code: 'validation_failed' } },
    } as never),
  )
  mount()
  await user.click(await screen.findByRole('button', { name: '기획안 올리기' }))
  await user.upload(
    screen.getByLabelText(/원본 파일/),
    new File(['# 기획안'], 'plan.md', { type: 'text/markdown' }),
  )
  await user.click(
    screen.getByRole('button', { name: '등록하고 비비 검토 받기' }),
  )
  expect(
    await screen.findByText(/비어 있지 않은 UTF-8 Markdown\(\.md\) 파일/),
  ).toBeInTheDocument()
  expect(screen.getByLabelText(/문서 제목/)).toHaveValue('기획안')
})

// 201: 버전 행에 올린 사람·서버가 알려 준 현재 기준·변경 제안 보기를 보인다.
it('버전 이력은 올린 사람과 현재 기준을 보이고 변경 제안이 있는 버전만 제안으로 이동한다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  vi.spyOn(membersApi, 'list').mockResolvedValue({
    items: [
      {
        id: '5',
        account_id: '12',
        name: '김동료',
        role: 'MEMBER',
        agent_connected: false,
      },
    ],
    has_more: false,
  })
  const first = {
    ...version,
    id: '21',
    status: 'APPROVED' as const,
    is_current_baseline: true,
    created_by: '12',
  }
  const second = {
    ...version,
    id: '22',
    version_no: 2,
    is_current_baseline: false,
    created_by: '12',
    change_proposal_id: '71',
  }
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [second, first],
    has_more: false,
  })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  const rows = await screen.findAllByRole('listitem')
  const v2 = rows.find((row) => within(row).queryByText('v2'))!
  const v1 = rows.find((row) => within(row).queryByText('v1'))!
  expect(await within(v2).findByText(/김동료/)).toBeInTheDocument()
  expect(within(v1).getByText('현재 기준')).toBeInTheDocument()
  expect(within(v2).queryByText('현재 기준')).not.toBeInTheDocument()
  expect(
    within(v1).queryByRole('button', { name: '변경 제안 보기' }),
  ).not.toBeInTheDocument()
  await user.click(within(v2).getByRole('button', { name: '변경 제안 보기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'changes',
    focusId: '71',
  })
})

it('서버가 현재 기준 여부를 주면 그 값으로 칩을 정한다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  const approved = { ...version, id: '22', status: 'APPROVED' as const }
  vi.mocked(documentsApi.get).mockResolvedValue({
    document: { ...focusedDocument, approved_version: approved },
    version,
  })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [
      { ...approved, version_no: 2, is_current_baseline: false },
      { ...version, is_current_baseline: true },
    ],
    has_more: false,
  })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  const rows = await screen.findAllByRole('listitem')
  const v1 = rows.find((row) => within(row).queryByText('v1'))!
  expect(within(v1).getByText('현재 기준')).toBeInTheDocument()
  expect(screen.getAllByText('현재 기준')).toHaveLength(1)
})

// 프로젝트 시작 전에는 서버가 모든 버전에 is_current_baseline=false 를 주므로 헤더(승인본)와 같은 버전에 칩을 단다.
it('시작 전 문서는 서버 값이 모두 false여도 승인본에 현재 기준 칩을 보인다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  const approved = { ...version, id: '22', status: 'APPROVED' as const }
  vi.mocked(documentsApi.get).mockResolvedValue({
    document: { ...focusedDocument, approved_version: approved },
    version,
  })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [
      { ...approved, version_no: 2, is_current_baseline: false },
      { ...version, is_current_baseline: false },
    ],
    has_more: false,
  })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  const rows = await screen.findAllByRole('listitem')
  const v2 = rows.find((row) => within(row).queryByText('v2'))!
  expect(within(v2).getByText('현재 기준')).toBeInTheDocument()
  expect(screen.getAllByText('현재 기준')).toHaveLength(1)
})

it('올린 사람이 구성원 목록에 없으면 팀원으로 보인다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  vi.spyOn(membersApi, 'list').mockResolvedValue({ items: [], has_more: false })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [{ ...version, created_by: '99' }],
    has_more: false,
  })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  const rows = await screen.findAllByRole('listitem')
  expect(await within(rows[0]).findByText(/· 팀원/)).toBeInTheDocument()
})

it('문서 내용 탭의 버전 드롭다운도 REJECTED를 미채택으로 보인다', async () => {
  useWorkspaceStore.setState({ focusId: '2' })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [
      { ...version, id: '32', version_no: 2, status: 'REJECTED' as const },
      { ...version, id: '31', version_no: 1, status: 'DRAFT' as const },
    ],
    has_more: false,
  })
  mount()
  expect(
    await screen.findByRole('option', { name: /v2 · 미채택/ }),
  ).toBeInTheDocument()
  expect(screen.queryByRole('option', { name: /반려/ })).not.toBeInTheDocument()
})

it('버전 이력은 승인·이전 버전·초안·미채택을 구분해 보인다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.setState({ focusId: '2' })
  const make = (
    no: number,
    status: 'DRAFT' | 'APPROVED' | 'SUPERSEDED' | 'REJECTED',
  ) => ({ ...version, id: String(30 + no), version_no: no, status })
  vi.mocked(documentsApi.versions).mockResolvedValue({
    items: [
      make(4, 'REJECTED'),
      make(3, 'APPROVED'),
      make(2, 'SUPERSEDED'),
      make(1, 'DRAFT'),
    ],
    has_more: false,
  })
  mount()
  await user.click(await screen.findByRole('tab', { name: /버전 이력/ }))
  const rows = await screen.findAllByRole('listitem')
  const row = (no: number) =>
    rows.find((item) => within(item).queryByText(`v${no}`))!
  expect(within(row(4)).getByText('미채택')).toBeInTheDocument()
  expect(within(row(3)).getByText('승인')).toBeInTheDocument()
  expect(within(row(2)).getByText('이전 버전')).toBeInTheDocument()
  expect(within(row(1)).getByText('초안')).toBeInTheDocument()
})

// S15P31A108-362: 참고 승인본이 바뀌어 서버가 검토를 쓸 수 없다고 알리면, 카드는 '팀장 검토 요청 전'이 아니라 다시 검토를 안내한다.
it('시작 준비 카드는 서버가 검토를 쓸 수 없다고 알린 문서를 다시 검토 필요로 보인다', async () => {
  vi.mocked(documentsApi.workingCopy).mockResolvedValue({
    document_id: '1',
    owner_account_id: '11',
    revision: 1,
    status: 'EDITING',
    content: '',
    file_name: 'requirements.md',
    latest_review: {
      id: '5',
      input_revision: 1,
      resolved_revision: 1,
      reference_hash: 'sha256:old',
      summary: '요약',
      unavailable_references: ['ERD'],
      references: [],
      suggestions: [],
      usage: [],
      steps: [],
      references_used: [],
    },
    submissions: [],
    review_stale: true,
  })
  mount()
  expect(await screen.findByText('다시 검토 필요')).toBeInTheDocument()
  expect(
    screen.getByText('참고 문서가 바뀌었어요. 비비 검토를 다시 받아 주세요'),
  ).toBeInTheDocument()
  expect(screen.queryByText('팀장 검토 요청 전')).not.toBeInTheDocument()
})

// S15P31A108-383: -382가 작업본에 검토 실패(bee_review_request)를 실었지만 시작 준비 카드는 그대로
// '비비 검토 중 · 비비가 검토하고 있어요'를 보여, 팀원은 끝나지 않을 검토를 기다렸다.
// 다시 검토는 올린 사람만, 등록 취소는 올린 사람과 팀장이 하므로 보는 사람마다 할 일을 다르게 알린다.
const failedCopy = (status: 'EDITING' | 'REJECTED' = 'EDITING') => ({
  document_id: '1',
  owner_account_id: '11',
  revision: 1,
  status,
  content: '',
  file_name: 'requirements.md',
  submissions: [],
  bee_review_request: {
    status: 'FAILED' as const,
    error_code: 'review_timeout',
    updated_at: '2026-11-10T08:16:05Z',
  },
})

it.each([
  [
    '11',
    false,
    '검토 실패',
    '비비 검토를 마치지 못했어요. 열어서 다시 검토해 주세요',
  ],
  [
    '12',
    false,
    '검토 실패',
    '비비 검토를 마치지 못했어요. 올린 사람이 다시 검토해요',
  ],
  [
    '12',
    true,
    '검토 실패',
    '비비 검토를 마치지 못했어요. 올린 사람이 다시 검토하거나, 열어서 등록을 취소할 수 있어요',
  ],
])(
  '시작 준비 카드는 검토가 실패한 문서를 보는 사람(%s, 팀장 %s)에게 할 일을 알린다',
  async (viewer, isLeader, label, hint) => {
    const me = vi.spyOn(authApi, 'me').mockResolvedValue({
      id: viewer,
      email: 'viewer@beez.local',
      name: '보는 사람',
      created_at: '2026-10-23T00:00:00Z',
    })
    try {
      vi.mocked(documentsApi.workingCopy).mockResolvedValue(failedCopy())
      mount(isLeader)
      expect(await screen.findByText(label)).toBeInTheDocument()
      expect(screen.getByText(hint)).toBeInTheDocument()
      expect(
        screen.queryByText('비비가 검토하고 있어요'),
      ).not.toBeInTheDocument()
    } finally {
      me.mockRestore()
    }
  },
)

it('반려 뒤 다시 받던 검토가 실패해도 시작 준비 카드는 반려됨을 지킨다', async () => {
  vi.mocked(documentsApi.workingCopy).mockResolvedValue(failedCopy('REJECTED'))
  mount(false)
  expect(await screen.findByText('반려됨')).toBeInTheDocument()
  expect(
    screen.getByText(/^반려 뒤 비비 검토를 마치지 못했어요\./),
  ).toBeInTheDocument()
})
