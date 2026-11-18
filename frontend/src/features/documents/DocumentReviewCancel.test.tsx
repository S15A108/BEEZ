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

// 팀장에게 검토 요청까지 마친 상태로 만든다.
async function submitted(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await user.click(
    await screen.findByRole('button', { name: '팀장에게 검토 요청' }),
  )
  await screen.findByText(/승인을 요청했어요/)
}

it('올린 사람은 검토 요청을 확인 뒤 취소하고, 취소한 요청은 이력에 취소됨으로 남는다', async () => {
  const user = userEvent.setup()
  const cancel = vi.spyOn(documentsApi, 'cancelSubmission')
  mount(true)
  await submitted(user)
  await user.click(screen.getByRole('button', { name: '검토 요청 취소' }))
  // 확인 창에서 돌아가면 아무것도 부르지 않는다.
  await user.click(await screen.findByRole('button', { name: '돌아가기' }))
  expect(cancel).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '검토 요청 취소' }))
  await user.click(await screen.findByRole('button', { name: '요청 취소하기' }))
  await waitFor(() => expect(cancel).toHaveBeenCalledTimes(1))
  // 취소하면 고치는 단계로 돌아가 비비 검토를 다시 받고, 이력에는 취소됨이 남는다.
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  const history = screen.getByRole('list', { name: '지난 검토 요청' })
  expect(within(history).getByText('취소됨')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '검토 요청 취소' }),
  ).not.toBeInTheDocument()
  cancel.mockRestore()
})

it('팀장이 먼저 결정해 취소가 막히면 안내하고, 다른 팀원에게는 취소 버튼이 없다', async () => {
  const user = userEvent.setup()
  const cancel = vi
    .spyOn(documentsApi, 'cancelSubmission')
    .mockRejectedValue(
      apiError(409, 'submission_decided', '팀장이 이미 결정했어요.'),
    )
  mount(true)
  await submitted(user)
  await user.click(screen.getByRole('button', { name: '검토 요청 취소' }))
  await user.click(await screen.findByRole('button', { name: '요청 취소하기' }))
  expect(
    await screen.findByText('이미 결정한 검토 요청이에요.'),
  ).toBeInTheDocument()
  cancel.mockRestore()
  cleanup()
  // 다른 사람(등록자가 아닌 팀원)은 팀장 대기 안내만 본다.
  const copy = documentsApi.workingCopy.bind(documentsApi)
  const other = vi
    .spyOn(documentsApi, 'workingCopy')
    .mockImplementation(async (...args) => ({
      ...(await copy(...args)),
      owner_account_id: '99',
      submissions: (await copy(...args)).submissions.map((item) => ({
        ...item,
        submitted_by: '99',
      })),
    }))
  const list = vi.spyOn(documentsApi, 'reviewSubmissions').mockResolvedValue([])
  mount(false)
  expect(await screen.findByText(/팀장 승인을 기다리고 있어요/)).toBeVisible()
  expect(
    screen.queryByRole('button', { name: '검토 요청 취소' }),
  ).not.toBeInTheDocument()
  other.mockRestore()
  list.mockRestore()
})

it('팀장 검토 중에 고치려 하면 submission_locked 안내에 취소 방법을 함께 보인다', async () => {
  const user = userEvent.setup()
  mount(true)
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  await screen.findByRole('heading', { name: '최종본 확인' })
  const submit = vi
    .spyOn(documentsApi, 'submitReview')
    .mockRejectedValue(apiError(409, 'submission_locked', '잠겼어요.'))
  await user.click(screen.getByRole('button', { name: '팀장에게 검토 요청' }))
  expect(
    await screen.findByText(/먼저 검토 요청을 취소한 뒤 고쳐 주세요/),
  ).toBeInTheDocument()
  submit.mockRestore()
})

it('팀장 상세는 비교하지 못한 단계와 사유를 보이고, 비비 완료를 팀장 승인으로 적지 않는다', async () => {
  const user = userEvent.setup()
  mount(true)
  await submitted(user)
  const notice = await screen.findByRole('note')
  expect(
    within(notice).getByText('일부 문서와는 비교하지 못한 검토예요'),
  ).toBeInTheDocument()
  const skipped = within(notice).getByRole('list', {
    name: '비교하지 못한 단계',
  })
  expect(within(skipped).getAllByRole('listitem')).toHaveLength(3)
  expect(within(skipped).getAllByText(/승인본이 없어 건너뜀/)).toHaveLength(3)
  expect(
    within(notice).getByText(/승인은 팀장이 직접 정해요/),
  ).toBeInTheDocument()
})
