/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { MISSING_API_MESSAGE } from '@/api/error'
import { authApi, meetingsApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { MeetingsPanel } from './MeetingsPanel'

let restore: () => void
let client: QueryClient
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  useWorkspaceStore.getState().openPane('meetings')
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})
afterEach(() => {
  client.clear()
  restore()
})

function renderPanel(isLeader: boolean) {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MeetingsPanel projectId="101" isLeader={isLeader} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function apiError(status: number, code: string) {
  return new AxiosError('error', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: 'error',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: code } },
  })
}

// 팀장이 대화한 뒤 비비에게 정리를 요청한다(MTG-A11). 정리는 구조화한 카드로 보인다.
async function requestSummary(user: ReturnType<typeof userEvent.setup>) {
  const log = await screen.findByRole('log', { name: '회의 대화' })
  await within(log).findByText('TASK-002에서 만료 전용 안내는 뺄게요.')
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  await within(log).findByRole('region', { name: '바뀔 문서' })
  return log
}

it('팀장은 메시지를 보내고 비비 정리를 구조화한 카드로 받는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  expect(
    screen.queryByRole('button', { name: '회의 만들기' }),
  ).not.toBeInTheDocument()
  // 회의 참여 응답(MTG-A05)은 폐지됐다. 참여하기 버튼을 두지 않는다.
  expect(
    screen.queryByRole('button', { name: '참여하기' }),
  ).not.toBeInTheDocument()
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    await within(log).findByText(/작업이 부딪혀서 회의를 열었어요/),
  ).toBeInTheDocument()
  await user.type(
    screen.getByRole('textbox', { name: /메시지/ }),
    '기존 계약을 유지하고 설명을 보완해요.',
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await within(log).findByText('기존 계약을 유지하고 설명을 보완해요.')
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  // 충돌 · 결정(정한 사람) · 바뀔 문서(지금/바뀐 뒤) · 할 일
  const changes = await within(log).findByRole('region', { name: '바뀔 문서' })
  expect(
    within(changes).getByText(/요구사항 명세서 · \d+행/),
  ).toBeInTheDocument()
  expect(within(changes).getByText('지금')).toBeInTheDocument()
  expect(within(changes).getByText('바뀐 뒤')).toBeInTheDocument()
  expect(within(log).getByRole('region', { name: '충돌' })).toHaveTextContent(
    '만료된 초대를 어떻게 안내할지 정해 주세요',
  )
  expect(within(log).getByRole('region', { name: '결정' })).toHaveTextContent(
    '기존 계약을 유지하고 설명을 보완해요.',
  )
  expect(within(log).getByRole('region', { name: '할 일' })).toHaveTextContent(
    'TASK-002',
  )
  // 정리 뒤에는 앞의 대화를 접고 마지막 두 메시지와 정리만 보인다(캔버스 PNL-06-v2-bibi-review, 기대값 변경 사용자 승인 11-04).
  await user.click(
    within(log).getByRole('button', { name: /^앞의 대화 \d+개$/ }),
  )
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  expect(
    screen.getByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '회의 끝내고 변경 제안 만들기' }),
  ).toBeEnabled()
})

// 회의 끝내기 버튼은 고칠 점 입력 폼 안에 있다. 눌러도 그 폼을 제출하지 않아야 한다.
it('고칠 점을 적은 채 회의 끝내기를 누르면 수정 요청을 보내지 않고 끝내기 확인만 연다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  const revise = vi.spyOn(meetingsApi, 'reviseSummary')
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '만료 안내 문구를 다시 정리해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  expect(
    await screen.findByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeInTheDocument()
  expect(revise).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

// MTG-A12: 고칠 점을 정리 수정 요청으로 보낸다. 새 정리가 최신이 되고 이전 정리는 기록에 남는다.
it('고칠 점으로 정리를 다시 요청하면 수정본이 최신 정리가 되고 이전 정리는 남는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const log = await requestSummary(user)
  const addEntry = vi.spyOn(meetingsApi, 'addEntry')
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '만료는 14일로 정했어요.',
  )
  await user.click(screen.getByRole('button', { name: '수정 요청' }))
  expect(await within(log).findByText('수정본')).toBeInTheDocument()
  expect(
    within(log).getByText(/고칠 점: 만료는 14일로 정했어요\./),
  ).toBeInTheDocument()
  expect(
    within(log).getByText('이전 정리', { exact: false }),
  ).toBeInTheDocument()
  expect(addEntry).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

// MTG-A13: 최신 정리로 회의를 끝내고 변경 제안을 만든다. 끝난 회의는 읽기만 하고 제안을 연다.
it('회의를 끝내면 변경 제안이 만들어지고 끝난 회의로 옮겨지며 제안을 열 수 있다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  await user.click(
    screen.getByRole('button', { name: '회의 끝내고 변경 제안 만들기' }),
  )
  const result = await screen.findByRole('region', { name: '회의 끝낸 결과' })
  expect(result).toHaveTextContent('변경 제안을 올렸어요')
  expect(result).toHaveTextContent('김동료')
  expect(
    await screen.findByText('끝난 회의라 메시지를 보낼 수 없어요.'),
  ).toBeInTheDocument()
  // 끝낸 직후 '회의를 끝냈어요' 알림은 띄우지 않는다. 변경 제안으로 가는 버튼은 머리의 '변경 제안 보기' 하나뿐이다.
  expect(screen.queryByText('회의를 끝냈어요.')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '변경 제안 열기' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '변경 제안 보기' }))
  expect(useWorkspaceStore.getState().pane).toBe('changes')
  expect(useWorkspaceStore.getState().focusId).toBeTruthy()
})

