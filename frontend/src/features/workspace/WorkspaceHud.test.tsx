/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, presenceApi } from '@/api/services'
import { agentConnectionsQuery } from '@/features/agents/useAgentStatusOf'
import { presenceQuery } from '@/features/presence/presenceQuery'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
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
  // 첫 방문 환영 창이 카드를 가리지 않게 본 것으로 둔다.
  localStorage.setItem(`beez.tour.${accountId}`, 'seen')
  useWorkspaceStore.setState({ pane: null, focusId: null })
})
afterEach(() => restore())

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
  expect(lineOf('이팀장의 에이전트')).toMatch(/^연결 끊김 · 마지막 통신 /)
})

it('사람 줄은 접속 상태를, 에이전트 줄은 연결 상태를 이름으로 보이고 연결 미확인을 오프라인·연결 끊김으로 보이지 않는다', async () => {
  await renderHud()
  expect(lineOf('이팀장 · 나')).toBe('접속 중 · 개발팀 · TASK-001 시작 전')
  // 접속한 적 없는 팀원도 회색 점만이 아니라 글로 오프라인이다.
  expect(lineOf('김동료')).toBe('오프라인 · TASK-002 대기 중')
  expect(lineOf('김동료의 에이전트')).toBe('연결됨')
  const recheck = lineOf('박팀원의 에이전트')
  expect(recheck).toMatch(/^연결 미확인 · 마지막 보고 /)
  expect(recheck).not.toMatch(/오프라인|연결 끊김/)
})
