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
import {
  FAILED_REVIEW_FOLLOW_MS,
  REVIEW_POLL_MS,
  SLOW_POLL_MS,
} from '@/lib/pollIntervals'
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
  sources: [],
  messages: [],
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

// S15P31A108-391: 검토는 접수(202)되고 서버 워커가 한다. 올린 사람 화면도 작업본을 다시 읽지 않으면
// 검토가 끝나도 '검토하고 있어요'에 머문다.
it('검토가 접수되면 올린 사람 화면도 작업본을 다시 읽어 검토가 끝나면 결과를 보인다', async () => {
  const state = { done: false, runningReads: 0 }
  const original = documentsApi.workingCopy.bind(documentsApi)
  // 검토를 부른 뒤(요청 상태가 생긴 뒤)에는 끝날 때까지 RUNNING으로 돌려준다.
  const read = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const copy = await original(...args)
      if (state.done || !copy.bee_review_request) return copy
      state.runningReads += 1
      return {
        ...copy,
        latest_review: undefined,
        bee_review_request: {
          status: 'RUNNING' as const,
          updated_at: new Date().toISOString(),
        },
      }
    })
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  try {
    mount(true)
    await waitFor(() => expect(state.runningReads).toBeGreaterThan(0))
    expect(
      screen.getByText(/이 화면을 떠나도 검토가 끝나면 알림으로 알려 드려요/),
    ).toBeInTheDocument()
    state.done = true
    expect(
      await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
    ).toBeInTheDocument()
    expect(request).toHaveBeenCalledTimes(1)
  } finally {
    read.mockRestore()
    request.mockRestore()
  }
})

// S15P31A108-391: 창을 다시 열거나 새로고침했을 때 검토가 이미 돌고 있으면 새로 부르지 않는다(AI 검토가 두 번 돈다).
it('이미 도는 검토가 있는 문서를 다시 열면 부르지 않고 지난 시간과 함께 기다린다', async () => {
  const original = documentsApi.workingCopy.bind(documentsApi)
  const startedAt = new Date(Date.now() - 70_000).toISOString()
  const read = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => ({
      ...(await original(...args)),
      latest_review: undefined,
      bee_review_request: { status: 'RUNNING' as const, updated_at: startedAt },
    }))
  const request = vi.spyOn(documentsApi, 'requestBeeReview')
  try {
    mount(true)
    expect(await screen.findByText(/1분 1\d초 지났어요/)).toBeInTheDocument()
    expect(
      screen.getByText('문서가 길면 2분까지 걸릴 수 있어요.'),
    ).toBeInTheDocument()
    // 끝날 때까지 다시 읽는다.
    await waitFor(() => expect(read.mock.calls.length).toBeGreaterThan(2))
    expect(request).not.toHaveBeenCalled()
  } finally {
    read.mockRestore()
    request.mockRestore()
  }
})

// S15P31A108-391: 실패한 검토는 기다려도 끝나지 않는다. 올린 사람 화면이 실패 뒤에도 계속 부르면 안 된다.
it('올린 사람 화면은 검토가 실패하면 작업본 다시 읽기를 멈춘다', async () => {
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  const read = vi.spyOn(documentsApi, 'workingCopy')
  try {
    mount(true)
    expect(
      await screen.findByText('비비 검토 시간이 초과됐어요'),
    ).toBeInTheDocument()
    const reads = read.mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, REVIEW_POLL_MS * 5))
    expect(read).toHaveBeenCalledTimes(reads)
  } finally {
    localStorage.removeItem('beez.mock.reviewRun')
    read.mockRestore()
  }
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

// PR #142 리뷰(양지훈): 올린 사람이 아닌 팀원은 검토를 부르지 않아 review_in_progress를 받지 못한다. 그래서 작업본을
// 다시 읽지 않아, 검토가 끝나도 창을 다시 열 때까지 '비비 검토 중'에 머물렀다. 검토 중인 작업본이면 끝날 때까지 다시 읽는다.
it('올린 사람이 아니어도 비비 검토가 끝나면 창을 다시 열지 않고 결과를 본다', async () => {
  // 올린 사람(데모 계정)이 검토를 마친 상태를 만들어 두고, 팀원 화면에는 끝나기 전까지 검토 결과를 빼고 돌려준다.
  const before = await documentsApi.workingCopy('102', '221')
  await documentsApi.requestBeeReview('102', '221', {
    expected_revision: before.revision,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  let finished = false
  const original = documentsApi.workingCopy.bind(documentsApi)
  const read = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const copy = await original(...args)
      return finished ? copy : { ...copy, latest_review: undefined }
    })
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    mount(false)
    expect(
      await screen.findByText(/올린 문서는 비비가 바로 검토해요/),
    ).toBeInTheDocument()
    finished = true
    await vi.advanceTimersByTimeAsync(REVIEW_POLL_MS)
    expect(
      await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
    ).toBeInTheDocument()
  } finally {
    vi.useRealTimers()
    read.mockRestore()
  }
})

