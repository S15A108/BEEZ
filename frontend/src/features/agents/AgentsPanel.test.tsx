/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen } from '@testing-library/react'
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
import { AgentsPanel } from './AgentsPanel'

let restore: () => void
beforeEach(async () => {
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
