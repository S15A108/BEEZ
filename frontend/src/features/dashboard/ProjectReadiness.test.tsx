import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { documentsApi } from '@/api/services/documents'
import { planningApi } from '@/api/services/planning'
import type {
  Document,
  DocumentVersion,
  DocumentWorkingCopy,
} from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ProjectReadiness } from './ProjectReadiness'

vi.mock('@/api/services/documents', () => ({
  documentsApi: { list: vi.fn(), workingCopy: vi.fn() },
}))
vi.mock('@/api/services/planning', () => ({
  planningApi: { items: vi.fn() },
}))

const KINDS = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const
const TITLES = ['기획안', 'ERD', '요구사항 명세서', 'API 명세서']
type Status = DocumentWorkingCopy['status']

// 종류별 작성 건 상태. 없으면 그 종류는 아직 올리지 않았다.
function seed(statuses: (Status | undefined)[], drafts = 0) {
  const documents = KINDS.flatMap((kind, index) => {
    const status = statuses[index]
    if (!status) return []
    const version = { id: `${index + 1}1`, version_no: 1 } as DocumentVersion
    return [
      {
        id: String(index + 1),
        kind,
        title: TITLES[index],
        ...(status === 'APPROVED'
          ? { approved_version: version }
          : { latest_draft: version }),
      } as Document,
    ]
  })
  vi.mocked(documentsApi.list).mockResolvedValue({
    items: documents,
    has_more: false,
  })
  vi.mocked(documentsApi.workingCopy).mockImplementation(
    async (_projectId, documentId) => ({
      document_id: documentId,
      owner_account_id: '11',
      revision: 1,
      status: statuses[Number(documentId) - 1]!,
      content: '',
      file_name: 'a.md',
      submissions: [],
    }),
  )
  vi.mocked(planningApi.items).mockResolvedValue({
    items: Array.from({ length: drafts }, (_, index) => ({
      id: String(900 + index),
    })),
    has_more: false,
  } as never)
}

function mount(isLeader = true) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ProjectReadiness projectId="10" isLeader={isLeader} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  useWorkspaceStore.setState({ pane: 'dashboard', focusId: null })
})

it('올리지 않은 문서가 있으면 다음 할 일은 그 문서 올리기이고 누르면 등록 화면을 연다', async () => {
  const user = userEvent.setup()
  seed(['EDITING', undefined, 'APPROVED', undefined])
  mount()
  expect(
    await screen.findByText('문서 올림 2 / 4 · 승인 1 / 4'),
  ).toBeInTheDocument()
  await user.click(await screen.findByRole('button', { name: 'ERD 올리기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'documents',
    focusId: 'upload:ERD',
  })
})

it('문서마다 단계(비비 검토·팀장 승인 대기·승인)를 보여 준다', async () => {
  seed(['EDITING', 'SUBMITTED', 'APPROVED', 'APPROVED'], 3)
  mount()
  expect(await screen.findByText('비비 검토 중')).toBeInTheDocument()
  const list = screen.getByRole('list', { name: '준비 상태' })
  expect(list).toHaveTextContent('팀장 승인 대기')
  expect(list).toHaveTextContent('승인 v1')
  expect(await screen.findByText('3개')).toBeInTheDocument()
})

it('팀장에게는 승인을 기다리는 문서를 먼저 다음 할 일로 보인다', async () => {
  const user = userEvent.setup()
  seed(['EDITING', 'SUBMITTED', 'APPROVED', 'APPROVED'])
  mount()
  await user.click(await screen.findByRole('button', { name: '승인 검토하기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'documents',
    focusId: 'review:2',
  })
})

it('팀원에게는 팀장 차례인 문서 대신 이어 갈 검토를 다음 할 일로 보인다', async () => {
  seed(['EDITING', 'SUBMITTED', 'APPROVED', 'APPROVED'])
  mount(false)
  expect(
    await screen.findByRole('button', { name: '기획안 검토 보기' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '승인 검토하기' }),
  ).not.toBeInTheDocument()
})

it('4종이 승인되고 설계 초안이 없으면 설계 초안 만들기로 기획 분석을 연다', async () => {
  const user = userEvent.setup()
  seed(['APPROVED', 'APPROVED', 'APPROVED', 'APPROVED'])
  mount()
  expect(await screen.findByText('아직 없어요')).toBeInTheDocument()
  await user.click(
    await screen.findByRole('button', { name: '설계 초안 만들기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('planning')
})

it('4종이 승인되고 설계 초안이 있으면 프로젝트 시작으로 기획 분석을 연다', async () => {
  const user = userEvent.setup()
  seed(['APPROVED', 'APPROVED', 'APPROVED', 'APPROVED'], 1)
  mount()
  await user.click(await screen.findByRole('button', { name: '프로젝트 시작' }))
  expect(useWorkspaceStore.getState().pane).toBe('planning')
})

it('팀원은 4종이 승인되면 버튼 없이 팀장이 발행한다고 안내한다', async () => {
  seed(['APPROVED', 'APPROVED', 'APPROVED', 'APPROVED'], 1)
  mount(false)
  expect(
    await screen.findByText(
      '팀장이 기획 분석에서 설계를 발행하면 프로젝트가 시작돼요.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '프로젝트 시작' }),
  ).not.toBeInTheDocument()
})

// 작성 건을 못 받으면 단계를 모른다. '비비 검토 중' 같은 단계를 지어내지 않는다.
it('작성 건을 못 받은 문서는 올렸다는 것만 적는다', async () => {
  seed(['EDITING', 'EDITING', 'EDITING', 'EDITING'])
  vi.mocked(documentsApi.workingCopy).mockRejectedValue(new Error('500'))
  mount()
  // 단계를 모르는 채로 다음 할 일은 정한다(첫 문서 검토 보기).
  expect(
    await screen.findByRole('button', { name: '기획안 검토 보기' }),
  ).toBeInTheDocument()
  expect(screen.getAllByText('올림')).toHaveLength(4)
  expect(screen.getByRole('list', { name: '준비 상태' })).not.toHaveTextContent(
    '비비 검토 중',
  )
})
