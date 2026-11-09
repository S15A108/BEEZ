/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi, changesApi, documentsApi } from '@/api/services'
import { api } from '@/api/client'
import { getApiErrorCode } from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// 올리기 전 비비 검토(CHG-A16~A20)와 초안으로 올리기(CHG-A01·A04) 예시 서버 규칙(S15P31A108-325).

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
  vi.restoreAllMocks()
  localStorage.removeItem('beez.mock.changeReview')
})

const codeOf = (promise: Promise<unknown>) =>
  promise.then(
    () => 'ok',
    (error: unknown) => getApiErrorCode(error),
  )

async function newDraft(
  line = 'REQ-006 참여한 프로젝트가 없으면 받은 초대를 먼저 보여 준다.',
) {
  const baseline = await documentsApi.baseline('101')
  const document = await documentsApi.get('101', '203')
  return changesApi.createDraft('101', {
    base_baseline_id: baseline.id,
    title: '빈 목록 안내',
    reason: '빈 목록에서 멈춰요.',
    items: [],
    documents: [
      {
        document_id: '203',
        base_document_version_id: document.version.id,
        after_content: `${document.version.content ?? ''}\n${line}`,
      },
    ],
  })
}

// 검토는 접수 시각부터 지난 시간으로 진행한다. 시계를 앞으로 돌려 끝낸다.
const later = (ms: number) => {
  const now = Date.now()
  vi.spyOn(Date, 'now').mockReturnValue(now + ms)
}

it('초안을 저장하고 검토가 끝나면 단계·영향·수정안을 주며 평가하지 않은 AI 후보는 null이다', async () => {
  const draft = await newDraft()
  expect(draft).toMatchObject({ revision: 1 })
  expect(draft.latest_review).toBeUndefined()
  const running = await changesApi.requestDraftReview('101', draft.id, {
    expected_revision: 1,
  })
  expect(running).toMatchObject({ status: 'RUNNING', input_revision: 1 })
  // 검토 중에는 고치거나 다시 요청할 수 없다.
  expect(
    await codeOf(
      changesApi.updateDraft('101', draft.id, {
        ...draft,
        documents: draft.documents.map((change) => ({
          document_id: change.document_id,
          base_document_version_id: change.base_document_version_id,
          after_content: `${change.after_content}\n더 고침`,
        })),
        expected_revision: 1,
      }),
    ),
  ).toBe('review_in_progress')
  later(10_000)
  const done = await changesApi.getDraft('101', draft.id)
  expect(done.latest_review).toMatchObject({ status: 'COMPLETED' })
  expect(done.latest_review!.steps.map((step) => step.status)).toEqual([
    'DONE',
    'DONE',
    'NOT_EVALUATED',
    'DONE',
  ])
  expect(done.latest_review!.ai_candidates).toBeUndefined()
  expect(done.latest_review!.suggestions.map((item) => item.type)).toEqual([
    'PATCH',
    'COMMENT',
  ])
  expect(done.review_state_hash).toBeTruthy()
})

it('수정안을 반영하면 고친 글과 리비전이 바뀌고 되돌리면 원래 글로 돌아온다', async () => {
  const draft = await newDraft()
  await changesApi.requestDraftReview('101', draft.id, { expected_revision: 1 })
  later(10_000)
  const reviewed = await changesApi.getDraft('101', draft.id)
  const review = reviewed.latest_review!
  const patch = review.suggestions[0]
  const applied = await changesApi.decideDraftSuggestion(
    '101',
    draft.id,
    review.id,
    patch.id,
    { expected_revision: 1, decision: 'APPLIED' },
  )
  expect(applied.revision).toBe(2)
  expect(applied.latest_review).toMatchObject({
    input_revision: 1,
    resolved_revision: 2,
  })
  expect(applied.documents[0].after_content).toContain(patch.replacement_text)
  // 오래된 리비전으로는 고를 수 없다.
  expect(
    await codeOf(
      changesApi.decideDraftSuggestion('101', draft.id, review.id, patch.id, {
        expected_revision: 1,
        decision: 'PENDING',
      }),
    ),
  ).toBe('version_conflict')
  const reverted = await changesApi.decideDraftSuggestion(
    '101',
    draft.id,
    review.id,
    patch.id,
    { expected_revision: 2, decision: 'PENDING' },
  )
  expect(reverted.documents[0].after_content).toBe(
    reviewed.documents[0].after_content,
  )
  expect(
    await codeOf(
      changesApi.decideDraftSuggestion(
        '101',
        draft.id,
        review.id,
        review.suggestions[1].id,
        { expected_revision: 3, decision: 'SKIPPED' },
      ),
    ),
  ).toBe('invalid_transition')
})

