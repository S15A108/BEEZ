/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { authApi, documentsApi, planningApi, tasksApi } from '@/api/services'
import { AxiosError, AxiosHeaders } from 'axios'
import { vi } from 'vitest'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { loadMockDatabase, saveMockDatabase } from '@/mocks/state'
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
type User = ReturnType<typeof userEvent.setup>
// 탭(문서 후보·보완 질문·답변 보완 후보·설계 항목 초안)을 연다
const openTab = async (user: User, name: RegExp) =>
  user.click(await screen.findByRole('tab', { name }))
// 왼쪽 목록에서 글자가 든 줄을 눌러 오른쪽 상세에 연다
const openRow = async (user: User, text: string) =>
  user.click(
    await screen.findByRole('button', {
      name: (name) => name.includes(text),
    }),
  )
// 지금 보이는 탭의 내용(숨은 탭은 빠진다)
const visiblePanel = () => within(screen.getByRole('tabpanel'))
// 보완 질문 목록이 다 읽혔는지: 필터의 '전체' 개수
const questionsLoaded = () => screen.findByRole('button', { name: '전체 5' })

it('승인 설계에서 배정 작업을 만들고 같은 요청의 작업을 다시 사용한다', async () => {
  const user = renderPanel(true, '101')
  const create = await screen.findByRole('button', {
    name: '작업 만들고 배정하기',
  })
  await user.click(create)
  expect(await screen.findByText(/작업 \d+건을 준비했어요/)).toBeInTheDocument()
  const batch = await tasksApi.breakdownStatus('101')
  if (batch?.status !== 'COMPLETED') throw new Error('Expected completed job')
  expect(batch.tasks).toHaveLength(1)
  expect(batch.tasks.some((task) => task.assignee_member_id)).toBe(true)
  expect(await tasksApi.generateBreakdown('101')).toEqual(batch)
  const task = await tasksApi.get('101', batch.tasks[0].id)
  expect(task.current_plan_revision.plan.design_refs).toHaveLength(1)

  await user.click(screen.getByRole('button', { name: '팀 전체 작업 보기' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    initialTaskTab: 'team',
  })
})

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
  expect(screen.getByRole('button', { name: '프로젝트 시작' })).toBeDisabled()
  await user.click(
    screen.getByRole('button', { name: '공용 문서함에서 승인하기' }),
  )
  expect(useWorkspaceStore.getState().pane).toBe('documents')
})

// 예시 서버가 지금 들고 있는 프로젝트 102. 요청을 처리하며 저장본을 바꿔 끼우므로 쓸 때마다 다시 꺼낸다.
const project102 = () =>
  loadMockDatabase().projects.find((item) => item.project.id === '102')!
// 예시 프로젝트 102의 문서 4종을 팀장 승인 상태로 둔다. 설계 초안(REQ-001) 1개는 예시 그대로다.
function approveAllDocuments() {
  const project = project102()
  for (const version of project.versions) version.status = 'APPROVED'
  for (const document of project.documents) {
    document.approved_version = project.versions.find(
      (version) => version.document_id === document.id,
    )
    delete document.latest_draft
  }
  for (const copy of project.working_copies) copy.status = 'APPROVED'
  saveMockDatabase(loadMockDatabase())
  return project
}
const startButton = async () => {
  const button = await screen.findByRole('button', { name: '프로젝트 시작' })
  await waitFor(() => expect(button).toBeEnabled())
  return button
}

// S15P31A108-460: '프로젝트 시작'은 '승인'을 입력하는 확인 창 없이 바로 기준 v1을 발행한다(PLN-A10).
it('프로젝트 시작을 누르면 확인 창 없이 한 번만 발행한다', async () => {
  approveAllDocuments()
  const publish = vi.spyOn(planningApi, 'publishBaseline')
  const user = renderPanel()
  const start = await startButton()
  await user.dblClick(start)
  expect(await screen.findByText('첫 기준 설계를 발행했어요.')).toBeVisible()
  expect(publish).toHaveBeenCalledTimes(1)
  expect(publish).toHaveBeenCalledWith({
    project_id: '102',
    document_version_ids: expect.arrayContaining(['231', '232', '233', '234']),
    design_item_version_ids: ['331'],
    draft_snapshot_hash: expect.any(String),
  })
  expect(publish.mock.calls[0][0].document_version_ids).toHaveLength(4)
  expect(screen.queryByText('최초 기준 설계 승인·발행')).toBeNull()
  expect(screen.queryByRole('textbox', { name: /승인/ })).toBeNull()
  expect(
    await screen.findByText(/기준 설계 v1이 발행되어 있어요/),
  ).toBeInTheDocument()
})

