/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi, documentsApi, planningApi } from '@/api/services'
import { AxiosError, AxiosHeaders } from 'axios'
import { vi } from 'vitest'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { WAITING_POLL_MS } from '@/lib/pollIntervals'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { PlanningPanel } from './PlanningPanel'

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
  vi.restoreAllMocks()
  restore()
  localStorage.clear()
})

function renderPanel(isLeader = true, projectId = '102') {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PlanningPanel projectId={projectId} isLeader={isLeader} />
    </QueryClientProvider>,
  )
  return user
}
const CONFLICT_QUESTION =
  '기획서의 "팀원은 문서를 직접 승인한다"와 요구사항의 "승인은 팀장만 한다"가 달라요. 어느 쪽이 맞나요?'
const cardOf = (text: string) =>
  screen.getByText(text).closest('article') as HTMLElement

// PR #72 6차 조사 SIXTH-DOC-02: 서버는 문서 4종이 모두 팀장 승인을 받아야 기준을 만든다(409 document_not_approved).
// 승인 전에는 발행 버튼을 끄고 이유와 공용 문서함으로 가는 길을 보인다.
it('문서 4종이 모두 승인되기 전에는 발행을 끄고 공용 문서함으로 보낸다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PlanningPanel projectId="102" isLeader />
    </QueryClientProvider>,
  )
  expect(
    await screen.findByText(/문서 4종이 모두 팀장 승인을 받아야/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '검토한 설계 승인·발행' }),
  ).toBeDisabled()
  await user.click(
    screen.getByRole('button', { name: '공용 문서함에서 승인하기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('documents')
})

// S15P31A108-202: 232 이후 질문은 유형·종류·근거 상태를 가진다.
it('질문 카드에 종류·유형·근거 미확인 칩과 전체 개수를 보인다', async () => {
  renderPanel()
  expect(await screen.findByText('전체 질문 5개')).toBeInTheDocument()
  const conflict = cardOf(CONFLICT_QUESTION)
  expect(within(conflict).getByText('문서 간 충돌')).toBeInTheDocument()
  expect(within(conflict).getByText('서술형')).toBeInTheDocument()
  expect(within(conflict).getByText('근거 미확인')).toBeInTheDocument()
  expect(
    within(cardOf('다국어를 지원할 계획이 있나요?')).getByText(
      '답변 없이 닫음',
    ),
  ).toBeInTheDocument()
  expect(
    within(cardOf('프로젝트 삭제는 누가 할 수 있나요?')).queryByText(
      '근거 미확인',
    ),
  ).not.toBeInTheDocument()
})

it('하나만 고르는 선택형은 선택지나 기타 입력으로 답한다', async () => {
  const user = renderPanel()
  await screen.findByText('전체 질문 5개')
  await user.click(cardOf('프로젝트 삭제는 누가 할 수 있나요?'))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: /^저장/ }))
  expect(
    await within(dialog).findByText('답변을 하나 이상 골라 주세요.'),
  ).toBeInTheDocument()
  await user.click(within(dialog).getByLabelText('기타'))
  await user.type(
    within(dialog).getByLabelText('기타 답변'),
    '팀장이 정한 사람',
  )
  await user.click(within(dialog).getByRole('button', { name: /^저장/ }))
  expect(await screen.findByText('답변을 저장했어요.')).toBeInTheDocument()
  expect(
    within(cardOf('프로젝트 삭제는 누가 할 수 있나요?')).getByText(
      '팀장이 정한 사람',
    ),
  ).toBeInTheDocument()
})

it('여러 개 고르는 선택형은 기타 없이 선택지를 모두 보여 준다', async () => {
  const user = renderPanel()
  await screen.findByText('전체 질문 5개')
  await user.click(cardOf('알림은 어떤 방식으로 보낼까요?'))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).queryByLabelText('기타')).not.toBeInTheDocument()
  await user.click(within(dialog).getByLabelText('앱 안 알림'))
  await user.click(within(dialog).getByLabelText('이메일'))
  await user.click(within(dialog).getByRole('button', { name: /^저장/ }))
  expect(
    await within(cardOf('알림은 어떤 방식으로 보낼까요?')).findByText(
      '앱 안 알림, 이메일',
    ),
  ).toBeInTheDocument()
})