it('확인할 상태가 있으면 확인과 사용자가 본 검토 상태로만 올리고, 제출 당시 상태를 리비전에 남긴다', async () => {
  const draft = await newDraft()
  await changesApi.requestDraftReview('101', draft.id, { expected_revision: 1 })
  later(10_000)
  const reviewed = await changesApi.getDraft('101', draft.id)
  // 처리하지 않은 수정안이 있다.
  expect(
    await codeOf(
      changesApi.submitDraft('101', {
        draft_id: draft.id,
        expected_draft_revision: 1,
        review_acknowledged: false,
      }),
    ),
  ).toBe('review_acknowledgement_required')
  expect(
    await codeOf(
      changesApi.submitDraft('101', {
        draft_id: draft.id,
        expected_draft_revision: 1,
        review_acknowledged: true,
        expected_review_state_hash: 'sha256:old',
      }),
    ),
  ).toBe('version_conflict')
  const proposal = await changesApi.submitDraft('101', {
    draft_id: draft.id,
    expected_draft_revision: 1,
    review_acknowledged: true,
    expected_review_state_hash: reviewed.review_state_hash,
  })
  expect(proposal.documents[0].after_content).toBe(
    reviewed.documents[0].after_content,
  )
  expect(proposal.pre_submit_review).toMatchObject({
    status: 'COMPLETED',
    edited_after_review: false,
    acknowledged: true,
    pending_suggestions: [{ type: 'PATCH', decision: 'PENDING' }],
    skipped_suggestions: [{ type: 'COMMENT' }],
  })
  expect(
    (await changesApi.getDraft('101', draft.id)).submitted_proposal_id,
  ).toBe(proposal.id)
  expect(
    await codeOf(
      changesApi.submitDraft('101', {
        draft_id: draft.id,
        expected_draft_revision: 1,
        review_acknowledged: true,
        expected_review_state_hash: reviewed.review_state_hash,
      }),
    ),
  ).toBe('draft_already_submitted')
})

it('검토 진행 중에 확인하고 올리면 그 검토를 취소로 끝내고, 본문 필드를 함께 보내면 400이다', async () => {
  const draft = await newDraft()
  await changesApi.requestDraftReview('101', draft.id, { expected_revision: 1 })
  const running = await changesApi.getDraft('101', draft.id)
  expect(
    await codeOf(
      // 화면은 본문을 보내지 않으므로 서버 규칙만 보려고 직접 보낸다.
      api.post('/projects/101/change-proposals', {
        draft_id: draft.id,
        expected_draft_revision: 1,
        review_acknowledged: true,
        base_baseline_id: draft.base_baseline_id,
        title: '우회',
        reason: '우회',
        items: [],
        documents: [],
      }),
    ),
  ).toBe('validation_failed')
  const proposal = await changesApi.submitDraft('101', {
    draft_id: draft.id,
    expected_draft_revision: 1,
    review_acknowledged: true,
    expected_review_state_hash: running.review_state_hash,
  })
  expect(proposal.pre_submit_review?.status).toBe('CANCELLED')
  later(10_000)
  expect(
    (await changesApi.getDraft('101', draft.id)).latest_review?.status,
  ).toBe('CANCELLED')
})

it('접수 뒤 시간이 지나 끝나지 못하면 FAILED·review_timeout이고 다시 요청할 수 있다', async () => {
  localStorage.setItem('beez.mock.changeReview', 'running')
  const draft = await newDraft()
  await changesApi.requestDraftReview('101', draft.id, { expected_revision: 1 })
  later(60_000)
  expect(
    (await changesApi.getDraft('101', draft.id)).latest_review?.status,
  ).toBe('RUNNING')
  vi.restoreAllMocks()
  later(10 * 60_000 + 1)
  const failed = await changesApi.getDraft('101', draft.id)
  expect(failed.latest_review).toMatchObject({
    status: 'FAILED',
    error_code: 'review_timeout',
  })
  localStorage.removeItem('beez.mock.changeReview')
  expect(
    await changesApi.requestDraftReview('101', draft.id, {
      expected_revision: 1,
    }),
  ).toMatchObject({ status: 'RUNNING' })
})

it('반려된 제안을 보완한 초안은 CHG-A04로 같은 제안 번호의 새 리비전이 된다', async () => {
  const { proposal } = await changesApi.get('101', '71')
  await changesApi.reject('101', '71', {
    reason: '다시 봐 주세요.',
    expected_revision: proposal.revision,
    expected_snapshot_hash: proposal.snapshot_hash,
  })
  await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
  const rejected = (await changesApi.get('101', '71')).proposal
  const draft = await changesApi.createDraft('101', {
    base_baseline_id: rejected.base_baseline_id,
    title: rejected.title,
    reason: rejected.reason,
    items: rejected.items,
    documents: [],
    source_proposal_id: '71',
  })
  const submission = {
    draft_id: draft.id,
    expected_draft_revision: 1,
    review_acknowledged: true,
  }
  // 반려 제안을 보완한 초안은 새 제안(CHG-A01)으로 올리지 못한다.
  expect(await codeOf(changesApi.submitDraft('101', submission))).toBe(
    'validation_failed',
  )
  const resubmitted = await changesApi.resubmitDraft('101', '71', {
    ...submission,
    expected_revision: rejected.revision,
    expected_snapshot_hash: rejected.snapshot_hash,
  })
  expect(resubmitted).toMatchObject({
    id: '71',
    revision: rejected.revision + 1,
    status: 'PROPOSED',
    pre_submit_review: { status: 'NOT_REQUESTED', acknowledged: true },
  })
})