it('검토한 뒤 설계 초안이 바뀌었으면 발행하지 않고 다시 검토하라고 알린다', async () => {
  approveAllDocuments()
  const publish = vi.spyOn(planningApi, 'publishBaseline')
  const user = renderPanel()
  const start = await startButton()
  // 화면이 받아 둔 뒤 다른 곳에서 초안이 하나 더 생긴다.
  await planningApi.createItem('102', {
    kind: 'REQUIREMENT',
    schema_version: 1,
    content: { description: '팀원은 자기 작업만 고친다.' },
  })
  await user.click(start)
  expect(
    await screen.findByText(
      '검토 후 내용이 바뀌었어요. 최신 내용을 다시 검토한 뒤 시작해 주세요.',
    ),
  ).toBeVisible()
  expect(publish).not.toHaveBeenCalled()
  // 다시 읽은 초안 2개가 화면에 보이고, 그대로 다시 누르면 그 내용으로 발행한다.
  await waitFor(() =>
    expect(
      screen.getByRole('tab', { name: /설계 항목 초안\s*2/ }),
    ).toBeVisible(),
  )
  await user.click(await startButton())
  expect(await screen.findByText('첫 기준 설계를 발행했어요.')).toBeVisible()
  expect(publish).toHaveBeenCalledTimes(1)
  expect(publish.mock.calls[0][0].design_item_version_ids).toHaveLength(2)
})

it('검토한 뒤 원본 문서 버전이 바뀌었으면 발행하지 않는다', async () => {
  approveAllDocuments()
  const publish = vi.spyOn(planningApi, 'publishBaseline')
  const user = renderPanel()
  const start = await startButton()
  // 화면이 받아 둔 뒤 기획안의 승인본이 새 버전으로 바뀐다.
  const project = project102()
  const plan = project.documents[0]
  const next = { ...plan.approved_version!, id: '9231', version_no: 2 }
  project.versions.push(next)
  plan.approved_version = next
  saveMockDatabase(loadMockDatabase())
  await user.click(start)
  expect(await screen.findByText(/검토 후 내용이 바뀌었어요/)).toBeVisible()
  expect(publish).not.toHaveBeenCalled()
})

it('서버가 발행을 거절하면 그 이유를 알린다', async () => {
  approveAllDocuments()
  vi.spyOn(planningApi, 'publishBaseline').mockRejectedValue(
    new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: {
          code: 'analysis_not_completed',
          message: 'analysis not completed',
        },
      },
    }),
  )
  const user = renderPanel()
  await user.click(await startButton())
  expect(
    await screen.findByText(/문서 4종의 색인이 아직 끝나지 않았어요/),
  ).toBeVisible()
  expect(
    screen.queryByText(
      '요청을 처리하지 못했어요. 최신 상태와 권한을 확인해 주세요.',
    ),
  ).toBeNull()
})

// 확인 창이 사라져도 채택하지 않은 후보 안내(S15P31A108-389)는 누르기 전에 보인다.
it('채택하지 않은 후보가 있으면 프로젝트 시작 위에 펼치지 않아도 안내한다', async () => {
  const project = approveAllDocuments()
  project.draft_jobs = [
    {
      id: '9001',
      document_version_id: '231',
      status: 'COMPLETED',
      mode: 'DOCUMENT_ONLY',
      candidates: [
        {
          id: '9002',
          candidate_key: 'draft-9002',
          change: 'CREATE',
          kind: 'REQUIREMENT',
          status: 'PENDING',
          after: { description: '팀장은 회의를 연다.' },
          changed_fields: [],
          conflicting_fields: [],
          sources: [{ location: '1. 개요', quote: '팀장은 회의를 연다.' }],
          answer_refs: [],
          support: 'DOCUMENT',
        },
      ],
    },
  ]
  saveMockDatabase(loadMockDatabase())
  renderPanel()
  await startButton()
  expect(
    screen.getByText(/아직 채택하지 않은 후보 1개는 기준에 들어가지 않아요/),
  ).toBeVisible()
})

