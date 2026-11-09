/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MISSING_API_MESSAGE } from '@/api/error'
import { agentsApi, authApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { useAgentCheckStore } from './agentCheckStore'
import { AgentsPanel } from './AgentsPanel'

let restore: () => void
beforeEach(async () => {
  useAgentCheckStore.setState({ checkedMemberId: null })
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

it('토큰을 한 번만 보여 주고, 연결 확인에 보고가 없으면 연결 실패와 할 일을 알려 준다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByRole('heading', { name: 'Claude Code에 BEEZ 추가' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '토큰 발급' }))
  expect(
    await screen.findByText('이 화면을 닫으면 토큰 값을 다시 볼 수 없어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText(/이 프로젝트에서만 써요/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  expect(await screen.findByText(/^연결 실패/)).toBeInTheDocument()
  expect(
    screen.getByText(/토큰과 MCP 주소를 확인하고 Claude Code를 다시 시작/),
  ).toBeInTheDocument()
})

// S15P31A108-338: 3단계 요약만으로는 처음 연결하는 팀원이 막혀 도움말 페이지로 잇는다. 같은 탭이면 설정 창이 닫힌다.
it('연결 미확인·연결 실패 상태 줄과 2단계 명령 아래에 새 탭 도움말 링크와 한 연결 안내를 보여 준다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
  // 예시 데이터의 내 에이전트는 보고가 끊겨 연결 미확인이다.
  const help = await screen.findByRole('link', { name: '연결 도움말' })
  expect(help).toHaveAttribute('href', '/help/agent-connection#check')
  expect(help).toHaveAttribute('target', '_blank')
  expect(
    screen.getByText(/같은 계정의 연결은 프로젝트마다 하나만 살아 있어요/),
  ).toHaveTextContent('나중 연결이 이기고')
  expect(
    screen.getByRole('link', { name: '자세한 연결 방법' }),
  ).toHaveAttribute('href', '/help/agent-connection#add')
  await user.click(screen.getByRole('button', { name: '연결 확인' }))
  await waitFor(() =>
    expect(screen.getByRole('link', { name: '연결 도움말' })).toHaveAttribute(
      'href',
      '/help/agent-connection#troubleshooting',
    ),
  )
})

it('활성 토큰이 5개면 토큰 이름 폼에 한도와 할 일을 알려 준다', async () => {
  for (let index = 0; index < 5; index++)
    await agentsApi.issueToken('101', { label: `PC ${index}` })
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('PC 0')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '토큰 발급' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '활성 토큰은 프로젝트마다 5개까지예요. 쓰지 않는 토큰을 폐기한 뒤 다시 발급해 주세요.',
  )
})

// API 없음: AGT-A04·A05(-178 구현 전). 404여도 토큰 발급은 그대로 되고, 연결 안내는 발급 응답으로 보여 준다.
it('연결 안내·연결 현황이 404여도 토큰을 발급하고 발급 응답으로 연결 안내를 보여 준다', async () => {
  const missing = () =>
    Promise.reject(
      new AxiosError('Not Found', 'ERR_BAD_REQUEST', undefined, undefined, {
        status: 404,
        statusText: 'Not Found',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
      }),
    )
  vi.spyOn(agentsApi, 'connectionGuide').mockImplementation(missing)
  vi.spyOn(agentsApi, 'list').mockImplementation(missing)
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText('연결 상태 확인은 준비 중이에요'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('토큰을 발급하면 연결 안내가 나와요.'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '토큰 발급' }))
  expect(
    await screen.findByRole('button', { name: 'MCP 주소 복사' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: 'CLAUDE.md 내용 복사' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

// PR #195 리뷰: refetch()는 실패해도 reject하지 않는다. 조회를 못 했으면 연결 실패가 아니라 확인하지 못한 것이다.
const serverError = () =>
  Promise.reject(
    new AxiosError('Server Error', 'ERR_BAD_RESPONSE', undefined, undefined, {
      status: 500,
      statusText: 'Internal Server Error',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'internal_error', message: '서버 오류' } },
    }),
  )
const notConnected = {
  items: [
    {
      id: '51',
      member_id: '21',
      connected: false,
      status_check_required: false,
    },
  ],
  has_more: false,
}
const mountPanel = () =>
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
const checkButton = (name: string) => screen.findByRole('button', { name })

it('첫 조회가 실패하면 확인하지 못했다고 알리고, 다시 확인해 성공하면 결과를 보인다', async () => {
  const list = vi.spyOn(agentsApi, 'list').mockImplementation(serverError)
  const user = userEvent.setup()
  mountPanel()
  await screen.findByText('연결 상태를 확인하지 못했어요')
  expect(useAgentCheckStore.getState().checkedMemberId).toBeNull()
  list.mockResolvedValue(notConnected)
  await user.click(await checkButton('다시 확인'))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
  expect(useAgentCheckStore.getState().checkedMemberId).toBe('21')
  vi.restoreAllMocks()
})

it('정상 조회 뒤 재조회만 실패하면 연결 실패로 저장하지 않고 앞선 결과도 지운다', async () => {
  const list = vi.spyOn(agentsApi, 'list').mockResolvedValue(notConnected)
  const user = userEvent.setup()
  mountPanel()
  await user.click(await checkButton('연결 확인'))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결 실패/),
  )
  list.mockImplementation(serverError)
  await user.click(await checkButton('다시 확인'))
  await screen.findByText('연결 상태를 확인하지 못했어요')
  expect(screen.getByRole('status')).not.toHaveTextContent('연결 실패')
  expect(useAgentCheckStore.getState().checkedMemberId).toBeNull()
  vi.restoreAllMocks()
})

it('재조회가 실패한 뒤 다시 확인해 연결을 받으면 연결됨을 보인다', async () => {
  const list = vi.spyOn(agentsApi, 'list').mockResolvedValue(notConnected)
  const user = userEvent.setup()
  mountPanel()
  await checkButton('연결 확인')
  list.mockImplementation(serverError)
  await user.click(await checkButton('연결 확인'))
  await screen.findByText('연결 상태를 확인하지 못했어요')
  list.mockResolvedValue({
    items: [{ ...notConnected.items[0], connected: true }],
    has_more: false,
  })
  await user.click(await checkButton('다시 확인'))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(/^연결됨/),
  )
  vi.restoreAllMocks()
})

it('연결 현황 API가 없으면(404) 준비 중으로 두고 확인 결과를 저장하지 않는다', async () => {
  vi.spyOn(agentsApi, 'list').mockImplementation(() =>
    Promise.reject(
      new AxiosError('Not Found', 'ERR_BAD_REQUEST', undefined, undefined, {
        status: 404,
        statusText: 'Not Found',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
      }),
    ),
  )
  mountPanel()
  expect(
    await screen.findByText('연결 상태 확인은 준비 중이에요'),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('연결 상태를 확인하지 못했어요'),
  ).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /확인/ })).not.toBeInTheDocument()
  vi.restoreAllMocks()
})

// PR #72 리뷰 7: MCP 연결 명령에 Authorization 헤더를 넣는다. 발급 직후에는 그 토큰, 모르면 자리표시자와 안내.
it('MCP 연결 명령은 발급한 토큰을 Bearer 헤더로 넣고, 모르면 자리표시자와 안내를 보인다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AgentsPanel projectId="101" />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/<에이전트 토큰> 자리에 발급할 때 받은 토큰/),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'MCP 주소 복사' }))
  expect(await navigator.clipboard.readText()).toContain(
    '--header "Authorization: Bearer <에이전트 토큰>"',
  )
  const issue = vi.spyOn(agentsApi, 'issueToken')
  await user.click(screen.getByRole('button', { name: '토큰 발급' }))
  await screen.findByText('이 화면을 닫으면 토큰 값을 다시 볼 수 없어요.')
  const { token } = await issue.mock.results[0].value
  await user.click(screen.getByRole('button', { name: 'MCP 주소 복사' }))
  expect(await navigator.clipboard.readText()).toContain(
    `--header "Authorization: Bearer ${token}"`,
  )
  expect(screen.queryByText(/<에이전트 토큰> 자리에/)).not.toBeInTheDocument()
  vi.restoreAllMocks()
})
