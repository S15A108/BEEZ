/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import {
  authApi,
  dashboardApi,
  documentsApi,
  membersApi,
  planningApi,
  presenceApi,
  projectsApi,
} from '@/api/services'
import type { Member } from '@/api/contracts/common'
import { agentConnectionsQuery } from '@/features/agents/useAgentStatusOf'
import { presenceQuery } from '@/features/presence/presenceQuery'
import type {
  GetResponse as Project,
  OnboardingResponse,
} from '@/features/projects/schemas'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { reviewDocument } from '@/test/beeReview'
import { sceneQuery } from './sceneQuery'
import { WorkspaceHud } from './WorkspaceHud'

let restore: () => void
let accountId: string
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  accountId = (
    await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  ).id
  // 첫 방문 환영 창이 카드를 가리지 않게 본 것으로 둔다. 12는 팀원 가이드 테스트의 김동료다.
  localStorage.setItem(`beez.tour.${accountId}`, 'seen')
  localStorage.setItem('beez.tour.12', 'seen')
  useWorkspaceStore.setState({ pane: null, focusId: null })
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

// 예시 데이터를 읽고 상황에 맞게 바꿔 HUD를 그린다. 102는 문서 4종을 초안으로 올린 시작 전 프로젝트, 101은 시작한 프로젝트다.
async function renderGuide(
  projectId: string,
  change: {
    project?: Partial<Project>
    onboarding?: Partial<OnboardingResponse>
    members?: (members: Member[]) => Member[]
    me?: (members: Member[]) => Member | undefined
  } = {},
) {
  const project = { ...(await projectsApi.get(projectId)), ...change.project }
  const all = (await membersApi.list(projectId)).items
  const members = change.members?.(all) ?? all
  const onboarding = {
    ...(await projectsApi.onboarding(projectId)),
    ...change.onboarding,
  }
  const me =
    change.me?.(members) ??
    members.find((member) => member.account_id === accountId)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <WorkspaceHud
          project={project}
          members={members}
          me={me}
          onboarding={onboarding}
          tasks={[]}
          myTasks={[]}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return screen.getByRole('region', { name: /시작 가이드/ })
}

const nextStep = (guide: HTMLElement) =>
  within(guide).queryByRole('heading', { level: 3 })

// S15P31A108-430: 선택인 초대·연결이 필수인 문서 올리기보다 먼저 강조됐다.
it('시작 전 팀장 가이드는 남은 필수 단계 하나만 크게 보이고, 초대·연결은 단계가 아닌 선택 링크다', async () => {
  const user = userEvent.setup()
  const guide = await renderGuide('102', {
    onboarding: {
      member_count: 1,
      my_agent_connected: false,
      required_documents: (
        await projectsApi.onboarding('102')
      ).required_documents.map((item) => ({ ...item, uploaded: false })),
    },
  })
  expect(nextStep(guide)).toHaveTextContent('문서 초안 4종 올리기')
  expect(within(guide).getByText('0 / 3 단계')).toBeInTheDocument()
  expect(
    within(guide).queryByText('팀원 초대 · 에이전트 연결'),
  ).not.toBeInTheDocument()
  expect(
    within(guide).queryByRole('button', { name: '나중에 할게요' }),
  ).not.toBeInTheDocument()

  // 잠긴 단계는 한 줄로 두고 눌러도 열리지 않는다.
  const rest = within(guide).getByRole('list', { name: '남은 단계' })
  const review = within(rest).getByRole('button', {
    name: /비비 검토 반영하기/,
  })
  expect(review).toHaveAttribute('aria-disabled', 'true')
  // PR #315 리뷰: 잠긴 이유가 title 툴팁에만 있어 키보드·터치로는 보이지 않았다.
  expect(within(review).getByText('4종이 다 올라가면 열려요.')).toBeVisible()
  await user.click(review)
  expect(nextStep(guide)).toHaveTextContent('문서 초안 4종 올리기')

  expect(
    within(guide).getByRole('button', { name: '지금 초대하기' }),
  ).toBeInTheDocument()
  await user.click(
    within(guide).getByRole('button', { name: '에이전트 연결하기' }),
  )
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'settings',
    focusId: 'agent',
  })
})