// S15P31A108-202: 232 이후 질문은 유형·종류·근거 상태를 가진다.
it('질문 상세에 종류·유형·근거 미확인 칩을 보이고 필터에 전체 개수를 센다', async () => {
  const user = renderPanel()
  expect(await questionsLoaded()).toBeInTheDocument()
  // 답할 질문이 남아 있으면 그것부터 보인다. 닫은 질문은 '전체'에만 있다.
  expect(screen.getByRole('button', { name: '답할 질문 4' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await openRow(user, CONFLICT_QUESTION)
  expect(visiblePanel().getByText('문서 간 충돌')).toBeInTheDocument()
  expect(visiblePanel().getByText('서술형')).toBeInTheDocument()
  expect(visiblePanel().getByText('근거 미확인')).toBeInTheDocument()
  await openRow(user, '프로젝트 삭제는 누가 할 수 있나요?')
  expect(visiblePanel().queryByText('근거 미확인')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '전체 5' }))
  await openRow(user, '다국어를 지원할 계획이 있나요?')
  expect(visiblePanel().getByText('답변 없이 닫음')).toBeInTheDocument()
})

const rowOf = (text: string) =>
  screen.queryByRole('button', { name: (name) => name.includes(text) })
const saved = (panel: ReturnType<typeof visiblePanel>) =>
  panel.findByText('저장됨', {}, { timeout: 3000 })

it('하나만 고르는 선택형의 기타 글은 입력을 멈추면 자동 저장하고, 빈 기타는 저장하지 않는다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  const question = '프로젝트 삭제는 누가 할 수 있나요?'
  await openRow(user, question)
  const panel = visiblePanel()
  await user.click(panel.getByLabelText('기타'))
  expect(panel.getByText('빈 답은 저장하지 않아요')).toBeInTheDocument()
  await user.type(panel.getByLabelText('기타 답변'), '팀장이 정한 사람')
  expect(await saved(panel)).toBeInTheDocument()
  // 자동 저장은 위쪽 안내 없이 답변 칸 옆에서만 알린다.
  expect(screen.queryByText('답변을 저장했어요.')).toBeNull()
  // 답한 질문도 다른 질문으로 옮길 때까지 '답할 질문' 목록에 남는다.
  expect(
    await screen.findByRole('button', { name: '답함 1' }),
  ).toBeInTheDocument()
  expect(rowOf(question)).toBeInTheDocument()
  await user.click(panel.getByRole('button', { name: /^다음/ }))
  expect(rowOf(question)).toBeNull()
  // '답함'에서 열면 저장한 답이 채워져 있다.
  await user.click(screen.getByRole('button', { name: '답함 1' }))
  await openRow(user, question)
  expect(panel.getByLabelText('기타 답변')).toHaveValue('팀장이 정한 사람')
  expect(panel.getByText('답변 완료')).toBeInTheDocument()
})

it('여러 개 고르는 선택형은 기타 없이 선택지를 보이고 고를 때마다 바로 저장한다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  await openRow(user, '알림은 어떤 방식으로 보낼까요?')
  const panel = visiblePanel()
  expect(panel.queryByLabelText('기타')).not.toBeInTheDocument()
  await user.click(panel.getByLabelText('앱 안 알림'))
  await user.click(panel.getByLabelText('이메일'))
  expect(await saved(panel)).toBeInTheDocument()
  // 서버에는 마지막으로 고른 상태가 남는다.
  await waitFor(async () => {
    const page = await planningApi.questions('102')
    expect(page.items.find((item) => item.id === '64')?.answer_payload).toEqual(
      { type: 'CHOICE', selected: ['앱 안 알림', '이메일'] },
    )
  })
})

