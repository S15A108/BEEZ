/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
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
import {
  discussSuggestionInputSchema,
  requestBeeSuggestionInputSchema,
} from './schemas'

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

const comment = {
  id: '9001',
  explanation: '참고 · 용어를 문서 전체에서 같게 써요.',
  type: 'COMMENT' as const,
  step: 'BODY' as const,
  sources: [],
  messages: [],
}

// 서버가 준 검토에 의견(COMMENT)을 섞는다. onlyComments면 수정 제안 없이 의견만 남긴다.
function withComment(onlyComments: boolean) {
  const original = documentsApi.workingCopy.bind(documentsApi)
  return vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const copy = await original(...args)
      const latest = copy.latest_review
      return latest
        ? {
            ...copy,
            latest_review: {
              ...latest,
              suggestions: [
                ...(onlyComments ? [] : latest.suggestions),
                comment,
              ],
            },
          }
        : copy
    })
}

// 의견은 처리 대기가 아니라 최종본 단계로 넘어가도, 그 단계에서 비비와 대화를 열 수 있어야 한다.
async function chatOnComment(user: ReturnType<typeof userEvent.setup>) {
  const send = vi.spyOn(documentsApi, 'discussSuggestion').mockResolvedValue({
    message: {
      id: '1',
      author_kind: 'HUMAN',
      text: '더 풀어서 설명해 줘',
      created_at: '2026-11-06T03:00:00Z',
    },
    reply: {
      id: '2',
      author_kind: 'BEE',
      text: '용어가 문서마다 달라서예요.',
      created_at: '2026-11-06T03:00:01Z',
    },
  })
  const notes = await screen.findByRole('list', { name: '비비 의견' })
  await user.click(
    within(notes).getByRole('button', { name: '비비와 이야기하기' }),
  )
  expect(screen.queryByRole('button', { name: '이 문장으로 반영' })).toBeNull()
  await user.type(
    screen.getByLabelText('비비에게 보낼 말'),
    '더 풀어서 설명해 줘',
  )
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() =>
    expect(send).toHaveBeenCalledWith(
      '102',
      '221',
      expect.any(String),
      '9001',
      { text: '더 풀어서 설명해 줘' },
    ),
  )
  send.mockRestore()
}

it('의견만 있는 검토는 최종본에서 의견에 대해 비비와 대화할 수 있고, 반영한 제안이 없다고 알린다', async () => {
  const user = userEvent.setup()
  const read = withComment(true)
  mount(true)
  expect(
    await screen.findByRole('heading', { name: '최종본 확인' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/반영한 제안 없이 지금 본문 그대로예요/),
  ).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '반영하기' })).toBeNull()
  await chatOnComment(user)
  read.mockRestore()
})

it('수정 제안을 모두 처리한 최종본에서도 의견과 대화할 수 있고, 반영했으면 반영한 최종본이라고 한다', async () => {
  const user = userEvent.setup()
  const read = withComment(false)
  mount(true)
  await screen.findByText(/수정 제안 \d개와 의견 1개를 남겼어요/)
  await user.click(screen.getAllByRole('button', { name: '반영하기' })[0])
  await screen.findByText('반영함')
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  expect(
    await screen.findByRole('heading', { name: '최종본 확인' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/비비 제안을 반영한 최종본이에요/),
  ).toBeInTheDocument()
  await chatOnComment(user)
  read.mockRestore()
})

it('대화·직접 요청 입력칸은 서버 상한(5,000자)을 넘기지 못한다', async () => {
  const user = userEvent.setup()
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(
    screen.getAllByRole('button', { name: '비비와 이야기하기' })[0],
  )
  expect(screen.getByLabelText('비비에게 보낼 말')).toHaveAttribute(
    'maxlength',
    '5000',
  )
  expect(
    discussSuggestionInputSchema.safeParse({ text: 'a'.repeat(5001) }).success,
  ).toBe(false)
  expect(
    requestBeeSuggestionInputSchema.safeParse({
      expected_revision: 1,
      text: 'a'.repeat(5000),
    }).success,
  ).toBe(true)
  expect(
    requestBeeSuggestionInputSchema.safeParse({
      expected_revision: 1,
      text: 'a'.repeat(5001),
    }).success,
  ).toBe(false)
})
