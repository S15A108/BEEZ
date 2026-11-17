/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { meetingSchema } from '@/api/contracts/common'
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
  vi.restoreAllMocks()
  restore()
})

function renderPanel(isLeader: boolean) {
  return render(
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

// 팀장이 대화한 뒤 비비에게 정리를 요청한다(MTG-A11). 정리가 오면 대화 대신 비비 정리 단계가 보인다(S15P31A108-454).
async function requestSummary(user: ReturnType<typeof userEvent.setup>) {
  const log = await screen.findByRole('log', { name: '회의 대화' })
  await within(log).findByText('TASK-002에서 만료 전용 안내는 뺄게요.')
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  return screen.findByRole('region', { name: '바뀔 문서' })
}

// 지금 단계는 그 단계에만 있는 내용으로 확인한다(단계 표시줄은 사용자 요청 11-17로 없앰).
const onStep = {
  대화: () => screen.queryByRole('log', { name: '회의 대화' }),
  '비비 정리': () => screen.queryByRole('heading', { name: '비비 정리' }),
  '수정 초안': () =>
    screen.queryByRole('heading', { name: '변경 제안에 담을 것' }),
  '변경 제안': () => screen.queryByRole('region', { name: '회의 끝낸 결과' }),
}

it('팀장은 메시지를 보내고 비비 정리를 받으면 정리 단계로, 수정 초안 단계로 넘어간다', async () => {
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
  // 비비 정리 단계: 대화 대신 충돌 · 결정(정한 사람) · 바뀔 문서(지금/바뀐 뒤) · 할 일만 보인다.
  const changes = await screen.findByRole('region', { name: '바뀔 문서' })
  expect(onStep['비비 정리']()).toBeInTheDocument()
  expect(onStep.대화()).not.toBeInTheDocument()
  expect(
    screen.queryByRole('list', { name: '회의 단계' }),
  ).not.toBeInTheDocument()
  expect(
    within(changes).getByText(/요구사항 명세서 · \d+행/),
  ).toBeInTheDocument()
  // 바뀌는 문장은 정리 단계에서 누구나 본다(PR #328 회귀 검토, dev와 같게).
  expect(within(changes).getByText('지금')).toBeInTheDocument()
  expect(within(changes).getByText('바뀐 뒤')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: '충돌' })).toHaveTextContent(
    '만료된 초대를 어떻게 안내할지 정해 주세요',
  )
  expect(screen.getByRole('region', { name: '결정' })).toHaveTextContent(
    '기존 계약을 유지하고 설명을 보완해요.',
  )
  expect(screen.getByRole('region', { name: '할 일' })).toHaveTextContent(
    'TASK-002',
  )
  // 수정 초안 단계: 바뀌는 문장(지금/바뀐 뒤)과 끝내기 버튼 하나
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(onStep['수정 초안']()).toBeInTheDocument()
  const draft = screen.getByRole('region', { name: '바뀔 문서' })
  expect(within(draft).getByText('지금')).toBeInTheDocument()
  expect(within(draft).getByText('바뀐 뒤')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '변경 제안' })).toBeEnabled()
})

// 고칠 점이 입력돼 있어도 다음 단계로 넘어가면 수정 요청을 전송하지 않는다.
it('고칠 점을 적은 채 수정 초안으로 넘어가면 수정 요청을 보내지 않는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  const revise = vi.spyOn(meetingsApi, 'reviseSummary')
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '만료 안내 문구를 다시 정리해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(
    await screen.findByRole('heading', { name: '변경 제안에 담을 것' }),
  ).toBeInTheDocument()
  expect(revise).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

// MTG-A12: 고칠 점을 정리 수정 요청으로 보낸다. 새 정리가 최신이 되고 이전 정리는 기록에 남는다.
it('고칠 점으로 정리를 다시 요청하면 수정본이 최신 정리가 되고 이전 정리는 남는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  const addEntry = vi.spyOn(meetingsApi, 'addEntry')
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '만료는 14일로 정했어요.',
  )
  await user.click(screen.getByRole('button', { name: '수정 요청' }))
  expect(await screen.findByText('수정본')).toBeInTheDocument()
  expect(
    screen.getByText(/고칠 점: 만료는 14일로 정했어요\./),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    within(log).getByText('이전 정리', { exact: false }),
  ).toBeInTheDocument()
  expect(addEntry).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

