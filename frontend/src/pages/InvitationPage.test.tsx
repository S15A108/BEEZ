/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useParams } from 'react-router'
import { authApi, membersApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { loadMockDatabase } from '@/mocks/state'
import { verifyEmail } from '@/test/verifyEmail'
import InvitationPage from './InvitationPage'

let restore: () => void
let token: string
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  const result = await membersApi.createInvitation('101', {
    email: 'new@beez.local',
  })
  token = new URL(result.invite_url).searchParams.get('token')!
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

// 어느 프로젝트로 갔는지 확인할 수 있게 주소의 ID도 그린다.
function ProjectScreen() {
  const { id } = useParams()
  return (
    <>
      <p>프로젝트 화면</p>
      <p>프로젝트 ID {id}</p>
    </>
  )
}

function open(entry = `/invite?token=${token}`) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/invite" element={<InvitationPage />} />
          <Route path="/projects/:id" element={<ProjectScreen />} />
          <Route path="/" element={<p>나의 도시</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const register = async (email: string) =>
  authApi.register({
    name: '새 팀원',
    email,
    password: 'beez-2026!',
    verified_token: await verifyEmail(email),
  })

// Q05(2026-10-29 확정): 수락 버튼 없이 로그인 상태면 저절로 참여하고, 로그아웃이면 로그인·가입으로 잇는다.
// PR #134부터 초대받은 이메일로 가입하면 그때 자동 참여해, 링크를 열어야 참여하는 이메일 없는 링크 초대로 본다.
it('로그인한 채로 초대 링크를 열면 버튼 없이 참여하고 프로젝트로 간다', async () => {
  const link = await membersApi.createInvitation('101')
  token = new URL(link.invite_url).searchParams.get('token')!
  await register('other@beez.local')
  open()
  expect(await screen.findByText('프로젝트 화면')).toBeInTheDocument()
})

// MEM-A05(PR #134): 로그인 자동 참여로 이미 들어온 사람이 메일 링크(/projects/{id}?invite=)로 돌아오면 409 already_member다.
it('메일 링크로 돌아왔는데 이미 팀원이면 그 프로젝트로 간다', async () => {
  await membersApi.createEmailInvitations('101', {
    emails: ['mail@beez.local'],
  })
  token = loadMockDatabase().invitations.findLast(
    (item) => item.invited_email === 'mail@beez.local',
  )!.token
  await register('mail@beez.local')
  open(`/invite?invite=${token}&project=101`)
  expect(await screen.findByText('프로젝트 화면')).toBeInTheDocument()
  expect(screen.getByText('프로젝트 ID 101')).toBeInTheDocument()
})

it('메일 링크의 project가 프로젝트 ID 모양이 아니면 이미 팀원이어도 나의 도시로 간다', async () => {
  await membersApi.createEmailInvitations('101', {
    emails: ['mail@beez.local'],
  })
  token = loadMockDatabase().invitations.findLast(
    (item) => item.invited_email === 'mail@beez.local',
  )!.token
  await register('mail@beez.local')
  open(
    `/invite?invite=${token}&project=${encodeURIComponent('../account/withdraw')}`,
  )
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
})

it('로그아웃 상태로 메일 링크를 열면 프로젝트 소개를 보이고 로그인·가입에 프로젝트도 넘긴다', async () => {
  await authApi.logout()
  open(`/invite?invite=${token}&project=101`)
  expect(
    await screen.findByText('승인 설계를 함께 관리하는 예시 프로젝트'),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('link', { name: '로그인하고 프로젝트 열기' }),
  ).toHaveAttribute('href', `/login?invite=${token}&project=101`)
  expect(
    screen.getByRole('link', { name: '이 이메일로 회원가입하기' }),
  ).toHaveAttribute('href', `/register?invite=${token}&project=101`)
})

it('미리보기가 초대한 팀장과 초대받은 이메일을 주면 카드에 보여 준다', async () => {
  await authApi.logout()
  open()
  expect(
    await screen.findByText('이팀장님(demo@beez.local)이 초대했어요'),
  ).toBeInTheDocument()
  expect(screen.getByText('초대받은 이메일 new@beez.local')).toBeInTheDocument()
})

it('로그아웃 상태로 열면 로그인·가입하고 참여하는 길만 보여 준다', async () => {
  await authApi.logout()
  open()
  expect(
    await screen.findByRole('link', { name: '로그인하고 프로젝트 열기' }),
  ).toHaveAttribute('href', `/login?invite=${token}`)
  expect(
    screen.getByRole('link', { name: '이 이메일로 회원가입하기' }),
  ).toHaveAttribute('href', `/register?invite=${token}`)
  expect(screen.queryByRole('button', { name: /수락/ })).not.toBeInTheDocument()
})

// PR #72 mock/live 전수 대조 LIVE-AMP-04: 서버는 다른 계정의 수락을 404로 숨긴다.
// 로그인한 채 다른 계정으로 열면 오류 코드 없이도 다시 로그인할 길을 보인다.
it('다른 계정으로 초대 링크를 열면 초대받은 계정으로 다시 로그인하게 한다', async () => {
  open()
  expect(await screen.findByText('다른 계정일 수 있어요')).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '초대받은 계정으로 다시 로그인' }),
  ).toBeInTheDocument()
})

it('수락이 404면 미리보기에 계정 정보가 없어도 다시 로그인할 길을 보인다', async () => {
  const preview = membersApi.previewInvitation
  vi.spyOn(membersApi, 'previewInvitation').mockImplementation(
    async (input, options) => ({
      ...(await preview(input, options)),
      account_matches: undefined,
    }),
  )
  open()
  expect(await screen.findByText('다른 계정일 수 있어요')).toBeInTheDocument()
})
