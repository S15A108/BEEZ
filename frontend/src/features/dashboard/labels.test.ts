import { expect, it } from 'vitest'
import {
  activityChip,
  agentLabel,
  agentTokenSuffix,
  callsText,
  costText,
} from './labels'

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

it('이름 뒤에 붙는 토큰 구분은 토큰이 둘 이상일 때만 생긴다', () => {
  const one = { member_id: '1', agent_session_id: '91', agent_name: '노트북' }
  const two = { member_id: '1', agent_session_id: '95' }
  expect(agentTokenSuffix(one, [one])).toBe('')
  expect(agentTokenSuffix(one, [one, two])).toBe(' · 노트북')
  expect(agentTokenSuffix(two, [one, two])).toBe(' · #95')
})

// S15P31A108-346: 호출이 모두 미보고면 서버가 주는 0은 모르는 값이라 '0 USD + 미보고 N건'으로 보이면 안 된다.
it('호출이 모두 비용 미보고면 금액 대신 미보고만, 일부만 모르면 금액에 건수를 붙인다', () => {
  expect(costText('0', 'USD', 4, '비용 미보고', 4)).toBe('비용 미보고')
  expect(costText('0.5', 'USD', 1, '비용 미보고', 4)).toBe(
    '0.5 USD + 미보고 1건',
  )
  // 호출 수를 모르는 호출부는 이전처럼 건수를 붙인다.
  expect(costText('0', 'USD', 4)).toBe('0 USD + 미보고 4건')
  expect(
    callsText(
      { call_count: 4, estimated_cost_amount: '0', cost_unknown_call_count: 4 },
      'USD',
    ),
  ).toBe('4회 · 비용 미보고')
})
