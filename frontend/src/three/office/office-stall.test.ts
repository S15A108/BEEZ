import { expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import { officePopulationOf } from './office-layout'
import {
  getOfficeLifeActivity,
  OfficeLifeSimulation,
  type OfficeLifeActor,
} from './office-life'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

// PR #72 전수 리뷰 THREE-01 재현(full-review/proofs/three-crowd)을 회귀 테스트로 옮겼다.
// 겹침 방지가 걸음을 막으면 같은 정적 경로를 다시 찾아 서로 마주 선 채 멈췄다.

it('통로에서 마주 걸어오는 두 사람이 비켜 지나 자기 자리에 닿는다', () => {
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, { dev: 1, lead: 1, humandesk: 1 })
      .floors[1],
    4,
  )
  const initial: OfficeLifeActor[] = [
    {
      id: 'left-worker',
      x: 112,
      y: 379,
      dir: 'right',
      moving: false,
      status: 'working',
    },
    {
      id: 'right-worker',
      x: 336,
      y: 379,
      dir: 'left',
      moving: false,
      status: 'working',
    },
  ]
  const swapped = initial.map((actor, index) => ({
    ...actor,
    x: initial[1 - index].x,
  }))
  const simulation = new OfficeLifeSimulation('stall-crossing')
  simulation.step(floor, initial, 0.05)
  let snapshot = initial
  for (let frame = 0; frame < 1200; frame++)
    snapshot = simulation.step(floor, swapped, 0.05)
  simulation.dispose()
  for (const [index, actor] of snapshot.entries())
    expect(
      Math.hypot(actor.x - swapped[index].x, actor.y - swapped[index].y),
    ).toBeLessThan(1)
})

it('기본 사무실 NPC는 걷는 중에 오래 멈춰 서 있지 않는다', () => {
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead', color: '#f5b800' },
    { id: 'member:21', name: 'me', kind: 'human', status: 'idle' },
    { id: 'member:24', name: 'member24', kind: 'agent', status: 'idle' },
    { id: 'member:23', name: 'member23', kind: 'agent', status: 'idle' },
    { id: 'member:22', name: 'member22', kind: 'agent', status: 'idle' },
  ])
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, officePopulationOf(agents)).floors[1],
    4,
  )
  const actors = officeActors(
    floor,
    agents,
    4,
    'member:21',
    officeHomeSeats(floor, agents),
  )
  const simulation = new OfficeLifeSimulation('stall-idle')
  const stall = new Map<string, number>()
  let maxStall = 0
  for (let frame = 0; frame < 2400; frame++)
    for (const actor of simulation.step(floor, actors, 0.05, {
      caller: { x: 240, y: 144 },
    })) {
      const waiting =
        getOfficeLifeActivity('stall-idle', actor.id)?.activity === 'walking' &&
        !actor.moving
      const current = waiting ? (stall.get(actor.id) ?? 0) + 0.05 : 0
      stall.set(actor.id, current)
      maxStall = Math.max(maxStall, current)
    }
  simulation.dispose()
  expect(maxStall).toBeLessThan(10)
})