it('서술형 질문은 답하지 않고 닫을 수 있다', async () => {
  const user = renderPanel()
  await screen.findByText('전체 질문 5개')
  const text = CONFLICT_QUESTION
  await user.click(cardOf(text))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText('0 / 500')).toBeInTheDocument()
  await user.click(
    within(dialog).getByRole('button', { name: '답변하지 않고 닫기' }),
  )
  expect(
    await screen.findByText('질문을 답변 없이 닫았어요.'),
  ).toBeInTheDocument()
  expect(within(cardOf(text)).getByText('답변 없이 닫음')).toBeInTheDocument()
})

it('팀원에게는 답변 입력을 보이지 않는다', async () => {
  renderPanel(false)
  await screen.findByText('전체 질문 5개')
  expect(cardOf('프로젝트 삭제는 누가 할 수 있나요?')).not.toHaveAttribute(
    'role',
    'button',
  )
  expect(screen.queryByText('눌러서 답변하기')).not.toBeInTheDocument()
})

it('질문 생성이 실패하면 안내하고 다시 만들 수 있다', async () => {
  localStorage.setItem('beez.mock.planQuestions', 'failed')
  const user = renderPanel()
  // 문서마다 따로 안내한다(예시 API 는 4종 모두 실패한다).
  expect(
    (await screen.findAllByText(/보완 질문을 만들지 못했어요/)).length,
  ).toBe(4)
  await user.click(
    screen.getAllByRole('button', { name: '질문 다시 만들기' })[0],
  )
  expect(
    await screen.findByText('보완 질문을 다시 만들도록 접수했어요.'),
  ).toBeInTheDocument()
})

// S15P31A108-202: 초안 생성은 후보를 만들고, 팀장이 채택해야 설계 항목 초안이 된다.
async function prepareCandidates() {
  const documents = await documentsApi.list('102')
  await planningApi.generateDrafts('102', {
    document_version_ids: documents.items.map((item) => item.latest_draft!.id),
  })
  await planningApi.drafts('102')
  await planningApi.drafts('102')
}

it('초안을 만드는 동안에는 진행 안내를 보이고 만들기 버튼을 끈다', async () => {
  // 예시 어댑터는 조회할 때마다 작업을 한 단계씩 진행시킨다. 진행 중 응답을 고정해 끝나기 전 화면을 확인한다.
  const original = planningApi.drafts.bind(planningApi)
  let running: Awaited<ReturnType<typeof original>> | undefined
  vi.spyOn(planningApi, 'drafts').mockImplementation(async (...args) => {
    if (running) return running
    const result = await original(...args)
    if (
      result.jobs.some(
        (job) => job.status === 'PENDING' || job.status === 'RUNNING',
      )
    )
      running = result
    return result
  })
  const user = renderPanel()
  await screen.findByText('전체 질문 5개')
  await user.click(screen.getByRole('button', { name: '답변으로 초안 만들기' }))
  expect(
    (await screen.findAllByText(/초안을 만들고 있어요/)).length,
  ).toBeGreaterThan(0)
  expect(
    screen.getByRole('button', { name: '답변으로 초안 만들기' }),
  ).toBeDisabled()
})

it('수정 후보는 바뀐 필드를 이전과 이후로 나란히 보인다', async () => {
  await prepareCandidates()
  renderPanel()
  const card = (await screen.findByText('기존 항목 수정')).closest(
    'article',
  ) as HTMLElement
  expect(within(card).getByText('기존 항목 수정')).toBeInTheDocument()
  expect(within(card).getByText('문서 근거')).toBeInTheDocument()
  expect(within(card).getByText('이전')).toBeInTheDocument()
  expect(within(card).getByText('이후')).toBeInTheDocument()
  expect(
    within(card).getByText(/로그인한 사용자는 참여 프로젝트를 조회한다\.$/),
  ).toBeInTheDocument()
  expect(within(card).getByText(/\(답변을 반영해 구체화\)/)).toBeInTheDocument()
  expect(within(card).queryByText('원문과 답변이 달라요')).toBeNull()
})

