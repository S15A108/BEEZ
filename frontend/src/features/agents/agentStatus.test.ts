import { expect, it } from 'vitest'
import { agentStatusLabels, agentStatusOf } from './agentStatus'

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

it('상태 이름은 캔버스 네 가지다', () => {
  expect(Object.values(agentStatusLabels)).toEqual([
    '연결됨',
    '연결 미확인',
    '연결 실패',
    '연결 전',
  ])
})