it('팀장은 정리 뒤에도 합의를 대화로 남기고 그 기록까지 다시 정리할 수 있다', async () => {
  const user = userEvent.setup()
  const summarize = vi.spyOn(meetingsApi, 'summarize')
  const revise = vi.spyOn(meetingsApi, 'reviseSummary')
  renderPanel(true)
  await requestSummary(user)
  const feedback = screen.getByRole('region', { name: '비비 정리 수정' })
  expect(
    within(feedback).getByRole('textbox', { name: '비비에게 고칠 점' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  const log = await screen.findByRole('log', { name: '회의 대화' })
  const decision =
    '만료된 초대는 14일 뒤 다시 요청하도록 안내하기로 결정했어요.'
  await user.type(screen.getByRole('textbox', { name: '메시지' }), decision)
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(await within(log).findByText(decision)).toBeInTheDocument()
  expect(revise).not.toHaveBeenCalled()
  const entries = await meetingsApi.entries('101', '901', {})
  const entry = entries.items.find((item) => item.text === decision)
  expect(entry?.kind).toBe('TEXT')
  const staleMessage = /정리한 뒤에 새 대화가 있어요/
  expect(await screen.findByText(staleMessage)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  await waitFor(() =>
    expect(summarize).toHaveBeenLastCalledWith('101', '901', {
      through_entry_id: entry!.id,
    }),
  )
  await waitFor(() =>
    expect(screen.queryByText(staleMessage)).not.toBeInTheDocument(),
  )
})

// MTG-A13: 최신 정리로 회의를 끝내고 변경 제안을 만든다. 끝난 회의는 읽기만 하고 제안을 연다.
it('변경 제안을 누르면 회의를 끝내고 만든 제안을 변경 제안 창에서 열며, 돌아오면 끝난 회의로 보인다', async () => {
  const user = userEvent.setup()
  const view = renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  // 알릴 사람과, 누르면 회의가 끝난다는 것을 누르기 전에 보인다.
  expect(screen.getByText(/김동료에게 변경 제안을 알려요/)).toBeInTheDocument()
  expect(
    screen.getByText('누르면 변경 제안을 올리고 회의를 끝내요'),
  ).toBeInTheDocument()
  // 뒤로·다음 할 일은 위쪽 줄 하나뿐이다(사용자 요청 11-17).
  expect(screen.getAllByRole('button', { name: '뒤로 가기' })).toHaveLength(1)
  const close = vi.spyOn(meetingsApi, 'close')
  await user.click(screen.getByRole('button', { name: '변경 제안' }))
  // MTG-A13 응답의 제안을 변경 제안 창에서 골라 둔 채 연다(알림·링크와 같은 openPane + focus id).
  await waitFor(() => expect(useWorkspaceStore.getState().pane).toBe('changes'))
  const result = await close.mock.results[0].value
  expect(result.change_proposal_id).toBeTruthy()
  expect(useWorkspaceStore.getState().focusId).toBe(result.change_proposal_id)
  // 회의실로 돌아오면(창을 다시 열면) 끝난 회의는 '끝남'에서 보인다.
  view.unmount()
  act(() => useWorkspaceStore.getState().openPane('meetings'))
  renderPanel(true)
  await screen.findByRole('heading', { level: 3 })
  await user.click(screen.getByRole('button', { name: /^끝남/ }))
  const closedList = screen.getByRole('list', { name: '끝난 회의' })
  await user.click(
    within(closedList).getByRole('button', { name: /초대 안내 문구 검토/ }),
  )
  expect(
    await screen.findByRole('region', { name: '회의 끝낸 결과' }),
  ).toHaveTextContent('변경 제안을 올렸어요')
  // 끝난 회의 기록은 읽기만 한다.
  await user.click(screen.getByRole('button', { name: '회의 기록 보기' }))
  expect(
    await screen.findByText('끝난 회의라 메시지를 보낼 수 없어요.'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /결과 보기/ }))
  // 단계 표시줄 대신 결과에서 비비 정리로 간다(사용자 확인 11-17). 끝난 회의라 정리 수정 칸은 없다.
  await user.click(screen.getByRole('button', { name: '비비 정리 보기' }))
  expect(onStep['비비 정리']()).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '바뀔 문서' })).getByText(
      '바뀐 뒤',
    ),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '비비 정리 수정' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /결과 보기/ }))
  await user.click(screen.getByRole('button', { name: '변경 제안에서 보기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'changes',
    focusId: result.change_proposal_id,
  })
  vi.restoreAllMocks()
})