it('충돌한 필드는 확정해야 채택하고 채택하면 설계 항목이 된다', async () => {
  await planningApi.answerQuestion('102', '65', { answer: '팀장만 승인해요.' })
  await prepareCandidates()
  const user = renderPanel()
  const card = (await screen.findByText('기존 항목 수정')).closest(
    'article',
  ) as HTMLElement
  expect(within(card).getByText('원문과 답변이 달라요')).toBeInTheDocument()
  // changed_fields 는 {field, change, before, after} 객체다. 답변으로 새로 생긴 필드는 '추가'로 보인다.
  expect(within(card).getByText('추가')).toBeInTheDocument()
  expect(within(card).getByText('답변 반영 내용')).toBeInTheDocument()
  const adopt = within(card).getByRole('button', { name: '채택' })
  expect(adopt).toBeDisabled()
  await user.click(
    within(card).getByLabelText(/‘설명’은 이후 내용으로 확정해요/),
  )
  expect(adopt).toBeEnabled()
  await user.click(adopt)
  expect(
    await screen.findByText('후보를 채택했어요. 설계 항목 초안에 반영했어요.'),
  ).toBeInTheDocument()
  expect(await screen.findByText(/처리한 후보 \d+개/)).toBeInTheDocument()
  expect(
    await screen.findByText(/\(답변을 반영해 구체화\)/, {
      selector: 'p.text-sm',
    }),
  ).toBeInTheDocument()
})

it('근거가 없는 후보는 확인 체크를 해야 채택할 수 있다', async () => {
  await prepareCandidates()
  const user = renderPanel()
  const card = (
    await screen.findByText('policy-', { exact: false, selector: 'strong' })
  ).closest('article') as HTMLElement
  expect(within(card).getAllByText('근거 없음').length).toBeGreaterThan(0)
  const adopt = within(card).getByRole('button', { name: '채택' })
  expect(adopt).toBeDisabled()
  await user.click(
    within(card).getByLabelText(/근거를 찾지 못한 제안이라는 점을 확인했어요/),
  )
  expect(adopt).toBeEnabled()
})

it('후보를 폐기하면 처리한 후보로 옮겨 간다', async () => {
  await prepareCandidates()
  const user = renderPanel()
  const card = (
    await screen.findByText('policy-', { exact: false, selector: 'strong' })
  ).closest('article') as HTMLElement
  await user.click(within(card).getByRole('button', { name: '폐기' }))
  expect(await screen.findByText('후보를 폐기했어요.')).toBeInTheDocument()
  expect(await screen.findByText(/처리한 후보 1개/)).toBeInTheDocument()
})

it('후보가 낡았으면 그 후보만 막고 초안을 다시 만들게 안내한다', async () => {
  await prepareCandidates()
  localStorage.setItem('beez.mock.planDraft', 'stale')
  const user = renderPanel()
  const card = (
    await screen.findByText('policy-', { exact: false, selector: 'strong' })
  ).closest('article') as HTMLElement
  await user.click(
    within(card).getByLabelText(/근거를 찾지 못한 제안이라는 점을 확인했어요/),
  )
  await user.click(within(card).getByRole('button', { name: '채택' }))
  expect(
    await within(card).findByText(/후보를 만든 뒤 문서나 답변/),
  ).toBeInTheDocument()
  localStorage.removeItem('beez.mock.planDraft')
  const generate = vi.spyOn(planningApi, 'generateDrafts')
  await user.click(
    within(card).getByRole('button', { name: '초안 다시 만들기' }),
  )
  await waitFor(() => expect(generate).toHaveBeenCalledTimes(1))
  // 낡은 후보가 나온 문서만 다시 만든다
  expect(generate.mock.calls[0][1].document_version_ids).toHaveLength(1)
  expect(
    await screen.findByText(
      '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.',
    ),
  ).toBeInTheDocument()
})

