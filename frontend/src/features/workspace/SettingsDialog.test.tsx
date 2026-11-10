/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { agentsApi, authApi } from '@/api/services'
import { useAgentCheckStore } from '@/features/agents/agentCheckStore'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { SettingsDialog } from './SettingsDialog'

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
afterEach(() => restore())

// 메뉴 이름 변경(S15P31A108-376, 사용자 요청 11-10): '팀'은 '팀 관리', '사무실 화면'은 '프로젝트 관리' 아래로 옮겨
// 아래 테스트들이 누르는 메뉴 이름만 바꿨다. 각 테스트가 확인하는 동작은 그대로다.

// S15P31A108-285: 메뉴는 '연결 미확인', 본문은 '연결 전'처럼 갈렸고 확인에 실패해도 메뉴는 그대로였다.
// 기대값 변경(사용자 승인 2026-11-09, S15P31A108-341): 예시 데이터의 내 에이전트는 AGT-A05 정의대로 끊긴 줄이라
// 시작 상태가 '연결 미확인'에서 '연결 끊김'이 됐다. 이 파일의 두 테스트 모두 같다.
it('연결 확인 실패를 메뉴·에이전트 화면·팀 목록의 내 줄이 같이 보이고, 워크스페이스를 나가면 지운다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.getState().openPane('settings', 'agent')
  const { unmount } = render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <SettingsDialog
          projectId="101"
          isLeader
          agentConnected={false}
          memberId="21"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const menu = await screen.findByRole('navigation', { name: '설정 메뉴' })
  const status = await screen.findByRole('status')
  await waitFor(() => expect(status).toHaveTextContent(/^연결 끊김/))
  expect(
    within(menu).getByRole('button', { name: /에이전트/ }),
  ).toHaveTextContent('연결 끊김')
  await user.click(within(status).getByRole('button', { name: '연결 확인' }))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
  expect(
    within(menu).getByRole('button', { name: /에이전트/ }),
  ).toHaveTextContent('연결 실패')
  await user.click(within(menu).getByRole('button', { name: '팀 관리' }))
  // 실패는 내 에이전트에만 붙는다. 다른 팀원은 서버 값 그대로다.
  expect(await screen.findByText('에이전트 연결 실패')).toBeInTheDocument()
  expect(screen.getAllByText('에이전트 연결 실패')).toHaveLength(1)
  expect(screen.getAllByText('에이전트 연결됨').length).toBeGreaterThan(0)
  await user.click(within(menu).getByRole('button', { name: /에이전트/ }))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
  unmount()
  expect(useAgentCheckStore.getState().checkedMemberId).toBeNull()
})

// PR #195 리뷰: 재조회가 실패하면 이전 data가 남아 메뉴와 팀 목록이 '연결 실패'를 보일 수 있었다.
it('연결 확인 재조회가 실패하면 메뉴와 팀 목록에 연결 실패를 보이지 않는다', async () => {
  const user = userEvent.setup()
  useWorkspaceStore.getState().openPane('settings', 'agent')
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <SettingsDialog
          projectId="101"
          isLeader
          agentConnected={false}
          memberId="21"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const menu = await screen.findByRole('navigation', { name: '설정 메뉴' })
  const agentItem = () => within(menu).getByRole('button', { name: /에이전트/ })
  const status = await screen.findByRole('status')
  await waitFor(() => expect(status).toHaveTextContent(/^연결 끊김/))
  await user.click(within(status).getByRole('button', { name: '연결 확인' }))
  await waitFor(() => expect(agentItem()).toHaveTextContent('연결 실패'))
  const list = vi
    .spyOn(agentsApi, 'list')
    .mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
  await user.click(
    within(screen.getByRole('status')).getByRole('button', {
      name: '다시 확인',
    }),
  )
  expect(
    await screen.findByText('연결 상태를 확인하지 못했어요'),
  ).toBeInTheDocument()
  expect(agentItem()).toHaveTextContent('연결 끊김')
  expect(useAgentCheckStore.getState().checkedMemberId).toBeNull()
  await user.click(within(menu).getByRole('button', { name: '팀 관리' }))
  await screen.findAllByText('에이전트 연결됨')
  expect(screen.queryByText('에이전트 연결 실패')).not.toBeInTheDocument()
  list.mockRestore()
})