it('서술형 질문은 답하지 않고 닫을 수 있다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  await openRow(user, CONFLICT_QUESTION)
  const panel = visiblePanel()
  expect(panel.getByText('0 / 500')).toBeInTheDocument()
  await user.click(panel.getByRole('button', { name: '답변하지 않고 닫기' }))
  expect(
    await screen.findByText('질문을 답변 없이 닫았어요.'),
  ).toBeInTheDocument()
  // 예시 API 는 닫은 뒤 질문을 다시 만들어 개수가 바뀔 수 있다.
  await user.click(screen.getByRole('button', { name: /^전체 \d+$/ }))
  await openRow(user, CONFLICT_QUESTION)
  expect(panel.getByText('답변 없이 닫음')).toBeInTheDocument()
})

it('팀원에게는 답변 입력을 보이지 않는다', async () => {
  const user = renderPanel(false)
  await questionsLoaded()
  await openRow(user, '프로젝트 삭제는 누가 할 수 있나요?')
  expect(
    visiblePanel().getByRole('heading', {
      name: '프로젝트 삭제는 누가 할 수 있나요?',
    }),
  ).toBeInTheDocument()
  expect(visiblePanel().queryByRole('textbox', { name: '답변' })).toBeNull()
  expect(visiblePanel().queryByRole('radio')).toBeNull()
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
  await openTab(user, /답변 보완 후보/)
  await user.click(
    await screen.findByRole('button', { name: '답변으로 보완 초안 만들기' }),
  )
  expect(
    (await screen.findAllByText(/초안을 만들고 있어요/)).length,
  ).toBeGreaterThan(0)
  expect(
    screen.getByRole('button', { name: '답변으로 보완 초안 만들기' }),
  ).toBeDisabled()
})

// 답변 보완 후보 탭을 열고 목록에서 후보를 골라 오른쪽 상세(article)를 돌려준다.
async function openAnswerCandidate(user: User, text: string) {
  await openTab(user, /답변 보완 후보/)
  await openRow(user, text)
  return screen.getByRole('article')
}
// 목록 줄: '{키}수정, {종류}'(기존 항목 수정)·'policy-…'(근거 없는 새 항목)
const UPDATE_ROW = '수정, '

it('수정 후보는 바뀐 필드를 이전과 이후로 나란히 보인다', async () => {
  await prepareCandidates()
  const user = renderPanel()
  const card = await openAnswerCandidate(user, UPDATE_ROW)
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
  const card = await openAnswerCandidate(user, UPDATE_ROW)
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
  // 채택한 내용이 설계 항목 초안 탭에 들어간다.
  await openTab(user, /설계 항목 초안/)
  expect(
    (await visiblePanel().findAllByText(/\(답변을 반영해 구체화\)/)).length,
  ).toBeGreaterThan(0)
})

it('근거가 없는 후보는 확인 체크를 해야 채택할 수 있다', async () => {
  await prepareCandidates()
  const user = renderPanel()
  const card = await openAnswerCandidate(user, 'policy-')
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
  const card = await openAnswerCandidate(user, 'policy-')
  await user.click(within(card).getByRole('button', { name: '폐기' }))
  expect(await screen.findByText('후보를 폐기했어요.')).toBeInTheDocument()
  expect(await screen.findByText(/처리한 후보 1개/)).toBeInTheDocument()
})

it('후보가 낡았으면 그 후보만 막고 초안을 다시 만들게 안내한다', async () => {
  await prepareCandidates()
  localStorage.setItem('beez.mock.planDraft', 'stale')
  const user = renderPanel()
  const card = await openAnswerCandidate(user, 'policy-')
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
  // 다른 탭에 있어도 탭에 실패를 알린다.
  expect(
    screen.getByRole('tab', { name: /답변 보완 후보.*실패 있음/ }),
  ).toBeInTheDocument()
  await openTab(user, /답변 보완 후보/)
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
  await openTab(user, /답변 보완 후보/)
  await user.click(
    await screen.findByRole('button', { name: '답변으로 보완 초안 만들기' }),
  )
  await waitFor(() => expect(generate).toHaveBeenCalledTimes(1))
  expect(generate.mock.calls[0][1].document_version_ids.length).toBeGreaterThan(
    1,
  )
})

