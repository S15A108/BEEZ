import { expect, it } from 'vitest'
import { activityChip, agentLabel } from './labels'

it('검증 통과와 검토 시작은 유형으로 구분하고, 통과만 좁은 곳에도 남긴다', () => {
  expect(
    activityChip({ type: 'VALIDATION_FINISHED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '통과', notable: true })
  expect(
    activityChip({ type: 'CHANGE_PROPOSAL_REVIEW_STARTED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '진행 중', notable: false })
  expect(
    activityChip({ type: 'TASK_STARTED', result: 'SUCCESS' }),
  ).toMatchObject({ label: '성공', notable: false })
})

it('실패는 유형과 상관없이 실패로 보인다', () => {
  expect(
    activityChip({ type: 'VALIDATION_FINISHED', result: 'FAILURE' }),
  ).toMatchObject({ label: '실패', notable: true })
})

it('토큰이 하나면 팀원 이름만, 둘이면 토큰 이름이나 세션 번호를 붙인다', () => {
  const name = () => '민수'
  const one = { member_id: '1', agent_session_id: '91', agent_name: '노트북' }
  const two = { member_id: '1', agent_session_id: '95' }
  expect(agentLabel(one, [one], name)).toBe('민수의 에이전트')
  expect(agentLabel(one, [one, two], name)).toBe('민수의 에이전트 · 노트북')
  expect(agentLabel(two, [one, two], name)).toBe('민수의 에이전트 · #95')
})