// PR #72 재전수 SECOND-AMP-02: 토큰 발급처럼 요청이 처리 중이면 다른 메뉴로 옮기지 않아 한 번만 보이는 토큰을 잃지 않는다.
it('요청이 처리 중이면 설정의 다른 메뉴로 옮기지 못하고 안내를 보인다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  useWorkspaceStore.getState().openPane('settings', 'agent')
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SettingsDialog projectId="101" isLeader agentConnected={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const team = await screen.findByRole('button', { name: '팀 관리' })
  expect(team).toBeEnabled()
  // 발급 요청이 끝나지 않은 상태를 흉내 낸다.
  act(() => {
    void client
      .getMutationCache()
      .build(client, { mutationFn: () => new Promise(() => undefined) })
      .execute(undefined)
  })
  expect(
    await screen.findByText(
      '처리 중이에요. 끝나면 다른 메뉴로 옮길 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '팀 관리' })).toBeDisabled()
  expect(screen.getByRole('link', { name: /회원 설정/ })).toHaveAttribute(
    'aria-disabled',
    'true',
  )
})

// D39: 사무실 시간대는 기본 자동(한국 시각)이고, 설정에서 낮·노을·밤 중 하나로 고정해 이 브라우저에 남긴다.
it('사무실 화면 설정에서 시간대를 밤으로 고정하면 브라우저에 저장한다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  useWorkspaceStore.getState().openPane('settings', 'team')
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SettingsDialog projectId="101" isLeader agentConnected={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.click(screen.getByRole('button', { name: '프로젝트 관리' }))
  expect(screen.getByRole('radio', { name: /자동/ })).toBeChecked()
  await user.click(screen.getByRole('radio', { name: /^밤/ }))
  expect(useWorkspaceStore.getState().officeTime).toBe('night')
  expect(localStorage.getItem('beez.scene.preferences.v1')).toContain(
    '"officeTime":"night"',
  )
  useWorkspaceStore.getState().setOfficeTime('auto')
})

function renderSettings(focus: string, isLeader = true) {
  useWorkspaceStore.getState().openPane('settings', focus)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <SettingsDialog
          projectId="101"
          isLeader={isLeader}
          agentConnected={false}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return screen.findByRole('navigation', { name: '설정 메뉴' })
}

// S15P31A108-376(사용자 요청 11-10): 메뉴는 회원 설정 → 팀 관리 → 내 에이전트 관리 → 프로젝트 관리 순서다.
it('설정 메뉴는 회원 설정·팀 관리·내 에이전트 관리·프로젝트 관리 순서로 보인다', async () => {
  const menu = await renderSettings('team')
  expect(menu).toHaveTextContent(
    /회원 설정.*팀 관리.*내 에이전트 관리.*프로젝트 관리/,
  )
  expect(within(menu).getByRole('link', { name: /회원 설정/ })).toHaveAttribute(
    'href',
    '/account',
  )
  for (const old of ['프로젝트 정보', '사무실 화면', '프로젝트 제거'])
    expect(within(menu).queryByText(old)).not.toBeInTheDocument()
})

// S15P31A108-376: 프로젝트 관리 한 페이지에 이름·소개·건물 위, 낮·노을·밤 아래. 예전 'office' 링크도 이 화면을 연다.
it('예전 사무실 화면 링크는 프로젝트 관리를 열고 이름·소개·건물과 시간대를 한 페이지에 보인다', async () => {
  const menu = await renderSettings('office')
  expect(
    within(menu).getByRole('button', { name: '프로젝트 관리' }),
  ).toHaveAttribute('aria-current', 'page')
  expect(
    await screen.findByRole('heading', { name: '프로젝트 관리', level: 2 }),
  ).toBeInTheDocument()
  expect(await screen.findByLabelText(/프로젝트 이름/)).toBeInTheDocument()
  expect(screen.getByLabelText('프로젝트 소개')).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /유리 오피스/ })).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /^밤/ })).toBeInTheDocument()
})

// PRJ-A04는 팀장만 고친다. 팀원은 프로젝트 관리에서 이름·소개를 보기만 하고 시간대는 고를 수 있다.
it('팀원은 프로젝트 관리에서 이름·소개를 보기만 하고 시간대는 고를 수 있다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const menu = await renderSettings('project', false)
  expect(
    within(menu).getByRole('button', { name: '프로젝트 관리' }),
  ).toHaveAttribute('aria-current', 'page')
  expect(
    await screen.findByText('프로젝트 이름·소개·건물은 팀장만 바꿀 수 있어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText('우리 팀 협업 서비스')).toBeInTheDocument()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument()
  expect(
    screen.queryByRole('radio', { name: /유리 오피스/ }),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /^밤/ })).toBeInTheDocument()
})

// 나가기 창(remove)은 프로젝트 관리 아래에 있어 메뉴에서 프로젝트 관리가 켜진다. 예전 링크(?settings=remove)도 그대로 열린다.
it('나가기 링크로 열면 프로젝트 관리 메뉴가 켜지고 프로젝트 관리에서 나가기 창으로 갈 수 있다', async () => {
  const user = userEvent.setup()
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const menu = await renderSettings('remove', false)
  expect(
    within(menu).getByRole('button', { name: '프로젝트 관리' }),
  ).toHaveAttribute('aria-current', 'page')
  expect(
    await screen.findByRole('heading', { name: '프로젝트 나가기', level: 2 }),
  ).toBeInTheDocument()
  await user.click(within(menu).getByRole('button', { name: '프로젝트 관리' }))
  await user.click(
    await screen.findByRole('button', { name: '프로젝트 나가기' }),
  )
  expect(useWorkspaceStore.getState().focusId).toBe('remove')
})
