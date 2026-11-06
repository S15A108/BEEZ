/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AxiosError, AxiosHeaders } from 'axios'
import { MemoryRouter, Route, Routes } from 'react-router'
import { authApi, membersApi, projectsApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { TeamPanel } from './TeamPanel'

let restore: () => void
let client: QueryClient
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})
afterEach(() => {
  client.clear()
  restore()
  vi.restoreAllMocks()
})

// PR #134(S15P31A108-290): 화면은 링크 복사 대신 메일 보내기만 쓴다. 보낸 결과를 이메일별로 보인다.
it('초대 칸 형식을 검사하고, 보내면 초대 메일을 보낸 결과를 이메일별로 보여 준다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByText('김동료')).toBeInTheDocument()
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(within(form).getByLabelText('초대할 이메일'), 'incorrect')
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(
    await within(form).findByText('이메일 형식을 확인해 주세요.'),
  ).toBeInTheDocument()
  await user.clear(within(form).getByLabelText('초대할 이메일'))
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'colleague@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(
    await screen.findByText('colleague@beez.local · 초대했어요'),
  ).toBeInTheDocument()
  expect(await screen.findByText('colleague@beez.local')).toBeInTheDocument()
  expect(screen.queryByLabelText('팀원 초대 링크')).not.toBeInTheDocument()
})

function renderTeam() {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it('쉼표로 여러 이메일과 함께 보낼 말을 넣으면 한 번에 초대하고 칸을 비운다', async () => {
  const user = userEvent.setup()
  const create = vi.spyOn(membersApi, 'createEmailInvitations')
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'one@beez.local, two@beez.local',
  )
  await user.type(within(form).getByLabelText('함께 보낼 말'), '같이 해요')
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(
    await screen.findByText('one@beez.local · 초대했어요'),
  ).toBeInTheDocument()
  expect(screen.getByText('two@beez.local · 초대했어요')).toBeInTheDocument()
  expect(create).toHaveBeenCalledTimes(1)
  expect(create).toHaveBeenCalledWith('101', {
    emails: ['one@beez.local', 'two@beez.local'],
    message: '같이 해요',
  })
  await waitFor(() =>
    expect(within(form).getByLabelText('초대할 이메일')).toHaveValue(''),
  )
  expect(within(form).getByLabelText('함께 보낼 말')).toHaveValue('')
})

it('한 번에 20명을 넘게 넣으면 보내지 않고 알린다', async () => {
  const user = userEvent.setup()
  const create = vi.spyOn(membersApi, 'createEmailInvitations')
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.click(within(form).getByLabelText('초대할 이메일'))
  await user.paste(
    Array.from({ length: 21 }, (_, i) => `u${i}@beez.local`).join(', '),
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(
    await within(form).findByText('한 번에 20명까지 초대할 수 있어요.'),
  ).toBeInTheDocument()
  expect(create).not.toHaveBeenCalled()
})

it('추방할 때 그 팀원이 맡은 작업을 넘길 작업으로 보여 준다', async () => {
  const user = userEvent.setup()
  renderTeam()
  await screen.findByText('김동료')
  await user.click(screen.getByRole('button', { name: '김동료 더보기' }))
  await user.click(await screen.findByRole('menuitem', { name: '추방' }))
  const list = await screen.findByRole('region', { name: '넘길 작업' })
  expect(
    await within(list).findByText(/TASK-002 · 초대 화면 정보 바꾸기/),
  ).toBeInTheDocument()
})

// PR #50 MEM-A06은 최근 들어온 순이라 팀장이 맨 아래로 온다. 화면은 팀장, 나, 나머지 순으로 그린다.
it('팀 목록은 팀장을 맨 위, 다음에 나, 나머지는 서버 순서로 보여 준다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await screen.findByText('김동료 · 나')
  expect(screen.getAllByRole('listitem').map((row) => row.textContent)).toEqual(
    [
      expect.stringContaining('이팀장'),
      expect.stringContaining('김동료 · 나'),
      expect.stringContaining('박팀원'),
      expect.stringContaining('최팀원'),
    ],
  )
})

// 사용자 결정 10-30: 팀장 위임은 팀원 줄 ⋯ → '팀장으로 위임' → 작은 확인 창
it('팀원 줄 ⋯에서 팀장으로 위임을 누르면 확인 창에서 팀장을 넘긴다', async () => {
  const user = userEvent.setup()
  renderTeam()
  await screen.findByText('김동료')
  await user.click(screen.getByRole('button', { name: '김동료 더보기' }))
  await user.click(
    await screen.findByRole('menuitem', { name: '팀장으로 위임' }),
  )
  const dialog = await screen.findByRole('dialog', {
    name: '김동료님에게 팀장을 넘길까요?',
  })
  await user.click(within(dialog).getByRole('button', { name: '팀장 넘기기' }))
  expect(await screen.findByText('팀장을 넘겼어요')).toBeInTheDocument()
  const members = await membersApi.list('101', { limit: 100 })
  expect(members.items.find((member) => member.name === '김동료')?.role).toBe(
    'LEADER',
  )
})