it('바꿀 문서가 없는 정리로 회의를 닫을 때 변경 제안을 예고하지 않고, 더보기에서 변경 없이 끝낼 수 있다', async () => {
  const summarize = meetingsApi.summarize
  const latestSummary = meetingsApi.latestSummary
  vi.spyOn(meetingsApi, 'summarize').mockImplementation(async (...args) => ({
    ...(await summarize(...args)),
    document_changes: [],
  }))
  vi.spyOn(meetingsApi, 'latestSummary').mockImplementation(
    async (...args) => ({
      ...(await latestSummary(...args)),
      document_changes: [],
    }),
  )
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await screen.findByText(/바뀔 문서가 없어요/)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(
    screen.getByText('확인한 정리를 기록에 남기고 회의를 닫아요.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '변경 제안' }),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '회의 끝내기' })).toBeEnabled()
  expect(screen.getByText('누르면 회의를 끝내요')).toBeInTheDocument()
  // 회의 중단(더보기): 바뀔 문서 없는 최신 정리면 변경 제안 없이 끝낸다.
  const close = vi.spyOn(meetingsApi, 'close')
  await user.click(screen.getByRole('button', { name: '회의 더보기' }))
  await user.click(
    await screen.findByRole('button', { name: '변경 없이 끝내기' }),
  )
  // 예시 서버는 자기 정리(바뀔 문서 있음)로 끝내 제안을 만들므로, 여기서는 지금 정리로 끝내기를 불렀는지까지 본다.
  await waitFor(() =>
    expect(close).toHaveBeenCalledWith('101', '901', {
      summary_id: expect.any(String),
    }),
  )
})

// 정리 뒤에 새 대화가 있으면 서버가 409 summary_outdated 로 막는다. 회의는 열린 채 정리를 다시 요청하게 한다.
it('정리 뒤에 새 대화가 있으면 끝내지 못하고 정리를 다시 요청하라고 안내한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  await meetingsApi.addEntry('101', '901', { text: '만료는 14일로 바꿔요.' })
  await user.click(screen.getByRole('button', { name: '변경 제안' }))
  expect(
    await screen.findByText(/정리한 뒤에 새 기록이 생겼거나/),
  ).toBeInTheDocument()
  // 회의는 열려 있고 대화 단계로 돌아와 정리를 다시 요청할 수 있다.
  expect(
    await screen.findByRole('button', { name: '비비에게 정리 요청' }),
  ).toBeEnabled()
  expect(onStep.대화()).toBeInTheDocument()
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
  // 새 화면(캐시 없음)에서 같은 회의를 열면 비비 정리 단계부터 보인다.
  client.clear()
  renderPanel(true)
  expect(
    await screen.findByRole('region', { name: '바뀔 문서' }),
  ).toBeInTheDocument()
  expect(onStep['비비 정리']()).toBeInTheDocument()
  expect(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(screen.getByRole('button', { name: '변경 제안' })).toBeEnabled()
})

it('정리 전에는 다음 단계로 갈 수 없고, 정리한 뒤에는 버튼으로 지난 단계를 오간다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  expect(
    await screen.findByRole('heading', { name: '초대 안내 문구 검토' }),
  ).toBeInTheDocument()
  // 정리가 없으면 정리·수정 초안 단계로 갈 수 없고 끝낼 수도 없다.
  expect(
    screen.queryByRole('button', { name: /비비 정리 보기|수정 초안 만들기/ }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: /회의 끝내기/ }),
  ).not.toBeInTheDocument()
  await requestSummary(user)
  // 노란 버튼은 다음 단계 하나다.
  expect(
    screen.queryByRole('button', { name: '비비에게 정리 요청' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  const log = await screen.findByRole('log', { name: '회의 대화' })
  // 대화에는 정리 전문 대신 정리 화면으로 가는 버튼만 남는다.
  expect(
    within(log).queryByRole('region', { name: '바뀔 문서' }),
  ).not.toBeInTheDocument()
  await user.click(
    within(log).getByRole('button', { name: '정리 화면에서 보기' }),
  )
  expect(onStep['비비 정리']()).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  // 제목 줄 오른쪽 끝의 다음 할 일 버튼(사용자 요청 11-17)
  await user.click(screen.getByRole('button', { name: '비비 정리 보기' }))
  expect(onStep['비비 정리']()).toBeInTheDocument()
})

// 끝난 회의도 기록은 그대로 읽는다. 목록과 머리의 표시는 '완료'다.
it('끝난 회의는 완료로 표시하고 실제로 나눈 대화를 그대로 보여 준다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(screen.getByRole('button', { name: /^끝남/ }))
  await user.click(
    screen.getByRole('button', { name: /API 오류 응답 형식 통일/ }),
  )
  // 끝난 회의는 결과 단계부터 보이고, 기록은 '회의 기록 보기'로 읽는다.
  await screen.findByRole('region', { name: '회의 끝낸 결과' })
  await user.click(screen.getByRole('button', { name: '회의 기록 보기' }))
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    await within(log).findByText(/error.code 하나로 맞추면/),
  ).toBeInTheDocument()
  expect(within(log).queryByText('아직 대화가 없어요.')).not.toBeInTheDocument()
  expect(screen.getAllByText('완료').length).toBeGreaterThan(0)
  expect(
    within(screen.getByRole('list', { name: '끝난 회의' })).queryByText('끝남'),
  ).not.toBeInTheDocument()
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
  // 가장 마지막 기록이 대화 맨 아래에 있다. 최신 정리 뒤에 말이 있어 대화 단계에 머문다.
  expect(await within(log).findByText('정리 뒤 마지막 말')).toBeInTheDocument()
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

// 메시지를 쓰는 동안 남은 기록 페이지가 자동으로 붙어도 쓰던 글은 사라지지 않는다. 보내기에 성공하면 비우고, 실패하면 남긴다.
it('쓰는 동안 기록 페이지가 더 붙어도 입력이 남고, 보내기에 성공해야 비워진다', async () => {
  for (let index = 1; index <= 22; index++)
    await meetingsApi.addEntry('101', '901', { text: `추가 의견 ${index}` })
  const real = meetingsApi.entries.bind(meetingsApi)
  vi.spyOn(meetingsApi, 'entries').mockImplementation(
    (projectId, meetingId, query) =>
      query?.cursor
        ? // 다음 페이지가 늦게 도착해, 쓰는 도중에 붙게 한다
          new Promise((resolve) =>
            setTimeout(() => resolve(real(projectId, meetingId, query)), 300),
          )
        : real(projectId, meetingId, query),
  )
  const user = userEvent.setup()
  renderPanel(true)
  const box = await screen.findByRole('textbox', { name: '메시지' })
  await user.type(box, '쓰는 중인 글')
  // 다음 페이지가 붙어 마지막 기록이 보일 때까지 기다린다
  const log = screen.getByRole('log', { name: '회의 대화' })
  await within(log).findByText('추가 의견 22', {}, { timeout: 3000 })
  expect(screen.getByRole('textbox', { name: '메시지' })).toHaveValue(
    '쓰는 중인 글',
  )
  // 보내기에 실패하면 글이 남는다
  vi.spyOn(meetingsApi, 'addEntry').mockRejectedValueOnce(
    apiError(500, 'server_error'),
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: '메시지' })).toHaveValue(
      '쓰는 중인 글',
    ),
  )
  // 성공하면 비워진다
  vi.mocked(meetingsApi.addEntry).mockRestore()
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: '메시지' })).toHaveValue(''),
  )
  vi.restoreAllMocks()
})

// 정리는 대화 단계의 '비비에게 정리 요청'으로만 만든다. 회의 수정 초안은 그 정리를 같은 캐시로 읽는다(정리가 두 개로 보이지 않는다).
it('회의 수정 초안은 위에서 요청한 정리를 그대로 쓰고 다시 정리해도 어긋나지 않는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  // 직접 만드는 수정 초안은 처음에 접혀 있다.
  expect(
    screen.queryByRole('region', { name: '회의 수정 초안' }),
  ).not.toBeInTheDocument()
  await user.click(
    screen.getByRole('button', { name: '직접 수정 초안 만들기' }),
  )
  const draft = await screen.findByRole('region', { name: '회의 수정 초안' })
  // 정리 단계에서 만든 정리의 결정을 여기서 고를 수 있다. 여기에는 정리를 만드는 버튼이 없다.
  expect(
    (await within(draft).findAllByRole('checkbox')).length,
  ).toBeGreaterThan(0)
  expect(
    within(draft).queryByRole('button', { name: /정리 만들기|다시 정리/ }),
  ).toBeNull()
  // 정리로 돌아가 다시 정리하면 수정 초안도 새 정리를 보고, '정리 이후 새 기록' 안내가 뜨지 않는다.
  await user.click(screen.getByRole('button', { name: '뒤로 가기' }))
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '할 일을 둘로 나눠 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '수정 요청' }))
  await screen.findByText('비비가 다시 정리했어요.')
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  const again = await screen.findByRole('region', { name: '회의 수정 초안' })
  expect(
    within(again).queryByText(/회의 정리 이후 새 기록이 있어요/),
  ).toBeNull()
})

