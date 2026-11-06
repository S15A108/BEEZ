import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Activity } from '@/api/contracts/common'
import { dashboardApi } from '@/api/services/dashboard'
import { membersApi } from '@/api/services/members'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ActivityPanel } from './ActivityPanel'
import type { ActivitiesResponse, UsageResponse } from './schemas'

vi.mock('@/api/services/dashboard', () => ({
  dashboardApi: {
    usage: vi.fn(),
    relations: vi.fn(),
    activities: vi.fn(),
    agentWork: vi.fn(),
  },
}))
vi.mock('@/api/services/members', () => ({ membersApi: { list: vi.fn() } }))
beforeEach(() => {
  vi.clearAllMocks()
  useWorkspaceStore.setState({ pane: 'dashboard', focusId: null })
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 120,
    output_tokens: 30,
    call_count: 2,
    by_feature: [],
    by_task: [],
    by_validation: [],
    by_agent: [],
    by_day: [],
  })
  vi.mocked(dashboardApi.relations).mockResolvedValue({ nodes: [], edges: [] })
  vi.mocked(dashboardApi.activities).mockResolvedValue({
    items: [],
    has_more: false,
  })
  vi.mocked(dashboardApi.agentWork).mockResolvedValue({
    items: [
      {
        member_id: '1',
        current_task_id: '8',
        task_status: 'WAITING',
        validation_status: 'PASS',
        waiting_reason: 'DEPENDENCY',
      },
    ],
    has_more: false,
  })
  vi.mocked(membersApi.list).mockResolvedValue({
    items: [
      {
        id: '1',
        account_id: '11',
        name: '민수',
        role: 'MEMBER',
        agent_connected: true,
      },
    ],
    has_more: false,
  })
})
function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ActivityPanel projectId="10" />
    </QueryClientProvider>,
  )
}
it('연결·대기 작업·통과한 계획을 분리하며 누락 비용을 0원으로 만들지 않는다', async () => {
  const user = userEvent.setup()
  mount()
  // 수정안: 대시보드는 한눈에 보기 / 활동 타임라인 / 에이전트 현황 / 사용량·비용 탭으로 나눈다.
  await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
  const name = await screen.findByText('민수')
  const row = name.closest('tr')!
  expect(within(row).getByText('연결됨')).toBeInTheDocument()
  expect(within(row).getByText('대기', { exact: true })).toBeInTheDocument()
  expect(within(row).getByText('계획 통과')).toBeInTheDocument()
  expect(within(row).getByText('선행 작업 대기')).toBeInTheDocument()
  await user.click(within(row).getByRole('button', { name: '작업 #8' }))
  expect(useWorkspaceStore.getState().focusId).toBe('8')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  expect(
    await screen.findByText('미보고', { selector: 'dd' }),
  ).toBeInTheDocument()
})
it('한국시간 날짜 범위를 UTC로 전달하고 역전된 기간은 조회하지 않는다', async () => {
  const user = userEvent.setup()
  mount()
  // 날짜 직접 입력은 사용량 탭에만 있다(11/5 결정).
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  const from = screen.getByLabelText('시작일 (한국시간)'),
    to = screen.getByLabelText('종료일 (한국시간)')
  await user.clear(from)
  await user.type(from, '2026-10-22')
  await user.clear(to)
  await user.type(to, '2026-10-23')
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  await waitFor(() =>
    expect(dashboardApi.usage).toHaveBeenCalledWith('10', {
      from: '2026-10-21T15:00:00.000Z',
      to: '2026-10-23T15:00:00.000Z',
    }),
  )
  const count = vi.mocked(dashboardApi.usage).mock.calls.length
  await user.clear(to)
  await user.type(to, '2026-10-21')
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후',
  )
  expect(dashboardApi.usage).toHaveBeenCalledTimes(count)
})
it('비용·토큰을 모르는 호출이 섞이면 0원·전체 값처럼 보이지 않게 건수를 붙인다', async () => {
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 120,
    output_tokens: 30,
    call_count: 3,
    estimated_cost_amount: '0.01',
    currency: 'USD',
    tokens_unknown_call_count: 1,
    cost_unknown_call_count: 1,
    by_feature: [
      {
        key: 'DOCUMENT_PARSE',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 1,
        estimated_cost_amount: '0',
        tokens_unknown_call_count: 1,
        cost_unknown_call_count: 1,
      },
      {
        key: 'TASK_VALIDATION',
        input_tokens: 120,
        output_tokens: 30,
        call_count: 2,
        estimated_cost_amount: '0.01',
        tokens_unknown_call_count: 0,
        cost_unknown_call_count: 0,
      },
    ],
    by_task: [],
    by_validation: [],
    by_agent: [],
    by_day: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  // 건수는 값 아래 줄에 붙는다(좁은 칸에서 꺾이지 않게). 기대값 변경은 사용자 승인 2026-11-03.
  const cost = await screen.findByText('0.01 USD', { selector: 'dd' })
  expect(within(cost).getByText('미보고 1건')).toBeInTheDocument()
  const input = screen.getByText('120', { selector: 'dd' })
  expect(within(input).getByText('미보고 1건')).toBeInTheDocument()
  // 첫 보기는 에이전트별이라 기능별로 옮겨 본다(-312).
  await user.click(screen.getByRole('tab', { name: '기능별' }))
  // 비용을 모르는 문서 분석 줄이 '0 USD'만으로 보이지 않는다
  const parse = screen.getByText('문서 분석').closest('li')!
  expect(
    within(within(parse).getByText('0 USD')).getByText('미보고 1건'),
  ).toBeInTheDocument()
  const validation = screen.getByText('작업 검증').closest('li')!
  expect(within(validation).getByText('0.01 USD')).toBeInTheDocument()
  expect(within(validation).queryByText(/미보고/)).not.toBeInTheDocument()
})
it('호출이 없으면 비용을 미보고로 보이지 않고, 모든 기능이 0회면 빈 안내를 보인다', async () => {
  const empty = (key: string) => ({
    key,
    input_tokens: 0,
    output_tokens: 0,
    call_count: 0,
    tokens_unknown_call_count: 0,
    cost_unknown_call_count: 0,
  })
  // 서버처럼: 비용 있는 호출이 없으면 비용·통화를 생략하고 기능 8개를 0으로 채운다.
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 0,
    output_tokens: 0,
    call_count: 0,
    by_feature: [empty('DOCUMENT_PARSE'), empty('TASK_VALIDATION')],
    by_task: [],
    by_validation: [],
    by_agent: [],
    by_day: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  expect(await screen.findByText('—', { selector: 'dd' })).toBeInTheDocument()
  expect(
    screen.queryByText('미보고', { selector: 'dd' }),
  ).not.toBeInTheDocument()
  expect(screen.getByText('기록된 사용량이 없어요.')).toBeInTheDocument()
})
it('호출이 없는 기능 줄은 0 토큰·0원 대신 비어 있다고 보인다', async () => {
  vi.mocked(dashboardApi.usage).mockResolvedValue({
    from: '2026-10-20T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 10,
    output_tokens: 0,
    call_count: 1,
    estimated_cost_amount: '0.01',
    currency: 'USD',
    by_feature: [
      {
        key: 'TASK_VALIDATION',
        input_tokens: 10,
        output_tokens: 0,
        call_count: 1,
        estimated_cost_amount: '0.01',
      },
      {
        key: 'BEE_CHAT',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 0,
        estimated_cost_amount: '0',
      },
    ],
    by_task: [],
    by_validation: [],
    by_agent: [],
    by_day: [],
  })
  const user = userEvent.setup()
  mount()
  await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
  await user.click(await screen.findByRole('tab', { name: '기능별' }))
  const chat = (await screen.findByText('비비 대화')).closest('li')!
  expect(within(chat).getAllByText('—')).toHaveLength(2)
  expect(within(chat).queryByText('0 USD')).not.toBeInTheDocument()
})

