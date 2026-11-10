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
import { useWorkspaceStore } from '@/stores/workspaceStore'
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
// 11-04 사용자 결정: 혼자 쓰는 프로젝트면 '프로젝트 삭제'만 보인다(버튼 이름 '프로젝트 제거' → '프로젝트 삭제').
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
  expect(
    screen.queryByRole('button', { name: '위임하고 나가기' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '프로젝트 삭제' }))
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
    screen.queryByRole('button', { name: '프로젝트 삭제' }),
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

async function renderProjectInfo() {
  useWorkspaceStore.setState({ placements: {} })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel projectId="101" isLeader view="project" />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return {
    user: userEvent.setup(),
    name: await screen.findByLabelText(/프로젝트 이름/),
    description: screen.getByLabelText('프로젝트 소개'),
    // 서버 값이 바뀌면 폼을 새로 그리므로 그때그때 찾는다.
    save: () => screen.getByRole('button', { name: '저장' }),
  }
}

it('프로젝트 정보를 바꾸지 않으면 저장이 꺼져 있고 Enter를 눌러도 보내지 않는다', async () => {
  const update = vi.spyOn(projectsApi, 'update')
  const { user, name, save } = await renderProjectInfo()
  expect(save()).toBeDisabled()
  await user.type(name, '{Enter}')
  expect(update).not.toHaveBeenCalled()
})

it('프로젝트 정보에 앞뒤 공백만 더하거나 고친 값을 되돌리면 저장이 꺼진다', async () => {
  const { user, name, description, save } = await renderProjectInfo()
  await user.type(name, '  ')
  await user.type(description, '  ')
  expect(save()).toBeDisabled()
  await user.type(name, '요')
  expect(save()).toBeEnabled()
  await user.type(name, '{Backspace}')
  expect(save()).toBeDisabled()
})

it('프로젝트 이름을 바꿔 저장하면 한 번만 보내고 알린 뒤 저장을 다시 끈다', async () => {
  const update = vi.spyOn(projectsApi, 'update')
  const { user, name, description, save } = await renderProjectInfo()
  const introduction = (description as HTMLTextAreaElement).value
  await user.clear(name)
  await user.type(name, '새 이름')
  expect(save()).toBeEnabled()
  await user.click(save())
  expect(await screen.findByRole('status')).toHaveTextContent(
    '프로젝트 정보를 저장했어요.',
  )
  expect(update).toHaveBeenCalledTimes(1)
  expect(update).toHaveBeenCalledWith('101', {
    name: '새 이름',
    description: introduction,
  })
  await waitFor(() => expect(save()).toBeDisabled())

  await user.type(screen.getByLabelText(/프로젝트 이름/), '요')
  expect(save()).toBeEnabled()
})

it('건물 디자인만 바꾸면 저장이 켜지고, 저장해도 서버에는 보내지 않은 뒤 다시 꺼진다', async () => {
  const update = vi.spyOn(projectsApi, 'update')
  const { user, save } = await renderProjectInfo()
  const account = await authApi.me()
  await screen.findByText(/지금 부지 \d/)
  await user.click(screen.getByRole('radio', { name: /유리 오피스/ }))
  expect(save()).toBeEnabled()
  await user.click(screen.getByRole('radio', { name: /모던 화이트/ }))
  expect(save()).toBeDisabled()

  await user.click(screen.getByRole('radio', { name: /유리 오피스/ }))
  await user.click(save())
  expect(await screen.findByRole('status')).toHaveTextContent(
    '프로젝트 정보를 저장했어요.',
  )
  expect(update).not.toHaveBeenCalled()
  expect(
    useWorkspaceStore.getState().placements[`${account.id}:101`],
  ).toMatchObject({ design: 'glass' })
  await waitFor(() => expect(save()).toBeDisabled())
})

it('도시 부지가 없는 프로젝트는 디자인을 저장할 수 없어 골라도 저장이 켜지지 않는다', async () => {
  const others = Array.from({ length: 12 }, (_, index) => ({
    id: String(200 + index),
  }))
  vi.spyOn(projectsApi, 'list').mockResolvedValue({
    items: [...others, { id: '101' }],
  } as never)
  const { user, save } = await renderProjectInfo()
  await user.click(screen.getByRole('radio', { name: /유리 오피스/ }))
  expect(save()).toBeDisabled()
})

