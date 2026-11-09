import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { dashboardApi, membersApi, projectsApi } from '@/api/services'
import type { Project } from '@/api/contracts/common'
import { ProjectHoverCard } from './ProjectHoverCard'

vi.mock('@/api/services', () => ({
  dashboardApi: {
    documents: vi.fn(),
    changes: vi.fn(),
    tasks: vi.fn(),
    activities: vi.fn(),
  },
  membersApi: { list: vi.fn() },
  projectsApi: { onboarding: vi.fn() },
}))

const project: Project = {
  id: '1',
  name: '우리 팀 협업 서비스',
  my_role: 'LEADER',
  current_baseline: {
    id: '9',
    version_no: 2,
    created_at: '2026-11-01T00:00:00Z',
  },
  created_at: '2026-11-01T00:00:00Z',
  updated_at: '2026-11-01T00:00:00Z',
}

const show = (value: Project, unread?: { text: string; label: string }) =>
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ProjectHoverCard project={value} design="modern" unread={unread} />
    </QueryClientProvider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(dashboardApi.documents).mockResolvedValue({
    by_kind: [
      { kind: 'PLAN', draft_count: 0, approved_count: 3 },
      { kind: 'ERD', draft_count: 1, approved_count: 1 },
    ],
  })
  vi.mocked(dashboardApi.changes).mockResolvedValue({
    by_status: [],
    review_required_count: 7,
  })
  vi.mocked(dashboardApi.tasks).mockResolvedValue({
    by_status: [{ status: 'IN_PROGRESS', count: 5 }],
    by_waiting_reason: [],
  })
  vi.mocked(dashboardApi.activities).mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.mocked(membersApi.list).mockResolvedValue({
    items: [
      {
        id: '1',
        account_id: '1',
        name: '이팀장',
        role: 'LEADER',
        agent_connected: true,
      },
      {
        id: '2',
        account_id: '2',
        name: '김동료',
        role: 'MEMBER',
        agent_connected: false,
      },
    ],
    has_more: false,
  })
})

it('진행 중 프로젝트의 팀장은 숫자 세 칸과 인원·에이전트를 본다', async () => {
  show(project, { text: '3', label: '안 읽은 알림 3건' })
  expect(screen.getByRole('tooltip')).toHaveTextContent('진행 중 · 기준 v2')
  expect(screen.getByText('팀장')).toBeInTheDocument()
  expect(await screen.findByText('7')).toBeInTheDocument()
  expect(screen.getByText('검토할 변경')).toBeInTheDocument()
  expect(await screen.findByText('5')).toBeInTheDocument()
  expect(await screen.findByText('4')).toBeInTheDocument()
  expect(await screen.findByText('2명 · 에이전트 1')).toBeInTheDocument()
  expect(screen.getByText('안 읽은 알림 3건')).toBeInTheDocument()
})

it('팀원에게는 검토할 변경 칸이 없고 부르지도 않는다', async () => {
  show({ ...project, my_role: 'MEMBER' })
  expect(await screen.findByText('5')).toBeInTheDocument()
  expect(screen.getByText('팀원')).toBeInTheDocument()
  expect(screen.queryByText('검토할 변경')).not.toBeInTheDocument()
  expect(dashboardApi.changes).not.toHaveBeenCalled()
})

it('시작 준비 중이면 문서 초안 진행과 다음 할 일을 보이고 집계는 부르지 않는다', async () => {
  vi.mocked(projectsApi.onboarding).mockResolvedValue({
    required_documents: [
      { kind: 'PLAN', uploaded: true, approved: false },
      { kind: 'ERD', uploaded: true, approved: false },
      { kind: 'REQUIREMENTS', uploaded: false, approved: false },
      { kind: 'API_SPEC', uploaded: false, approved: false },
    ],
    baseline_published: false,
    member_count: 2,
    my_agent_connected: false,
  })
  show({ ...project, current_baseline: undefined })
  expect(screen.getByText('시작 준비 중')).toBeInTheDocument()
  expect(await screen.findByText('문서 초안 2 / 4 올림')).toBeInTheDocument()
  expect(screen.getByText('다음: 문서 초안 올리기')).toBeInTheDocument()
  expect(dashboardApi.documents).not.toHaveBeenCalled()
  expect(dashboardApi.tasks).not.toHaveBeenCalled()
})

it('최근 활동이 있으면 활동 시각을, 없으면 만든 시각을 보인다', async () => {
  const { unmount } = show(project)
  expect(await screen.findByText(/만듦$/)).toBeInTheDocument()
  unmount()
  vi.mocked(dashboardApi.activities).mockResolvedValue({
    items: [
      {
        id: '1',
        type: 'TASK_STARTED',
        actor_type: 'HUMAN',
        result: 'SUCCESS',
        created_at: new Date().toISOString(),
      },
    ],
    has_more: false,
  })
  show(project)
  expect(await screen.findByText('방금 활동')).toBeInTheDocument()
})

it('집계 하나가 실패해도 그 칸만 —로 두고 나머지는 보인다', async () => {
  vi.mocked(dashboardApi.tasks).mockRejectedValue(new Error('실패'))
  show(project)
  expect(await screen.findByText('—')).toBeInTheDocument()
  expect(await screen.findByText('7')).toBeInTheDocument()
})
