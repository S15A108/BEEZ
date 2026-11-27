import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { documentsApi } from '@/api/services/documents'
import { SuggestionRequest } from './SuggestionRequest'
import type { DocumentReviewSuggestion } from '@/api/contracts/common'

const suggestion = {
  id: '1',
  type: 'COMMENT',
  step: 'BODY',
  explanation: '속도를 명세해 주세요',
  decision: 'PENDING',
} as DocumentReviewSuggestion

function mount() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SuggestionRequest
        projectId="1"
        documentId="2"
        reviewId="3"
        revision={1}
        suggestion={suggestion}
        disabled={false}
        onStale={vi.fn()}
      />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

// 비비가 "이미 있어서 고칠 곳이 없다"고 답하면 오류 안내("잠시 뒤 다시 시도")가 아니라 이유를 보이고 창을 닫지 않는다.
it('고칠 곳이 없다는 답은 이유를 보이고 요청 창을 열어 둔다', async () => {
  const user = userEvent.setup()
  const spy = vi.spyOn(documentsApi, 'requestBeeSuggestion').mockResolvedValue({
    outcome: 'NO_CHANGE',
    reason: '초당 블록 수가 3.2절에 이미 있어요.',
  })
  mount()
  await user.click(screen.getByRole('button', { name: '비비에게 요청하기' }))
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  expect(await screen.findByRole('status')).toHaveTextContent(
    '비비가 고칠 곳을 찾지 못했어요: 초당 블록 수가 3.2절에 이미 있어요.',
  )
  expect(spy).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('alert')).toBeNull()
  expect(
    screen.getByRole('button', { name: '요청 보내기' }),
  ).toBeInTheDocument()
})

it('새 제안이 붙으면 요청 창을 닫는다', async () => {
  const user = userEvent.setup()
  vi.spyOn(documentsApi, 'requestBeeSuggestion').mockResolvedValue({
    outcome: 'CREATED',
    suggestion,
  })
  mount()
  await user.click(screen.getByRole('button', { name: '비비에게 요청하기' }))
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '요청 보내기' })).toBeNull(),
  )
})

// 화면 단계가 바뀌면 카드가 다시 만들어진다. 돌고 있던 요청의 진행 표시와 실패 안내는 남아야 한다(S15P31A108-382).
it('카드가 다시 만들어져도 진행 표시와 실패 안내가 남는다', async () => {
  const user = userEvent.setup()
  let reject: (cause: Error) => void = () => undefined
  vi.spyOn(documentsApi, 'requestBeeSuggestion').mockReturnValue(
    new Promise((_, fail) => {
      reject = fail
    }),
  )
  mount()
  await user.click(screen.getByRole('button', { name: '비비에게 요청하기' }))
  await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  cleanup()
  mount()
  expect(
    screen.getByRole('button', { name: '비비가 제안을 만들고 있어요' }),
  ).toBeDisabled()
  reject(new Error('실패'))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 요청하기' })).toBeEnabled()
})