it('서버 값이 다시 들어오면 입력과 비교 기준이 그 값을 따른다', async () => {
  const { save } = await renderProjectInfo()
  await projectsApi.update('101', { name: '다른 곳에서 바꾼 이름' })
  await client.invalidateQueries({ queryKey: ['projects', '101'] })
  await waitFor(() =>
    expect(screen.getByLabelText(/프로젝트 이름/)).toHaveValue(
      '다른 곳에서 바꾼 이름',
    ),
  )
  expect(save()).toBeDisabled()
})

// PR #199 리뷰: 저장 뒤 프로젝트를 먼저 다시 읽으면 폼이 새로 그려진다. 팀원 목록 같은 다른 조회가 끝나기 전까지는 다시 보내지 않는다.
it('저장 처리가 끝나기 전에는 폼이 새로 그려져도 다시 보낼 수 없다', async () => {
  const realList = membersApi.list
  const list = vi.spyOn(membersApi, 'list')
  const update = vi.spyOn(projectsApi, 'update')
  const { user, name, save } = await renderProjectInfo()
  await waitFor(() => expect(list).toHaveBeenCalled())
  let release = () => {}
  const gate = new Promise<void>((resolve) => (release = resolve))
  list.mockImplementation(async (...args) => {
    await gate
    return realList(...args)
  })
  await user.clear(name)
  await user.type(name, '새 이름')
  await user.click(save())
  await waitFor(() =>
    expect(screen.getByLabelText(/프로젝트 이름/)).not.toBe(name),
  )
  await user.type(screen.getByLabelText(/프로젝트 이름/), '요')
  expect(save()).toBeDisabled()

  release()
  await waitFor(() => expect(save()).toBeEnabled())
  expect(update).toHaveBeenCalledTimes(1)
})

