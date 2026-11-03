import { expect, it } from 'vitest'
import { officeAgentState, showsOfficeAgent } from './officeAgents'

const connected = { id: '22', agent_connected: true }

it('작업 현황(MON-A05)대로 에이전트 상태를 정하고, 세션이 없으면 연결 끊김이다', () => {
  const work = (fields: object) => [
    { member_id: '22', agent_session_id: '901', ...fields },
  ]
  expect(officeAgentState(connected, work({}), []).state).toBe('online')
  expect(
    officeAgentState(connected, work({ task_status: 'IN_PROGRESS' }), []).state,
  ).toBe('working')
  expect(
    officeAgentState(
      connected,
      work({ task_status: 'READY', validation_status: 'RUNNING' }),
      [],
    ).state,
  ).toBe('validating')
  expect(
    officeAgentState(
      connected,
      work({ task_status: 'WAITING', waiting_reason: 'APPROVAL' }),
      [],
    ),
  ).toEqual({ state: 'waiting', note: '승인 대기' })
  expect(officeAgentState(connected, [{ member_id: '22' }], []).state).toBe(
    'offline',
  )
  expect(
    officeAgentState(
      { id: '22', agent_connected: false },
      work({ task_status: 'IN_PROGRESS' }),
      [],
    ).state,
  ).toBe('offline')
})

it('작업 현황 API가 없으면 연결 여부와 주인의 진행 중 작업으로 어림한다', () => {
  expect(officeAgentState(connected, undefined, []).state).toBe('online')
  expect(
    officeAgentState(connected, undefined, [{ status: 'IN_PROGRESS' }]).state,
  ).toBe('working')
  expect(
    officeAgentState({ id: '22', agent_connected: false }, undefined, []).state,
  ).toBe('offline')
})

it('연결된 에이전트, 세션이 있는 에이전트, 아직 연결하지 않은 내 에이전트만 사무실에 그린다', () => {
  const member = (id: string, account_id: string, agent_connected = false) => ({
    id,
    account_id,
    agent_connected,
  })
  const work = [{ member_id: '25', agent_session_id: '903' }]
  expect(showsOfficeAgent(member('22', '12', true), work, '11')).toBe(true)
  expect(showsOfficeAgent(member('25', '13'), work, '11')).toBe(true)
  expect(showsOfficeAgent(member('21', '11'), work, '11')).toBe(true)
  expect(showsOfficeAgent(member('26', '14'), work, '11')).toBe(false)
})
