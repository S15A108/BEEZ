/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi, membersApi, projectsApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import AccountWithdrawPage from './AccountWithdrawPage'

// 회원 탈퇴(ACC-A10·A13)는 목 어댑터로 실제 경로를 탄다.
// 이팀장(DEMO)은 프로젝트 101(팀원 3명)·102(팀원 1명)의 팀장이라 막히고, 김동료는 팀원으로만 참여해 탈퇴할 수 있다.
const MEMBER_EMAIL = 'member@beez.local'
const BLOCKED_TITLE = '이 프로젝트 때문에 지금은 탈퇴할 수 없어요'
let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/account/withdraw']}>
        <Routes>
          <Route path="/account/withdraw" element={<AccountWithdrawPage />} />
          <Route path="/login" element={<p>로그인 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function withdraw(password: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('비밀번호 확인'), password)
  await user.click(
    screen.getByRole('checkbox', { name: /다시 로그인할 수 없다/ }),
  )
  await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
}

it('탈퇴를 막는 프로젝트를 서버 판정으로 보여 주고 프로젝트마다 팀장 위임으로만 잇는다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  const memberLists = vi.spyOn(membersApi, 'list')
  renderPage()

  const blocked = await screen.findByRole('region', { name: BLOCKED_TITLE })
  const [first, second] = within(blocked).getAllByRole('listitem')
  expect(first).toHaveTextContent('우리 팀 협업 서비스')
  expect(first).toHaveTextContent('팀장 · 팀원 3명')
  expect(second).toHaveTextContent('동네 책방 예약')
  expect(second).toHaveTextContent('팀장 · 팀원 1명')
  expect(
    within(first).getByRole('link', { name: '팀장 위임' }),
  ).toHaveAttribute('href', '/projects/101?settings=team')
  // 설정 창이 팀원이 있는 프로젝트의 삭제를 아직 막아서(S15P31A108-282) 삭제 링크는 두지 않는다.
  expect(
    within(blocked).queryByRole('link', { name: '프로젝트 삭제' }),
  ).not.toBeInTheDocument()
  expect(blocked).not.toHaveTextContent('삭제')
  expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeDisabled()
  // 팀원 목록으로 세지 않고 ACC-A13 결과를 쓴다.
  expect(memberLists).not.toHaveBeenCalled()
})

it('탈퇴 비밀번호가 틀리면 이메일 문구 없이 비밀번호를 확인해 달라고 알리고 로그인을 유지한다', async () => {
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  renderPage()
  await withdraw('wrong-pass-1')

  expect(await screen.findByRole('alert')).toHaveTextContent(
    /^비밀번호를 확인해 주세요\.$/,
  )
  expect((await authApi.me()).email).toBe(MEMBER_EMAIL)
})

it('화면을 연 뒤 팀 프로젝트의 팀장이 되어 409로 막히면 서버가 준 프로젝트를 보여 주고 버튼을 끈다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  vi.spyOn(authApi, 'withdrawalCheck').mockResolvedValueOnce({
    can_withdraw: true,
    blocked_projects: [],
  })
  renderPage()
  await withdraw(DEMO_PASSWORD)

  const blocked = await screen.findByRole('region', { name: BLOCKED_TITLE })
  expect(within(blocked).getAllByRole('listitem')).toHaveLength(2)
  expect(screen.getByRole('alert')).toHaveTextContent(
    '팀장이라 탈퇴할 수 없어요',
  )
  expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeDisabled()
})

it('화면을 연 뒤 혼자 쓰는 프로젝트를 새로 만들고 409로 막히면 탈퇴 가능 여부를 다시 읽어 함께 삭제될 프로젝트에 넣는다', async () => {
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  vi.spyOn(authApi, 'withdrawalCheck').mockResolvedValueOnce({
    can_withdraw: true,
    blocked_projects: [],
  })
  renderPage()
  await screen.findByLabelText('비밀번호 확인')
  await projectsApi.create({ name: '혼자 만든 메모장' })
  await withdraw(DEMO_PASSWORD)

  await screen.findByRole('region', { name: BLOCKED_TITLE })
  expect(
    screen.getByRole('region', {
      name: '탈퇴하면 기존 프로젝트는 이렇게 돼요',
    }),
  ).toHaveTextContent('혼자 쓰는 프로젝트(혼자 만든 메모장)는 함께 삭제돼요.')
  expect(screen.getByRole('alert')).toHaveTextContent(
    '팀장이라 탈퇴할 수 없어요',
  )
})

it('탈퇴하면 첫 화면인 로그인으로 간다', async () => {
  await authApi.login({ email: MEMBER_EMAIL, password: DEMO_PASSWORD })
  renderPage()
  await withdraw(DEMO_PASSWORD)

  expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
})