// 정리하는 동안 올라온 대화는 정리 결과 기록보다 ID 가 작아도 정리에 들어가지 않았으므로 새 대화로 알린다.
it('정리하는 동안 올라온 대화도 정리한 뒤의 새 대화로 알린다', async () => {
  await meetingsApi.addEntry('101', '901', { text: '정리에 들어갈 말' })
  const first = (await meetingsApi.entries('101', '901', {})).items.at(-1)!
  await meetingsApi.addEntry('101', '901', { text: '정리 중에 올라온 말' })
  await meetingsApi.summarize('101', '901', { through_entry_id: first.id })
  renderPanel(true)
  expect(
    await screen.findByText(/정리한 뒤에 새 대화가 있어요/),
  ).toBeInTheDocument()
})

// 서버 한도는 기록 본문·고칠 점 모두 4,000자다. 넘으면 요청 전에 칸 아래에서 안내한다.
it('수정 요청은 4,000자를 넘으면 칸 아래에서 안내하고 보내지 않는다', async () => {
  const user = userEvent.setup()
  const revise = vi.spyOn(meetingsApi, 'reviseSummary')
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('textbox', { name: '비비에게 고칠 점' }))
  await user.paste('가'.repeat(4001))
  await user.click(screen.getByRole('button', { name: '수정 요청' }))
  expect(
    await screen.findByText(/4,000자까지 쓸 수 있어요/),
  ).toBeInTheDocument()
  expect(revise).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

it('메시지는 4,000자를 넘으면 칸 아래에서 안내하고 보내지 않는다', async () => {
  const user = userEvent.setup()
  const add = vi.spyOn(meetingsApi, 'addEntry')
  renderPanel(false)
  await user.click(await screen.findByRole('textbox', { name: '메시지' }))
  await user.paste('가'.repeat(4001))
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(
    await screen.findByText(/4,000자까지 쓸 수 있어요/),
  ).toBeInTheDocument()
  expect(add).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})

// 새 정리를 받은 직후 기록 목록을 다시 읽기 전에도 정리 카드는 글로 바뀌지 않고 입력줄도 그대로다.
it('수정 요청이 성공하면 기록 목록을 다시 읽기 전에도 새 정리 카드를 바로 보인다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  // 이후의 기록 목록 재조회는 끝나지 않게 막는다
  vi.spyOn(meetingsApi, 'entries').mockImplementation(
    () => new Promise(() => undefined),
  )
  await user.type(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
    '두 번째 결정만 남겨 주세요',
  )
  await user.click(screen.getByRole('button', { name: '수정 요청' }))
  expect(await screen.findByText('수정본')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: '바뀔 문서' })).toBeInTheDocument()
  expect(
    screen.getByRole('textbox', { name: '비비에게 고칠 점' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

// PR #328 회귀 검토: 보고 있는 단계는 서버 상태가 바뀌어도 자동으로 옮기지 않는다. 낡은 정리로 끝내는 것만 막는다.
it('수정 초안을 보는 중에 새 대화가 생겨도 화면을 옮기지 않아 저장 전 초안이 남고, 끝내기만 막는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  await user.click(
    screen.getByRole('button', { name: '직접 수정 초안 만들기' }),
  )
  const draft = await screen.findByRole('region', { name: '회의 수정 초안' })
  const [first] = await within(draft).findAllByRole('checkbox')
  await user.click(first)
  expect(first).toBeChecked()
  await meetingsApi.addEntry('101', '901', { text: '정리 뒤의 새 말' })
  await client.invalidateQueries()
  expect(
    await screen.findByText(/정리한 뒤에 새 대화가 있어요. 대화로 돌아가/),
  ).toBeInTheDocument()
  // 수정 초안 화면과 고르던 내용이 그대로 있고, 낡은 정리로는 끝낼 수 없다.
  expect(screen.getByRole('region', { name: '회의 수정 초안' })).toBe(draft)
  expect(first).toBeChecked()
  expect(screen.getByRole('button', { name: '변경 제안' })).toBeDisabled()
  // 대화로 돌아가 정리를 다시 요청하면 다시 끝낼 수 있다
  await user.click(screen.getByRole('button', { name: '뒤로 가기' }))
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  await screen.findByRole('region', { name: '비비 정리 수정' })
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(screen.getByRole('button', { name: '변경 제안' })).toBeEnabled()
})

// 정리 전용 오류 문구는 정리·수정 요청·끝내기에만 쓴다. 메시지 보내기 실패에는 붙지 않는다.
it('메시지 보내기가 400으로 실패해도 정리 오류 문구를 보이지 않는다', async () => {
  const user = userEvent.setup()
  vi.spyOn(meetingsApi, 'addEntry').mockRejectedValueOnce(
    apiError(400, 'validation_failed'),
  )
  renderPanel(false)
  await user.type(
    await screen.findByRole('textbox', { name: '메시지' }),
    '안녕',
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: '메시지' })).toHaveValue('안녕'),
  )
  expect(screen.queryByText(/제출 규칙에 맞지 않아요/)).not.toBeInTheDocument()
  vi.restoreAllMocks()
})

