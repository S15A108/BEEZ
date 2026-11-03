/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
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
import { useWorkspaceStore } from '@/stores/workspaceStore'
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

it('비비 검토 → 제안 반영·넘기기 → 최종본 → 팀장 승인까지 같은 화면에서 이어진다', async () => {
  const user = userEvent.setup()
  mount(true)
  // 올린 문서는 비비 검토가 자동으로 시작된다(DOC-A16, 사용자 승인으로 버튼 누르기를 뺌).
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(screen.getByText(/실제 AI 호출 없음/)).toBeInTheDocument()
  await user.click(screen.getAllByRole('button', { name: '반영하기' })[0])
  await screen.findByText('반영함')
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  expect(
    await screen.findByRole('heading', { name: '최종본 확인' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '팀장에게 검토 요청' }))
  expect(await screen.findByText(/승인을 요청했어요/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '승인' }))
  expect(await screen.findByText(/팀장이 승인했어요/)).toBeInTheDocument()
})

it('팀장은 사유를 써서 반려하고, 올린 사람에게는 반려 사유가 보인다', async () => {
  const user = userEvent.setup()
  mount(true)
  await screen.findByText(/비비가 수정 제안/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await user.click(
    await screen.findByRole('button', { name: '팀장에게 검토 요청' }),
  )
  await user.click(await screen.findByRole('button', { name: '반려' }))
  await user.click(screen.getByRole('button', { name: '반려하기' }))
  expect(
    await screen.findByText('반려 사유를 입력해 주세요.'),
  ).toBeInTheDocument()
  await user.type(
    screen.getByLabelText(/반려 사유/),
    '요구사항 번호가 빠졌어요.',
  )
  await user.click(screen.getByRole('button', { name: '반려하기' }))
  expect(await screen.findByText('팀장 반려')).toBeInTheDocument()
  expect(screen.getByText('요구사항 번호가 빠졌어요.')).toBeInTheDocument()
  // 반려 뒤에는 비비가 반려 사유를 보고 자동으로 다시 검토한다(사용자 승인으로 버튼 확인을 바꿈).
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '비비에게 검토 요청' }),
  ).not.toBeInTheDocument()
})

it('기준 v1이 생긴 뒤에는 고치는 버튼 대신 변경 제안으로 안내한다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DocumentReview
        projectId="101"
        documentId="201"
        title="기획안"
        kind="PLAN"
        isLeader
        locked
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/기준 설계 생성 이후 문서 수정은 변경 제안을 통해/),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '비비에게 검토 요청' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '변경 제안으로 요청' }))
  expect(useWorkspaceStore.getState().pane).toBe('changes')
})

it('비비 검토는 같은 리비전으로 한 번만 자동으로 부르고, 단계별 결과를 보여 준다', async () => {
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  mount(true)
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(1)
  // 서버 v2 검토는 단계 4개이고 첫 단계는 본문이다(S15P31A108-252, 사용자 승인으로 기대값 변경).
  expect(screen.getByText(/1단계 \/ 4단계 · 본문 · 검토함/)).toBeInTheDocument()
  request.mockRestore()
})

const comment = {
  id: '9001',
  explanation: '참고 · 용어를 문서 전체에서 같게 써요.',
  type: 'COMMENT' as const,
  step: 'BODY' as const,
}

// 서버가 준 검토에 의견(COMMENT)을 섞는다. onlyComments면 수정 제안 없이 의견만 남긴다.
function withComment(onlyComments = false) {
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
              unavailable_references: ['REQ_SPEC'],
              suggestions: [
                ...(onlyComments ? [] : latest.suggestions),
                comment,
              ],
            },
          }
        : copy
    })
}

// S15P31A108-252: 의견(COMMENT)은 읽기만 하고 반영·넘기기 없이 최종본 확인을 막지 않는다.
// PR #89 리뷰: 마지막 수정 제안을 처리해 최종본으로 넘어가도 의견과 비교하지 못한 문서 안내가 남는다.
it('비비 의견은 반영 버튼 없이 보이고, 수정 제안을 다 처리한 최종본에서도 의견이 남는다', async () => {
  const user = userEvent.setup()
  const read = withComment()
  mount(true)
  expect(
    await screen.findByText(/수정 제안 \d개와 의견 1개를 남겼어요/),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/의견 \d+ · 참고 · 용어를 문서 전체에서 같게 써요/),
  ).toBeInTheDocument()
  expect(screen.getByText(/· 본문 · 검토함/)).toBeInTheDocument()
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  expect(
    await screen.findByRole('heading', { name: '최종본 확인' }),
  ).toBeInTheDocument()
  expect(
    within(screen.getByRole('list', { name: '비비 의견' })).getByText(
      /용어를 문서 전체에서 같게 써요/,
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByText('승인본이 없어 비교하지 못한 문서: 요구사항 명세서'),
  ).toBeInTheDocument()
  read.mockRestore()
})

// PR #89 리뷰: 처리할 수정 제안 없이 의견만 오면 바로 최종본 단계가 되므로, 거기서 의견을 보여 준다.
it('의견만 있는 검토는 바로 최종본으로 가고 그 화면에서 의견을 보여 준다', async () => {
  const read = withComment(true)
  mount(true)
  expect(
    await screen.findByRole('heading', { name: '최종본 확인' }),
  ).toBeInTheDocument()
  expect(screen.getByText('비비 의견 1개')).toBeInTheDocument()
  expect(
    within(screen.getByRole('list', { name: '비비 의견' })).getByText(
      /용어를 문서 전체에서 같게 써요/,
    ),
  ).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '반영하기' })).toBeNull()
  read.mockRestore()
})

it('검토가 실패하면 다시 검토하기로만 다시 부른다', async () => {
  const user = userEvent.setup()
  const request = vi
    .spyOn(documentsApi, 'requestBeeReview')
    .mockRejectedValueOnce(new Error('timeout'))
  mount(true)
  await user.click(await screen.findByRole('button', { name: '다시 검토하기' }))
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(2)
  request.mockRestore()
})

it('올린 사람이 아니면 검토를 부르지 않고 자동 검토 안내를 본다', async () => {
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  mount(false)
  expect(
    await screen.findByText(/올린 문서는 비비가 바로 검토해요/),
  ).toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
  request.mockRestore()
})

// PR #72 리뷰 2: 참고 승인본이 바뀌어 409 review_stale이면 검토를 무효로 보이고, 다시 열어도 '비비 검토 다시 받기'로 이어진다.
it('반영이 review_stale로 막히면 검토 무효 안내와 다시 받기를 보이고, 다시 열어도 그대로다', async () => {
  const user = userEvent.setup()
  const stale = new AxiosError(
    'Conflict',
    'ERR_BAD_REQUEST',
    undefined,
    undefined,
    {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: { code: 'review_stale', message: '참고 승인본이 바뀌었어요.' },
      },
    },
  )
  const decide = vi
    .spyOn(documentsApi, 'decideSuggestion')
    .mockRejectedValue(stale)
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(screen.getAllByRole('button', { name: '반영하기' })[0])
  expect(
    await screen.findByText(/이 검토는 더 쓸 수 없어요/),
  ).toBeInTheDocument()
  expect(screen.queryAllByRole('button', { name: '반영하기' })).toHaveLength(0)
  cleanup()
  mount(true)
  expect(
    await screen.findByRole('button', { name: '비비 검토 다시 받기' }),
  ).toBeInTheDocument()
  decide.mockRestore()
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  await user.click(screen.getByRole('button', { name: '비비 검토 다시 받기' }))
  await waitFor(() => expect(request).toHaveBeenCalled())
  request.mockRestore()
})