it('팀원은 후보를 볼 수 있지만 채택·폐기·만들기는 할 수 없다', async () => {
  await prepareCandidates()
  const user = renderPanel(false)
  const card = await openAnswerCandidate(user, UPDATE_ROW)
  expect(within(card).getByText('기존 항목 수정')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '채택' })).toBeNull()
  expect(screen.queryByRole('button', { name: '폐기' })).toBeNull()
  expect(
    screen.queryByRole('button', { name: '답변으로 보완 초안 만들기' }),
  ).toBeNull()
})

// live 점검: 다시 만들기가 실패해도 서버는 기존 후보를 바꾸지 않으므로 이전 후보를 계속 보인다.
it('다시 만들기가 실패해도 이전에 완료된 후보를 계속 보인다', async () => {
  await prepareCandidates()
  localStorage.setItem('beez.mock.planDraft', 'failed')
  await prepareCandidates()
  const user = renderPanel()
  const card = await openAnswerCandidate(user, UPDATE_ROW)
  expect(within(card).getByText('기존 항목 수정')).toBeInTheDocument()
  expect(
    (await screen.findAllByText(/초안을 만들지 못했어요/)).length,
  ).toBeGreaterThan(0)
})

// 리뷰 반영: 닫으면 서버가 이전 답변을 지우므로 이미 답한 질문에는 닫기를 보이지 않는다.
it('이미 답한 질문을 고칠 때는 답하지 않고 닫기를 보이지 않는다', async () => {
  await planningApi.answerQuestion('102', '65', { answer: '팀장만 승인해요.' })
  const user = renderPanel()
  await questionsLoaded()
  await user.click(screen.getByRole('button', { name: '답함 1' }))
  await openRow(user, CONFLICT_QUESTION)
  const panel = visiblePanel()
  expect(
    panel.queryByRole('button', { name: '답변하지 않고 닫기' }),
  ).not.toBeInTheDocument()
  expect(panel.getByRole('textbox', { name: '답변' })).toHaveValue(
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
      {
        id: '5',
        number: 5,
        question: '반려된 문서의 질문',
        status: 'OPEN',
        freshness: 'AWAITING_RESUBMISSION',
      },
    ],
    has_more: false,
    total_count: 5,
  })
  const user = renderPanel()
  await screen.findByRole('button', { name: '전체 5' })
  // 답할 수 없는 미답변 질문은 목록에 잠금 표시가 붙는다.
  expect(
    screen.getAllByRole('img', { name: '지금은 답할 수 없음' }),
  ).toHaveLength(3)
  const panel = visiblePanel()
  const answerable = () => panel.queryByRole('textbox', { name: '답변' })

  await openRow(user, '갱신 중인 질문')
  expect(panel.getByText('이전 본문 기준 · 갱신 중')).toBeInTheDocument()
  expect(panel.getByText(/끝난 뒤 답할 수 있어요/)).toBeInTheDocument()
  expect(answerable()).toBeNull()

  await openRow(user, '갱신 실패한 질문')
  expect(panel.getByText('최신 본문으로 갱신하지 못함')).toBeInTheDocument()
  expect(answerable()).toBeNull()

  await openRow(user, '반려된 문서의 질문')
  expect(panel.getByText('반려된 문서 · 다시 제출 대기')).toBeInTheDocument()
  expect(panel.getByText(/문서를 다시 제출하면/)).toBeInTheDocument()
  expect(answerable()).toBeNull()

  await openRow(user, '최신 질문')
  expect(
    panel.queryByText(/이전 본문 기준|갱신하지 못함/),
  ).not.toBeInTheDocument()
  expect(answerable()).toBeInTheDocument()

  // 답한 질문은 이력이라 이전 본문 기준이어도 고칠 수 있다.
  await user.click(screen.getByRole('button', { name: '답함 1' }))
  await openRow(user, '이미 답한 질문')
  expect(panel.getByText('최신 본문으로 갱신하지 못함')).toBeInTheDocument()
  expect(answerable()).toBeInTheDocument()
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
  expect(visiblePanel().queryByRole('textbox', { name: '답변' })).toBeNull()
  refreshed = true
  await waitFor(() =>
    expect(
      screen.queryByText('이전 본문 기준 · 갱신 중'),
    ).not.toBeInTheDocument(),
  )
  expect(
    visiblePanel().getByRole('textbox', { name: '답변' }),
  ).toBeInTheDocument()
})

