/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { documentsApi } from '@/api/services/documents'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { DocumentReview } from './DocumentReview'

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

function mount(isLeader: boolean) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DocumentReview
        projectId="102"
        documentId="221"
        title="기획안"
        kind="PLAN"
        isLeader={isLeader}
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  )
}

function apiError(status: number, code: string, message: string) {
  return new AxiosError(message, String(status), undefined, undefined, {
    status,
    statusText: message,
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message } },
  })
}

it('제안마다 분류·중요도 칩과 근거가 보인다', async () => {
  mount(true)
  expect(await screen.findByText('누락')).toBeInTheDocument()
  expect(screen.getByText('중요')).toBeInTheDocument()
  expect(screen.getByText('일관성')).toBeInTheDocument()
  expect(screen.getByText('가벼움')).toBeInTheDocument()
})

it('제안에 대해 비비와 대화하고, 비비가 낸 다른 문장으로 반영한다', async () => {
  const user = userEvent.setup()
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(
    screen.getAllByRole('button', { name: '비비와 이야기하기' })[0],
  )
  await user.type(
    screen.getByLabelText('비비에게 보낼 말'),
    '확인 시각도 남겨 줘',
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  const chat = await screen.findByRole('list', { name: '대화 내용' })
  expect(within(chat).getByText('확인 시각도 남겨 줘')).toBeInTheDocument()
  expect(within(chat).getByText(/말씀하신 내용을 담아/)).toBeInTheDocument()
  // 대화만으로는 본문이 바뀌지 않는다.
  expect(screen.queryByText('반영함')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '이 문장으로 반영' }))
  expect(await screen.findByText('반영함')).toBeInTheDocument()
  expect(
    screen.getByText('비비가 대화에서 낸 문장으로 반영했어요.'),
  ).toBeInTheDocument()
})

it('올린 사람이 아니면 대화를 시작할 수 없고, 지난 대화는 읽을 수 있다', async () => {
  const user = userEvent.setup()
  // 올린 사람이 먼저 검토를 받아 둔 뒤 다른 팀원이 연다.
  await documentsApi.requestBeeReview('102', '221', { expected_revision: 1 })
  const copy = documentsApi.workingCopy.bind(documentsApi)
  const spy = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const data = await copy(...args)
      const latest = data.latest_review
      return {
        ...data,
        owner_account_id: '99',
        latest_review: latest && {
          ...latest,
          suggestions: latest.suggestions.map((item, index) =>
            index
              ? item
              : {
                  ...item,
                  messages: [
                    {
                      id: '9101',
                      author_kind: 'HUMAN' as const,
                      text: '이유가 뭐야?',
                      created_at: '2026-11-06T03:00:00Z',
                    },
                  ],
                },
          ),
        },
      }
    })
  mount(false)
  await screen.findByText(/제안은 올린 사람이 반영하거나 넘겨요/)
  expect(
    screen.queryByRole('button', { name: '비비와 이야기하기' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '비비와 나눈 대화 1개' }))
  expect(screen.getByText('이유가 뭐야?')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '보내기' }),
  ).not.toBeInTheDocument()
  spy.mockRestore()
})

it('비비를 부를 수 없으면 대화 창에 안내를 보이고, 다시 보낼 수 있다', async () => {
  const user = userEvent.setup()
  localStorage.setItem('beez.mock.review', 'unavailable')
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(
    screen.getAllByRole('button', { name: '비비와 이야기하기' })[0],
  )
  await user.type(screen.getByLabelText('비비에게 보낼 말'), '안녕')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(
    await screen.findByText(
      '지금은 비비를 부를 수 없어요. 잠시 뒤 다시 시도해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('비비에게 보낼 말')).toHaveValue('안녕')
  localStorage.removeItem('beez.mock.review')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(await screen.findByText(/말씀하신 내용을 담아/)).toBeInTheDocument()
})

it('최종본에서 비비에게 직접 요청하면 새 제안이 직접 요청 칩과 함께 붙고 제안 확인 단계로 돌아간다', async () => {
  const user = userEvent.setup()
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await screen.findByRole('heading', { name: '최종본 확인' })
  await user.type(
    screen.getByLabelText('비비에게 요청할 내용'),
    '오류 표에 403 행을 추가해 줘',
  )
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  expect(await screen.findByText('직접 요청')).toBeInTheDocument()
  expect(
    screen.getByText(/요청대로 반영해요 · 오류 표에 403 행을 추가해 줘/),
  ).toBeInTheDocument()
  // 새 제안을 처리하기 전에는 팀장에게 요청할 수 없다(제안 확인 단계).
  expect(
    screen.queryByRole('button', { name: '팀장에게 검토 요청' }),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '반영하기' })).toBeInTheDocument()
})

it('대화가 review_stale 로 막히면 검토 무효 안내와 다시 받기를 보인다', async () => {
  const user = userEvent.setup()
  const send = vi
    .spyOn(documentsApi, 'discussSuggestion')
    .mockRejectedValue(apiError(409, 'review_stale', '바뀌었어요.'))
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(
    screen.getAllByRole('button', { name: '비비와 이야기하기' })[0],
  )
  await user.type(screen.getByLabelText('비비에게 보낼 말'), '안녕')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(
    await screen.findByText(/이 검토는 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '비비 검토 다시 받기' }),
  ).toBeInTheDocument()
  send.mockRestore()
})
