import { expect, it } from 'vitest'
import { officeAgentState, showsOfficeAgent } from './officeAgents'

const connected = { id: '22', agent_connected: true }

it('작업 현황(MON-A05)대로 에이전트 상태를 정하고, 세션이 없으면 연결 전이다', () => {
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
})

// PR #128 리뷰(양지훈): 팀원 목록의 agent_connected는 사무실을 열 때 한 번 받은 값이다. 서버는 agent_connected와
// 작업 현황의 행을 같은 조건(폐기되지 않은 토큰에 에이전트 세션이 있음)으로 만드므로, 30초마다 새로 받는 작업 현황의
// 세션으로 연결 상태를 정한다. 화면을 연 뒤 연결한 에이전트도 연결 전으로 남지 않는다.
it('화면을 연 뒤 연결된 에이전트는 새 작업 현황의 세션으로 연결 상태를 정한다', () => {
  const openedBeforeConnect = { id: '22', agent_connected: false }
  expect(
    officeAgentState(
      openedBeforeConnect,
      [
        {
          member_id: '22',
          agent_session_id: '901',
          task_status: 'IN_PROGRESS',
        },
      ],
      [],
    ).state,
  ).toBe('working')
  expect(
    officeAgentState(
      openedBeforeConnect,
      [{ member_id: '22', agent_session_id: '901' }],
      [],
    ).state,
  ).toBe('online')
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

// PR #128 리뷰: 작업 현황이 있으면 첫 조회의 agent_connected 대신 새 작업 현황의 세션으로 그릴지 정한다. 열어 둔 동안
// 토큰이 폐기된 에이전트는 사라지고, 연 뒤에 연결한 에이전트는 나타난다. 기대값 변경: 작업 현황이 있을 때
// agent_connected만 참이고 세션이 없는 22번은 true → false.
it('작업 현황에 세션이 있는 에이전트와 내 에이전트만 사무실에 그린다', () => {
  const member = (id: string, account_id: string, agent_connected = false) => ({
    id,
    account_id,
    agent_connected,
  })
  const work = [{ member_id: '25', agent_session_id: '903' }]
  expect(showsOfficeAgent(member('22', '12', true), work, '11')).toBe(false)
  expect(showsOfficeAgent(member('25', '13'), work, '11')).toBe(true)
  expect(showsOfficeAgent(member('21', '11'), work, '11')).toBe(true)
  expect(showsOfficeAgent(member('26', '14'), work, '11')).toBe(false)
})

it('작업 현황 API가 없으면 연결된 에이전트와 내 에이전트를 그린다', () => {
  const member = (id: string, account_id: string, agent_connected = false) => ({
    id,
    account_id,
    agent_connected,
  })
  expect(showsOfficeAgent(member('22', '12', true), undefined, '11')).toBe(true)
  expect(showsOfficeAgent(member('21', '11'), undefined, '11')).toBe(true)
  expect(showsOfficeAgent(member('26', '14'), undefined, '11')).toBe(false)
})

// S15P31A108-341: 작업 현황(MON-A05)에는 확인 필요 표시가 없어(11-09 전진 제안, 박민용 동의) 사무실도 연결 현황(AGT-A05)의
// 상태를 함께 본다. 연결은 연결 현황으로, 자리는 작업 현황으로 정해 다른 화면과 같은 순간 같은 연결 상태를 보인다.
it('연결 현황이 있으면 연결은 그 상태로, 자리는 작업 현황으로 정한다', () => {
  const working = [
    {
      member_id: '22',
      agent_session_id: '901',
      task_status: 'IN_PROGRESS' as const,
    },
  ]
  expect(officeAgentState(connected, working, [], 'connected')).toEqual({
    state: 'working',
  })
  // 연결 미확인: 자리는 그대로 두고 이름표 아래에 상태를 단다
  expect(officeAgentState(connected, working, [], 'recheck')).toEqual({
    state: 'working',
    note: '연결 미확인',
  })
  expect(
    officeAgentState(
      connected,
      [
        {
          member_id: '22',
          agent_session_id: '901',
          task_status: 'WAITING',
          waiting_reason: 'APPROVAL',
        },
      ],
      [],
      'recheck',
    ),
  ).toEqual({ state: 'waiting', note: '연결 미확인 · 승인 대기' })
  // 두 조회의 시차: 연결 현황이 끊김이면 작업 현황에 세션이 남아 있어도 끊김이다
  expect(officeAgentState(connected, working, [], 'disconnected')).toEqual({
    state: 'disconnected',
  })
  // 연결 현황은 연결됨인데 작업 현황에 아직 세션이 없으면 할 일 없는 연결됨이다
  expect(
    officeAgentState(connected, [{ member_id: '22' }], [], 'connected').state,
  ).toBe('online')
  expect(officeAgentState(connected, working, [], 'none').state).toBe('offline')
  // 작업 현황을 아직 못 받았으면 주인의 진행 중 작업으로 어림한다
  expect(
    officeAgentState(
      connected,
      undefined,
      [{ status: 'IN_PROGRESS' }],
      'recheck',
    ),
  ).toEqual({ state: 'working', note: '연결 미확인' })
})

it('연결 현황이 있으면 연결됨·연결 미확인 에이전트와 내 에이전트를 그린다', () => {
  const member = (id: string, account_id: string, agent_connected = false) => ({
    id,
    account_id,
    agent_connected,
  })
  const work = [{ member_id: '25', agent_session_id: '903' }]
  expect(showsOfficeAgent(member('22', '12'), [], '11', 'connected')).toBe(true)
  expect(showsOfficeAgent(member('22', '12'), [], '11', 'recheck')).toBe(true)
  expect(
    showsOfficeAgent(member('25', '13', true), work, '11', 'disconnected'),
  ).toBe(false)
  expect(showsOfficeAgent(member('26', '14', true), work, '11', 'none')).toBe(
    false,
  )
  expect(showsOfficeAgent(member('21', '11'), [], '11', 'disconnected')).toBe(
    true,
  )
})