// S15P31A108-457: 정리를 요청하면 아래 입력칸이 '비비 정리 수정'으로 바뀌고, 정리는 대화 칸이 아니라 정리 단계에서 읽는다.
it('정리 단계의 아래 입력칸은 메시지 대신 비비 정리 수정이다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  expect(
    screen.queryByRole('textbox', { name: '메시지' }),
  ).not.toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '비비 정리 수정' })).getByRole(
      'textbox',
      { name: '비비에게 고칠 점' },
    ),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  expect(
    await screen.findByRole('textbox', { name: '메시지' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('textbox', { name: '비비에게 고칠 점' }),
  ).not.toBeInTheDocument()
})

// 비비가 정리하는 동안에는 정리 요청을 다시 보낼 수 없다(AI 가 두 번 불리지 않게).
it('비비가 정리하는 동안에는 정리 요청을 다시 보낼 수 없고, 정리가 오면 수정 요청을 쓸 수 있다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const log = await screen.findByRole('log', { name: '회의 대화' })
  await within(log).findByText('TASK-002에서 만료 전용 안내는 뺄게요.')
  const real = meetingsApi.summarize.bind(meetingsApi)
  vi.spyOn(meetingsApi, 'summarize').mockImplementation(
    (...args) =>
      new Promise((resolve) => setTimeout(() => resolve(real(...args)), 300)),
  )
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  expect(
    screen.getByRole('button', { name: '비비에게 정리 요청' }),
  ).toBeDisabled()
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '수정 요청' })).toBeEnabled(),
  )
  expect(meetingsApi.summarize).toHaveBeenCalledTimes(1)
  vi.restoreAllMocks()
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
  // 기록을 다 읽지 못했으면 읽힌 곳까지만 정리하게 되므로 정리 요청을 막는다.
  expect(
    screen.getByRole('button', { name: '비비에게 정리 요청' }),
  ).toBeDisabled()
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

it('올린 제안이 종료 직전에 거두어지면 수정 초안 단계에 머물고 다시 끝내라고 안내한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  vi.spyOn(meetingsApi, 'close').mockRejectedValueOnce(
    apiError(409, 'meeting_proposal_changed'),
  )
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  await user.click(screen.getByRole('button', { name: '변경 제안' }))
  expect(await screen.findByText(/다시 누르면 새 제안/)).toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '변경 제안에 담을 것' }),
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
    screen.queryByRole('region', { name: '바뀔 문서' }),
  ).not.toBeInTheDocument()
  expect(
    within(log).getByText('TASK-002에서 만료 전용 안내는 뺄게요.'),
  ).toBeInTheDocument()
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

// 10-29 수정안: 진행 중/끝난 회의, 안건 고정, 단계 표시, 끝난 회의는 읽기만. 11-17: 목록 필터, 안건 처음부터 펼침
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
  // 안내 전문은 처음부터 펼쳐 둔다(S15P31A108-457). 접기·펼치기는 상자 버튼이다.
  const guidance = within(agenda).getByRole('region', {
    name: '비비의 충돌 안내',
  })
  expect(guidance).toHaveTextContent('회의에서 정할 내용')
  expect(within(guidance).queryByRole('table')).not.toBeInTheDocument()
  await user.click(within(agenda).getByRole('button', { name: '접기' }))
  expect(
    within(agenda).queryByRole('region', { name: '비비의 충돌 안내' }),
  ).not.toBeInTheDocument()
  expect(onStep.대화()).toBeInTheDocument()
  // 목록은 진행 중·끝남 필터로 나눈다(S15P31A108-457).
  expect(screen.getByRole('button', { name: /^진행 중/ })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(screen.getByRole('button', { name: /^진행 중/ })).toHaveTextContent(
    '1',
  )
  expect(
    screen.queryByRole('button', { name: /API 오류 응답 형식 통일/ }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /^끝남/ }))
  await user.click(
    screen.getByRole('button', { name: /API 오류 응답 형식 통일/ }),
  )
  await user.click(
    await screen.findByRole('button', { name: '회의 기록 보기' }),
  )
  expect(
    await screen.findByText('끝난 회의라 메시지를 보낼 수 없어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText(/이팀장이 끝냄/)).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '비비에게 정리 요청' }),
  ).not.toBeInTheDocument()
})

it('회의가 열리면 두 계획의 충돌 값과 이유를 표시하고 근거 버전과 작업을 바로 연다', async () => {
  const get = meetingsApi.get
  vi.spyOn(meetingsApi, 'get').mockImplementation(async (...args) =>
    meetingSchema.parse({
      ...(await get(...args)),
      conflict_guidance: {
        validation_id: '700',
        tasks: [
          { task_id: '502', title: '화면 담당', revision_no: 2 },
          { task_id: '501', title: 'API 담당', revision_no: 3 },
        ],
        conflicts: [
          {
            message: '두 작업이 같은 API 계약을 변경하려고 해요.',
            design_item: {
              id: '115',
              version_id: '126',
              key: 'API-01',
              title: 'GET /users',
              document_id: '203',
              document_version_id: '213',
              location: '5행',
            },
            differences: [
              {
                path: 'response.status',
                first_value: 'string',
                second_value: 'integer',
              },
              { path: 'response.message', first_value: 'string' },
            ],
          },
        ],
      },
    }),
  )
  renderPanel(true)
  // 안내는 처음부터 펼쳐져 있다(S15P31A108-457).
  const guidance = await screen.findByRole('region', {
    name: '비비의 충돌 안내',
  })
  expect(guidance).toHaveTextContent('API-01 · GET /users')
  expect(guidance).toHaveTextContent(
    '두 작업이 같은 API 계약을 변경하려고 해요.',
  )
  expect(guidance).toHaveTextContent(
    '누가 설계 변경을 맡을지와 표에 나온 항목의 최종 규격',
  )
  expect(guidance).toHaveTextContent('작업 범위를 나누고 회의 대화에 남겨요.')
  expect(guidance).toHaveTextContent('승인·발행하면 공용 문서에 반영돼요.')
  const table = within(guidance).getByRole('table')
  expect(
    within(table).getByRole('columnheader', { name: /화면 담당 · 계획 r2/ }),
  ).toBeInTheDocument()
  expect(
    within(table).getByRole('columnheader', { name: /API 담당 · 계획 r3/ }),
  ).toBeInTheDocument()
  const status = within(table).getByRole('row', { name: /response\.status/ })
  expect(
    within(status)
      .getAllByRole('cell')
      .map((cell) => cell.textContent),
  ).toEqual(['string', 'integer'])
  expect(
    within(table).getByRole('row', { name: /response\.message/ }),
  ).toHaveTextContent('제안 값 없음')
  const user = userEvent.setup()
  const agenda = screen.getByRole('region', { name: '안건' })
  await user.click(
    await within(agenda).findByRole('button', { name: /TASK-002/ }),
  )
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: '502',
  })
  act(() => useWorkspaceStore.getState().openPane('meetings'))
  // 회의실을 다시 열어도 안내는 펼쳐져 있다.
  const documentLink = await screen.findByRole('button', {
    name: /근거 문서.*5행/,
  })
  await user.click(documentLink)
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'documents',
    focusId: 'version:203:213',
  })
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

