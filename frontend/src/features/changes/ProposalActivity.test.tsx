import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import type { Activity } from '@/api/contracts/common'
import { dashboardApi } from '@/api/services/dashboard'
import { ProposalActivity } from './ProposalActivity'

vi.mock('@/api/services/dashboard', () => ({
  dashboardApi: { activities: vi.fn() },
}))

const activities = vi.mocked(dashboardApi.activities)
const row = (id: string, extra: Partial<Activity> = {}): Activity => ({
  id,
  type: 'CHANGE_PROPOSAL_SUBMITTED',
  actor_type: 'HUMAN',
  actor_name: '김동료',
  change_proposal_id: '71',
  result: 'SUCCESS',
  created_at: '2026-10-23T03:00:00Z',
  ...extra,
})

beforeEach(() => {
  vi.clearAllMocks()
})

function mount(proposalId = '71') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onShowComments = vi.fn()
  const view = (id: string) => (
    <QueryClientProvider client={client}>
      <ProposalActivity
        key={id}
        projectId="10"
        proposalId={id}
        onShowComments={onShowComments}
      />
    </QueryClientProvider>
  )
  const result = render(view(proposalId))
  return {
    ...result,
    onShowComments,
    switchTo: (id: string) => result.rerender(view(id)),
  }
}

it('선택한 제안의 id 로 조회하고 행위자 4종을 이름과 에이전트만 알약으로 구분해 보인다', async () => {
  activities.mockResolvedValue({
    items: [
      row('5', {
        type: 'CHANGE_PROPOSAL_PUBLISHED',
        actor_type: 'SYSTEM',
        actor_name: undefined,
      }),
      row('4', {
        type: 'CHANGE_PROPOSAL_REVIEW_REQUESTED',
        actor_type: 'BEE',
        actor_name: undefined,
      }),
      row('3', {
        type: 'CHANGE_PROPOSAL_COMMENTED',
        actor_type: 'AGENT',
        actor_name: '민수 에이전트',
      }),
      row('2', {
        type: 'CHANGE_PROPOSAL_REVIEW_STARTED',
        actor_name: '이팀장',
      }),
      row('1'),
    ],
    has_more: false,
  })
  mount()
  const list = await screen.findByRole('list')
  const items = within(list).getAllByRole('listitem')
  expect(items).toHaveLength(5)
  // 서버 순서 그대로(재정렬 없음)
  expect(items[0]).toHaveTextContent('시스템 새 기준으로 발행했어요')
  expect(items[1]).toHaveTextContent('비비 검토를 요청했어요')
  expect(items[2]).toHaveTextContent('민수 에이전트 에이전트의견을 남겼어요')
  expect(items[3]).toHaveTextContent('이팀장 검토를 시작했어요')
  expect(items[4]).toHaveTextContent('김동료 제안을 올렸어요')
  expect(activities).toHaveBeenCalledWith('10', {
    change_proposal_id: '71',
    cursor: undefined,
    limit: 10,
  })
})

it('회의를 끝내 제안이 만들어진 활동은 라벨로 보인다', async () => {
  activities.mockResolvedValue({
    items: [row('1', { type: 'MEETING_CLOSED' })],
    has_more: false,
  })
  mount()
  expect(await screen.findByRole('listitem')).toHaveTextContent(
    '회의를 끝내고 제안을 만들었어요',
  )
})

it('검토 시작은 대시보드 타임라인과 같은 "진행 중" 칩으로 보인다', async () => {
  activities.mockResolvedValue({
    items: [row('1', { type: 'CHANGE_PROPOSAL_REVIEW_STARTED' })],
    has_more: false,
  })
  mount()
  expect(await screen.findByRole('listitem')).toHaveTextContent('진행 중')
})

