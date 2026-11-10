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

function mount(handlers: { onClose?: () => void; onDiscarded?: () => void }) {
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

it('이전 단계(파일 등록)로 돌아가 문서를 고쳐 저장하면 실패 안내가 사라지고 비비가 다시 검토한다', async () => {
  const user = userEvent.setup()
  const update = vi.spyOn(documentsApi, 'updateWorkingCopy')
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({})
  await screen.findByText('비비 검토 시간이 초과됐어요')
  await user.click(
    screen.getByRole('button', { name: '이전 단계로 돌아가 문서 고치기' }),
  )
  expect(
    screen.getByText('문서를 고친 뒤 저장하면 비비가 처음부터 다시 검토해요'),
  ).toBeInTheDocument()
  localStorage.removeItem('beez.mock.reviewRun')
  const body = screen.getByLabelText(/본문/)
  await user.clear(body)
  await user.type(body, '# 줄인 계획')
  await user.click(screen.getByRole('button', { name: '고친 내용 저장' }))
  await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
  expect(
    await screen.findByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('비비 검토 시간이 초과됐어요'),
  ).not.toBeInTheDocument()
  update.mockRestore()
})

it('앞 단계 고치기를 접으면 실패 안내로 돌아온다', async () => {
  const user = userEvent.setup()
  localStorage.setItem('beez.mock.reviewRun', 'timeout')
  mount({})
  await screen.findByText('비비 검토 시간이 초과됐어요')
  const back = screen.getByRole('button', {
    name: '이전 단계로 돌아가 문서 고치기',
  })
  await user.click(back)
  await user.click(back)
  expect(
    screen.getByRole('button', { name: '다시 검토하기' }),
  ).toBeInTheDocument()
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