it('초안 작업이 실패하면 이유를 알리고 다시 만들 수 있다', async () => {
  localStorage.setItem('beez.mock.planDraft', 'failed')
  await prepareCandidates()
  const user = renderPanel()
  expect(
    (await screen.findAllByText(/초안을 만들지 못했어요/)).length,
  ).toBeGreaterThan(0)
  expect(
    screen.getAllByRole('button', { name: '다시 만들기' })[0],
  ).toBeEnabled()
  localStorage.removeItem('beez.mock.planDraft')
  const generate = vi.spyOn(planningApi, 'generateDrafts')
  const retries = screen.getAllByRole('button', { name: '다시 만들기' })
  await user.click(retries[0])
  expect(
    await screen.findByText(
      '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.',
    ),
  ).toBeInTheDocument()
  // 실패한 문서 하나만 다시 만든다. 문서마다 AI 를 한 번씩 부르므로 나머지까지 보내면 비용이 든다.
  expect(generate).toHaveBeenCalledTimes(1)
  expect(generate.mock.calls[0][1].document_version_ids).toHaveLength(1)
})

it('초안 만들기 버튼은 모든 문서를, 낡은 후보의 다시 만들기는 그 문서만 보낸다', async () => {
  await prepareCandidates()
  const generate = vi.spyOn(planningApi, 'generateDrafts')
  const user = renderPanel()
  await user.click(
    await screen.findByRole('button', { name: '답변으로 초안 만들기' }),
  )
  await waitFor(() => expect(generate).toHaveBeenCalledTimes(1))
  expect(generate.mock.calls[0][1].document_version_ids.length).toBeGreaterThan(
    1,
  )
})

it('팀원은 후보를 볼 수 있지만 채택·폐기·만들기는 할 수 없다', async () => {
  await prepareCandidates()
  renderPanel(false)
  await screen.findByText('기존 항목 수정')
  expect(screen.queryByRole('button', { name: '채택' })).toBeNull()
  expect(screen.queryByRole('button', { name: '폐기' })).toBeNull()
  expect(
    screen.queryByRole('button', { name: '답변으로 초안 만들기' }),
  ).toBeNull()
})

// live 점검: 다시 만들기가 실패해도 서버는 기존 후보를 바꾸지 않으므로 이전 후보를 계속 보인다.
it('다시 만들기가 실패해도 이전에 완료된 후보를 계속 보인다', async () => {
  await prepareCandidates()
  localStorage.setItem('beez.mock.planDraft', 'failed')
  await prepareCandidates()
  renderPanel()
  expect(await screen.findByText('기존 항목 수정')).toBeInTheDocument()
  expect(
    (await screen.findAllByText(/초안을 만들지 못했어요/)).length,
  ).toBeGreaterThan(0)
})

// 리뷰 반영: 닫으면 서버가 이전 답변을 지우므로 이미 답한 질문에는 닫기를 보이지 않는다.
it('이미 답한 질문의 수정 창에는 답하지 않고 닫기를 보이지 않는다', async () => {
  await planningApi.answerQuestion('102', '65', { answer: '팀장만 승인해요.' })
  const user = renderPanel()
  await screen.findByText('전체 질문 5개')
  await user.click(cardOf(CONFLICT_QUESTION))
  const dialog = await screen.findByRole('dialog')
  expect(
    within(dialog).queryByRole('button', { name: '답변하지 않고 닫기' }),
  ).not.toBeInTheDocument()
  expect(within(dialog).getByRole('textbox', { name: '답변' })).toHaveValue(
    '팀장만 승인해요.',
  )
})

