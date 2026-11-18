import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import type { PlanningQuestion } from '@/api/contracts/common'
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

const showQuestions = (
  items: (Partial<PlanningQuestion> & { id: string; question: string })[],
  onAnswer = vi.fn().mockResolvedValue(undefined),
) => {
  vi.spyOn(planningApi, 'questions').mockResolvedValue({
    items,
    has_more: false,
    total_count: items.length,
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
        onAnswer={onAnswer}
      />
    </QueryClientProvider>,
  )
}
const rowOf = (text: string) =>
  screen.queryByRole('button', { name: (name) => name.includes(text) })

it('질문 목록은 20개씩 보이고 더 보기로 이어서 본다', async () => {
  const user = userEvent.setup()
  showQuestions(
    Array.from({ length: 25 }, (_, index) => ({
      id: String(index + 1),
      number: index + 1,
      question: `내용 ${index + 1}번`,
      status: 'OPEN' as const,
    })),
  )
  expect(await screen.findByRole('button', { name: '전체 25' })).toBeVisible()
  expect(rowOf('내용 20번')).toBeInTheDocument()
  expect(rowOf('내용 21번')).toBeNull()
  await user.click(
    screen.getByRole('button', { name: '질문 더 보기 (5개 남음)' }),
  )
  expect(rowOf('내용 25번')).toBeInTheDocument()
})

// 예전 답변 창의 '남은 질문만 넘기기'와 번호 칩은 왼쪽 목록이 맡는다. 목록에 보이는 질문만 이전·다음으로 넘긴다.
it('답할 질문이 기본 목록이라 답한 질문은 목록과 이전·다음에서 빠지고, 전체로 바꾸면 모두 넘긴다', async () => {
  const user = userEvent.setup()
  showQuestions([
    { id: '1', number: 1, question: '첫 질문', status: 'OPEN' },
    {
      id: '2',
      number: 2,
      question: '답한 질문',
      status: 'ANSWERED',
      answer: '답',
    },
    { id: '3', number: 3, question: '셋째 질문', status: 'OPEN' },
  ])
  expect(
    await screen.findByRole('button', { name: '답할 질문 2' }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(rowOf('답한 질문')).toBeNull()
  // 처음에는 목록의 첫 질문이 열려 있다.
  expect(screen.getByRole('heading', { name: '첫 질문' })).toBeInTheDocument()
  expect(screen.getByText('질문 1 / 3')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /^다음/ }))
  expect(screen.getByText('질문 3 / 3')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '전체 3' }))
  await user.click(rowOf('셋째 질문')!)
  await user.click(screen.getByRole('button', { name: /^이전/ }))
  expect(screen.getByRole('heading', { name: '답한 질문' })).toBeInTheDocument()
  expect(screen.getByText('질문 2 / 3')).toBeInTheDocument()
})

const shortAnswer = {
  question_type: 'SHORT_ANSWER',
  answer_format: { type: 'SHORT_ANSWER', max_length: 200 },
} as const

// PR #303 재검토 2: 잠긴 질문을 열어도 답변 폼이 내려가지 않아, 저장에 실패해 남겨 둔 답이 사라지지 않는다.
it('저장에 실패해 남겨 둔 답은 잠긴 질문을 열었다 돌아와도 그대로 남는다', async () => {
  const user = userEvent.setup()
  showQuestions(
    [
      {
        id: '1',
        number: 1,
        question: '먼저 쓰는 질문',
        status: 'OPEN',
        ...shortAnswer,
      },
      {
        id: '2',
        number: 2,
        question: '갱신 중인 질문',
        status: 'OPEN',
        freshness: 'REFRESHING',
      },
    ],
    vi.fn().mockRejectedValue(new Error('x')),
  )
  await user.type(await screen.findByLabelText('답변'), '남길 답')
  await user.tab()
  expect(await screen.findByText('저장 못 했어요')).toBeInTheDocument()
  await user.click(rowOf('갱신 중인 질문')!)
  expect(
    screen.getByText(/새 본문으로 질문을 다시 만드는 중이라/),
  ).toBeInTheDocument()
  expect(screen.queryByLabelText('답변')).toBeNull()
  await user.click(rowOf('먼저 쓰는 질문')!)
  expect(screen.getByLabelText('답변')).toHaveValue('남길 답')
  expect(screen.getByText('저장 못 했어요')).toBeInTheDocument()
})

// PR #303 재검토 3: 받아 둔 목록이 있으면 다시 읽기가 한 번 실패해도 답변 판을 내리지 않는다.
it('질문 목록을 다시 읽다 실패해도 답변 판과 쓰던 답을 그대로 두고 안내만 보인다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  vi.spyOn(planningApi, 'questions')
    .mockResolvedValueOnce({
      items: [
        {
          id: '1',
          number: 1,
          question: '먼저 쓰는 질문',
          status: 'OPEN',
          ...shortAnswer,
        },
      ],
      has_more: false,
      total_count: 1,
    } as never)
    .mockRejectedValue(new Error('network'))
  render(
    <QueryClientProvider client={client}>
      <PlanningQuestions
        projectId="1"
        isLeader
        busy={false}
        generations={[]}
        answerable
        published={false}
        onAnswer={vi.fn().mockResolvedValue(undefined)}
      />
    </QueryClientProvider>,
  )
  await user.type(await screen.findByLabelText('답변'), '쓰던 답')
  await act(() => client.invalidateQueries())
  expect(
    await screen.findByText(/질문 목록을 새로 불러오지 못했어요/),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('답변')).toHaveValue('쓰던 답')
  expect(rowOf('먼저 쓰는 질문')).toBeInTheDocument()
})
