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

it('팀장은 메시지를 보내고 비비 정리를 받은 뒤 회의 끝내기 확인을 연다', async () => {
  const user = userEvent.setup()
  renderPanel(true)
  await screen.findByRole('heading', { name: '초대 안내 문구 검토' })
  expect(
    screen.queryByRole('button', { name: '회의 만들기' }),
  ).not.toBeInTheDocument()
  const log = await screen.findByRole('log', { name: '회의 대화' })
  expect(
    await within(log).findByText(/작업이 부딪혀서 회의를 열었어요/),
  ).toBeInTheDocument()
  expect(
    await within(log).findByText('TASK-002에서 만료 전용 안내는 뺄게요.'),
  ).toBeInTheDocument()
  await user.type(
    screen.getByRole('textbox', { name: /메시지/ }),
    '기존 계약을 유지하고 설명을 보완해요.',
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await within(log).findByText('기존 계약을 유지하고 설명을 보완해요.')
  await user.click(screen.getByRole('button', { name: '비비에게 정리 요청' }))
  expect(
    await within(log).findByText(/회의 내용을 정리했어요 · 메시지 4개 기준/),
  ).toBeInTheDocument()
  expect(
    within(log).getAllByText('예시 템플릿 · 실제 AI 호출 없음'),
  ).toHaveLength(2)
  await user.click(screen.getByRole('button', { name: '회의 끝내기' }))
  expect(
    screen.getByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '회의 끝내고 변경 제안 만들기' }),
  ).toBeDisabled()
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