it('모르는 활동 유형은 원문으로, 실패 결과는 따로 표시한다', async () => {
  activities.mockResolvedValue({
    items: [row('1', { type: 'CHANGE_PROPOSAL_FUTURE', result: 'FAILURE' })],
    has_more: false,
  })
  mount()
  const item = await screen.findByRole('listitem')
  expect(item).toHaveTextContent('CHANGE_PROPOSAL_FUTURE')
  expect(item).toHaveTextContent('실패')
})

it('더 보기로 서버가 준 cursor 를 그대로 이어 읽는다', async () => {
  const user = userEvent.setup()
  activities
    .mockResolvedValueOnce({
      items: [row('3'), row('2')],
      has_more: true,
      next_cursor: 'server-cursor-2',
    })
    .mockResolvedValueOnce({ items: [row('1')], has_more: false })
  mount()
  await screen.findAllByRole('listitem')
  await user.click(screen.getByRole('button', { name: '더 보기' }))
  await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3))
  expect(activities).toHaveBeenLastCalledWith(
    '10',
    expect.objectContaining({ cursor: 'server-cursor-2' }),
  )
  expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
})

it('제안을 바꾸면 이전 목록과 필터가 남지 않는다', async () => {
  const user = userEvent.setup()
  activities.mockImplementation(async (_project, query) => ({
    items:
      query?.change_proposal_id === '71'
        ? [row('2', { actor_name: '71번 행위자' })]
        : [row('9', { change_proposal_id: '72', actor_name: '72번 행위자' })],
    has_more: false,
  }))
  const { switchTo } = mount('71')
  expect(await screen.findByText('71번 행위자')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '비비' }))
  await waitFor(() =>
    expect(activities).toHaveBeenLastCalledWith(
      '10',
      expect.objectContaining({ actor_type: 'BEE' }),
    ),
  )
  switchTo('72')
  expect(await screen.findByText('72번 행위자')).toBeInTheDocument()
  expect(screen.queryByText('71번 행위자')).toBeNull()
  expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(activities).toHaveBeenLastCalledWith(
    '10',
    expect.not.objectContaining({ actor_type: expect.anything() }),
  )
})

it('성공한 빈 목록과 조회 실패를 구분하고, 실패를 0건으로 보이지 않는다', async () => {
  const user = userEvent.setup()
  activities.mockResolvedValueOnce({ items: [], has_more: false })
  const first = mount()
  expect(await screen.findByText('기록된 활동이 없습니다.')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).toBeNull()
  first.unmount()

  activities
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValueOnce({ items: [row('1')], has_more: false })
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '정보를 불러오지 못했어요',
  )
  expect(screen.queryByText('기록된 활동이 없습니다.')).toBeNull()
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByRole('listitem')).toBeInTheDocument()
})

it('다음 페이지 조회가 실패해도 받은 목록을 지키고 그 아래에서 다시 시도한다', async () => {
  const user = userEvent.setup()
  activities
    .mockResolvedValueOnce({
      items: [row('2')],
      has_more: true,
      next_cursor: 'c2',
    })
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValueOnce({ items: [row('1')], has_more: false })
  mount()
  await screen.findByRole('listitem')
  await user.click(screen.getByRole('button', { name: '더 보기' }))
  expect(
    await screen.findByText('다음 활동을 불러오지 못했어요.'),
  ).toBeVisible()
  expect(screen.getAllByRole('listitem')).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
  expect(screen.queryByRole('alert')).toBeNull()
})

it('필터 결과가 비면 조건 안내를 보이고, 의견 탭 안내 버튼을 누르면 알린다', async () => {
  const user = userEvent.setup()
  activities.mockImplementation(async (_project, query) => ({
    items: query?.actor_type ? [] : [row('1')],
    has_more: false,
  }))
  const { onShowComments } = mount()
  await screen.findByRole('listitem')
  await user.click(screen.getByRole('button', { name: '에이전트' }))
  expect(
    await screen.findByText('이 조건에 맞는 활동이 없어요.'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '의견 탭에서 보기' }))
  expect(onShowComments).toHaveBeenCalledOnce()
})
