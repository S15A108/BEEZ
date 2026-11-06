import { expect, it } from 'vitest'
import { leaderSetupStep } from './leaderSetupStep'

const base = {
  invited: false,
  agentConnected: false,
  skipFlag: false,
  started: false,
}
const links = (step: ReturnType<typeof leaderSetupStep>) =>
  step.links.map((link) => link.label)

it('처음에는 초대와 에이전트 연결 두 줄을 보이고 완료로 세지 않는다', () => {
  const step = leaderSetupStep(base)
  expect(step.done).toBe(false)
  expect(step.skipped).toBe(false)
  expect(step.detail).toBe('둘 다 안 해도 다음 단계로 넘어갈 수 있어요.')
  expect(step.rows.map((row) => [row.label, row.action, row.focus])).toEqual([
    ['팀원 초대', '초대하기', 'team'],
    ['내 에이전트 연결', '연결하기', 'agent'],
  ])
})

it('초대만 끝나면 단계는 남고 초대 줄만 완료로 보인다', () => {
  const step = leaderSetupStep({ ...base, invited: true })
  expect(step.done).toBe(false)
  expect(step.rows.map((row) => row.done)).toEqual([true, false])
})

it('둘 다 끝나면 건너뛴 기록이 있어도 건너뜀이 아니라 완료다', () => {
  const step = leaderSetupStep({
    ...base,
    invited: true,
    agentConnected: true,
    skipFlag: true,
  })
  expect(step.done).toBe(true)
  expect(step.skipped).toBe(false)
  expect(step.links).toEqual([])
})

it('건너뛰면 완료로 세고 시작 전에는 설정에서 다시 할 수 있다고 알린다', () => {
  const step = leaderSetupStep({ ...base, skipFlag: true })
  expect(step.done).toBe(true)
  expect(step.skipped).toBe(true)
  expect(step.detail).toBe('설정에서 언제든 할 수 있어요.')
  expect(links(step)).toEqual(['지금 초대하기', '에이전트 연결하기'])
})

it('건너뛴 채로 시작하면 아까 건너뛰었다고 알리고 남은 쪽 안내와 링크만 둔다', () => {
  const skipped = { ...base, skipFlag: true, started: true }
  const both = leaderSetupStep(skipped)
  expect(both.detail).toBe(
    '아까 건너뛰었어요 · 팀원을 부르면 같이 작업 계획을 올릴 수 있어요. 에이전트는 설정 › 에이전트에서 연결해요.',
  )
  expect(links(both)).toEqual(['팀원 초대하기', '에이전트 연결하기'])

  const invited = leaderSetupStep({ ...skipped, invited: true })
  expect(invited.detail).toBe(
    '아까 건너뛰었어요 · 에이전트는 설정 › 에이전트에서 연결해요.',
  )
  expect(links(invited)).toEqual(['에이전트 연결하기'])

  const connected = leaderSetupStep({ ...skipped, agentConnected: true })
  expect(connected.detail).toBe(
    '아까 건너뛰었어요 · 팀원을 부르면 같이 작업 계획을 올릴 수 있어요.',
  )
  expect(links(connected)).toEqual(['팀원 초대하기'])
})
