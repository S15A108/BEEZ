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