// S15P31A108-383: -382는 실패한 검토를 기다리지 않도록 팀원 창의 다시 읽기를 멈췄다. 그런데 올린 사람이
// 다시 검토해 성공해도 팀원 창은 다시 열 때까지 '검토 실패'에 머물렀다. 실패 뒤 10분 동안은 가끔 다시 읽고,
// 그보다 오래된 실패는 열어 둔 창이 끝없이 부르지 않게 멈춘다.
async function watchFailedReview(
  failedAt: string,
  status: 'EDITING' | 'REJECTED' = 'EDITING',
) {
  const before = await documentsApi.workingCopy('102', '221')
  await documentsApi.requestBeeReview('102', '221', {
    expected_revision: before.revision,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const state = { retried: false }
  const original = documentsApi.workingCopy.bind(documentsApi)
  const read = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => {
      const copy = await original(...args)
      return state.retried
        ? copy
        : {
            ...copy,
            status,
            latest_review: undefined,
            bee_review_request: {
              status: 'FAILED' as const,
              error_code: 'review_timeout',
              updated_at: failedAt,
            },
          }
    })
  return { read, state }
}

it('검토가 방금 실패한 문서를 보는 팀원 창은 느린 주기로 다시 읽어 성공을 따라간다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const { read, state } = await watchFailedReview(new Date().toISOString())
  try {
    mount(false)
    expect(
      await screen.findByText(/올린 사람이 다시 검토하거나 문서를 고쳐야 해요/),
    ).toBeInTheDocument()
    const reads = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(REVIEW_POLL_MS * 2)
    expect(read).toHaveBeenCalledTimes(reads)
    state.retried = true
    await vi.advanceTimersByTimeAsync(SLOW_POLL_MS)
    expect(
      await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
    ).toBeInTheDocument()
  } finally {
    vi.useRealTimers()
    read.mockRestore()
  }
})

// S15P31A108-383 리뷰: 반려된 문서는 무조건 다시 읽기를 멈춰, 반려 사유로 다시 받던 검토가 실패했다가
// 올린 사람의 재시도로 성공해도 팀원 창이 따라가지 못했다.
it('반려 뒤 다시 받던 검토가 실패한 문서도 팀원 창이 재시도 성공을 따라간다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const { read, state } = await watchFailedReview(
    new Date().toISOString(),
    'REJECTED',
  )
  try {
    mount(false)
    expect(
      await screen.findByText(/올린 사람이 다시 검토하거나 문서를 고쳐야 해요/),
    ).toBeInTheDocument()
    state.retried = true
    await vi.advanceTimersByTimeAsync(SLOW_POLL_MS)
    expect(
      await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
    ).toBeInTheDocument()
  } finally {
    vi.useRealTimers()
    read.mockRestore()
  }
})

it('10분 넘게 지난 검토 실패는 팀원 창이 다시 읽지 않는다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const old = new Date(Date.now() - FAILED_REVIEW_FOLLOW_MS - 60_000)
  const { read } = await watchFailedReview(old.toISOString())
  try {
    mount(false)
    expect(
      await screen.findByText(/올린 사람이 다시 검토하거나 문서를 고쳐야 해요/),
    ).toBeInTheDocument()
    const reads = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(SLOW_POLL_MS * 2)
    expect(read).toHaveBeenCalledTimes(reads)
  } finally {
    vi.useRealTimers()
    read.mockRestore()
  }
})

// review_stale 테스트가 모듈의 무효 검토 목록에 같은 검토 id를 남기므로 그보다 앞에 둔다.
// 사용자 요청(11-02): 팀장이 승인하면 부르는 쪽(공용 문서함)이 목록으로 돌아가 같은 알림을 띄운다. 승인이 실패하면 검토 화면에 남는다.
it('승인에 성공했을 때만 승인 알림 문구로 목록에 돌아가라고 알린다', async () => {
  const user = userEvent.setup()
  const onApproved = vi.fn()
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
        isLeader
        onClose={vi.fn()}
        onApproved={onApproved}
      />
    </QueryClientProvider>,
  )
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await user.click(
    await screen.findByRole('button', { name: '팀장에게 검토 요청' }),
  )
  await screen.findByText(/승인을 요청했어요/)
  const decide = vi.spyOn(documentsApi, 'decideSubmission')
  decide.mockRejectedValueOnce(
    new AxiosError('conflict', '409', undefined, undefined, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'version_conflict', message: '바뀌었어요.' } },
    }),
  )
  await user.click(screen.getByRole('button', { name: '승인' }))
  await waitFor(() => expect(decide).toHaveBeenCalledTimes(1))
  expect(onApproved).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '승인' }))
  await waitFor(() =>
    expect(onApproved).toHaveBeenCalledWith(
      '승인했어요. 현재 문서로 옮겼어요.',
    ),
  )
  decide.mockRestore()
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
