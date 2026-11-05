/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
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

it('validates an invitation form and displays the created invitation link', async () => {
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
  const link = await screen.findByLabelText<HTMLInputElement>('팀원 초대 링크')
  expect(link.value).toContain('mock-invite-')
  expect(await screen.findByText('colleague@beez.local')).toBeInTheDocument()
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

it('쉼표로 여러 이메일을 넣으면 사람마다 초대를 만들고 칸을 비운다', async () => {
  const user = userEvent.setup()
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'one@beez.local, two@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  // 링크는 만들어질 때마다 바로 보이므로 둘 다 보일 때까지 기다린다(PR #72 4차 조사 FOURTH-AMP-01).
  await waitFor(() =>
    expect(screen.getAllByLabelText('팀원 초대 링크')).toHaveLength(2),
  )
  expect(await screen.findByText('one@beez.local')).toBeInTheDocument()
  expect(await screen.findByText('two@beez.local')).toBeInTheDocument()
  expect(within(form).getByLabelText('초대할 이메일')).toHaveValue('')
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

// PR #72 전수 리뷰 AMP-03: 일괄 초대 중 하나가 실패해도 이미 만든 링크를 지키고, 실패한 이메일만 칸에 남겨 알린다.
it('일괄 초대 중 하나가 실패해도 만든 링크를 보여 주고 실패한 이메일만 남긴다', async () => {
  const user = userEvent.setup()
  const create = membersApi.createInvitation
  vi.spyOn(membersApi, 'createInvitation').mockImplementation(
    async (projectId, input, options) => {
      if (input?.email === 'two@beez.local') throw new Error('서버 오류')
      return create(projectId, input, options)
    },
  )
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'one@beez.local, two@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  expect(await screen.findAllByLabelText('팀원 초대 링크')).toHaveLength(1)
  expect(screen.getByText('one@beez.local')).toBeInTheDocument()
  expect(
    screen.getByText('1명은 초대하지 못했어요. 입력 칸에 남겨 뒀어요.'),
  ).toBeInTheDocument()
  expect(within(form).getByLabelText('초대할 이메일')).toHaveValue(
    'two@beez.local',
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

// PR #72 mock/live 전수 대조 LIVE-AMP-05: 백엔드는 초대를 7일 뒤 만료한다. 만료된 초대가 있어도 같은 이메일로 다시 초대한다.
it('만료된 초대가 있는 이메일은 건너뛰지 않고 다시 초대한다', async () => {
  const user = userEvent.setup()
  const list = membersApi.invitations
  vi.spyOn(membersApi, 'invitations').mockImplementation(
    async (projectId, query, options) => {
      const page = await list(projectId, query, options)
      return {
        ...page,
        items: [
          ...page.items,
          {
            ...page.items[0],
            id: '990',
            invited_email: 'late@beez.local',
            accepted_at: undefined,
            revoked_at: undefined,
            expires_at: '2026-01-01T00:00:00Z',
          },
        ],
      }
    },
  )
  const create = vi.spyOn(membersApi, 'createInvitation')
  renderTeam()
  await screen.findByText('김동료')
  const form = screen.getByRole('form', { name: '팀원 초대' })
  await user.type(
    within(form).getByLabelText('초대할 이메일'),
    'late@beez.local',
  )
  await user.click(within(form).getByRole('button', { name: '초대' }))
  await vi.waitFor(() =>
    expect(create).toHaveBeenCalledWith('101', { email: 'late@beez.local' }),
  )
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

// PR #72 4차 조사 FOURTH-AMP-01: 초대 목록 조회가 실패해도 오류와 다시 불러오기를 보이고, 방금 만든 초대 링크는
// 목록 응답과 상관없이 보인다(한 번만 받는 링크라 숨으면 잃는다).
it('초대 목록 조회가 실패해도 오류와 방금 만든 초대 링크를 보인다', async () => {
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
  const link = await screen.findByLabelText<HTMLInputElement>('팀원 초대 링크')
  expect(link.value).toContain('mock-invite-')
  expect(screen.getByText('later@beez.local · 방금 초대')).toBeInTheDocument()
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