it('끝낸 단계는 한 줄로 접혀 있다가 누르면 펼쳐지고, 남은 단계 줄을 누르면 그 단계를 크게 연다', async () => {
  const user = userEvent.setup()
  const guide = await renderGuide('102')
  expect(nextStep(guide)).toHaveTextContent('비비 검토 반영하기')

  const done = within(guide).getByRole('button', { name: /완료 1단계/ })
  expect(done).toHaveAttribute('aria-expanded', 'false')
  expect(within(guide).getByText('문서 초안 4종 올리기')).not.toBeVisible()
  await user.click(done)
  expect(done).toHaveAttribute('aria-expanded', 'true')
  const list = within(guide).getByRole('list', { name: '완료한 단계' })
  expect(done).toHaveAttribute('aria-controls', list.id)
  expect(within(list).getByText('문서 초안 4종 올리기')).toBeInTheDocument()
  expect(within(list).getByText('4 / 4 올림')).toBeInTheDocument()
  await user.click(done)
  expect(
    within(guide).queryByRole('list', { name: '완료한 단계' }),
  ).not.toBeInTheDocument()

  await user.click(
    within(guide).getByRole('button', { name: /4종 승인하고 프로젝트 시작/ }),
  )
  expect(nextStep(guide)).toHaveTextContent('4종 승인하고 프로젝트 시작')
  expect(
    within(guide).getByRole('button', { name: /비비 검토 반영하기/ }),
  ).toBeInTheDocument()
})

it('필수 단계를 다 끝낸 팀장 가이드는 한 줄 요약과 선택 링크만 남는다', async () => {
  const guide = await renderGuide('101', {
    members: (members) => members.filter((member) => member.role === 'LEADER'),
    onboarding: { member_count: 1, my_agent_connected: false },
  })
  expect(nextStep(guide)).toBeNull()
  expect(
    within(guide).queryByRole('list', { name: '남은 단계' }),
  ).not.toBeInTheDocument()
  expect(
    within(guide).getByRole('button', { name: /필수 3단계를 모두 마쳤어요/ }),
  ).toHaveAttribute('aria-expanded', 'false')
  expect(
    within(guide).getByRole('button', { name: '팀원 초대하기' }),
  ).toBeInTheDocument()
  expect(
    within(guide).getByRole('button', { name: '에이전트 연결하기' }),
  ).toBeInTheDocument()
  expect(
    within(guide).getByRole('button', { name: '가이드 숨기기' }),
  ).toBeInTheDocument()
})

it('팀원 가이드는 팀장을 기다리는 담당 도메인보다 버튼이 있는 에이전트 연결을 먼저 크게 보인다', async () => {
  const guide = await renderGuide('101', {
    project: { my_role: 'MEMBER' },
    onboarding: { my_agent_connected: false },
    members: (members) =>
      members.map((member) =>
        member.account_id === '12' ? { ...member, domain: undefined } : member,
      ),
    me: (members) => members.find((member) => member.account_id === '12'),
  })
  expect(nextStep(guide)).toHaveTextContent('내 에이전트 연결하기')
  expect(
    within(guide).getByRole('button', { name: '설정에서 연결하기' }),
  ).toBeInTheDocument()
  expect(
    within(guide).getByRole('button', { name: /내 담당 도메인 확인/ }),
  ).toBeInTheDocument()
  expect(
    within(guide).queryByRole('button', { name: '지금 초대하기' }),
  ).not.toBeInTheDocument()
})

// PR #315 리뷰: 남은 단계가 모두 잠긴 팀원은 큰 칸이 없고 '필수 3단계를 모두 마쳤어요'와 잠긴 줄이 함께 보였다.
it('남은 단계가 모두 잠긴 팀원에게는 첫 잠긴 단계를 기다림 칸으로 크게 보이고 이유를 적는다', async () => {
  const guide = await renderGuide('101', {
    project: { my_role: 'MEMBER' },
    onboarding: { my_agent_connected: true, baseline_published: false },
    me: (members) => members.find((member) => member.account_id === '12'),
  })
  expect(nextStep(guide)).toHaveTextContent(/읽기/)
  expect(
    within(guide).getByText('팀장이 프로젝트를 시작하면 열려요.'),
  ).toBeVisible()
  expect(
    within(guide).queryByRole('button', { name: '공용 문서함에서 보기' }),
  ).not.toBeInTheDocument()
  expect(
    within(guide).queryByRole('button', { name: '읽었어요' }),
  ).not.toBeInTheDocument()
  const rest = within(guide).getByRole('list', { name: '남은 단계' })
  expect(
    within(rest).getByText('프로젝트를 시작한 뒤에 할 수 있어요.'),
  ).toBeVisible()
  expect(
    within(guide).getByRole('button', { name: /완료 3단계/ }),
  ).toBeInTheDocument()
  expect(within(guide).queryByText(/모두 마쳤어요/)).not.toBeInTheDocument()
})

