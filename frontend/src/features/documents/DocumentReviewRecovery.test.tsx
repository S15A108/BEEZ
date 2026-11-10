/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
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

// 비비 검토가 실패했을 때의 복구(S15P31A108-382): 시간 초과 안내, 앞 단계로 돌아가 고치기, 다시 검토, 등록 취소.
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
afterEach(() => {
  localStorage.removeItem('beez.mock.reviewRun')
  cleanup()
  restore()
})

function mount(handlers: {
  onClose?: () => void
  onDiscarded?: () => void
  onReupload?: (current: {
    fileName: string
    content: string
    revision: number
  }) => void
}) {
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
        onClose={handlers.onClose ?? vi.fn()}
        onDiscarded={handlers.onDiscarded}
        onReupload={handlers.onReupload}
      />
    </QueryClientProvider>,
  )
}

it('검토가 시간 초과로 실패하면 원인을 알리고 다시 검토·등록 취소를 보인다', async () => {
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({})
  expect(
    await screen.findByText('비비 검토 시간이 초과됐어요'),
  ).toBeInTheDocument()
  expect(screen.getByText(/시간이 너무 오래 걸렸어요/)).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '다시 검토하기' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '등록 취소' })).toBeInTheDocument()
})

it('다른 장애는 시간 초과와 다른 제목으로 안내한다', async () => {
  localStorage.setItem('beez.mock.reviewRun', 'unavailable')
  mount({})
  expect(
    await screen.findByText('지금은 비비를 부를 수 없어요'),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('비비 검토 시간이 초과됐어요'),
  ).not.toBeInTheDocument()
})

it('실패한 뒤 다시 검토하기를 누르면 검토가 끝나 제안이 보인다', async () => {
  const user = userEvent.setup()
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({})
  await screen.findByText('비비 검토 시간이 초과됐어요')
  localStorage.removeItem('beez.mock.reviewRun')
  await user.click(screen.getByRole('button', { name: '다시 검토하기' }))
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('비비 검토 시간이 초과됐어요'),
  ).not.toBeInTheDocument()
})

it('이전 단계(파일 등록)를 누르면 지금 등록된 파일 정보를 넘겨 파일 다시 올리기를 연다', async () => {
  const user = userEvent.setup()
  const onReupload = vi.fn()
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({ onReupload })
  await screen.findByText('비비 검토 시간이 초과됐어요')
  await user.click(
    screen.getByRole('button', { name: '이전 단계로 돌아가 파일 다시 올리기' }),
  )
  expect(onReupload).toHaveBeenCalledTimes(1)
  expect(onReupload.mock.calls[0][0]).toEqual({
    fileName: expect.any(String),
    content: expect.stringMatching(/\S/),
    revision: expect.any(Number),
  })
})

it('파일 다시 올리기를 연결하지 않은 화면에서는 단계 표시줄이 보기만 한다', async () => {
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({})
  await screen.findByText('비비 검토 시간이 초과됐어요')
  expect(
    screen.queryByRole('button', {
      name: '이전 단계로 돌아가 파일 다시 올리기',
    }),
  ).not.toBeInTheDocument()
})

it('의견 카드의 비비에게 요청하기는 그 의견으로 쓴 요청을 보내 새 제안을 받는다', async () => {
  const user = userEvent.setup()
  const request = vi.spyOn(documentsApi, 'requestBeeSuggestion')
  mount({})
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  await user.click(
    screen.getAllByRole('button', { name: '비비에게 요청하기' })[0],
  )
  const box = await screen.findByRole('textbox', {
    name: '비비에게 요청할 내용',
  })
  // 의견 내용이 미리 채워져 있고 고쳐 쓸 수 있다
  expect((box as HTMLTextAreaElement).value).toContain(
    '다음 비비 의견을 반영해 본문을 수정해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
  expect(request.mock.calls[0][3]).toEqual({
    expected_revision: expect.any(Number),
    text: expect.stringContaining('다음 비비 의견을 반영해'),
  })
  expect(await screen.findByText('직접 요청')).toBeInTheDocument()
  request.mockRestore()
})

it('제안을 모두 처리한 뒤에도 반영·넘김 내역을 계속 볼 수 있다', async () => {
  const user = userEvent.setup()
  mount({})
  await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/)
  while (screen.queryAllByRole('button', { name: '넘기기' }).length)
    await user.click(screen.getAllByRole('button', { name: '넘기기' })[0])
  expect(await screen.findByText(/비비 제안 처리 내역/)).toBeInTheDocument()
  expect(screen.getByText(/넘김 \d+개/)).toBeInTheDocument()
})

it('등록 취소는 확인 뒤에만 부르고, 성공하면 알림과 함께 목록으로 돌려보낸다', async () => {
  const user = userEvent.setup()
  const discard = vi.spyOn(documentsApi, 'discardDocument')
  const onDiscarded = vi.fn()
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({ onDiscarded })
  await screen.findByText('비비 검토 시간이 초과됐어요')
  await user.click(screen.getByRole('button', { name: '등록 취소' }))
  await user.click(await screen.findByRole('button', { name: '돌아가기' }))
  expect(discard).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '등록 취소' }))
  await user.click(await screen.findByRole('button', { name: '등록 취소하기' }))
  await waitFor(() => expect(discard).toHaveBeenCalledTimes(1))
  expect(discard).toHaveBeenCalledWith('102', '221')
  await waitFor(() =>
    expect(onDiscarded).toHaveBeenCalledWith(
      expect.stringContaining('같은 종류의 문서를 새로 올릴 수 있어요'),
    ),
  )
  discard.mockRestore()
})

it('등록 취소가 409로 막히면 서버 문구로 안내하고 화면을 닫지 않는다', async () => {
  const user = userEvent.setup()
  const onDiscarded = vi.fn()
  const onClose = vi.fn()
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  vi.spyOn(documentsApi, 'discardDocument').mockRejectedValue(
    Object.assign(new Error('conflict'), {
      isAxiosError: true,
      response: {
        status: 409,
        data: { error: { code: 'document_approved', message: '승인됨' } },
      },
    }),
  )
  mount({ onDiscarded, onClose })
  await screen.findByText('비비 검토 시간이 초과됐어요')
  await user.click(screen.getByRole('button', { name: '등록 취소' }))
  await user.click(await screen.findByRole('button', { name: '등록 취소하기' }))
  expect(
    await screen.findByText(
      /이미 승인된 적이 있는 문서는 등록을 취소할 수 없어요/,
    ),
  ).toBeInTheDocument()
  expect(onDiscarded).not.toHaveBeenCalled()
  expect(onClose).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})
