/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, documentsApi } from '@/api/services'
import { getApiErrorCode } from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

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

const codeOf = (promise: Promise<unknown>) =>
  promise.then(
    () => 'ok',
    (error: unknown) => getApiErrorCode(error),
  )

it('비비 검토 → 제안 반영·넘기기 → 팀장 검토 요청 → 승인 흐름을 지킨다', async () => {
  const documentId = '221'
  const copy = await documentsApi.workingCopy('102', documentId)
  expect(copy.status).toBe('EDITING')
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  expect(review.suggestions.length).toBeGreaterThan(0)
  expect(
    await codeOf(
      documentsApi.submitReview('102', documentId, {
        expected_revision: copy.revision,
        review_id: review.id,
      }),
    ),
  ).toBe('review_decisions_pending')
  let current = await documentsApi.decideSuggestion(
    '102',
    documentId,
    review.id,
    review.suggestions[0].id,
    { expected_revision: copy.revision, decision: 'APPLY' },
  )
  expect(current.revision).toBe(copy.revision + 1)
  expect(current.content).toContain(review.suggestions[0].replacement_text)
  for (const suggestion of review.suggestions.slice(1))
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  expect(submission.status).toBe('SUBMITTED')
  expect(
    (await documentsApi.reviewSubmissions('102', { status: 'SUBMITTED' })).map(
      (item) => item.id,
    ),
  ).toContain(submission.id)
  expect(
    await codeOf(
      documentsApi.updateWorkingCopy('102', documentId, {
        expected_revision: current.revision,
        content: '고침',
        file_name: 'plan.md',
      }),
    ),
  ).toBe('submission_locked')
  const decided = await documentsApi.decideSubmission(
    '102',
    documentId,
    submission.id,
    { decision: 'APPROVE' },
  )
  expect(decided.status).toBe('APPROVED')
  const { document } = await documentsApi.get('102', documentId)
  expect(document.approved_version?.id).toBe(submission.document_version_id)
})