// 답하는 동안 새 본문으로 질문이 다시 만들어져 그 질문이 목록에서 빠지면 창이 안내 없이 닫히고 쓰던 답이 사라졌다.
it('답하던 질문이 목록에서 빠져도 답변 창과 쓰던 답을 그대로 둔다', async () => {
  let gone = false
  const essay = {
    id: '1',
    number: 1,
    question: '답하던 질문',
    status: 'OPEN' as const,
    freshness: 'CURRENT' as const,
    question_type: 'ESSAY' as const,
    answer_format: { type: 'ESSAY' as const, max_length: 2000 },
  }
  // 답한 질문이 '갱신 중'이면 목록을 계속 다시 읽는다.
  const refreshing = {
    id: '2',
    number: 2,
    question: '갱신 중인 답한 질문',
    status: 'ANSWERED' as const,
    answer: '답',
    freshness: 'REFRESHING' as const,
  }
  const questions = vi
    .spyOn(planningApi, 'questions')
    .mockImplementation(async () => ({
      items: gone ? [refreshing] : [essay, refreshing],
      has_more: false,
      total_count: gone ? 1 : 2,
    }))
  const user = renderPanel()
  await openRow(user, '답하던 질문')
  const panel = visiblePanel()
  await user.type(panel.getByRole('textbox', { name: '답변' }), '쓰던 답')
  gone = true
  const calls = questions.mock.calls.length
  await waitFor(() =>
    expect(questions.mock.calls.length).toBeGreaterThan(calls + 1),
  )
  // 목록에서는 빠졌지만 오른쪽 상세와 쓰던 답은 그대로다.
  expect(
    screen.queryByRole('button', {
      name: (name) => name.includes('답하던 질문'),
    }),
  ).not.toBeInTheDocument()
  expect(
    panel.getByRole('heading', { name: '답하던 질문' }),
  ).toBeInTheDocument()
  expect(panel.getByRole('textbox', { name: '답변' })).toHaveValue('쓰던 답')
})

it('기준 설계가 발행된 프로젝트에서는 질문에 답할 수 없다', async () => {
  const user = renderPanel(true, '101')
  await openRow(user, '프로젝트 삭제 권한은 인간 팀장만 가지나요?')
  expect(
    await screen.findByText('기준 설계가 발행되어 답변을 바꿀 수 없어요.'),
  ).toBeInTheDocument()
  expect(visiblePanel().queryByRole('textbox', { name: '답변' })).toBeNull()
  // 발행 뒤에는 후보 탭이 없다.
  expect(screen.queryByRole('tab', { name: /문서 후보/ })).toBeNull()
})

it('초안 작업 조회에 실패하면 후보 자리에 안내와 다시 불러오기를 보인다', async () => {
  vi.spyOn(planningApi, 'drafts').mockRejectedValue(new Error('network'))
  const user = renderPanel()
  await openTab(user, /문서 후보/)
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
  expect(await questionsLoaded()).toBeInTheDocument()
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
  await openTab(user, /설계 항목 초안/)
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
  await openTab(user, /설계 항목 초안/)
  await user.click(await screen.findByRole('button', { name: '항목 추가' }))
  await user.click(screen.getByLabelText(/설계 내용/))
  await user.paste('로그인할 수 있어야 한다')
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(await screen.findByText(/JSON 형식으로 입력해 주세요/)).toBeVisible()
  expect(create).not.toHaveBeenCalled()
})

it('서버가 내용을 거절하면 서버 문구를 폼에만 보이고 위쪽에 틀린 안내를 띄우지 않는다', async () => {
  const user = renderPanel()
  await openTab(user, /설계 항목 초안/)
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
  await openTab(user, /답변 보완 후보/)
  await user.click(
    await screen.findByRole('button', { name: '답변으로 보완 초안 만들기' }),
  )
  expect(
    await screen.findByText(
      '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.',
    ),
  ).toBeInTheDocument()
})

