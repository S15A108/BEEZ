import { expect, it } from 'vitest'
import { agentStatusLabels, agentStatusOf, pickAgentRow } from './agentStatus'

const row = (connected: boolean, status_check_required: boolean) => ({
  connected,
  status_check_required,
})

// S15P31A108-341: AGT-A05(-75) 정의. 줄이 있고 connected=false면 status_check_required와 상관없이 연결 끊김이고,
// 줄이 없을 때만 연결 전이다. 기대값 변경(사용자 승인 2026-11-09): (false, true) recheck → disconnected,
// (false, false) none → disconnected.
it('연결 현황에 따라 상태를 하나로 정한다', () => {
  expect(agentStatusOf(row(true, false))).toBe('connected')
  expect(agentStatusOf(row(true, true))).toBe('recheck')
  expect(agentStatusOf(row(false, true))).toBe('disconnected')
  expect(agentStatusOf(row(false, false))).toBe('disconnected')
  expect(agentStatusOf(undefined)).toBe('none')
})

it("연결이 없을 때만 '연결 확인' 실패가 연결 실패가 된다", () => {
  expect(agentStatusOf(undefined, true)).toBe('failed')
  expect(agentStatusOf(row(false, true), true)).toBe('failed')
  expect(agentStatusOf(row(false, false), true)).toBe('failed')
  expect(agentStatusOf(row(true, false), true)).toBe('connected')
  expect(agentStatusOf(row(true, true), true)).toBe('recheck')
})

it('세션 줄이 여럿이면 연결된 줄 중 가장 최근 것, 없으면 가장 최근 줄을 고른다', () => {
  const at = (id: string, connected: boolean, last_report_at?: string) => ({
    id,
    connected,
    last_report_at,
  })
  expect(pickAgentRow([])).toBeUndefined()
  expect(
    pickAgentRow([
      at('old-off', false, '2026-11-03T00:00:00Z'),
      at('on', true, '2026-11-01T00:00:00Z'),
    ])?.id,
  ).toBe('on')
  expect(
    pickAgentRow([
      at('on-old', true, '2026-11-01T00:00:00Z'),
      at('on-new', true, '2026-11-02T00:00:00Z'),
    ])?.id,
  ).toBe('on-new')
  expect(
    pickAgentRow([
      at('no-time', false),
      at('off-new', false, '2026-11-02T00:00:00Z'),
    ])?.id,
  ).toBe('off-new')
})

// 기대값 변경(사용자 승인 2026-11-09, S15P31A108-341): 캔버스 네 가지에 AGT-A05의 '연결 끊김'을 더한다.
it('상태 이름은 캔버스 네 가지와 연결 끊김이다', () => {
  expect(Object.values(agentStatusLabels)).toEqual([
    '연결됨',
    '연결 미확인',
    '연결 실패',
    '연결 끊김',
    '연결 전',
  ])
})
