/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi, documentsApi, planningApi } from '@/api/services'
import { vi } from 'vitest'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
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
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  expect(
    await within(dialog).findByText('답변을 하나 이상 골라 주세요.'),
  ).toBeInTheDocument()
  await user.click(within(dialog).getByLabelText('기타'))
  await user.type(
    within(dialog).getByLabelText('기타 답변'),
    '팀장이 정한 사람',
  )
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
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
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
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
  expect(
    await screen.findByText(/보완 질문을 만들지 못했어요/),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '질문 다시 만들기' }))
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
  await user.click(
    within(card).getByRole('button', { name: '초안 다시 만들기' }),
  )
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
  await user.click(screen.getAllByRole('button', { name: '다시 만들기' })[0])
  expect(
    await screen.findByText(
      '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.',
    ),
  ).toBeInTheDocument()
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
