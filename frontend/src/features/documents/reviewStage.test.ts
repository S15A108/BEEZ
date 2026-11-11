import { expect, it } from 'vitest'
import type {
  DocumentBeeReview,
  DocumentWorkingCopy,
} from '@/api/contracts/common'
import { reviewStage } from './reviewStage'

const review: DocumentBeeReview = {
  id: '1',
  input_revision: 1,
  resolved_revision: 1,
  reference_hash: 'sha256:a',
  summary: '요약',
  unavailable_references: [],
  references: [],
  suggestions: [],
  usage: [],
  steps: [],
  references_used: [],
}

const copy = (patch: Partial<DocumentWorkingCopy>): DocumentWorkingCopy => ({
  document_id: '1',
  owner_account_id: '1',
  revision: 1,
  status: 'EDITING',
  content: '본문',
  file_name: 'plan.md',
  latest_review: review,
  submissions: [],
  ...patch,
})

it('서버가 검토를 쓸 수 없다고 하면 본문이 그대로여도 다시 검토 필요 단계로 본다', () => {
  expect(reviewStage(copy({ review_stale: true }))).toMatchObject({
    step: 1,
    label: '다시 검토 필요',
    stale: true,
  })
})

it('review_stale이 없거나 false면 지금처럼 최종본 확인 단계로 본다', () => {
  expect(reviewStage(copy({}))).toMatchObject({ step: 3, stale: false })
  expect(reviewStage(copy({ review_stale: false }))).toMatchObject({
    step: 3,
    stale: false,
  })
})

it('팀장 승인을 기다리거나 승인된 건은 review_stale이어도 무효로 보지 않는다', () => {
  expect(
    reviewStage(copy({ status: 'SUBMITTED', review_stale: true })),
  ).toMatchObject({ step: 3, label: '팀장 승인 대기', stale: false })
  expect(
    reviewStage(copy({ status: 'APPROVED', review_stale: true })),
  ).toMatchObject({ step: 4, stale: false })
})

it('본문 리비전이 달라진 검토는 stale이 아니라 검토 중으로 본다', () => {
  expect(reviewStage(copy({ revision: 2, review_stale: true }))).toMatchObject({
    step: 1,
    label: '비비 검토 중',
    stale: false,
  })
})

// S15P31A108-383: 서버가 지금 본문의 마지막 검토 요청이 실패했다고 알리면(-382) '검토 중'이 아니라 실패로 본다.
const failure = {
  status: 'FAILED' as const,
  error_code: 'review_timeout',
  updated_at: '2026-11-10T08:16:05Z',
}

it('검토 결과 없이 마지막 요청이 실패했으면 검토 실패 단계로 본다', () => {
  expect(
    reviewStage(
      copy({ latest_review: undefined, bee_review_request: failure }),
    ),
  ).toMatchObject({ step: 1, label: '검토 실패', failed: true, stale: false })
  expect(
    reviewStage(
      copy({
        latest_review: undefined,
        bee_review_request: { ...failure, status: 'RUNNING' },
      }),
    ),
  ).toMatchObject({ label: '비비 검토 중', failed: false })
})

it('무효가 된 검토나 팀장 검토 중인 건은 실패보다 그 상태를 먼저 본다', () => {
  expect(
    reviewStage(copy({ review_stale: true, bee_review_request: failure })),
  ).toMatchObject({ label: '다시 검토 필요', stale: true, failed: false })
  expect(
    reviewStage(
      copy({
        status: 'SUBMITTED',
        latest_review: undefined,
        bee_review_request: failure,
      }),
    ),
  ).toMatchObject({ label: '팀장 승인 대기', failed: false })
})