it('프로젝트 정보 저장이 실패하면 쓴 값을 두고 다시 누를 수 있다', async () => {
  vi.spyOn(projectsApi, 'update').mockRejectedValueOnce(new Error('서버 오류'))
  const { user, name, save } = await renderProjectInfo()
  await user.clear(name)
  await user.type(name, '새 이름')
  await user.click(save())
  expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0)
  expect(screen.getByLabelText(/프로젝트 이름/)).toHaveValue('새 이름')
  expect(save()).toBeEnabled()
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
it('팀원에게는 본인 역할·담당 도메인 고치기 버튼을 보이지 않고 mock도 403으로 거절한다', async () => {
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
    screen.queryByRole('button', { name: '역할·담당 도메인' }),
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

function renderRemoveView(isLeader: boolean, projectId = '101') {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/projects/${projectId}`]}>
        <Routes>
          <Route
            path="/projects/:id"
            element={
              <TeamPanel
                projectId={projectId}
                isLeader={isLeader}
                view="remove"
              />
            }
          />
          <Route path="/" element={<p>나의 도시</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// 11-04 사용자 결정(S15P31A108-282): 팀원은 열린 작업이 있어도 경고만 보고 나간다. 고르지 않으면 담당자를 비운다.
// (이전: PR #72 5차 조사 FIFTH-AMP-01, 넘겨받을 동료를 골라야 나갈 수 있었다.)
it('열린 작업이 있는 팀원은 경고를 보고, 넘겨받을 사람을 고르지 않으면 담당자를 비우고 나간다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const leave = vi.spyOn(membersApi, 'leave')
  renderRemoveView(false)
  expect(
    await screen.findByText('내가 맡은 열린 작업이 1개 있어요'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText(/넘겨받을 사람 선택/)).toHaveDisplayValue(
    '고르지 않음 · 담당자 비우기',
  )
  await user.type(screen.getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(leave).toHaveBeenCalledWith('101', {
    reassign_to_member_id: undefined,
  })
})

it('팀원이 넘겨받을 사람을 고르면 열린 작업을 그 사람에게 넘기고 나간다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const leave = vi.spyOn(membersApi, 'leave')
  renderRemoveView(false)
  await user.selectOptions(
    await screen.findByLabelText(/넘겨받을 사람 선택/),
    '이팀장 (팀장)',
  )
  await user.type(screen.getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(leave).toHaveBeenCalledWith('101', { reassign_to_member_id: '21' })
})

it('나가기가 409로 막히면 이유를 보이고 역할과 팀원 목록을 다시 불러온다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  // 그사이 팀장이 되었는데 창은 아직 팀원 나가기 모양이다. 서버는 409 leader_transfer_required로 막는다.
  renderRemoveView(false)
  // 열린 작업을 알게 되면 폼을 새로 그리므로 경고가 보인 뒤에 입력한다.
  await screen.findByText('내가 맡은 열린 작업이 1개 있어요')
  await user.type(screen.getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(
    await screen.findByText('인간 팀장을 먼저 위임해 주세요.'),
  ).toBeInTheDocument()
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['projects', '101'] })
  expect(screen.queryByText('나의 도시')).not.toBeInTheDocument()
})

// 11-04 사용자 결정: 팀장의 나가기 창은 '위임하고 나가기'와 '프로젝트 삭제' 두 길이다(MEM-A10·PRJ-A05).
it('팀장은 넘길 팀원을 골라야 위임하고 나가고, 위임과 나가기를 한 번에 요청한다', async () => {
  const user = userEvent.setup()
  const leave = vi.spyOn(membersApi, 'leave')
  renderRemoveView(true)
  const handoff = await screen.findByRole('region', {
    name: '위임하고 나가기',
  })
  expect(
    await within(handoff).findByText('내가 맡은 열린 작업이 1개 있어요'),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('region', { name: '프로젝트 삭제' }),
  ).toBeInTheDocument()
  await user.click(
    within(handoff).getByRole('button', { name: '위임하고 나가기' }),
  )
  expect(
    await within(handoff).findByText('팀장을 넘겨받을 팀원을 골라 주세요.'),
  ).toBeInTheDocument()
  expect(leave).not.toHaveBeenCalled()
  await user.selectOptions(
    within(handoff).getByLabelText(/팀장을 넘겨받을 팀원/),
    '김동료',
  )
  await user.click(
    within(handoff).getByRole('button', { name: '위임하고 나가기' }),
  )
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(leave).toHaveBeenCalledWith('101', {
    transfer_leader_to_member_id: '22',
    reassign_to_member_id: undefined,
  })
})

// PR #170: 다른 창에서 이미 위임한 뒤 위임하고 나가기를 누르면 서버가 403 forbidden을 준다. 창이 팀장 모양으로
// 남으면 같은 오류만 반복되므로 역할과 팀원 목록을 다시 불러온다.
it('이미 위임해 위임하고 나가기가 403으로 막히면 이유를 보이고 역할과 팀원 목록을 다시 불러온다', async () => {
  const user = userEvent.setup()
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  vi.spyOn(membersApi, 'leave').mockRejectedValueOnce(
    new AxiosError('forbidden', '403', undefined, undefined, {
      status: 403,
      statusText: 'Forbidden',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'forbidden', message: 'forbidden' } },
    }),
  )
  renderRemoveView(true)
  const handoff = await screen.findByRole('region', {
    name: '위임하고 나가기',
  })
  await within(handoff).findByText('내가 맡은 열린 작업이 1개 있어요')
  await user.selectOptions(
    within(handoff).getByLabelText(/팀장을 넘겨받을 팀원/),
    '김동료',
  )
  await user.click(
    within(handoff).getByRole('button', { name: '위임하고 나가기' }),
  )
  expect(
    await screen.findByText('이 작업의 권한이 없습니다.'),
  ).toBeInTheDocument()
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['projects', '101'] })
  expect(screen.queryByText('나의 도시')).not.toBeInTheDocument()
})

it('팀장은 팀원이 있어도 프로젝트 이름을 입력하면 삭제하고 남은 팀원도 못 쓴다고 알린다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  const remove = vi.spyOn(projectsApi, 'remove')
  renderRemoveView(true)
  const removal = await screen.findByRole('region', { name: '프로젝트 삭제' })
  expect(
    within(removal).getByText(/팀원 3명도 이 프로젝트를 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  await user.type(within(removal).getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(
    within(removal).getByRole('button', { name: '프로젝트 삭제' }),
  )
  expect(await screen.findByText('나의 도시')).toBeInTheDocument()
  expect(remove).toHaveBeenCalledWith('101', { confirm_name: name })
})

it('추방할 때 넘겨받을 동료를 고르지 않으면 담당자만 비우고 제외한다', async () => {
  const user = userEvent.setup()
  const remove = vi.spyOn(membersApi, 'remove')
  renderTeam()
  await screen.findByText('김동료')
  await user.click(screen.getByRole('button', { name: '김동료 더보기' }))
  await user.click(await screen.findByRole('menuitem', { name: '추방' }))
  expect(
    await screen.findByText('김동료님이 맡은 열린 작업 1개'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText(/작업을 넘겨받을 동료 선택/)).toHaveDisplayValue(
    '고르지 않음 · 담당자 비우기',
  )
  await user.click(screen.getByRole('button', { name: '추방' }))
  await waitFor(() =>
    expect(remove).toHaveBeenCalledWith('101', '22', {
      reassign_to_member_id: undefined,
    }),
  )
})

// MEM-A09·A10: 담당자를 넘기거나 비우는 동안 그 작업 상태가 바뀌면 409 revision_conflict로 전체가 되돌아간다. 다시 누르면 된다.
const RETRY_MESSAGE = '작업 상태가 방금 바뀌었어요. 다시 시도해 주세요.'
const revisionConflict = () =>
  new AxiosError('conflict', '409', undefined, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code: 'revision_conflict', message: 'conflict' } },
  })

it('팀원 나가기가 작업 상태 변경으로 409면 다시 시도하라고 알린다', async () => {
  const user = userEvent.setup()
  const { name } = await projectsApi.get('101')
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  vi.spyOn(membersApi, 'leave').mockRejectedValueOnce(revisionConflict())
  renderRemoveView(false)
  await screen.findByText('내가 맡은 열린 작업이 1개 있어요')
  await user.type(screen.getByLabelText(/프로젝트 이름 입력/), name)
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(await screen.findByText(RETRY_MESSAGE)).toBeInTheDocument()
  expect(screen.queryByText('나의 도시')).not.toBeInTheDocument()
})

it('위임하고 나가기가 작업 상태 변경으로 409면 다시 시도하라고 알린다', async () => {
  const user = userEvent.setup()
  vi.spyOn(membersApi, 'leave').mockRejectedValueOnce(revisionConflict())
  renderRemoveView(true)
  const handoff = await screen.findByRole('region', {
    name: '위임하고 나가기',
  })
  await within(handoff).findByText('내가 맡은 열린 작업이 1개 있어요')
  await user.selectOptions(
    within(handoff).getByLabelText(/팀장을 넘겨받을 팀원/),
    '김동료',
  )
  await user.click(
    within(handoff).getByRole('button', { name: '위임하고 나가기' }),
  )
  expect(await within(handoff).findByText(RETRY_MESSAGE)).toBeInTheDocument()
})

it('추방이 작업 상태 변경으로 409면 다시 시도하라고 알리고 창을 닫지 않는다', async () => {
  const user = userEvent.setup()
  vi.spyOn(membersApi, 'remove').mockRejectedValueOnce(revisionConflict())
  renderTeam()
  await screen.findByText('김동료')
  await user.click(screen.getByRole('button', { name: '김동료 더보기' }))
  await user.click(await screen.findByRole('menuitem', { name: '추방' }))
  await screen.findByText('김동료님이 맡은 열린 작업 1개')
  await user.click(screen.getByRole('button', { name: '추방' }))
  expect(await screen.findByText(RETRY_MESSAGE)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '추방' })).toBeEnabled()
})

// S15P31A108-377(사용자 보고 11-10): 팀장에게 '프로젝트 나가기' 자리에 '프로젝트 삭제'·'프로젝트 제거'가 보였다.
// MEM-A10은 팀장도 팀장을 넘기고 나가게 한다. 나가기 창 제목은 팀장도 '프로젝트 나가기'이고, 삭제는 그 안의 다른 길이다.
it('팀장의 나가기 창 제목은 프로젝트 나가기이고 위임하고 나가기와 프로젝트 삭제를 따로 보인다', async () => {
  renderRemoveView(true)
  expect(
    await screen.findByRole('heading', { name: '프로젝트 나가기', level: 2 }),
  ).toBeInTheDocument()
  expect(screen.queryByText('프로젝트 제거')).not.toBeInTheDocument()
  expect(
    await screen.findByRole('region', { name: '위임하고 나가기' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('region', { name: '프로젝트 삭제' }),
  ).toBeInTheDocument()
})

it('혼자 쓰는 프로젝트의 팀장에게는 팀장을 넘길 팀원이 없어 나갈 수 없다고 알리고 삭제만 보인다', async () => {
  const project = await projectsApi.create({ name: '혼자 프로젝트' })
  renderRemoveView(true, project.id)
  expect(
    await screen.findByText(
      '팀장을 넘길 팀원이 없어 지금은 나갈 수 없어요. 팀 관리에서 팀원을 초대하고 팀장을 넘기면 나갈 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('region', { name: '프로젝트 삭제' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '위임하고 나가기' }),
  ).not.toBeInTheDocument()
})

it('팀장도 프로젝트 관리 맨 아래에서 프로젝트 나가기로 나가기 창을 연다', async () => {
  const user = userEvent.setup()
  const onViewChange = vi.fn()
  useWorkspaceStore.setState({ placements: {} })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamPanel
          projectId="101"
          isLeader
          view="project"
          onViewChange={onViewChange}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(
      '팀장을 넘기고 나가거나, 이름을 입력해 프로젝트를 삭제할 수 있어요.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '프로젝트 삭제' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '프로젝트 나가기' }))
  expect(onViewChange).toHaveBeenCalledWith('remove')
})

// S15P31A108-378(사용자 보고 11-10): 표시 역할·담당 영역이 무엇을 쓰는 칸인지 몰랐다. 목록 위와 수정 칸 아래에 작은 글로 알린다.
// 3D 이름표는 이름(에이전트는 이름 · 상태)만 보여 두 칸이 나오지 않는다(office-actors.ts). 보이는 곳만 적는다.
const roleHint = '팀에서 맡은 역할 이름(예: 프론트엔드). 이름 아래에 보여요.'
const domainHint =
  '주로 맡는 기능(예: 게시판 작업). 사무실 팀원 목록에도 보여요.'

it('팀 목록 위에 표시 역할과 담당 도메인이 무엇을 쓰는 칸인지 보인다', async () => {
  renderTeam()
  await screen.findByText('김동료')
  const legend = screen.getByRole('group', { name: '표시 역할과 담당 도메인' })
  expect(within(legend).getByText('표시 역할')).toBeInTheDocument()
  expect(within(legend).getByText(roleHint)).toBeInTheDocument()
  expect(within(legend).getByText('담당 도메인')).toBeInTheDocument()
  expect(within(legend).getByText(domainHint)).toBeInTheDocument()
})

it('역할 수정 창의 표시 역할·담당 도메인 칸 아래에 짧은 설명이 보인다', async () => {
  const user = userEvent.setup()
  renderTeam()
  await user.click(
    await screen.findByRole('button', { name: '역할·담당 도메인' }),
  )
  const sheet = await screen.findByRole('dialog')
  expect(within(sheet).getByLabelText('표시 역할')).toBeInTheDocument()
  expect(within(sheet).getByText(roleHint)).toBeInTheDocument()
  expect(within(sheet).getByText(domainHint)).toBeInTheDocument()
  expect(within(sheet).queryByText(/3D 이름표/)).not.toBeInTheDocument()
})