const usageWith = (fields: Partial<UsageResponse>): UsageResponse => ({
  from: '2026-10-20T00:00:00Z',
  to: '2026-10-23T00:00:00Z',
  input_tokens: 0,
  output_tokens: 0,
  call_count: 0,
  by_feature: [],
  by_task: [],
  by_validation: [],
  by_agent: [],
  by_day: [],
  ...fields,
})
const bucket = (key: string, call_count: number) => ({
  key,
  input_tokens: 100,
  output_tokens: 20,
  call_count,
  estimated_cost_amount: '0.5',
})
const activity = (id: string, fields: Partial<Activity> = {}): Activity => ({
  id,
  type: 'MOCK_UPDATE',
  actor_type: 'HUMAN',
  actor_account_id: '11',
  result: 'SUCCESS',
  created_at: '2026-10-27T01:00:00Z',
  ...fields,
})
const session = { member_id: '1', agent_session_id: '91' }

describe('기간', () => {
  afterEach(() => {
    vi.useRealTimers()
  })
  it('기본은 이번 달이고, 고르면 한국시간 달 경계로 다시 조회한다', async () => {
    // 한국시간 11월 1일 00:30. UTC로는 아직 10월이다.
    vi.useFakeTimers({
      now: new Date('2026-10-31T15:30:00Z'),
      toFake: ['Date'],
    })
    const user = userEvent.setup()
    mount()
    const select = screen.getByRole('combobox', { name: '기간' })
    expect(select).toHaveValue('this_month')
    expect(
      screen.getByRole('option', { name: '기간 · 이번 달' }),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(dashboardApi.usage).toHaveBeenCalledWith('10', {
        from: '2026-10-31T15:00:00.000Z',
        to: '2026-11-01T15:00:00.000Z',
      }),
    )
    await user.selectOptions(select, 'last_month')
    await waitFor(() =>
      expect(dashboardApi.usage).toHaveBeenCalledWith('10', {
        from: '2026-09-30T15:00:00.000Z',
        to: '2026-10-31T15:00:00.000Z',
      }),
    )
  })

  it('날짜 입력은 사용량 탭에만 있고, 직접 넣은 기간은 한눈에 보기에 직접 입력으로 보인다', async () => {
    const user = userEvent.setup()
    mount()
    expect(screen.queryByLabelText('시작일 (한국시간)')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    const from = screen.getByLabelText('시작일 (한국시간)')
    await user.clear(from)
    await user.type(from, '2026-01-01')
    await user.click(screen.getByRole('button', { name: '기간 적용' }))
    await user.click(screen.getByRole('tab', { name: '한눈에 보기' }))
    expect(screen.getByRole('combobox', { name: '기간' })).toHaveValue('custom')
    expect(
      screen.getByRole('option', { name: '기간 · 직접 입력' }),
    ).toBeInTheDocument()
  })

  it('366일을 넘는 기간은 서버에 보내지 않고 안내한다', async () => {
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
    const count = vi.mocked(dashboardApi.usage).mock.calls.length
    const from = screen.getByLabelText('시작일 (한국시간)')
    const to = screen.getByLabelText('종료일 (한국시간)')
    await user.clear(from)
    await user.type(from, '2025-01-01')
    await user.clear(to)
    await user.type(to, '2026-01-02')
    await user.click(screen.getByRole('button', { name: '기간 적용' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('366일 이하')
    expect(dashboardApi.usage).toHaveBeenCalledTimes(count)
  })
})

describe('활동 타임라인', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [session],
      has_more: false,
    })
  })
  const pages = (next: () => Promise<ActivitiesResponse>) =>
    vi.mocked(dashboardApi.activities).mockImplementation((_id, query) => {
      if (query?.limit === 4)
        return Promise.resolve({ items: [], has_more: false })
      return query?.cursor
        ? next()
        : Promise.resolve({
            items: [activity('2')],
            has_more: true,
            next_cursor: 'c1',
          })
    })

  it('이전 활동 더 보기는 받은 활동 아래에 이어 붙이고, 끝나면 버튼이 없어진다', async () => {
    pages(() =>
      Promise.resolve({
        items: [
          activity('1', {
            actor_type: 'AGENT',
            agent_session_id: '91',
            created_at: '2026-10-26T01:00:00Z',
          }),
        ],
        has_more: false,
      }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    await screen.findByRole('button', { name: '이전 활동 더 보기' })
    await user.click(screen.getByRole('button', { name: '이전 활동 더 보기' }))
    await screen.findByRole('region', { name: /10월 26일/ })
    expect(screen.getByRole('region', { name: /10월 27일/ })).toHaveTextContent(
      '민수',
    )
    expect(
      within(screen.getByRole('region', { name: /10월 26일/ })).getByText(
        '민수의 에이전트',
      ),
    ).toBeInTheDocument()
    expect(dashboardApi.activities).toHaveBeenLastCalledWith('10', {
      cursor: 'c1',
      limit: 20,
    })
    expect(
      screen.queryByRole('button', { name: '이전 활동 더 보기' }),
    ).not.toBeInTheDocument()
  })

  it('이전 활동을 못 받아도 받아 둔 목록은 그대로 두고 알린다', async () => {
    pages(() => Promise.reject(new Error('500')))
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    await user.click(
      await screen.findByRole('button', { name: '이전 활동 더 보기' }),
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이전 활동을 불러오지 못했어요',
    )
    expect(
      screen.getByRole('region', { name: /10월 27일/ }),
    ).toBeInTheDocument()
  })

  it('보기를 고르면 그 조건으로 다시 받고, 없으면 이 보기에 없다고 보인다', async () => {
    vi.mocked(dashboardApi.activities).mockResolvedValue({
      items: [],
      has_more: false,
    })
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    const filters = screen.getByRole('group', { name: '보기' })
    expect(
      within(filters).getByRole('button', { name: '전체' }),
    ).toHaveAttribute('aria-pressed', 'true')
    expect(
      await screen.findByText('아직 기록된 프로젝트 활동이 없어요.'),
    ).toBeInTheDocument()
    await user.click(within(filters).getByRole('button', { name: '사람' }))
    await waitFor(() =>
      expect(dashboardApi.activities).toHaveBeenCalledWith('10', {
        actor_type: 'HUMAN',
        limit: 20,
      }),
    )
    await user.click(
      await within(filters).findByRole('button', { name: '민수의 에이전트' }),
    )
    await waitFor(() =>
      expect(dashboardApi.activities).toHaveBeenCalledWith('10', {
        agent_session_id: '91',
        limit: 20,
      }),
    )
    expect(
      await screen.findByText('이 보기에 맞는 활동이 없어요.'),
    ).toBeInTheDocument()
  })

  it('연도가 다른 같은 날짜는 합치지 않고 연도를 붙여 나눈다', async () => {
    vi.useFakeTimers({
      now: new Date('2026-11-06T03:00:00Z'),
      toFake: ['Date'],
    })
    vi.mocked(dashboardApi.activities).mockResolvedValue({
      items: [
        activity('2', { created_at: '2026-10-27T01:00:00Z' }),
        activity('1', { created_at: '2025-10-27T01:00:00Z' }),
      ],
      has_more: false,
    })
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    expect(
      await screen.findByRole('region', { name: '10월 27일' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: '2025년 10월 27일' }),
    ).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('에이전트 현황에서 쪽을 넘겨도 보기 칩은 첫 쪽 에이전트를 쓴다', async () => {
    vi.mocked(dashboardApi.activities).mockResolvedValue({
      items: [],
      has_more: false,
    })
    vi.mocked(dashboardApi.agentWork).mockImplementation((_id, query) =>
      Promise.resolve(
        query?.cursor
          ? {
              items: [{ member_id: '2', agent_session_id: '92' }],
              has_more: false,
            }
          : { items: [session], has_more: true, next_cursor: 'w2' },
      ),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
    await user.click(
      await screen.findByRole('button', { name: '다음 작업 현황' }),
    )
    expect(await screen.findByText('팀원 #2')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    const filters = screen.getByRole('group', { name: '보기' })
    expect(
      await within(filters).findByRole('button', { name: '민수의 에이전트' }),
    ).toBeInTheDocument()
    expect(
      within(filters).queryByRole('button', { name: '팀원 #2의 에이전트' }),
    ).not.toBeInTheDocument()
  })

  it('비비·시스템·못 찾은 에이전트를 이름으로 구분하고 실패 결과를 한국어로 보인다', async () => {
    vi.mocked(dashboardApi.activities).mockResolvedValue({
      items: [
        activity('4', { actor_type: 'BEE', actor_account_id: undefined }),
        activity('3', { actor_type: 'SYSTEM', actor_account_id: undefined }),
        activity('2', {
          actor_type: 'AGENT',
          actor_account_id: '77',
          actor_name: '나간 토큰',
        }),
        activity('1', { result: 'FAILURE' }),
      ],
      has_more: false,
    })
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '활동 타임라인' }))
    expect(await screen.findByText('비비')).toBeInTheDocument()
    expect(screen.getByText('시스템')).toBeInTheDocument()
    expect(screen.getByText('나간 토큰')).toBeInTheDocument()
    expect(screen.getByText('실패')).toBeInTheDocument()
  })
})

describe('에이전트별 사용량', () => {
  it('사용량 탭의 첫 보기는 에이전트별이고 WEB 줄을 웹에서 직접으로 보인다', async () => {
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [session],
      has_more: false,
    })
    vi.mocked(dashboardApi.usage).mockResolvedValue(
      usageWith({
        call_count: 3,
        currency: 'USD',
        estimated_cost_amount: '1',
        by_agent: [bucket('91', 2), bucket('WEB', 1)],
      }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
    expect(
      await screen.findByRole('tab', { name: '에이전트별' }),
    ).toHaveAttribute('aria-selected', 'true')
    const agent = (await screen.findByText('민수의 에이전트')).closest('li')!
    expect(within(agent).getByText('2회')).toBeInTheDocument()
    const web = screen.getByText('웹에서 직접').closest('li')!
    expect(within(web).getByText('1회')).toBeInTheDocument()
  })

  it('호출이 있는데 에이전트별 줄이 비면 호출이 없다고 말하지 않고 기능별 보기를 안내한다', async () => {
    vi.mocked(dashboardApi.usage).mockResolvedValue(
      usageWith({ call_count: 5 }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '사용량·비용' }))
    expect(
      await screen.findByText(/에이전트별로 나눈 기록이 없어요/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('기록된 사용량이 없어요.'),
    ).not.toBeInTheDocument()
  })
})

describe('에이전트 현황', () => {
  afterEach(() => {
    vi.useRealTimers()
  })
  it('작업 제목, 마지막 보고 시각, 이번 달 사용량, 웹 직접 호출 줄을 보인다', async () => {
    vi.useFakeTimers({
      now: new Date('2026-11-06T03:00:00Z'),
      toFake: ['Date'],
    })
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [
        {
          ...session,
          current_task_id: '8',
          task_title: '프로젝트 목록 화면',
          reported_at: '2026-10-28T05:10:00Z',
        },
      ],
      has_more: false,
    })
    vi.mocked(dashboardApi.usage).mockResolvedValue(
      usageWith({
        call_count: 3,
        currency: 'USD',
        estimated_cost_amount: '1',
        by_agent: [bucket('91', 2), bucket('WEB', 1)],
      }),
    )
    const user = userEvent.setup()
    mount()
    // 위에서 다른 기간을 골라도 에이전트 현황은 이번 달을 본다.
    await user.selectOptions(
      screen.getByRole('combobox', { name: '기간' }),
      'last_month',
    )
    await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
    const row = (await screen.findByText('민수')).closest('tr')!
    expect(
      within(row).getByRole('button', { name: '프로젝트 목록 화면' }),
    ).toBeInTheDocument()
    expect(within(row).getByText('연결됨 · 10월 28일 보고')).toBeInTheDocument()
    expect(await within(row).findByText('2회 · 0.5 USD')).toBeInTheDocument()
    expect(dashboardApi.usage).toHaveBeenCalledWith('10', {
      from: '2026-10-31T15:00:00.000Z',
      to: '2026-11-06T15:00:00.000Z',
    })
    expect(screen.getByText('웹에서 직접 한 호출')).toBeInTheDocument()
    expect(screen.getByText('1회 · 0.5 USD')).toBeInTheDocument()
  })

  it('호출이 있는데 서버가 호출자별로 나누지 않으면 0회로 단정하지 않고 사용량 탭과 같은 안내를 보인다', async () => {
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [session],
      has_more: false,
    })
    vi.mocked(dashboardApi.usage).mockResolvedValue(
      usageWith({ call_count: 5 }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
    await screen.findByText('민수')
    expect(await screen.findAllByText('나눈 기록 없음')).toHaveLength(2)
    expect(
      screen.getByText(/에이전트별로 나눈 기록이 없어요/),
    ).toBeInTheDocument()
    expect(screen.queryByText('0회')).not.toBeInTheDocument()
  })

  it('이번 달 호출이 없으면 0회로 보이고 안내는 없다', async () => {
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [session],
      has_more: false,
    })
    vi.mocked(dashboardApi.usage).mockResolvedValue(
      usageWith({ call_count: 0 }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
    await screen.findByText('민수')
    expect(await screen.findAllByText('0회')).toHaveLength(2)
    expect(
      screen.queryByText(/에이전트별로 나눈 기록이 없어요/),
    ).not.toBeInTheDocument()
  })

  it('이번 달 사용량을 못 받으면 0회나 기록 없음 대신 —로 보인다', async () => {
    vi.mocked(dashboardApi.agentWork).mockResolvedValue({
      items: [session],
      has_more: false,
    })
    vi.mocked(dashboardApi.usage).mockRejectedValue(new Error('500'))
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('tab', { name: '에이전트 현황' }))
    await screen.findByText('민수')
    await waitFor(() =>
      expect(screen.getAllByText('—').length).toBeGreaterThan(1),
    )
    expect(screen.queryByText('나눈 기록 없음')).not.toBeInTheDocument()
  })
})