// 정리 뒤에 새 대화가 있으면 서버가 409 summary_outdated 로 막는다. 회의는 열린 채 정리를 다시 요청하게 한다.
it('정리 뒤에 새 대화가 있으면 끝내지 못하고 정리를 다시 요청하라고 안내한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await meetingsApi.addEntry('101', '901', { text: '만료는 14일로 바꿔요.' })
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  await user.click(
    screen.getByRole('button', { name: '회의 끝내고 변경 제안 만들기' }),
  )
  expect(
    await screen.findByText(/정리한 뒤에 새 기록이 생겼거나/),
  ).toBeInTheDocument()
  // 회의는 열려 있고 대화 화면으로 돌아와 정리를 다시 요청할 수 있다.
  expect(
    screen.getByRole('button', { name: '비비에게 정리 요청' }),
  ).toBeEnabled()
  expect(
    screen.queryByRole('region', { name: '회의 끝낸 결과' }),
  ).not.toBeInTheDocument()
})

// 정리 ID·구조화한 정리는 서버에서 다시 읽는다(MTG-A15). 새로 고치거나 다른 기기에서 열어도 끝내기를 이어 갈 수 있다.
it('새로 고쳐도 최신 정리를 서버에서 다시 받아 수정 요청과 끝내기를 이어 간다', async () => {
  const user = userEvent.setup()
  const first = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MeetingsPanel projectId="101" isLeader />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await requestSummary(user)
  first.unmount()
  // 새 화면(캐시 없음)에서 같은 회의를 연다.
  client.clear()
  renderPanel(true)
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    await within(log).findByRole('region', { name: '바뀔 문서' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '회의 끝내기' })).toBeEnabled()
})

// 회의 끝내기는 비비에게 정리 요청 옆에 있고, 끝내기 확인 화면에서도 정리를 다시 요청할 수 있다.
it('회의 끝내기는 정리 요청 옆에 있고 끝내기 확인에서도 정리를 다시 요청할 수 있다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  expect(
    await screen.findByRole('heading', { name: '초대 안내 문구 검토' }),
  ).toBeInTheDocument()
  // 정리가 없으면 끝낼 수 없다.
  expect(
    screen.queryByRole('button', { name: '회의 끝내기' }),
  ).not.toBeInTheDocument()
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  const summarize = vi.spyOn(meetingsApi, 'summarize')
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  await waitFor(() => expect(summarize).toHaveBeenCalledTimes(1))
  expect(
    screen.getByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

// 끝난 회의도 기록은 그대로 읽는다. 목록과 머리의 표시는 '완료'다.
it('끝난 회의는 완료로 표시하고 실제로 나눈 대화를 그대로 보여 준다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(
    screen.getByRole('button', { name: /API 오류 응답 형식 통일/ }),
  )
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    await within(log).findByText(/error.code 하나로 맞추면/),
  ).toBeInTheDocument()
  expect(within(log).queryByText('아직 대화가 없어요.')).not.toBeInTheDocument()
  expect(screen.getAllByText('완료').length).toBeGreaterThan(0)
  expect(screen.queryByText('끝남')).not.toBeInTheDocument()
})

