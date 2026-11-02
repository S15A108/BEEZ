import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { documentsApi } from '@/api/services/documents'
import { planningApi } from '@/api/services/planning'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { DocumentVersion } from '@/api/contracts/common'
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

function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <DocumentsPanel projectId="10" isLeader />
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
