/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { authApi, membersApi, projectsApi } from '@/api/services'
import type { Member } from '@/api/contracts/common'
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
import { WorkspaceHud } from './WorkspaceHud'

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  localStorage.setItem('beez.tour.11', 'seen')
  localStorage.setItem('beez.tour.12', 'seen')
  useWorkspaceStore.setState({ pane: null, focusId: null })
})
afterEach(() => restore())

// 예시 데이터를 읽고 상황에 맞게 바꿔 HUD를 그린다. 102는 문서 4종을 초안으로 올린 시작 전 프로젝트, 101은 시작한 프로젝트다.
async function renderHud(
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
    change.me?.(members) ?? members.find((member) => member.account_id === '11')
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
  const guide = await renderHud('102', {
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
  const guide = await renderHud('102')
  expect(nextStep(guide)).toHaveTextContent('비비 검토 반영하기')

  const done = within(guide).getByRole('button', { name: /완료 1단계/ })
  expect(done).toHaveAttribute('aria-expanded', 'false')
  expect(within(guide).queryByText('문서 초안 4종 올리기')).toBeNull()
  await user.click(done)
  expect(done).toHaveAttribute('aria-expanded', 'true')
  const list = within(guide).getByRole('list', { name: '완료한 단계' })
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
  const guide = await renderHud('101', {
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
  const guide = await renderHud('101', {
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

it('팀 목록은 요약 한 줄로 접혀 있다가 누르면 펼쳐진다', async () => {
  const user = userEvent.setup()
  await renderHud('102')
  const summary = screen.getByRole('button', { name: /^팀 \d/ })
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
