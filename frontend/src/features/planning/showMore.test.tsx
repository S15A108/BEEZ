import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { planningApi } from '@/api/services'
import { DocumentCandidates } from './DocumentCandidates'
import { PlanningQuestions } from './PlanningQuestions'
import type { DraftCandidate, DraftJob } from './schemas'

afterEach(() => vi.restoreAllMocks())

const candidate = (id: number): DraftCandidate => ({
  id: String(id),
  candidate_key: `draft-${id}`,
  change: 'CREATE',
  kind: 'REQUIREMENT',
  status: 'PENDING',
  after: { description: `후보 ${id}` },
  changed_fields: [],
  conflicting_fields: [],
  sources: [{ location: '1', quote: `원문 ${id}` }],
  answer_refs: [],
  support: 'DOCUMENT',
})

const job: DraftJob = {
  id: '1',
  document_version_id: '11',
  status: 'COMPLETED',
  mode: 'DOCUMENT_ONLY',
  candidates: Array.from({ length: 12 }, (_, index) => candidate(index + 1)),
}

it('후보가 많으면 5개씩 보이고, 전체 선택은 보이는 후보만 고른다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi.fn().mockResolvedValue({
    succeeded: 5,
    failed: 0,
    results: [],
  })
  render(
    <DocumentCandidates
      jobs={[job]}
      labelOfVersion={(id) => `문서 ${id}`}
      isLeader
      busy={false}
      onRetry={vi.fn()}
      onAdopt={onAdopt}
      onDiscard={vi.fn()}
    />,
  )
  expect(
    screen.getAllByRole('checkbox', { name: /^후보 \d+ 선택$/ }),
  ).toHaveLength(5)
  await user.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  expect(screen.getByText('5개 선택')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(onAdopt.mock.calls[0][0].items).toHaveLength(5)
  await user.click(
    screen.getByRole('button', { name: /후보 더 보기 \(7개 남음\)/ }),
  )
  expect(
    screen.getAllByRole('checkbox', { name: /^후보 \d+ 선택$/ }),
  ).toHaveLength(10)
})

it('질문은 5개씩 보이고 더 보기로 이어서 본다', async () => {
  const user = userEvent.setup()
  const questions = Array.from({ length: 12 }, (_, index) => ({
    id: String(index + 1),
    number: index + 1,
    question: `내용 ${index + 1}번`,
    status: 'OPEN' as const,
  }))
  vi.spyOn(planningApi, 'questions').mockResolvedValue({
    items: questions,
    has_more: false,
    total_count: 12,
  } as never)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PlanningQuestions
        projectId="1"
        isLeader
        busy={false}
        generations={[]}
        answerable
        published={false}
        onAnswer={vi.fn()}
      />
    </QueryClientProvider>,
  )
  expect(await screen.findByText(/^내용 5번$/)).toBeInTheDocument()
  expect(screen.queryByText(/^내용 6번$/)).toBeNull()
  expect(screen.getByText(/전체 질문 12개 · 5개 표시/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /질문 더 보기/ }))
  expect(screen.getByText(/^내용 10번$/)).toBeInTheDocument()
  expect(screen.queryByText(/^내용 11번$/)).toBeNull()
})
