/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { agentsApi, authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { AccountDialog } from './AccountDialog'
import { ACCOUNT_MUTATION_KEY, useAccountDialog } from './useAccountDialog'

// D48: 회원 설정은 페이지가 아니라 지금 화면 위에 뜨는 창이다. 상태는 주소의 ?account=에 있다.
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
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

// 헤더·설정 창의 '회원 설정' 링크처럼 지금 주소에서 창을 연다.
function Screen() {
  const location = useLocation()
  const account = useAccountDialog()
  return (
    <>
      <p>
        주소 {location.pathname}
        {location.search}
      </p>
      <Link {...account.linkTo('profile')}>회원 설정 열기</Link>
      <AccountDialog />
    </>
  )
}

function renderAt(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="*" element={<Screen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { client, user: userEvent.setup() }
}
const dialog = () => screen.queryByRole('dialog', { name: '회원 설정' })
const address = () => screen.getByText(/^주소 /)

it.each(['/', '/projects/101'])(
  '%s 에서 열면 그 화면의 경로를 두고 위에 창을 띄우고, X를 누르면 처음 주소로 돌아간다',
  async (path) => {
    const { user } = renderAt(path)
    await user.click(screen.getByRole('link', { name: '회원 설정 열기' }))
    expect(
      await screen.findByRole('dialog', { name: '회원 설정' }),
    ).toBeVisible()
    expect(address()).toHaveTextContent(`주소 ${path}?account=profile`)
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(dialog()).not.toBeInTheDocument()
    expect(address()).toHaveTextContent(new RegExp(`^주소 ${path}$`))
  },
)

it('하위 화면에서 Esc는 먼저 회원 설정으로 돌아가고, 한 번 더 누르면 창을 닫는다', async () => {
  const { user } = renderAt('/projects/101')
  await user.click(screen.getByRole('link', { name: '회원 설정 열기' }))
  await user.click(await screen.findByRole('button', { name: '비밀번호 변경' }))
  expect(
    await screen.findByRole('dialog', { name: '비밀번호 변경' }),
  ).toBeVisible()
  expect(address()).toHaveTextContent('?account=password')

  await user.keyboard('{Escape}')
  expect(await screen.findByRole('dialog', { name: '회원 설정' })).toBeVisible()
  expect(address()).toHaveTextContent('?account=profile')

  await user.keyboard('{Escape}')
  expect(dialog()).not.toBeInTheDocument()
  expect(address()).toHaveTextContent(/^주소 \/projects\/101$/)
})

it("하위 화면의 '‹ 회원 설정'으로 돌아오면 고치던 프로필이 남아 있다", async () => {
  const { user } = renderAt('/?account=profile')
  const name = await screen.findByLabelText('표시 이름')
  await user.clear(name)
  await user.type(name, '고친 이름')
  await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
  expect(await screen.findByRole('dialog', { name: '회원 탈퇴' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '회원 설정' }))
  expect(await screen.findByLabelText('표시 이름')).toHaveValue('고친 이름')
})

it('프로필을 고치던 중에 닫으면 한 번 묻고, 계속 고치면 그대로 두고 버리면 닫는다', async () => {
  const { user } = renderAt('/?account=profile')
  await user.click(await screen.findByRole('radio', { name: '로봇' }))
  await user.click(screen.getByRole('button', { name: '닫기' }))
  expect(
    await screen.findByRole('dialog', { name: '고치던 프로필을 닫을까요?' }),
  ).toBeVisible()
  await user.click(screen.getByRole('button', { name: '계속 고치기' }))
  expect(dialog()).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: '로봇' })).toBeChecked()

  await user.keyboard('{Escape}')
  await user.click(await screen.findByRole('button', { name: '버리고 닫기' }))
  expect(dialog()).not.toBeInTheDocument()
  expect(address()).toHaveTextContent(/^주소 \/$/)
})

// 끝나지 않는 요청을 흉내 낸다. 회원 설정 창의 요청인지는 mutationKey로 가린다.
function startPendingRequest(
  client: QueryClient,
  mutationKey?: readonly string[],
) {
  act(() => {
    void client
      .getMutationCache()
      .build(client, {
        mutationKey,
        mutationFn: () => new Promise(() => undefined),
      })
      .execute(undefined)
  })
}

it('이 창의 요청을 처리하는 중에는 X와 Esc로 닫히지 않는다', async () => {
  const { client, user } = renderAt('/?account=profile')
  await screen.findByRole('dialog', { name: '회원 설정' })
  startPendingRequest(client, ACCOUNT_MUTATION_KEY)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '닫기' })).toBeDisabled(),
  )
  await user.keyboard('{Escape}')
  expect(dialog()).toBeInTheDocument()
})

// PR #316 리뷰: 앱의 다른 요청이 도는 동안까지 창을 잠그지 않는다.
it('창과 상관없는 요청이 돌고 있어도 X로 닫힌다', async () => {
  const { client, user } = renderAt('/?account=profile')
  await screen.findByRole('dialog', { name: '회원 설정' })
  startPendingRequest(client)
  await user.click(screen.getByRole('button', { name: '닫기' }))
  expect(dialog()).not.toBeInTheDocument()
})

// PR #316 리뷰: 창 안 링크로 떠날 때도 고치던 프로필이면 X·Esc처럼 묻는다.
it("프로필을 고치던 중에 탈퇴 화면의 '팀장 위임'을 누르면 묻고, 버리면 그 주소로 간다", async () => {
  const { user } = renderAt('/?account=profile')
  await user.click(await screen.findByRole('radio', { name: '로봇' }))
  await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
  const [handover] = await screen.findAllByRole('link', { name: '팀장 위임' })
  await user.click(handover)
  expect(
    await screen.findByRole('dialog', { name: '고치던 프로필을 닫을까요?' }),
  ).toBeVisible()
  expect(address()).toHaveTextContent('?account=withdraw')
  await user.click(screen.getByRole('button', { name: '버리고 닫기' }))
  expect(address()).toHaveTextContent(/^주소 \/projects\/101\?settings=team$/)
  expect(dialog()).not.toBeInTheDocument()
})

// PR #316 머지 뒤 리뷰: 저장 중에 링크로 떠나며 '버리고 닫기'를 고르면, 창이 닫힌 뒤 저장이 끝나 바꾼 캐릭터가 그대로 저장됐다.
it('프로필을 저장하는 중에는 토큰의 프로젝트 링크를 눌러도 묻지 않고 창에 남는다', async () => {
  await agentsApi.issueToken('101', { label: '내 PC' })
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const updateMe = authApi.updateMe
  vi.spyOn(authApi, 'updateMe').mockImplementation(async (...args) => {
    await held
    return updateMe(...args)
  })
  const { user } = renderAt('/?account=profile')
  await user.click(await screen.findByRole('radio', { name: '로봇' }))
  await user.click(screen.getByRole('button', { name: '프로필 저장' }))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '닫기' })).toBeDisabled(),
  )
  const project = within(await screen.findByRole('table')).getByRole('link')
  await user.click(project)
  expect(
    screen.queryByRole('dialog', { name: '고치던 프로필을 닫을까요?' }),
  ).not.toBeInTheDocument()
  expect(address()).toHaveTextContent(/^주소 \/\?account=profile$/)

  // 저장이 끝나면 고치던 것이 없으니 링크가 바로 그 프로젝트로 간다.
  act(() => release())
  expect(await screen.findByText('저장했어요')).toBeInTheDocument()
  await user.click(project)
  expect(address()).toHaveTextContent(/^주소 \/projects\/101$/)
})