// S15P31A108-396: 본문이 바뀐 뒤 질문을 다시 만드는 중이거나 그러지 못하면 이전 질문을 최신처럼 보이지 않고,
// 서버가 막는 미답변 질문(409 question_outdated)은 누를 수 없게 한다. 답한 질문은 이력이라 고칠 수 있다.
it('이전 본문으로 만든 질문에는 갱신 상태를 보이고 미답변 질문은 답할 수 없게 한다', async () => {
  vi.spyOn(planningApi, 'questions').mockResolvedValue({
    items: [
      {
        id: '1',
        number: 1,
        question: '갱신 중인 질문',
        status: 'OPEN',
        freshness: 'REFRESHING',
      },
      {
        id: '2',
        number: 2,
        question: '갱신 실패한 질문',
        status: 'OPEN',
        freshness: 'REFRESH_FAILED',
      },
      {
        id: '3',
        number: 3,
        question: '이미 답한 질문',
        status: 'ANSWERED',
        answer: '팀장만',
        freshness: 'REFRESH_FAILED',
      },
      {
        id: '4',
        number: 4,
        question: '최신 질문',
        status: 'OPEN',
        freshness: 'CURRENT',
      },
    ],
    has_more: false,
    total_count: 4,
  })
  renderPanel()
  await screen.findByText('갱신 중인 질문')
  const refreshing = cardOf('갱신 중인 질문')
  expect(
    within(refreshing).getByText('이전 본문 기준 · 갱신 중'),
  ).toBeInTheDocument()
  expect(refreshing).not.toHaveAttribute('role', 'button')
  expect(
    within(refreshing).getByText(/끝난 뒤 답할 수 있어요/),
  ).toBeInTheDocument()

  const failed = cardOf('갱신 실패한 질문')
  expect(
    within(failed).getByText('최신 본문으로 갱신하지 못함'),
  ).toBeInTheDocument()
  expect(failed).not.toHaveAttribute('role', 'button')

  const answered = cardOf('이미 답한 질문')
  expect(
    within(answered).getByText('최신 본문으로 갱신하지 못함'),
  ).toBeInTheDocument()
  expect(answered).toHaveAttribute('role', 'button')

  const current = cardOf('최신 질문')
  expect(
    within(current).queryByText(/이전 본문 기준|갱신하지 못함/),
  ).not.toBeInTheDocument()
  expect(current).toHaveAttribute('role', 'button')
})

// 질문 생성이 시작되기 전 문서 재분석 단계에는 진행 안내가 없어 목록을 다시 읽지 않았다. '갱신 중' 질문이 있으면 끝날 때까지 따라간다.
it('갱신 중인 질문이 있으면 목록을 다시 읽어 갱신이 끝난 것을 보인다', async () => {
  let refreshed = false
  vi.spyOn(planningApi, 'questions').mockImplementation(async () => ({
    items: [
      {
        id: '1',
        number: 1,
        question: '다시 만드는 질문',
        status: 'OPEN',
        freshness: refreshed ? 'CURRENT' : 'REFRESHING',
      },
    ],
    has_more: false,
    total_count: 1,
  }))
  renderPanel()
  expect(
    await screen.findByText('이전 본문 기준 · 갱신 중'),
  ).toBeInTheDocument()
  refreshed = true
  await waitFor(() =>
    expect(
      screen.queryByText('이전 본문 기준 · 갱신 중'),
    ).not.toBeInTheDocument(),
  )
  expect(cardOf('다시 만드는 질문')).toHaveAttribute('role', 'button')
})

it('기준 설계가 발행된 프로젝트에서는 질문 카드를 눌러 답할 수 없다', async () => {
  renderPanel(true, '101')
  const question = await screen.findByText(
    '프로젝트 삭제 권한은 인간 팀장만 가지나요?',
  )
  expect(
    await screen.findByText('기준 설계가 발행되어 답변을 바꿀 수 없어요.'),
  ).toBeInTheDocument()
  expect(question.closest('article')).not.toHaveAttribute('role', 'button')
})