// PR #315 리뷰: 승인 요청이 와도 비비 검토 단계가 크게 남고 '승인 요청 보기'는 접힌 줄에 묻혔다.
it('팀장에게 승인 요청이 와 있으면 승인 단계를 먼저 크게 보인다', async () => {
  const documentId = '221'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await reviewDocument('102', documentId, copy.revision)
  let revision = copy.revision
  for (const suggestion of review.suggestions)
    revision = (
      await documentsApi.decideSuggestion(
        '102',
        documentId,
        review.id,
        suggestion.id,
        { expected_revision: revision, decision: 'SKIP' },
      )
    ).revision
  await documentsApi.submitReview('102', documentId, {
    expected_revision: revision,
    review_id: review.id,
  })
  const guide = await renderGuide('102')
  await waitFor(() =>
    expect(nextStep(guide)).toHaveTextContent('4종 승인하고 프로젝트 시작'),
  )
  expect(
    within(guide).getByRole('button', { name: '승인 요청 보기' }),
  ).toBeInTheDocument()
  expect(within(guide).getByText(/승인 요청 1건/)).toBeVisible()
})

// S15P31A108-424: 기준 v1 발행(PLN-A10)은 기획 분석의 '검토한 설계 승인·발행' 한 곳에서 한다.
// 승인 전 3단계는 지금 버튼('문서함 보기')과 다음 순서를 알리고, '켜진다'처럼 없는 버튼을 가리키지 않는다.
it('4종 승인 전 3단계는 문서함 보기와 함께 승인 뒤 설계 초안 만들기로 이어진다고 알린다', async () => {
  const user = userEvent.setup()
  const guide = await renderGuide('102')
  await user.click(
    within(guide).getByRole('button', { name: /4종 승인하고 프로젝트 시작/ }),
  )
  expect(
    within(guide).getByText(
      /팀장이 4종을 승인하면 '설계 초안 만들기'로 기획 분석을 열어요/,
    ),
  ).toBeVisible()
  expect(
    within(guide).getByRole('button', { name: '문서함 보기' }),
  ).toBeInTheDocument()
  expect(within(guide).queryByText(/켜져요/)).not.toBeInTheDocument()
})