// 화면 검수 11-16(S15P31A108-438): 참여자를 제목 줄로 올려 입력줄에 가리지 않게 하고, 넓은 화면에서는 창 대신 대화 칸만 스크롤한다.
// jsdom은 레이아웃을 계산하지 않아 순서와 높이 클래스로 확인한다(실제 높이는 E2E 회의 시나리오에서 본다).
it('참여자는 안건보다 앞 제목 줄에 있고, 처음부터 펼친 안건은 대화 칸과 남는 높이를 나눠 안에서 스크롤한다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const title = await screen.findByRole('heading', {
    name: '초대 안내 문구 검토',
  })
  const people = screen.getByRole('group', { name: '참여자' })
  const agenda = screen.getByRole('region', { name: '안건' })
  const log = await screen.findByRole('log', { name: '회의 대화' })
  const after = (a: Element, b: Element) =>
    !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
  expect(after(title, people)).toBe(true)
  expect(after(people, agenda)).toBe(true)
  expect(after(agenda, log)).toBe(true)
  // 펼친 안건은 대화 칸과 높이를 나누되 최소 높이를 지키고(PR #306 재검토), 접은 '정할 것' 한 줄은 줄어들지 않는다.
  expect(agenda).toHaveClass(
    'lg:flex-1',
    'lg:max-h-max',
    'lg:min-h-32',
    'lg:overflow-y-auto',
  )
  await user.click(within(agenda).getByRole('button', { name: '접기' }))
  expect(agenda).not.toHaveClass('lg:flex-1')
  expect(log).toHaveClass('flex-1', 'lg:min-h-28')
})

// PR #306 재검토: 역할·작업은 칩에 펼치지 않지만 마우스와 키보드 초점 모두로 툴팁을 열어 볼 수 있다.
it('참여자 칩에 마우스를 올리거나 키보드로 초점을 두면 역할과 작업을 툴팁으로 보인다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const people = await screen.findByRole('group', { name: '참여자' })
  await within(people).findByText('TASK-002')
  // Tab으로 김동료 칩까지 간다(초점이 body면 body 글자에 이름이 섞여 있어 건너뛴다).
  const onChip = () =>
    document.activeElement !== document.body &&
    !!document.activeElement?.textContent?.includes('김동료')
  for (let step = 0; step < 40 && !onChip(); step++) await user.tab()
  expect(onChip()).toBe(true)
  expect(await screen.findByRole('tooltip')).toHaveTextContent(
    '김동료 · TASK-002',
  )
  // jsdom은 닫힘 애니메이션이 끝나지 않아 앞 툴팁이 남을 수 있어, 열린 툴팁들 중에서 찾는다.
  await user.hover(within(people).getByText('이팀장'))
  await waitFor(() =>
    expect(
      screen.getAllByRole('tooltip').map((tip) => tip.textContent),
    ).toContain('이팀장 · 팀장 · TASK-001'),
  )
})

// PR #306 리뷰: 역할은 툴팁으로 접어도, 다른 곳에 없는 참여 상태(참여 요청·불참)는 칩에 글자로 남긴다.
it('관련자로 부른 사람의 칩에는 참여 요청이 글자로 보인다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(screen.getByRole('button', { name: '관련자' }))
  await user.click(await screen.findByRole('checkbox', { name: /박팀원/ }))
  await user.keyboard('{Escape}')
  const people = screen.getByRole('group', { name: '참여자' })
  expect(await within(people).findByText('참여 요청')).not.toHaveClass(
    'sr-only',
  )
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

// S15P31A108-457: 목록의 '작업 충돌'은 빨간색, 비비 말풍선은 노란 테두리, 내 말은 노란 바탕이다.
// jsdom은 색을 합성하지 않아 클래스로 확인한다(실제 모습은 PR 사진).
it('목록의 작업 충돌은 빨간색이고, 비비 말풍선은 테두리로, 내 말은 노란 바탕으로 보인다', async () => {
  renderPanel(true)
  const list = await screen.findByRole('list', { name: '진행 중 회의' })
  expect(within(list).getByText('작업 충돌')).toHaveClass(
    'text-destructive-ink',
  )
  const log = await screen.findByRole('log', { name: '회의 대화' })
  const bee = await within(log).findByText(/작업이 부딪혀서 회의를 열었어요/)
  expect(bee.closest('article')).toHaveClass('border-2', 'border-primary')
  const mine = await within(log).findByText(
    '그렇게 가요. 요구사항에 이 문장을 넣어 주세요.',
  )
  expect(mine).toHaveClass('bg-primary')
  expect(mine).not.toHaveClass('bg-[#2c291f]')
})

// S15P31A108-457: 메시지 칸은 채팅 앱처럼 여러 줄이다. Enter로 보내고 Shift+Enter로 줄을 바꾼다.
it('메시지 칸은 Shift+Enter로 줄을 바꾸고 Enter로 보낸다', async () => {
  const user = userEvent.setup()
  const add = vi.spyOn(meetingsApi, 'addEntry')
  renderPanel(false)
  const box = await screen.findByRole('textbox', { name: '메시지' })
  expect(box.tagName).toBe('TEXTAREA')
  await user.type(box, '첫 줄{Shift>}{Enter}{/Shift}둘째 줄')
  expect(box).toHaveValue('첫 줄\n둘째 줄')
  expect(add).not.toHaveBeenCalled()
  await user.type(box, '{Enter}')
  await waitFor(() =>
    expect(add).toHaveBeenCalledWith('101', '901', {
      text: '첫 줄\n둘째 줄',
    }),
  )
  await waitFor(() => expect(box).toHaveValue(''))
  vi.restoreAllMocks()
})

it('정리 전이나 바뀔 문서가 있는 정리에서는 회의 중단이 막히고 이유를 보인다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  await user.click(screen.getByRole('button', { name: '회의 더보기' }))
  expect(
    await screen.findByRole('button', { name: '변경 없이 끝내기' }),
  ).toBeDisabled()
  expect(
    screen.getByText(/비비 정리가 있어야 회의를 끝낼 수 있어요/),
  ).toBeInTheDocument()
  await user.keyboard('{Escape}')
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '회의 더보기' }))
  expect(
    await screen.findByRole('button', { name: '변경 없이 끝내기' }),
  ).toBeDisabled()
  expect(screen.getByText(/정리에 바뀔 문서가 있어/)).toBeInTheDocument()
})