it('초안 작업 조회에 실패하면 후보 자리에 안내와 다시 불러오기를 보인다', async () => {
  vi.spyOn(planningApi, 'drafts').mockRejectedValue(new Error('network'))
  renderPanel()
  expect(
    await screen.findByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
  vi.restoreAllMocks()
})

// 리뷰 반영: 질문 생성 상태는 문서마다 따로 따라간다.
async function stubAnalyses(
  reply: (kind: string, call: number) => object | undefined,
) {
  const docs = (await documentsApi.list('102')).items
  const calls: Record<string, number> = {}
  vi.spyOn(planningApi, 'analysis').mockImplementation(
    async (_projectId, documentId, query) => {
      const doc = docs.find((item) => item.id === documentId)!
      calls[doc.kind] = (calls[doc.kind] ?? 0) + 1
      const generation = reply(doc.kind, calls[doc.kind])
      return {
        document_version_id: query.document_version_id,
        status: 'COMPLETED',
        items: [],
        questions: [],
        ...(generation ? { question_generation: generation } : {}),
      } as never
    },
  )
  return calls
}

it('분석 직후 질문 생성 행이 아직 없어도 기다렸다가 진행·완료를 따라가고 목록을 갱신한다', async () => {
  // REQUIREMENTS: question_generation 없음(대기) → RUNNING → COMPLETED. 단계는 호출 횟수가 아니라 시험이 넘긴다.
  // 폴링 주기가 짧아 호출 횟수로 넘기면 첫 안내를 확인하기 전에 완료까지 지나갈 수 있다.
  let phase: 'waiting' | 'running' | 'done' = 'waiting'
  const calls = await stubAnalyses((kind) =>
    kind !== 'REQUIREMENTS' || phase === 'done'
      ? { status: 'COMPLETED' }
      : phase === 'running'
        ? { status: 'RUNNING' }
        : undefined,
  )
  const original = planningApi.questions
  vi.spyOn(planningApi, 'questions').mockImplementation(
    async (projectId, query, options) => {
      const result = await original(projectId, query, options)
      return phase === 'done'
        ? result
        : { ...result, items: [], total_count: 0 }
    },
  )
  renderPanel()
  expect(
    await screen.findByText(/비비가 보완 질문을 만들고 있어요/),
  ).toBeInTheDocument()
  // 실행 행이 없는 동안에도, 진행 중일 때도 계속 다시 읽는다.
  const waiting = calls.REQUIREMENTS
  await waitFor(() => expect(calls.REQUIREMENTS).toBeGreaterThan(waiting + 1))
  phase = 'running'
  const running = calls.REQUIREMENTS
  await waitFor(() => expect(calls.REQUIREMENTS).toBeGreaterThan(running + 1))
  expect(
    screen.getByText(/비비가 보완 질문을 만들고 있어요/),
  ).toBeInTheDocument()
  phase = 'done'
  await waitFor(() =>
    expect(
      screen.queryByText(/비비가 보완 질문을 만들고 있어요/),
    ).not.toBeInTheDocument(),
  )
  expect(await screen.findByText('전체 질문 5개')).toBeInTheDocument()
})

it('한 문서의 질문 생성이 실패해도 다른 문서의 진행을 따라가고 끝나면 목록을 갱신한다', async () => {
  let erdDone = false
  const calls = await stubAnalyses((kind) =>
    kind === 'PLAN'
      ? { status: 'FAILED', error_code: 'ai_unavailable', retryable: true }
      : kind === 'ERD'
        ? erdDone
          ? { status: 'COMPLETED' }
          : { status: 'RUNNING' }
        : { status: 'COMPLETED' },
  )
  renderPanel()
  expect(
    await screen.findByText(/기획안 v1 보완 질문을 만들지 못했어요/),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/비비가 보완 질문을 만들고 있어요/),
  ).toBeInTheDocument()
  // 실패한 문서가 있어도 진행 중인 문서(ERD)는 끝날 때까지 계속 읽는다.
  const before = calls.ERD
  await waitFor(() => expect(calls.ERD).toBeGreaterThan(before + 1))
  erdDone = true
  await waitFor(() =>
    expect(
      screen.queryByText(/비비가 보완 질문을 만들고 있어요/),
    ).not.toBeInTheDocument(),
  )
  // 실패 안내와 다시 만들기는 그대로 남는다.
  expect(
    screen.getByText(/기획안 v1 보완 질문을 만들지 못했어요/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '질문 다시 만들기' }),
  ).toBeInTheDocument()
})

