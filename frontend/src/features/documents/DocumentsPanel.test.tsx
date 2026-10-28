import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { documentsApi } from '@/api/services/documents'
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

it.each([
  {
    label: '최초 DRAFT',
    approvedId: undefined,
    disabled: true,
    message: '최초 승인 후 수정 초안을 작성할 수 있어요.',
  },
  {
    label: '현재 승인본',
    approvedId: '21',
    disabled: false,
    message: 'v1을 기준으로 새 초안을 보관해요. 승인된 본문은 바꾸지 않아요.',
  },
  {
    label: '과거 승인본',
    approvedId: '22',
    disabled: true,
    message: '현재 승인본에서 수정 초안을 작성해 주세요.',
  },
  {
    label: '후속 DRAFT',
    approvedId: '20',
    disabled: true,
    message: '현재 승인본에서 수정 초안을 작성해 주세요.',
  },
])(
  '$label 화면에서 수정 가능 여부와 사유를 표시한다',
  async ({ approvedId, disabled, message }) => {
    useWorkspaceStore.setState({ focusId: '2' })
    vi.mocked(documentsApi.get).mockResolvedValue({
      document: {
        ...focusedDocument,
        approved_version: approvedId
          ? { ...version, id: approvedId, status: 'APPROVED' }
          : undefined,
      },
      version: {
        ...version,
        status: approvedId && approvedId !== '20' ? 'APPROVED' : 'DRAFT',
      },
    })
    mount()
    const button = await screen.findByRole('button', { name: '수정 초안 작성' })
    if (disabled) expect(button).toBeDisabled()
    else expect(button).toBeEnabled()
    expect(screen.getByText(message)).toBeInTheDocument()
  },
)

it('과거 버전에서 현재 승인본으로 이동한 뒤 해당 승인본을 기준으로 수정한다', async () => {
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
  vi.mocked(documentsApi.uploadDraft).mockResolvedValue({
    ...version,
    id: '23',
    version_no: 3,
  })
  mount()
  await user.click(
    await screen.findByRole('button', { name: '현재 승인본 보기' }),
  )
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '수정 초안 작성' }),
    ).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: '수정 초안 작성' }))
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', {
      name: '새 초안 저장',
    }),
  )
  await waitFor(() =>
    expect(documentsApi.uploadDraft).toHaveBeenCalledWith(
      '10',
      '2',
      expect.objectContaining({ base_document_version_id: '22' }),
    ),
  )
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
  await user.click(screen.getByRole('button', { name: '문서 등록' }))
  const dialog = screen.getByRole('dialog')
  await user.type(within(dialog).getByLabelText('문서 제목'), '같은 제목')
  await user.upload(
    within(dialog).getByLabelText('원본 파일'),
    new File(['등록할 원문'], 'requirements.md', { type: 'text/markdown' }),
  )
  await user.click(within(dialog).getByRole('button', { name: '문서 등록' }))
  await waitFor(() => expect(documentsApi.upload).toHaveBeenCalledTimes(1))
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  await act(async () =>
    resolveUpload({ document: focusedDocument, analysis_status: 'PENDING' }),
  )
  await waitFor(() =>
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
  )
  expect(useWorkspaceStore.getState().focusId).toBe('2')
})