// 실제 서버의 기록 목록(MTG-A10)은 오래된 순이고 페이지(기본 20개)로 나뉜다. 기록이 많아도 최신 정리와 마지막 말을 놓치지 않는다.
it('기록이 한 페이지를 넘어도 대화를 오래된 순으로 보이고 최신 정리를 찾는다', async () => {
  for (let index = 1; index <= 22; index++)
    await meetingsApi.addEntry('101', '901', { text: `추가 의견 ${index}` })
  const entries = await meetingsApi.entries('101', '901', {})
  const through = entries.items.at(-1)!
  await meetingsApi.summarize('101', '901', { through_entry_id: through.id })
  await meetingsApi.addEntry('101', '901', { text: '정리 뒤 마지막 말' })
  renderPanel(true)
  const log = await screen.findByRole('log', { name: '회의 대화' })
  // 가장 마지막 기록이 대화 맨 아래에 있고, 최신 정리는 구조화한 카드로 보인다.
  expect(await within(log).findByText('정리 뒤 마지막 말')).toBeInTheDocument()
  expect(
    await within(log).findByRole('region', { name: '바뀔 문서' }),
  ).toBeInTheDocument()
  const texts = within(log)
    .getAllByText(/^(추가 의견 \d+|정리 뒤 마지막 말)$/)
    .map((node) => node.textContent)
  expect(texts.at(-1)).toBe('정리 뒤 마지막 말')
  expect(texts.indexOf('추가 의견 22')).toBeLessThan(texts.length - 1)
  // 정리 뒤에 새 대화가 있다는 안내가 보인다.
  expect(
    await screen.findByText(/정리한 뒤에 새 대화가 있어요/),
  ).toBeInTheDocument()
})

// 다음 페이지 조회가 실패해도 자동으로 계속 다시 부르지 않고, 사람이 '다시 불러오기'를 눌러야 이어 간다.
it('기록의 다음 페이지 조회가 실패하면 자동 재시도를 멈추고 다시 불러오기를 보인다', async () => {
  for (let index = 1; index <= 22; index++)
    await meetingsApi.addEntry('101', '901', { text: `추가 의견 ${index}` })
  const real = meetingsApi.entries.bind(meetingsApi)
  const spy = vi
    .spyOn(meetingsApi, 'entries')
    .mockImplementation((projectId, meetingId, query) =>
      query?.cursor
        ? // 실제 네트워크처럼 잠시 뒤에 실패해 '불러오는 중' 상태를 거치게 한다.
          new Promise((_, reject) =>
            setTimeout(() => reject(apiError(503, 'server_error')), 20),
          )
        : real(projectId, meetingId, query),
    )
  const user = userEvent.setup()
  renderPanel(true)
  expect(
    await screen.findByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
  const calls = spy.mock.calls.length
  await new Promise((resolve) => setTimeout(resolve, 300))
  expect(spy.mock.calls.length).toBe(calls)
  // 다시 불러오기를 누르면 조회를 다시 시작한다.
  spy.mockImplementation(real)
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(
    await screen.findByRole('log', { name: '회의 대화' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

it('올린 제안이 종료 직전에 거두어지면 끝내기 확인을 그대로 두고 다시 끝내라고 안내한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  vi.spyOn(meetingsApi, 'close').mockRejectedValueOnce(
    apiError(409, 'meeting_proposal_changed'),
  )
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  await user.click(
    screen.getByRole('button', { name: '회의 끝내고 변경 제안 만들기' }),
  )
  expect(await screen.findByText(/다시 누르면 새 제안/)).toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

it('비비 정리가 AI 오류로 실패하면 회의 기록은 그대로 두고 다시 요청하라고 안내한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const log = await screen.findByRole('log', { name: '회의 대화' })
  await within(log).findByText('TASK-002에서 만료 전용 안내는 뺄게요.')
  vi.spyOn(meetingsApi, 'summarize').mockRejectedValueOnce(
    apiError(502, 'ai_unavailable'),
  )
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  expect(
    await screen.findByText(/비비가 지금 정리하지 못했어요/),
  ).toBeInTheDocument()
  expect(
    within(log).queryByRole('region', { name: '바뀔 문서' }),
  ).not.toBeInTheDocument()
  vi.restoreAllMocks()
})

// 좁은 화면(lg 미만)에서도 대화 칸 안에서 스크롤해야 새 메시지로 내려간다. jsdom은 레이아웃을 계산하지 않아 높이 제한 클래스로 확인한다.
it('대화 칸은 lg 미만에서 높이를 화면 절반으로 묶고 lg부터는 창 높이에 맡긴다', async () => {
  renderPanel(true)
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(log).toHaveClass('max-h-[50vh]', 'lg:max-h-none', 'overflow-auto')
})

it('팀원은 대화만 하고 검토 요청은 팀장만 할 수 있다고 안내받는다', async () => {
  renderPanel(false)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  expect(
    screen.getByRole('button', { name: '비비에게 정리 요청' }),
  ).toBeDisabled()
  expect(screen.getByText('팀장만 요청할 수 있어요')).toBeInTheDocument()
})

// PNL-06 수정안 10-29: GitHub 리뷰어처럼 '관련자'에서 찾아 체크하면 바로 부르고, 빼기(×)는 팀장만(MTG-A14).
it('관련자 창에서 이름을 찾아 체크하면 바로 부르고, 팀장은 칩의 ×로 뺀다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(screen.getByRole('button', { name: '관련자' }))
  expect(await screen.findByRole('checkbox', { name: /이팀장/ })).toBeDisabled()
  await user.type(screen.getByLabelText('이름 찾기'), '박')
  await user.click(screen.getByRole('checkbox', { name: /박팀원/ }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '관련자를 추가했어요.',
  )
  await user.keyboard('{Escape}')
  await user.click(await screen.findByRole('button', { name: '박팀원 빼기' }))
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '박팀원 빼기' }),
    ).not.toBeInTheDocument(),
  )
  expect(
    screen.queryByRole('button', { name: '이팀장 빼기' }),
  ).not.toBeInTheDocument()
})

// 체크 상자는 눈에 안 보이므로(sr-only) 키보드 초점 테두리는 줄(label) 전체에 그린다. 체크 표시는 체크한 줄에만 보여서 거기에 두면 안 보인다.
it('관련자 창에서 체크하지 않은 팀원 줄도 키보드 초점 테두리를 줄 전체에 그린다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(screen.getByRole('button', { name: '관련자' }))
  const unchecked = await screen.findByRole('checkbox', { name: /박팀원/ })
  expect(unchecked).not.toBeChecked()
  expect(unchecked.closest('label')).toHaveClass(
    'has-focus-visible:ring-2',
    'has-focus-visible:ring-ring',
  )
})