// 리뷰 반영: 서버 워커는 문서를 하나씩 처리해서 뒤 문서는 첫 AI 호출이 길면 한참 실행 행이 없다.
// 대기 상태는 시간으로 끊지 않고, 나중에 실패·완료가 와도 새로고침 없이 따라간다.
it('여러 문서가 대기하고 첫 AI 호출이 15초를 넘겨도 계속 읽어 나중의 실패와 완료를 보여 준다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    // 세 문서가 이어서 대기: REQUIREMENTS 는 AI 호출 중(RUNNING), ERD·API_SPEC 은 아직 행이 없다(순서 대기).
    let phase: 'running' | 'later' = 'running'
    const calls = await stubAnalyses((kind) => {
      if (kind === 'PLAN') return { status: 'COMPLETED' }
      if (phase === 'running')
        return kind === 'REQUIREMENTS' ? { status: 'RUNNING' } : undefined
      // 25초쯤 뒤: 앞 문서는 끝나고 ERD 는 실패, API_SPEC 은 이제 막 진행 중이다.
      return kind === 'REQUIREMENTS'
        ? { status: 'COMPLETED' }
        : kind === 'ERD'
          ? { status: 'FAILED', error_code: 'ai_unavailable', retryable: true }
          : { status: 'RUNNING' }
    })
    renderPanel()
    expect(
      await screen.findByText(/비비가 보완 질문을 만들고 있어요/),
    ).toBeInTheDocument()
    const before = calls.ERD
    // 15초(예전 제한)를 훌쩍 넘겨도 대기 문서를 계속 읽는다.
    await vi.advanceTimersByTimeAsync(25_000)
    expect(calls.ERD).toBeGreaterThan(before + 5)
    expect(
      screen.getByText(/비비가 보완 질문을 만들고 있어요/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText(/보완 질문을 만들지 못했어요/),
    ).not.toBeInTheDocument()
    phase = 'later'
    await vi.advanceTimersByTimeAsync(4_000)
    expect(
      await screen.findByText(/ERD v1 보완 질문을 만들지 못했어요/),
    ).toBeInTheDocument()
    // API_SPEC 은 계속 진행 중이라 진행 안내도 남는다.
    expect(
      screen.getByText(/비비가 보완 질문을 만들고 있어요/),
    ).toBeInTheDocument()
  } finally {
    vi.useRealTimers()
  }
}, 30_000)

it('기준 설계가 발행된 프로젝트는 질문 생성을 기다리지 않는다', async () => {
  const calls = await stubAnalyses(() => undefined)
  renderPanel(true, '101')
  await screen.findByText('기준 설계가 발행되어 답변을 바꿀 수 없어요.')
  expect(
    screen.queryByText(/비비가 보완 질문을 만들고 있어요/),
  ).not.toBeInTheDocument()
  const seen = { ...calls }
  // 대기 주기를 여러 번 지나도 다시 읽지 않는다.
  await new Promise((resolve) => setTimeout(resolve, WAITING_POLL_MS * 5))
  expect(calls).toEqual(seen)
})

// S15P31A108-363: 항목 추가 폼은 JSON 구조를 안내하고, 틀린 이유를 원인대로 알린다.
it('항목 추가 폼은 내용 구조 버전 칸 없이 안내와 예시를 보이고 버전 1로 저장한다', async () => {
  const create = vi.spyOn(planningApi, 'createItem')
  const user = renderPanel()
  await user.click(await screen.findByRole('button', { name: '항목 추가' }))
  expect(screen.queryByLabelText(/내용 구조 버전/)).toBeNull()
  expect(screen.getByText(/API 계약은 description·method·path/)).toBeVisible()
  expect(screen.getByText(/"method": "GET"/)).toBeVisible()
  await user.click(screen.getByLabelText(/설계 내용/))
  await user.paste('{"description":"직접 추가한 요구사항"}')
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(await screen.findByText('초안을 추가했어요.')).toBeInTheDocument()
  expect(create).toHaveBeenCalledWith(
    '102',
    expect.objectContaining({ schema_version: 1 }),
  )
})