// S15P31A108-389: 문서에서 뽑은 후보는 질문·답변 보완과 따로 보이고, 고른 후보만 설계 항목 초안이 된다.
async function prepareDocumentCandidates() {
  const documents = await documentsApi.list('102')
  await planningApi.generateDrafts('102', {
    document_version_ids: documents.items.map((item) => item.latest_draft!.id),
    mode: 'DOCUMENT_ONLY',
  })
  await planningApi.drafts('102')
  await planningApi.drafts('102')
}

it('문서에서 뽑은 후보를 골라 채택하면 고른 것만 설계 항목 초안이 된다', async () => {
  await prepareDocumentCandidates()
  const before = (await planningApi.items('102')).items.length
  const user = renderPanel()
  const first = (
    await screen.findAllByRole('checkbox', {
      name: '문서에서 뽑은 예시 1 선택',
    })
  )[0]
  expect(screen.getByRole('tab', { name: /문서 후보/ })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(
    screen.getByRole('tab', { name: /답변 보완 후보/ }),
  ).toBeInTheDocument()
  await user.click(first)
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(
    await screen.findByText(/1개를 설계 항목 초안으로 채택했어요/),
  ).toBeInTheDocument()
  await waitFor(async () =>
    expect((await planningApi.items('102')).items.length).toBe(before + 1),
  )
})

it('문서에서 뽑은 작업이 실패하면 출력 상한 안내를 보이고 실패한 부분만 다시 접수한다', async () => {
  localStorage.setItem('beez.mock.planDraft', 'failed')
  await prepareDocumentCandidates()
  const generate = vi.spyOn(planningApi, 'generateDrafts')
  const user = renderPanel()
  expect(
    (await screen.findAllByRole('alert')).some((alert) =>
      alert.textContent?.includes('출력 길이 상한'),
    ),
  ).toBe(true)
  localStorage.removeItem('beez.mock.planDraft')
  await user.click(
    screen.getAllByRole('button', { name: '실패한 부분만 다시 시도' })[0],
  )
  await waitFor(() =>
    expect(generate).toHaveBeenCalledWith(
      '102',
      expect.objectContaining({ mode: 'DOCUMENT_ONLY' }),
    ),
  )
  expect(generate.mock.calls[0][1].document_version_ids).toHaveLength(1)
})

// PR #303 리뷰: 탭은 처음 데이터가 들어왔을 때 한 번만 정한다. 답하는 중에 문서 후보 작업이 새로 생겨도 넘어가지 않는다.
it('처음 정한 탭은 문서 후보 작업이 새로 생겨 창을 다시 읽어도 저절로 바뀌지 않는다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  const questionsTab = screen.getByRole('tab', { name: /보완 질문/ })
  expect(questionsTab).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('tab', { name: /문서 후보/ }).textContent).toMatch(
    /^문서 후보0/,
  )
  // 백엔드가 분석이 끝난 문서마다 문서 후보 작업을 만들고, 질문을 닫으면 창 전체를 다시 읽는다.
  await prepareDocumentCandidates()
  await openRow(user, CONFLICT_QUESTION)
  await user.click(
    visiblePanel().getByRole('button', { name: '답변하지 않고 닫기' }),
  )
  expect(
    await screen.findByText('질문을 답변 없이 닫았어요.'),
  ).toBeInTheDocument()
  await waitFor(() =>
    expect(
      screen.getByRole('tab', { name: /문서 후보/ }).textContent,
    ).not.toMatch(/^문서 후보0/),
  )
  expect(questionsTab).toHaveAttribute('aria-selected', 'true')
})

// PR #303 재검토: 문서 목록이 기준·초안 작업보다 늦게 와도, 문서 후보에 할 일이 있으면 처음 탭을 문서 후보로 정한다.
it('문서 목록이 기준 설계·초안 작업보다 늦게 와도 처음 탭을 문서 후보로 정한다', async () => {
  await prepareDocumentCandidates()
  const list = documentsApi.list.bind(documentsApi)
  let release = () => {}
  const late = new Promise<void>((resolve) => {
    release = resolve
  })
  vi.spyOn(documentsApi, 'list').mockImplementation(async (...args) => {
    await late
    return list(...args)
  })
  renderPanel()
  // 기준 설계와 초안 작업을 먼저 다 읽어 화면에 반영될 때까지 둔다(문서 목록 없이 거르면 문서 작업이 하나도 남지 않는다).
  expect(
    await screen.findByText(/승인되고 색인이 끝나면 후보가 여기에 나와요/),
  ).toBeInTheDocument()
  release()
  await waitFor(() =>
    expect(screen.getByRole('tab', { name: /문서 후보/ })).toHaveAttribute(
      'aria-selected',
      'true',
    ),
  )
})

