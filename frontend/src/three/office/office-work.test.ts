import { expect, test } from 'vitest'
import { officeSceneAgents, officeSceneSnapshot } from '../sceneData'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officePopulationOf } from './office-layout'
import { buildOfficeWork, officeHomeSeats } from './office-work'

// PR #128 리뷰: 팀원의 AI 에이전트(hired_by)가 아일랜드 작업 자리를 받아, 빈 자리 모니터에 엉뚱한 에이전트
// ('나의 에이전트 · 자리 비움')가 떴다. 에이전트는 자기 자리 없이 주인 자리를 기준으로 움직인다(D37).
test('에이전트가 있어도 작업 자리와 모니터의 주인은 팀원뿐이다', () => {
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
    { id: 'member:22', name: '김동료', kind: 'agent' },
    { id: 'member:26', name: '박팀원', kind: 'agent' },
    {
      id: 'agent:21',
      name: '나의 에이전트',
      kind: 'agent',
      owner: '21',
      status: 'offline',
    },
    {
      id: 'agent:26',
      name: '박팀원의 에이전트',
      kind: 'agent',
      owner: '26',
      status: 'working',
    },
  ])
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', undefined, officePopulationOf(agents))
      .floors[1],
    4,
  )
  expect([...officeHomeSeats(floor, agents).keys()]).not.toContain('agent:21')
  expect([...officeHomeSeats(floor, agents).keys()]).not.toContain('agent:26')
  const work = buildOfficeWork(
    floor,
    officeSceneSnapshot({ id: '1', name: '프로젝트' }, agents, [
      {
        id: 'TASK-003',
        title: '로그인',
        state: 'IN_PROGRESS',
        assignee_id: 'member:26',
      },
    ]),
  )
  const monitors = Object.values(work.monitors)
  expect(monitors.map((monitor) => monitor.agentId)).toContain('member:26')
  for (const monitor of monitors) {
    expect(monitor.agentId).toMatch(/^member:/)
    expect(monitor.owner).not.toContain('에이전트')
  }
})