it('4종이 승인되면 3단계의 설계 초안 만들기는 발행하지 않고 기획 분석을 연다', async () => {
  const user = userEvent.setup()
  const counts = await dashboardApi.documents('102')
  vi.spyOn(dashboardApi, 'documents').mockResolvedValue({
    ...counts,
    by_kind: counts.by_kind.map((item) => ({ ...item, approved_count: 1 })),
  })
  const publish = vi.spyOn(planningApi, 'publishBaseline')
  const guide = await renderGuide('102')
  await waitFor(() =>
    expect(nextStep(guide)).toHaveTextContent('4종 승인하고 프로젝트 시작'),
  )
  expect(within(guide).getByText(/문서 4종 승인 4 \/ 4/)).toBeVisible()
  expect(
    within(guide).queryByRole('button', { name: '프로젝트 시작' }),
  ).not.toBeInTheDocument()
  await user.click(
    within(guide).getByRole('button', { name: '설계 초안 만들기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('planning')
  expect(publish).not.toHaveBeenCalled()
})

it('팀 목록은 요약 한 줄로 접혀 있다가 누르면 펼쳐진다', async () => {
  const user = userEvent.setup()
  await renderGuide('102')
  const summary = screen.getByRole('button', { name: /^팀원 \d+명/ })
  const list = screen.getByRole('list', { name: '팀 목록' })
  expect(summary).toHaveAttribute('aria-expanded', 'false')
  expect(within(list).queryByText('이팀장 · 나')).not.toBeInTheDocument()
  await user.click(summary)
  expect(summary).toHaveAttribute('aria-expanded', 'true')
  expect(within(list).getByText('이팀장 · 나')).toBeInTheDocument()
  expect(within(list).getByText('김동료')).toBeInTheDocument()
  await user.click(summary)
  expect(within(list).queryByText('이팀장 · 나')).not.toBeInTheDocument()
})

// 예시 프로젝트 101: 이팀장(나)·김동료·최팀원·박팀원. 에이전트는 내 것·최팀원 연결 끊김, 김동료 연결됨, 박팀원 연결 미확인.
// 연결 현황(AGT-A05)과 접속 상태(MON-A07)를 먼저 받아 두고 그린다. solo면 나만 있는 팀으로 그린다.
async function renderHud({ solo = false } = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  // 나만 사무실(개발팀)에 접속해 있다. 다른 팀원은 보고한 적이 없다.
  await presenceApi.report('101', { zone: 'dev' })
  const [data] = await Promise.all([
    client.fetchQuery(sceneQuery('101', accountId)),
    client.prefetchQuery(agentConnectionsQuery('101')),
    client.prefetchQuery(presenceQuery('101')),
  ])
  const me = data.members.items.find(
    (member) => member.account_id === accountId,
  )
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <WorkspaceHud
          project={data.project}
          members={solo && me ? [me] : data.members.items}
          me={me}
          onboarding={data.onboarding}
          tasks={data.tasks.items}
          myTasks={[]}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return screen.getByRole('region', { name: '프로젝트' })
}

// 팀 목록은 접혀 있어(S15P31A108-430) 요약 줄을 눌러 펼친 뒤 읽는다.
const openTeam = () =>
  userEvent.click(screen.getByRole('button', { name: /^팀원 \d+명/ }))

// 팀 목록 한 줄의 둘째 줄(상태 · 위치 · 하는 일).
const lineOf = (name: string) =>
  within(screen.getByRole('list', { name: '팀 목록' })).getByText(name, {
    exact: true,
    selector: 'strong',
  }).nextElementSibling?.textContent

// 디자인 검수 F05(S15P31A108-432): '팀 7'처럼 사람과 에이전트 줄을 합쳐 세어 팀 관리의 사람 수와 달랐다.
it('팀 카드는 팀 관리와 같은 팀원 수와 연결된 에이전트 수를 나눠 보인다', async () => {
  const card = await renderHud()
  // 연결 미확인(박팀원)도 연결된 에이전트로 센다. 연결 끊김(나·최팀원)은 세지 않는다.
  expect(card).toHaveTextContent('팀원 4명 · 에이전트 2개')
  expect(card).not.toHaveTextContent(/팀 \d/)
})

it('혼자인 팀장은 팀원 1명으로 보이고, 끊긴 내 에이전트는 줄에만 있고 세지 않는다', async () => {
  const card = await renderHud({ solo: true })
  expect(card).toHaveTextContent('팀원 1명 · 에이전트 0개')
  expect(card).not.toHaveTextContent('아직 혼자예요')
  expect(
    within(card).getByRole('button', { name: '+ 팀원 초대' }),
  ).toBeInTheDocument()
  await openTeam()
  expect(lineOf('이팀장의 에이전트')).toMatch(/^연결 끊김 · 마지막 통신 /)
})

it('사람 줄은 접속 상태를, 에이전트 줄은 연결 상태를 이름으로 보이고 연결 미확인을 오프라인·연결 끊김으로 보이지 않는다', async () => {
  await renderHud()
  await openTeam()
  expect(lineOf('이팀장 · 나')).toBe('접속 중 · 개발팀 · TASK-001 시작 전')
  // 접속한 적 없는 팀원도 회색 점만이 아니라 글로 오프라인이다.
  expect(lineOf('김동료')).toBe('오프라인 · TASK-002 대기 중')
  expect(lineOf('김동료의 에이전트')).toBe('연결됨')
  const recheck = lineOf('박팀원의 에이전트')
  expect(recheck).toMatch(/^연결 미확인 · 마지막 보고 /)
  expect(recheck).not.toMatch(/오프라인|연결 끊김/)
})
