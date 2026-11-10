/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi, changesApi, tasksApi } from '@/api/services'
import { dashboardApi } from '@/api/services/dashboard'
import { statusLabel } from '@/lib/display'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { RelationsView } from './RelationsView'

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
// 테스트가 실패해도 spy가 다음 테스트로 새지 않게 여기서 되돌린다.
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

it('왼쪽에서 고른 작업 기준으로 확인된 관계와 검토할 후보를 나눠 보여 준다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RelationsView projectId="101" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /TASK-002/ }))
  const list = await screen.findByRole('region', { name: '관계 목록' })
  expect(
    await within(list).findByText(/TASK-002 초대 화면 정보 바꾸기 → REQ-001/),
  ).toHaveTextContent('확인된 관계')
  expect(
    within(list).getByText(/프로젝트 목록 빈 화면 안내 → REQ-001/),
  ).toHaveTextContent('검토할 후보 · 확정 아님')
})

it('위쪽에 고른 작업의 제목과 상태를 보여 주고 작업 현황으로 보낸다', async () => {
  const user = userEvent.setup()
  const { items } = await tasksApi.list('101', { limit: 100 })
  const task = items.find((entry) => entry.key === 'TASK-002')!
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RelationsView projectId="101" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /TASK-002/ }))
  const heading = await screen.findByRole('heading', {
    name: new RegExp(task.current_plan_revision.plan.title),
  })
  expect(within(heading).getByText(statusLabel(task.status))).toBeVisible()
  await user.click(screen.getByRole('button', { name: '작업 현황에서 보기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: task.id,
  })
})

it('그래프의 검증 상자를 누르면 고른 작업의 작업 현황을 연다', async () => {
  const user = userEvent.setup()
  const { items } = await tasksApi.list('101', { limit: 100 })
  const task = items.find((entry) => entry.key === 'TASK-002')!
  vi.spyOn(dashboardApi, 'relations').mockResolvedValue({
    nodes: [{ id: '12', type: 'VALIDATION', label: '계획 검증 PASS' }],
    edges: [],
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RelationsView projectId="101" />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /TASK-002/ }))
  const svg = await screen.findByRole('img', { name: /관계 그래프/ })
  await user.click(within(svg).getByText('계획 검증 PASS'))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: task.id,
  })
})

// PR #72 리뷰 8: 관계 목록에서 선행 작업을 누르면 지금 작업이 아니라 그 작업을 연다.
it('관계 목록의 작업 보기는 누른 작업을 연다', async () => {
  const user = userEvent.setup()
  vi.spyOn(dashboardApi, 'relations').mockResolvedValue({
    nodes: [
      { id: '501', type: 'TASK', label: 'TASK-001 목록 화면' },
      { id: '502', type: 'TASK', label: 'TASK-002 선행 작업' },
    ],
    edges: [
      {
        from_id: '501',
        from_type: 'TASK',
        to_id: '502',
        to_type: 'TASK',
        relation: 'DEPENDENCY',
        confirmed: true,
      },
    ],
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RelationsView projectId="101" />
    </QueryClientProvider>,
  )
  const list = await screen.findByRole('region', { name: '관계 목록' })
  await user.click(within(list).getByRole('button', { name: '작업 보기 ›' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: '502',
  })
})

// PR #72 mock/live 전수 대조 LA-TC-10: PROPOSED_FROM(이 작업에서 나온 제안)은 영향 경고가 아니다.
// 경고는 진행 중 제안이 이 작업이 참조하는 설계 항목을 바꿀 때(항목 기준 관계의 제안 → 항목)만 띄운다.
const taskGraph = {
  nodes: [
    { id: '501', type: 'TASK' as const, label: 'TASK-001 목록 화면' },
    { id: '301', type: 'DESIGN_ITEM' as const, label: 'REQ-001' },
    { id: '71', type: 'CHANGE_PROPOSAL' as const, label: '빈 화면 안내' },
  ],
  edges: [
    {
      from_id: '501',
      from_type: 'TASK' as const,
      to_id: '301',
      to_type: 'DESIGN_ITEM' as const,
      relation: 'IMPLEMENTS',
      confirmed: true,
    },
    {
      from_id: '71',
      from_type: 'CHANGE_PROPOSAL' as const,
      to_id: '501',
      to_type: 'TASK' as const,
      relation: 'PROPOSED_FROM',
      confirmed: true,
    },
  ],
}
function renderView() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RelationsView projectId="101" />
    </QueryClientProvider>,
  )
}

it('이 작업에서 나온 제안(PROPOSED_FROM)만 있으면 영향 경고를 띄우지 않는다', async () => {
  vi.spyOn(dashboardApi, 'relations').mockImplementation(async (_id, query) =>
    query?.design_item_id ? { nodes: [], edges: [] } : taskGraph,
  )
  const user = userEvent.setup()
  renderView()
  await user.click(await screen.findByRole('button', { name: /TASK-001/ }))
  const list = await screen.findByRole('region', { name: '관계 목록' })
  expect(
    await within(list).findByText(/이 작업에서 나온 제안/),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('검토 중인 변경 제안이 이 작업의 근거 설계를 고쳐요'),
  ).not.toBeInTheDocument()
})

