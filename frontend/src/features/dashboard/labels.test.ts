import { expect, it } from 'vitest'
import { activityChip } from './labels'

it('검증 통과와 검토 시작은 유형으로 구분하고 성공은 좁은 곳에서 뺀다', () => {
  expect(
    activityChip({ type: 'VALIDATION_FINISHED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '통과', notable: true })
  expect(
    activityChip({ type: 'CHANGE_PROPOSAL_REVIEW_STARTED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '진행 중', notable: true })
  expect(
    activityChip({ type: 'TASK_STARTED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '성공', notable: false })
})

it('실패는 유형과 상관없이 실패로 보인다', () => {
  expect(
    activityChip({ type: 'VALIDATION_FINISHED', result: 'FAILURE' }),
  ).toMatchObject({ label: '실패', notable: true })
})
