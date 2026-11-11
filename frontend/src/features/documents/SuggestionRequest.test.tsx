import { render, screen, waitFor } from '@testing-library/react'
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