// 사용자 지적 10-30: 제거한 프로젝트를 다시 불러 '접근 권한' 오류가 뜨지 않고 바로 도시로 간다.
it('혼자 쓰는 프로젝트를 제거하면 바로 나의 도시로 간다', async () => {
  const user = userEvent.setup()
  const project = await projectsApi.create({ name: '혼자 프로젝트' })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/projects/${project.id}`]}>
        <Routes>
          <Route
            path="/projects/:id"
            element={
              <TeamPanel projectId={project.id} isLeader view="remove" />
            }
          />
          <Route path="/" element={<p>나의 도시</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await user.type(
    await screen.findByLabelText(/프로젝트 이름 입력/),
    '혼자 프로젝트',
  )
  await user.click(screen.getByRole('button', { name: '프로젝트 제거' }))
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(screen.queryByText(/접근 권한/)).not.toBeInTheDocument()
})

// PR #72 리뷰 6: 팀원 조회가 끝나기 전·실패하면 '혼자 쓰는 프로젝트' 삭제 폼을 보이지 않는다.
it('팀원 목록을 못 불러오면 프로젝트 삭제 폼을 보이지 않는다', async () => {
  vi.spyOn(membersApi, 'list').mockRejectedValue(new Error('network'))
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader view="remove" />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/정보를 불러오지 못했어요/),
  ).toBeInTheDocument()
  expect(screen.queryByLabelText(/프로젝트 이름 입력/)).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '프로젝트 제거' }),
  ).not.toBeInTheDocument()
  vi.restoreAllMocks()
})

// PR #72 전수 리뷰 AMP-03: 초대가 실패하면 입력한 이메일을 칸에 남겨 알린다. 이메일 초대(PR #134)는 요청 한 번이라
// 일부만 실패하는 경우가 없고, 실패하면 이메일 전부를 남긴다.
it('초대 요청이 실패하면 입력한 이메일을 칸에 남기고 이유를 보여 준다', async () => {
  const user = userEvent.setup()
  vi.spyOn(membersApi, 'createEmailInvitations').mockRejectedValue(
    new Error('서버 오류'),
  )
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'one@beez.local, two@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(await within(form).findByRole('alert')).toHaveTextContent(
    '요청을 완료하지 못했습니다. 다시 시도해 주세요.',
  )
  expect(within(form).getByLabelText('초대할 이메일')).toHaveValue(
    'one@beez.local, two@beez.local',
  )
})

// PR #72 전수 리뷰 AMP-04: 프로젝트 정보 저장은 다른 프로젝트가 쓰는 부지를 덮어쓰지 않는다.
it('프로젝트 정보를 저장해도 다른 프로젝트의 부지를 덮어쓰지 않는다', async () => {
  const user = userEvent.setup()
  const { useWorkspaceStore } = await import('@/stores/workspaceStore')
  const account = await authApi.me()
  useWorkspaceStore.setState({
    placements: { [`${account.id}:102`]: { lot: 0, design: 'wood' } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader view="project" />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByText(/지금 부지 \d/)).toBeInTheDocument()
  await user.click(screen.getByRole('radio', { name: /유리 오피스/ }))
  await user.click(screen.getByRole('button', { name: '저장' }))
  await vi.waitFor(() =>
    expect(
      useWorkspaceStore.getState().placements[`${account.id}:101`],
    ).toMatchObject({ design: 'glass' }),
  )
  const placements = useWorkspaceStore.getState().placements
  expect(placements[`${account.id}:101`].lot).not.toBe(0)
  expect(placements[`${account.id}:102`]).toEqual({ lot: 0, design: 'wood' })
})

// PR #72 mock/live 전수 대조 LIVE-AMP-05의 후속: 초대 만료는 없어졌고(PR #134) 이미 초대 중인지는 서버가 이메일마다 판정한다.
// 화면은 대기 초대가 있는 이메일도 거르지 않고 보내 결과로 알린다.
it('이미 초대 중이거나 팀원인 이메일도 건너뛰지 않고 보내 결과로 알린다', async () => {
  const user = userEvent.setup()
  await membersApi.createEmailInvitations('101', {
    emails: ['late@beez.local'],
  })
  const create = vi.spyOn(membersApi, 'createEmailInvitations')
  renderTeam()
  expect(await screen.findByText('late@beez.local')).toBeInTheDocument()
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'late@beez.local, member@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  await vi.waitFor(() =>
    expect(create).toHaveBeenCalledWith('101', {
      emails: ['late@beez.local', 'member@beez.local'],
    }),
  )
  expect(
    await screen.findByText(
      'late@beez.local · 이미 초대 중이에요. 아래에서 메일을 다시 보낼 수 있어요',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByText('member@beez.local · 이미 팀원이에요'),
  ).toBeInTheDocument()
})

it('초대 중인 줄의 메일 다시 보내기를 누르면 새 링크로 보냈다고 알린다', async () => {
  const user = userEvent.setup()
  const { results } = await membersApi.createEmailInvitations('101', {
    emails: ['late@beez.local'],
  })
  const resend = vi.spyOn(membersApi, 'resendInvitation')
  renderTeam()
  await user.click(
    await screen.findByRole('button', { name: '메일 다시 보내기' }),
  )
  expect(
    await screen.findByText(
      'late@beez.local · 초대 메일을 다시 보냈어요. 이전 링크는 이제 열리지 않아요.',
    ),
  ).toBeInTheDocument()
  expect(resend).toHaveBeenCalledWith('101', results[0].invitation_id)
})

// MEM-A11: 메일을 못 보내면(503 mail_send_failed) 토큰이 바뀌지 않아 이전 링크가 그대로 쓰인다.
it('다시 보내기 메일이 실패하면 이전 링크는 그대로 쓸 수 있다고 알린다', async () => {
  const user = userEvent.setup()
  await membersApi.createEmailInvitations('101', {
    emails: ['late@beez.local'],
  })
  vi.spyOn(membersApi, 'resendInvitation').mockRejectedValue(
    new AxiosError('mail', '503', undefined, undefined, {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'mail_send_failed' } },
    }),
  )
  renderTeam()
  await user.click(
    await screen.findByRole('button', { name: '메일 다시 보내기' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'late@beez.local · 초대 메일을 보내지 못했어요. 이전에 보낸 링크는 그대로 쓸 수 있어요.',
  )
})

// PR #146 리뷰: 한 줄을 보내는 동안 다른 줄의 다시 보내기 버튼은 그대로 눌린다.
it('다시 보내는 중에는 그 줄의 버튼만 끈다', async () => {
  const user = userEvent.setup()
  await membersApi.createEmailInvitations('101', {
    emails: ['one@beez.local', 'two@beez.local'],
  })
  vi.spyOn(membersApi, 'resendInvitation').mockReturnValue(
    new Promise(() => {}),
  )
  renderTeam()
  await screen.findByText('one@beez.local')
  const row = (email: string) =>
    screen
      .getAllByRole('listitem')
      .find((item) => within(item).queryByText(email))!
  await user.click(
    within(row('one@beez.local')).getByRole('button', {
      name: '메일 다시 보내기',
    }),
  )
  expect(
    within(row('one@beez.local')).getByRole('button', {
      name: '메일 다시 보내기',
    }),
  ).toBeDisabled()
  expect(
    within(row('two@beez.local')).getByRole('button', {
      name: '메일 다시 보내기',
    }),
  ).toBeEnabled()
})

// PR #72 재전수 SECOND-AMP-01: 역할·담당 영역은 서버가 팀장만 고치게 해서, 팀원에게는 본인 것도 고치기 버튼이 없다.
it('팀원에게는 본인 역할·담당 영역 고치기 버튼을 보이지 않고 mock도 403으로 거절한다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect((await screen.findAllByText(/김동료/)).length).toBeGreaterThan(0)
  expect(
    screen.queryByRole('button', { name: '역할·담당 영역' }),
  ).not.toBeInTheDocument()
  const account = await authApi.me()
  const me = (await membersApi.list('101')).items.find(
    (item) => item.account_id === account.id,
  )!
  await expect(
    membersApi.update('101', me.id, { domain: '프론트' }),
  ).rejects.toMatchObject({ response: { status: 403 } })
})

// PR #72 4차 조사 FOURTH-AMP-01: 초대 목록 조회가 실패해도 오류와 다시 불러오기를 보이고, 방금 보낸 초대 결과는
// 목록 응답과 상관없이 보인다.
it('초대 목록 조회가 실패해도 오류와 방금 보낸 초대 결과를 보인다', async () => {
  const user = userEvent.setup()
  vi.spyOn(membersApi, 'invitations').mockRejectedValue(new Error('503'))
  renderTeam()
  await screen.findByText('김동료')
  expect(
    await screen.findByRole('button', { name: /다시 불러오기/ }),
  ).toBeInTheDocument()
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'later@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(
    await screen.findByText('later@beez.local · 초대했어요'),
  ).toBeInTheDocument()
})

// PR #72 5차 조사 FIFTH-AMP-01: 열린 작업이 있는 팀원은 넘겨받을 동료를 골라야 나갈 수 있다(서버 409 active_tasks_exist).
it('열린 작업이 있는 팀원은 넘겨받을 동료를 골라 프로젝트를 나간다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const leave = vi.spyOn(membersApi, 'leave')
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/101']}>
        <Routes>
          <Route
            path="/projects/:id"
            element={
              <TeamPanel projectId="101" isLeader={false} view="remove" />
            }
          />
          <Route path="/" element={<p>나의 도시</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  const section = await screen.findByRole('region', { name: '넘길 작업' })
  expect(within(section).getByText(/TASK-002/)).toBeInTheDocument()
  await user.type(screen.getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(
    await screen.findByText('작업을 넘겨받을 동료를 골라 주세요.'),
  ).toBeInTheDocument()
  expect(leave).not.toHaveBeenCalled()
  await user.selectOptions(
    screen.getByLabelText(/작업을 넘겨받을 동료/),
    '이팀장',
  )
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(leave.mock.calls[0][1]?.reassign_to_member_id).toBeTruthy()
})
