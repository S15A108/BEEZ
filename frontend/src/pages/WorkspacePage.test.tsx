import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, it } from 'vitest'
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
