import { expect, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import {
  getOfficeLifeActivity,
  OfficeLifeSimulation,
  PERSONAL_SPACE,
  type OfficeLifeActor,
} from './office-life'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

// D37(S15P31A108-453): 대기 중인 에이전트(연결됨·대기)는 돌아다니기와 라운지 리클라이너·창가에서 쉬기를 번갈아 하고,
// 작업을 시작하면 작업 자리로 돌아가 앉는다. 연결 전 에이전트의 충전 자리는 그대로다.

const floor = makeDreamFloor(
  makeBuilding(ALL_KEYS, 'glass', null, {}).floors[1],
  4,
)
const team = (status: string) =>
  officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
    { id: 'member:22', name: '김동료', kind: 'agent' },
    {
      id: 'agent:22',
      name: '김동료의 에이전트',
      kind: 'agent',
      owner: '22',
      status,
    },
  ])
const actorsOf = (status: string) => {
  const agents = team(status)
  return officeActors(
    floor,
    agents,
    4,
    'member:21',
    officeHomeSeats(floor, agents),
  )
}
const REST = ['resting', 'window', 'coffee']

test.each(['online', 'waiting'])(
  '%s 에이전트는 주로 쉬고 가끔 가까운 곳에 짧게 들른다',
  (status) => {
    const id = `idle-${status}`
    const simulation = new OfficeLifeSimulation(id)
    const actors = actorsOf(status)
    expect(actors.find((actor) => actor.id === 'agent:22')).toMatchObject({
      idle: 'lounge',
    })
    let resting = 0,
      visiting = 0,
      sat = false
    const visits: { x: number; y: number }[] = []
    let lastRest: { x: number; y: number } | undefined
    // 10분 동안 지켜본다.
    for (let frame = 0; frame < 12000; frame++) {
      const agent = simulation
        .step(floor, actors, 0.05)
        .find((actor) => actor.id === 'agent:22')!
      const activity = getOfficeLifeActivity(id, 'agent:22')!.activity
      if (REST.includes(activity)) {
        resting += 0.05
        sat ||= activity === 'resting' && !!agent.seated
        lastRest = { x: agent.x, y: agent.y }
      } else if (activity === 'dropby' || activity === 'home') {
        visiting += 0.05
        // 짧게 들르는 곳은 쉬던 곳에서 가깝다(6칸 안 + 길 여유).
        if (lastRest && !visits.length)
          expect(
            Math.hypot(agent.x - lastRest.x, agent.y - lastRest.y),
          ).toBeLessThanOrEqual(7 * 32)
        visits.push({ x: agent.x, y: agent.y })
      }
    }
    simulation.dispose()
    expect(sat).toBe(true)
    // 쉬는 시간이 들르는 시간의 두 배 넘는다.
    expect(resting).toBeGreaterThan(visiting * 2)
    expect(resting).toBeGreaterThan(120)
  },
)

test('대기 중인 에이전트가 작업을 시작하면 작업 자리에 가서 앉고, 끝내면 먼저 쉬러 간다', () => {
  const simulation = new OfficeLifeSimulation('idle-to-work')
  const idle = actorsOf('online')
  const working = actorsOf('working')
  const seat = working.find((actor) => actor.id === 'agent:22')!
  const run = (actors: typeof idle, seconds: number) => {
    let agent = actors[0] as OfficeLifeActor
    for (let t = 0; t < seconds; t += 0.05)
      agent = simulation
        .step(floor, actors, 0.05)
        .find((actor) => actor.id === 'agent:22')!
    return agent
  }
  // 한동안 돌아다니다가 작업이 들어온다.
  const before = run(idle, 30)
  expect(Math.hypot(before.x - seat.x, before.y - seat.y)).toBeGreaterThan(32)
  const atWork = run(working, 40)
  expect(Math.hypot(atWork.x - seat.x, atWork.y - seat.y)).toBeLessThan(32)
  expect(atWork.seated).toBe(true)
  expect(getOfficeLifeActivity('idle-to-work', 'agent:22')?.activity).toBe(
    'working',
  )
  // 일을 끝내면 자리에서 일어나 쉬는 곳으로 간다.
  run(idle, 0.2)
  expect(
    getOfficeLifeActivity('idle-to-work', 'agent:22')?.destination,
  ).toBeOneOf(REST)
  simulation.dispose()
})

test('쉬던 리클라이너가 연결 전 에이전트의 충전 자리가 되면 일어나 비켜 준다', () => {
  const simulation = new OfficeLifeSimulation('idle-charging')
  const agent: OfficeLifeActor = {
    id: 'resting-agent',
    x: floor.exit.x * 32 + 16,
    y: floor.exit.y * 32 + 27,
    dir: 'right',
    moving: false,
    status: 'idle',
    idle: 'lounge',
  }
  let current = agent
  let pose: { x: number; y: number } | undefined
  for (let frame = 0; frame < 3600 && !pose; frame++) {
    current = simulation.step(floor, [agent], 0.05)[0]
    if (
      current.seated &&
      getOfficeLifeActivity('idle-charging', agent.id)?.activity === 'resting'
    )
      pose = { x: current.x, y: current.y }
  }
  expect(pose).toBeDefined()
  const charging: OfficeLifeActor = {
    id: 'charging-agent',
    ...pose!,
    dir: 'down',
    moving: false,
    stationary: true,
  }
  for (let t = 0; t < 8; t += 0.05)
    current = simulation.step(floor, [agent, charging], 0.05)[0]
  simulation.dispose()
  expect(current.seated).toBeFalsy()
  expect(
    Math.hypot(current.x - pose!.x, current.y - pose!.y),
  ).toBeGreaterThanOrEqual(PERSONAL_SPACE)
})