it('반려하면 같은 작성 건을 다시 열고, 기준이 생긴 프로젝트는 변경 제안으로 보낸다', async () => {
  const documentId = '222'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  let current = copy
  for (const suggestion of review.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  await documentsApi.decideSubmission('102', documentId, submission.id, {
    decision: 'REJECT',
    rejection_reason: 'ERD와 테이블 이름이 달라요.',
  })
  const reopened = await documentsApi.workingCopy('102', documentId)
  expect(reopened.status).toBe('REJECTED')
  expect(reopened.revision).toBe(current.revision + 1)
  expect(reopened.latest_review).toBeUndefined()
  expect(reopened.submissions[0].rejection_reason).toBe(
    'ERD와 테이블 이름이 달라요.',
  )
  const approved = await documentsApi.workingCopy('101', '201')
  expect(
    await codeOf(
      documentsApi.requestBeeReview('101', '201', {
        expected_revision: approved.revision,
      }),
    ),
  ).toBe('change_proposal_required')
})

it('비비와 대화하면 내 말과 비비 답이 제안에 남고, 비비의 다른 대체문으로 반영할 수 있다', async () => {
  const documentId = '221'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  const [first] = review.suggestions
  const chat = await documentsApi.discussSuggestion(
    '102',
    documentId,
    review.id,
    first.id,
    { text: '목적을 더 짧게 쓸 수 있을까?' },
  )
  expect(chat.message.author_kind).toBe('HUMAN')
  expect(chat.reply.author_kind).toBe('BEE')
  expect(chat.reply.proposed_text).toBeTruthy()
  const reread = await documentsApi.workingCopy('102', documentId)
  expect(reread.latest_review?.suggestions[0].messages).toHaveLength(2)
  const applied = await documentsApi.decideSuggestion(
    '102',
    documentId,
    review.id,
    first.id,
    {
      expected_revision: copy.revision,
      decision: 'APPLY',
      replacement_text: chat.reply.proposed_text,
    },
  )
  expect(applied.content).toContain(chat.reply.proposed_text)
  expect(applied.latest_review?.suggestions[0].applied_replacement_text).toBe(
    chat.reply.proposed_text,
  )
  // 직접 고쳐 이전 검토가 무효가 되면 그 검토로 대화할 수 없다.
  await documentsApi.updateWorkingCopy('102', documentId, {
    expected_revision: applied.revision,
    content: '직접 고침',
    file_name: 'plan.md',
  })
  expect(
    await codeOf(
      documentsApi.discussSuggestion(
        '102',
        documentId,
        review.id,
        review.suggestions[1].id,
        { text: '이건?' },
      ),
    ),
  ).toBe('review_stale')
})

it('직접 요청하면 DIRECT_REQUEST 제안이 붙고, 팀장 검토 요청 중에는 대화·요청이 잠긴다', async () => {
  const documentId = '222'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  const added = await documentsApi.requestBeeSuggestion(
    '102',
    documentId,
    review.id,
    { expected_revision: copy.revision, text: '오류 표에 403 행을 추가해 줘.' },
  )
  expect(added.suggestion.origin).toBe('DIRECT_REQUEST')
  expect(added.suggestion.decision).toBe('PENDING')
  let current = copy
  const all = (await documentsApi.workingCopy('102', documentId)).latest_review!
  for (const suggestion of all.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  expect(
    await codeOf(
      documentsApi.requestBeeSuggestion('102', documentId, review.id, {
        expected_revision: current.revision,
        text: '하나 더',
      }),
    ),
  ).toBe('submission_locked')
  expect(
    await codeOf(
      documentsApi.discussSuggestion(
        '102',
        documentId,
        review.id,
        all.suggestions[0].id,
        { text: '하나 더' },
      ),
    ),
  ).toBe('submission_locked')
})

it('검토 요청을 취소하면 작성 건이 다시 열리고 이력에 WITHDRAWN 으로 남으며, 팀장 결정은 막힌다', async () => {
  const documentId = '223'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  let current = copy
  for (const suggestion of review.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  const reopened = await documentsApi.cancelSubmission(
    '102',
    documentId,
    submission.id,
  )
  expect(reopened.status).toBe('EDITING')
  expect(reopened.revision).toBe(current.revision + 1)
  expect(reopened.latest_review).toBeUndefined()
  expect(reopened.submissions[0].status).toBe('WITHDRAWN')
  // 같은 제출을 다시 취소해도 같은 결과(멱등)다.
  await expect(
    documentsApi.cancelSubmission('102', documentId, submission.id),
  ).resolves.toMatchObject({ status: 'EDITING' })
  expect(
    await codeOf(
      documentsApi.decideSubmission('102', documentId, submission.id, {
        decision: 'APPROVE',
      }),
    ),
  ).toBe('submission_withdrawn')
})

it('팀장이 먼저 결정했으면 취소는 submission_decided 로 막힌다', async () => {
  const documentId = '224'
  const copy = await documentsApi.workingCopy('102', documentId)
  const review = await documentsApi.requestBeeReview('102', documentId, {
    expected_revision: copy.revision,
  })
  let current = copy
  for (const suggestion of review.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      documentId,
      review.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', documentId, {
    expected_revision: current.revision,
    review_id: review.id,
  })
  await documentsApi.decideSubmission('102', documentId, submission.id, {
    decision: 'APPROVE',
  })
  expect(
    await codeOf(
      documentsApi.cancelSubmission('102', documentId, submission.id),
    ),
  ).toBe('submission_decided')
})

// S15P31A108-362: 다른 문서가 승인돼 참고 승인본이 바뀌면 서버처럼 review_stale을 먼저 알리고, 쓰기는 409로 막는다.
it('다른 문서가 승인되면 본문이 그대로여도 작업본이 review_stale을 알리고, 다시 검토하면 풀린다', async () => {
  const stale = async (documentId: string) =>
    (await documentsApi.workingCopy('102', documentId)).review_stale
  const copy = await documentsApi.workingCopy('102', '221')
  const review = await documentsApi.requestBeeReview('102', '221', {
    expected_revision: copy.revision,
  })
  expect(await stale('221')).toBe(false)

  // 223을 끝까지 검토해 승인한다.
  const other = await documentsApi.workingCopy('102', '223')
  const otherReview = await documentsApi.requestBeeReview('102', '223', {
    expected_revision: other.revision,
  })
  let current = other
  for (const suggestion of otherReview.suggestions)
    current = await documentsApi.decideSuggestion(
      '102',
      '223',
      otherReview.id,
      suggestion.id,
      { expected_revision: current.revision, decision: 'SKIP' },
    )
  const submission = await documentsApi.submitReview('102', '223', {
    expected_revision: current.revision,
    review_id: otherReview.id,
  })
  // 팀장 결정을 기다리는 동안에는 223 자신은 무효로 보이지 않는다.
  expect(await stale('223')).toBe(false)
  await documentsApi.decideSubmission('102', '223', submission.id, {
    decision: 'APPROVE',
  })

  expect(await stale('221')).toBe(true)
  expect(
    await codeOf(
      documentsApi.decideSuggestion(
        '102',
        '221',
        review.id,
        review.suggestions[0].id,
        { expected_revision: copy.revision, decision: 'SKIP' },
      ),
    ),
  ).toBe('review_stale')

  await documentsApi.requestBeeReview('102', '221', {
    expected_revision: copy.revision,
  })
  expect(await stale('221')).toBe(false)
})