it('항목 추가 폼에 일반 글을 쓰면 JSON 형식 안내를 보이고 저장하지 않는다', async () => {
  const create = vi.spyOn(planningApi, 'createItem')
  const user = renderPanel()
  await user.click(await screen.findByRole('button', { name: '항목 추가' }))
  await user.click(screen.getByLabelText(/설계 내용/))
  await user.paste('로그인할 수 있어야 한다')
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(await screen.findByText(/JSON 형식으로 입력해 주세요/)).toBeVisible()
  expect(create).not.toHaveBeenCalled()
})

it('서버가 내용을 거절하면 서버 문구를 폼에만 보이고 위쪽에 틀린 안내를 띄우지 않는다', async () => {
  const user = renderPanel()
  await user.click(await screen.findByRole('button', { name: '항목 추가' }))
  await user.click(screen.getByLabelText(/설계 내용/))
  // 폼 검사를 통과해 서버까지 가는 경우(종류별 필수 키 누락)를 만들려고 서버 응답을 거절로 바꾼다.
  vi.spyOn(planningApi, 'createItem').mockRejectedValue(
    new AxiosError('bad', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: {
          code: 'validation_failed',
          message: 'API method는 대문자 HTTP 메서드입니다.',
        },
      },
    }),
  )
  await user.paste('{"description":"API"}')
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(
    await screen.findByText('API method는 대문자 HTTP 메서드입니다.'),
  ).toBeVisible()
  expect(screen.queryByText(/최신 상태와 권한을 확인해 주세요/)).toBeNull()
})

it('보완 질문을 오래 만드는 중이면 만드는 문서를 알리고 상태를 다시 확인할 길을 준다 (S15P31A108-359)', async () => {
  const calls = await stubAnalyses((kind) =>
    kind === 'PLAN'
      ? { status: 'FAILED', error_code: 'ai_unavailable', retryable: true }
      : kind === 'ERD'
        ? undefined
        : { status: 'COMPLETED' },
  )
  renderPanel()
  expect(
    await screen.findByText(/기획안 v1 보완 질문을 만들지 못했어요/),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '질문 다시 만들기' }),
  ).toBeInTheDocument()
  expect(
    await screen.findByText(/만드는 중인 문서: ERD v1/),
  ).toBeInTheDocument()
  // 처음에는 오래 걸린다는 안내가 없고, 같은 상태가 이어지면 안내와 확인 버튼이 생긴다.
  expect(screen.queryByText(/평소보다 오래 걸리고 있어요/)).toBeNull()
  const button = await screen.findByRole('button', { name: '상태 다시 확인' })
  const before = calls.ERD
  await userEvent.click(button)
  await waitFor(() => expect(calls.ERD).toBeGreaterThan(before))
  // 실패한 문서의 다시 만들기는 그대로 남는다.
  expect(
    screen.getByRole('button', { name: '질문 다시 만들기' }),
  ).toBeInTheDocument()
})

it('보완 질문 생성이 전부 실패한 상태에서도 초안 만들기를 누르면 접수 안내가 보인다 (S15P31A108-359)', async () => {
  await stubAnalyses(() => ({
    status: 'FAILED',
    error_code: 'ai_unavailable',
    retryable: true,
  }))
  const user = renderPanel()
  expect(
    (await screen.findAllByText(/보완 질문을 만들지 못했어요/)).length,
  ).toBe(4)
  await user.click(
    await screen.findByRole('button', { name: '답변으로 초안 만들기' }),
  )
  expect(
    await screen.findByText(
      '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.',
    ),
  ).toBeInTheDocument()
})