it('진행 중 제안이 참조 설계 항목을 바꾸면 항목 기준 관계로 영향 경고를 띄운다', async () => {
  vi.spyOn(dashboardApi, 'relations').mockImplementation(async (_id, query) =>
    query?.design_item_id
      ? {
          nodes: [
            { id: '301', type: 'DESIGN_ITEM', label: 'REQ-001' },
            { id: '71', type: 'CHANGE_PROPOSAL', label: '빈 화면 안내' },
          ],
          edges: [
            {
              from_id: '71',
              from_type: 'CHANGE_PROPOSAL',
              to_id: '301',
              to_type: 'DESIGN_ITEM',
              relation: 'UPDATE',
              confirmed: true,
            },
          ],
        }
      : taskGraph,
  )
  const user = userEvent.setup()
  renderView()
  await user.click(await screen.findByRole('button', { name: /TASK-001/ }))
  expect(
    await screen.findByText(
      '검토 중인 변경 제안이 이 작업의 근거 설계를 고쳐요',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/‘빈 화면 안내’ 제안이 승인되면 REQ-001의 새/),
  ).toBeInTheDocument()
})

// PR #72 재전수 R2-TC-02: 작업을 마지막 페이지까지 읽어 101번째 이후 작업도 찾고 관계를 본다.
it('작업 100개를 넘어도 다음 페이지 작업을 찾아 고를 수 있다', async () => {
  const user = userEvent.setup()
  const list = tasksApi.list
  vi.spyOn(tasksApi, 'list').mockImplementation(async (projectId, query) => {
    const page = await list(projectId, { limit: 100 })
    if (!query?.cursor) return { ...page, has_more: true, next_cursor: 'c2' }
    return {
      items: [
        {
          ...page.items[0],
          id: '999',
          key: 'TASK-101',
          current_plan_revision: {
            ...page.items[0].current_plan_revision,
            plan: {
              ...page.items[0].current_plan_revision.plan,
              title: '백한 번째 작업',
            },
          },
        },
      ],
      has_more: false,
    }
  })
  const relations = vi
    .spyOn(dashboardApi, 'relations')
    .mockResolvedValue({ nodes: [], edges: [] })
  renderView()
  await user.type(
    await screen.findByPlaceholderText('작업 번호·제목'),
    'TASK-101',
  )
  await user.click(await screen.findByRole('button', { name: /TASK-101/ }))
  await waitFor(() =>
    expect(relations).toHaveBeenCalledWith('101', { task_id: '999' }),
  )
})

// S15P31A108-346: 기준 v2인 프로젝트에서도 '기준 v1이 생기기 전에는 올릴 수 없다'고 안내했다.
it('작업이 없을 때 기준 v1 안내는 기준이 아직 없을 때만 붙인다', async () => {
  vi.spyOn(tasksApi, 'list').mockResolvedValue({ items: [], has_more: false })
  const client = () =>
    new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const { unmount } = render(
    <QueryClientProvider client={client()}>
      <RelationsView projectId="101" hasBaseline />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('아직 그릴 관계가 없어요')).toBeVisible()
  expect(screen.queryByText(/기준 v1이 생기기 전에는/)).not.toBeInTheDocument()
  unmount()
  render(
    <QueryClientProvider client={client()}>
      <RelationsView projectId="101" hasBaseline={false} />
    </QueryClientProvider>,
  )
  expect(await screen.findByText(/기준 v1이 생기기 전에는/)).toBeVisible()
})

// PR #72 4차 TC-02: 진행 중 제안이 첫 50개 밖에 있으면 영향 경고가 사라졌다. 마지막 페이지까지 읽어 맞춘다.
it('진행 중 제안이 첫 페이지 밖에 있어도 영향 경고를 띄운다', async () => {
  vi.spyOn(dashboardApi, 'relations').mockImplementation(async (_id, query) =>
    query?.design_item_id
      ? {
          nodes: [
            { id: '301', type: 'DESIGN_ITEM', label: 'REQ-001' },
            { id: '71', type: 'CHANGE_PROPOSAL', label: '빈 화면 안내' },
          ],
          edges: [
            {
              from_id: '71',
              from_type: 'CHANGE_PROPOSAL',
              to_id: '301',
              to_type: 'DESIGN_ITEM',
              relation: 'UPDATE',
              confirmed: true,
            },
          ],
        }
      : taskGraph,
  )
  const list = changesApi.list
  const changes = vi
    .spyOn(changesApi, 'list')
    .mockImplementation(async (projectId, query) => {
      const page = await list(projectId, { group: 'IN_PROGRESS' })
      const later = page.items.filter((item) => item.id === '71')
      expect(later).toHaveLength(1)
      return query?.cursor === 'p2'
        ? { items: later, has_more: false }
        : {
            items: page.items.filter((item) => item.id !== '71'),
            has_more: true,
            next_cursor: 'p2',
          }
    })
  const user = userEvent.setup()
  renderView()
  await user.click(await screen.findByRole('button', { name: /TASK-001/ }))
  expect(
    await screen.findByText(
      '검토 중인 변경 제안이 이 작업의 근거 설계를 고쳐요',
    ),
  ).toBeInTheDocument()
  expect(changes).toHaveBeenCalledWith('101', {
    group: 'IN_PROGRESS',
    cursor: 'p2',
    limit: 100,
  })
})
