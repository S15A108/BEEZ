/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi, projectsApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import WorkspacePage from './WorkspacePage'

function InviteScreen() {
  return <p>초대 화면 {useLocation().search}</p>
}

// MEM-A01(PR #134) 초대 메일의 '프로젝트로 가기'는 /projects/{id}?invite=토큰이다. 아직 팀원이 아니면 프로젝트를
// 열 수 없어(404) 로그인 여부와 상관없이 초대 화면이 먼저 받고, 이미 팀원일 때 돌아갈 프로젝트 ID를 함께 넘긴다.
it('초대 메일 링크로 프로젝트를 열면 프로젝트 대신 초대 화면으로 보낸다', async () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/projects/101?invite=mock-invite-7']}>
        <Routes>
          <Route path="/projects/:projectId" element={<WorkspacePage />} />
          <Route path="/invite" element={<InviteScreen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText('초대 화면 ?invite=mock-invite-7&project=101'),
  ).toBeInTheDocument()
})

// 11-10 QA(S15P31A108-371): 없는·볼 수 없는 프로젝트 주소가 '다시 불러오기'만 보여 나갈 길이 없었다.
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
function renderProject(path: string) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/projects/:projectId" element={<WorkspacePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
async function expectProjectNotFound() {
  expect(
    await screen.findByText('프로젝트를 찾을 수 없거나 볼 권한이 없어요'),
  ).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '나의 도시로' })).toHaveAttribute(
    'href',
    '/',
  )
  expect(
    screen.queryByRole('button', { name: '다시 불러오기' }),
  ).not.toBeInTheDocument()
}

it.each(['/projects/999999', '/projects/abc'])(
  '없는 프로젝트 주소(%s)면 찾을 수 없다고 알리고 나의 도시로 안내한다',
  async (path) => {
    renderProject(path)
    await expectProjectNotFound()
  },
)

it('볼 권한이 없는 프로젝트(403)도 찾을 수 없다고 알리고 나의 도시로 안내한다', async () => {
  vi.spyOn(projectsApi, 'get').mockRejectedValue(
    new AxiosError('forbidden', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 403,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'forbidden' } },
    }),
  )
  renderProject('/projects/101')
  await expectProjectNotFound()
})