// 10-29 수정안: 진행 중/끝난 회의, 안건 고정, 단계 표시, 끝난 회의는 읽기만
it('진행 중과 끝난 회의를 나누고, 안건과 단계를 보이며, 끝난 회의에는 쓸 수 없다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  const agenda = screen.getByRole('region', { name: '안건' })
  expect(
    within(agenda).getByText('만료된 초대를 어떻게 안내할지 정해 주세요'),
  ).toBeInTheDocument()
  expect(
    await within(agenda).findByRole('button', { name: /TASK-001/ }),
  ).toBeInTheDocument()
  const steps = screen.getByRole('list', { name: '회의 단계' })
  expect(within(steps).getByText('대화').closest('li')).toHaveAttribute(
    'aria-current',
    'step',
  )
  expect(screen.getByRole('heading', { name: /진행 중/ })).toHaveTextContent(
    '1',
  )
  await user.click(
    screen.getByRole('button', { name: /API 오류 응답 형식 통일/ }),
  )
  expect(
    await screen.findByText('끝난 회의라 메시지를 보낼 수 없어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText(/이팀장이 끝냄/)).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '비비에게 정리 요청' }),
  ).not.toBeInTheDocument()
})

// 캔버스 PNL-06-v2: 참여자 수는 비비를 포함하고, 칩마다 이 회의에서 맡은 작업을 보인다.
it('참여자 칩은 비비를 포함해 세고 사람마다 이 회의의 작업을 보인다', async () => {
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  const people = screen.getByRole('group', { name: '참여자' })
  expect(within(people).getByText('AI 팀장')).toBeInTheDocument()
  expect(await within(people).findByText('팀장 · TASK-001')).toBeInTheDocument()
  expect(within(people).getByText('TASK-002')).toBeInTheDocument()
  expect(within(people).getByText(/^참여자 \d+$/)).toHaveTextContent('참여자 4')
})

// live 모드: dev 백엔드에 회의 API가 아직 없으면 회의 칸만 '준비 중'으로 두고 창은 그대로 연다.
it('회의 API가 서버에 없으면 오류 대신 준비 중으로 보인다', async () => {
  vi.spyOn(meetingsApi, 'list').mockRejectedValue(
    new AxiosError('Not Found', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 404,
      statusText: 'Not Found',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
    }),
  )
  renderPanel(true)
  expect(
    await screen.findByText('이 기능은 서버에서 아직 준비 중이에요.'),
  ).toBeInTheDocument()
  expect(screen.queryByText(/정보를 불러오지 못했어요/)).not.toBeInTheDocument()
  vi.restoreAllMocks()
})