// PR #303 재검토 4: 자동 저장이 409로 거절되면(예: 다른 곳에서 기준을 발행함) 프로젝트를 모두 새로 읽어 기준 설계도 바로잡는다.
it('자동 저장이 409로 거절되면 기준 설계까지 프로젝트를 모두 새로 읽는다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  vi.spyOn(planningApi, 'answerQuestion').mockRejectedValue(
    new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: {
          code: 'design_already_published',
          message: 'design already published',
        },
      },
    }),
  )
  const baseline = vi.spyOn(documentsApi, 'baseline')
  await openRow(user, '프로젝트 삭제 권한은 인간 팀장만 가지나요?')
  await user.type(visiblePanel().getByRole('textbox', { name: '답변' }), '답')
  await user.tab()
  expect(await visiblePanel().findByText('저장 못 했어요')).toBeInTheDocument()
  expect(
    visiblePanel().getByText(/이미 기준 설계가 발행되어 답변을 바꿀 수 없어요/),
  ).toBeInTheDocument()
  await waitFor(() => expect(baseline).toHaveBeenCalled())
})

// PR #303 재검토 5: 분석이 실패하거나 진행 중인 문서의 안내는 마우스를 올리지 않아도 읽힌다. 완료 안내는 보이지 않는다.
it('분석이 실패하거나 진행 중인 문서의 안내는 문서 줄 아래에 글자로 보인다', async () => {
  const docs = (await documentsApi.list('102')).items
  vi.spyOn(planningApi, 'analysis').mockImplementation(
    async (_projectId, documentId, query) => {
      const kind = docs.find((item) => item.id === documentId)!.kind
      return {
        document_version_id: query.document_version_id,
        status:
          kind === 'PLAN' ? 'FAILED' : kind === 'ERD' ? 'RUNNING' : 'COMPLETED',
        message:
          kind === 'PLAN'
            ? '문서를 읽지 못했어요.'
            : kind === 'ERD'
              ? '문서를 나눠 읽는 중이에요.'
              : '검색 준비가 완료되었습니다.',
        items: [],
        questions: [],
      } as never
    },
  )
  renderPanel()
  const notes = await screen.findByRole('list', { name: '문서 분석 안내' })
  expect(within(notes).getByText('기획안: 문서를 읽지 못했어요.')).toBeVisible()
  expect(
    within(notes).getByText('ERD: 문서를 나눠 읽는 중이에요.'),
  ).toBeVisible()
  expect(within(notes).queryByText(/검색 준비가 완료/)).toBeNull()
})

// PR #303 재검토 7: 탭은 좌우 화살표·Home·End 로 옮기고, Tab 키는 고른 탭 하나에만 들른다.
it('탭은 좌우 화살표·Home·End 로 옮기고 Tab 키는 고른 탭에만 들른다', async () => {
  const user = renderPanel()
  await questionsLoaded()
  const tabOf = (name: RegExp) => screen.getByRole('tab', { name })
  expect(tabOf(/보완 질문/)).toHaveAttribute('tabindex', '0')
  expect(tabOf(/문서 후보/)).toHaveAttribute('tabindex', '-1')
  tabOf(/보완 질문/).focus()
  await user.keyboard('{ArrowRight}')
  expect(tabOf(/답변 보완 후보/)).toHaveAttribute('aria-selected', 'true')
  expect(tabOf(/답변 보완 후보/)).toHaveFocus()
  await user.keyboard('{End}')
  expect(tabOf(/설계 항목 초안/)).toHaveFocus()
  await user.keyboard('{Home}')
  expect(tabOf(/문서 후보/)).toHaveAttribute('aria-selected', 'true')
  expect(tabOf(/문서 후보/)).toHaveFocus()
  await user.keyboard('{ArrowLeft}')
  expect(tabOf(/설계 항목 초안/)).toHaveAttribute('aria-selected', 'true')
})