it('팀원에게는 회의 중단 메뉴가 없다', async () => {
  renderPanel(false)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  expect(
    screen.queryByRole('button', { name: '회의 더보기' }),
  ).not.toBeInTheDocument()
})

// 사용자 요청 11-17: 대화 단계의 다음 할 일(비비에게 정리 요청)은 제목 줄 오른쪽 끝에 있고, 참여자는 그 아래 줄이다.
// 목록과 머리 정보에는 '비비가 엶'·'비비가 열었어요'를 쓰지 않는다.
it('정리 요청 버튼은 제목 줄에 있고 참여자는 그 아래 줄이며, 비비가 열었다는 문구는 없다', async () => {
  renderPanel(true)
  const title = await screen.findByRole('heading', {
    name: '초대 안내 문구 검토',
  })
  const request = screen.getByRole('button', { name: '비비에게 정리 요청' })
  const people = screen.getByRole('group', { name: '참여자' })
  const log = await screen.findByRole('log', { name: '회의 대화' })
  const after = (a: Element, b: Element) =>
    !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
  expect(title.parentElement?.parentElement).toContainElement(request)
  expect(after(request, people)).toBe(true)
  expect(after(request, log)).toBe(true)
  expect(
    screen.queryByText(/비비가 엶|비비가 열었어요/),
  ).not.toBeInTheDocument()
})

// PR #328 리뷰 P2: 새 대화가 없어도 정리 뒤 다른 제안이 문서 기준을 발행하면 서버가 끝내기를 409 summary_outdated 로 거절한다.
// 그 정리는 '준비 완료'에서 빠져, 제목 줄에 '비비 정리 보기' 대신 '비비에게 정리 요청'이 다시 나오고 정리를 다시 받을 수 있다.
it('기준이 바뀌어 끝내기가 거절된 정리는 새 대화가 없어도 다시 정리를 요청할 수 있다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  const close = vi
    .spyOn(meetingsApi, 'close')
    .mockRejectedValueOnce(apiError(409, 'summary_outdated'))
  const summarize = vi.spyOn(meetingsApi, 'summarize')
  await user.click(screen.getByRole('button', { name: '변경 제안' }))
  expect(
    await screen.findByText(/정리한 뒤에 새 기록이 생겼거나 기준이 바뀌었어요/),
  ).toBeInTheDocument()
  expect(close).toHaveBeenCalledTimes(1)
  // 새 대화는 없다. 대화 단계로 돌아오고 거절된 정리로는 다시 갈 수 없다.
  expect(
    await screen.findByRole('log', { name: '회의 대화' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '비비 정리 보기' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText(/정리한 뒤에 기준이 바뀌어 이 정리로는 끝낼 수 없어요/),
  ).toBeInTheDocument()
  const request = screen.getByRole('button', { name: '비비에게 정리 요청' })
  expect(request).toBeEnabled()
  await user.click(request)
  await waitFor(() => expect(summarize).toHaveBeenCalledTimes(1))
  // 새 정리는 다시 '준비 완료'라 정리 단계로 넘어가고 끝낼 수 있다.
  expect(
    await screen.findByRole('heading', { name: '비비 정리' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  expect(screen.getByRole('button', { name: '변경 제안' })).toBeEnabled()
  vi.restoreAllMocks()
})

// PR #328 회귀 검토: 대화를 쓰는 중에 팀장의 정리가 와도 화면이 정리로 넘어가지 않아 쓰던 글이 남는다. 새 정리는 제목 줄의
// '비비 정리 보기'로 알린다. 직접 정리 화면에 갔다 와도 쓰던 글이 남는다.
it('메시지를 쓰는 중에 정리가 와도 대화 화면과 쓰던 글이 남는다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  const box = await screen.findByRole('textbox', { name: '메시지' })
  await screen.findByRole('button', { name: '비비에게 정리 요청' })
  await user.type(box, '쓰는 중인 글')
  const entries = await meetingsApi.entries('101', '901', {})
  await meetingsApi.summarize('101', '901', {
    through_entry_id: entries.items.at(-1)!.id,
  })
  await client.invalidateQueries()
  expect(
    await screen.findByRole('button', { name: '비비 정리 보기' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('log', { name: '회의 대화' })).toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '메시지' })).toHaveValue(
    '쓰는 중인 글',
  )
  await user.click(screen.getByRole('button', { name: '비비 정리 보기' }))
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  expect(await screen.findByRole('textbox', { name: '메시지' })).toHaveValue(
    '쓰는 중인 글',
  )
})

// PR #328 회귀 검토: 보내는 중에 Enter를 한 번 더 눌러도 한 번만 보낸다(버튼이 꺼진 것과 같게).
it('메시지와 정리 수정 요청은 보내는 중에 Enter를 다시 눌러도 한 번만 보낸다', async () => {
  const user = userEvent.setup()
  const realAdd = meetingsApi.addEntry.bind(meetingsApi)
  const add = vi
    .spyOn(meetingsApi, 'addEntry')
    .mockImplementation(
      (...args) =>
        new Promise((resolve) =>
          setTimeout(() => resolve(realAdd(...args)), 300),
        ),
    )
  renderPanel(true)
  const box = await screen.findByRole('textbox', { name: '메시지' })
  await user.type(box, '한 번만 가야 하는 말')
  await user.keyboard('{Enter}{Enter}')
  await waitFor(() => expect(box).toHaveValue(''))
  expect(add).toHaveBeenCalledTimes(1)
  // 정리 수정 요청(MTG-A12)도 보내는 동안에는 Enter를 무시한다.
  const real = meetingsApi.reviseSummary.bind(meetingsApi)
  const revise = vi
    .spyOn(meetingsApi, 'reviseSummary')
    .mockImplementation(
      (...args) =>
        new Promise((resolve) => setTimeout(() => resolve(real(...args)), 300)),
    )
  add.mockRestore()
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  const feedback = await screen.findByRole('textbox', {
    name: '비비에게 고칠 점',
  })
  await user.type(feedback, '할 일을 둘로 나눠 주세요.')
  await user.keyboard('{Enter}{Enter}')
  expect(await screen.findByText('수정본')).toBeInTheDocument()
  expect(revise).toHaveBeenCalledTimes(1)
  vi.restoreAllMocks()
})

// PR #328 회귀 검토: 팀장이 아닌 참여자도 비비 정리에서 바뀌는 문장(지금/바뀐 뒤)을 본다.
it('팀장이 아닌 참여자도 비비 정리에서 바뀌는 문장을 본다', async () => {
  const entries = await meetingsApi.entries('101', '901', {})
  await meetingsApi.summarize('101', '901', {
    through_entry_id: entries.items.at(-1)!.id,
  })
  // 최신 정리가 있으면 비비 정리 화면부터 열린다.
  renderPanel(false)
  const changes = await screen.findByRole('region', { name: '바뀔 문서' })
  expect(within(changes).getByText('지금')).toBeInTheDocument()
  expect(within(changes).getByText('바뀐 뒤')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '수정 초안 만들기' }),
  ).not.toBeInTheDocument()
})

