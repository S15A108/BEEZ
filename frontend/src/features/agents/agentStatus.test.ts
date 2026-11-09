import { expect, it } from 'vitest'
import { agentStatusLabels, agentStatusOf, pickAgentRow } from './agentStatus'

const row = (connected: boolean, status_check_required: boolean) => ({
  connected,
  status_check_required,
})

it('연결 현황에 따라 상태를 하나로 정한다', () => {
  expect(agentStatusOf(row(true, false))).toBe('connected')
  expect(agentStatusOf(row(true, true))).toBe('recheck')
  expect(agentStatusOf(row(false, true))).toBe('recheck')
  expect(agentStatusOf(row(false, false))).toBe('none')
  expect(agentStatusOf(undefined)).toBe('none')
})

it("연결이 없을 때만 '연결 확인' 실패가 연결 실패가 된다", () => {
  expect(agentStatusOf(undefined, true)).toBe('failed')
  expect(agentStatusOf(row(false, true), true)).toBe('failed')
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

it('상태 이름은 캔버스 네 가지다', () => {
  expect(Object.values(agentStatusLabels)).toEqual([
    '연결됨',
    '연결 미확인',
    '연결 실패',
    '연결 전',
  ])
})