// PR #328 리뷰: 정리가 있어도 팀장은 대화 화면에서 처음부터 다시 정리를 받을 수 있다(dev처럼).
it('정리가 있어도 팀장은 대화 화면에서 다시 정리를 요청할 수 있다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  const summarize = vi.spyOn(meetingsApi, 'summarize')
  const again = await screen.findByRole('button', { name: '다시 정리 요청' })
  expect(again).toBeEnabled()
  await user.click(again)
  await waitFor(() => expect(summarize).toHaveBeenCalledTimes(1))
  expect(
    await screen.findByRole('heading', { name: '비비 정리' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

it('팀원에게는 다시 정리 요청이 없다', async () => {
  const entries = await meetingsApi.entries('101', '901', {})
  await meetingsApi.summarize('101', '901', {
    through_entry_id: entries.items.at(-1)!.id,
  })
  const user = userEvent.setup()
  renderPanel(false)
  await screen.findByRole('heading', { name: '비비 정리' })
  await user.click(screen.getByRole('button', { name: '대화로 돌아가기' }))
  expect(
    await screen.findByRole('button', { name: '비비 정리 보기' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '다시 정리 요청' }),
  ).not.toBeInTheDocument()
})

// PR #328 확인 요청: 제안 없이 회의를 끝내면(바뀔 문서 없음·회의 중단) 회의실에 머물며 바로 결과가 보인다.
// 끝낸 직후에는 회의 정보를 다시 읽기 전이라 단계를 옛 상태로 고정하지 않고 결과 단계로 정한다.
it('제안 없이 회의를 끝내면 바로 끝낸 결과가 보인다', async () => {
  // 예시 서버는 자기 정리(바뀔 문서 있음)로 제안을 만들므로, 응답과 회의 정보에서 제안을 빼 '변경 없이 끝낸' 경우를 만든다.
  // 실제 네트워크처럼 끝낸 뒤 회의 정보는 늦게 온다.
  const realClose = meetingsApi.close.bind(meetingsApi)
  const realGet = meetingsApi.get.bind(meetingsApi)
  let closed = false
  vi.spyOn(meetingsApi, 'close').mockImplementation(async (...args) => {
    const result = await realClose(...args)
    closed = true
    return { ...result, change_proposal_id: undefined }
  })
  vi.spyOn(meetingsApi, 'get').mockImplementation(async (...args) => {
    const meeting = {
      ...(await realGet(...args)),
      change_proposal_id: undefined,
    }
    if (closed) await new Promise((resolve) => setTimeout(resolve, 300))
    return meeting
  })
  const user = userEvent.setup()
  renderPanel(true)
  await requestSummary(user)
  await user.click(screen.getByRole('button', { name: '수정 초안 만들기' }))
  await user.click(screen.getByRole('button', { name: '변경 제안' }))
  expect(
    await screen.findByRole('region', { name: '회의 끝낸 결과' }),
  ).toHaveTextContent('변경 제안은 만들지 않았어요')
  expect(useWorkspaceStore.getState().pane).toBe('meetings')
  vi.restoreAllMocks()
})
