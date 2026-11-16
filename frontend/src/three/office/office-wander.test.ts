import { expect, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import {
  getOfficeLifeActivity,
  OfficeLifeSimulation,
  PERSONAL_SPACE,
  retreatSpot,
  strollCandidates,
} from './office-life'
import { officeWalkable } from './office-navigation'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

// D37(S15P31A108-452): 고정 사무실의 팀원과 할 일 없는 에이전트는 빈 바닥을 돌아다니다 멈춰 쉰다. 화면 연출이라
// 위치는 프론트가 정하고, 같은 입력이면 늘 같은 길로 걷는다(난수 대신 id·차례로 섞는다).

test('돌아다닐 자리 후보는 같은 seed면 같은 순서이고, 가까운 곳과 남의 자리 곁은 뺀다', () => {
  const spots = Array.from({ length: 40 }, (_, index) => ({
    x: (index % 8) * 32 + 16,
    y: Math.floor(index / 8) * 32 + 27,
  }))
  const from = spots[0]
  const other = spots[20]
  const first = strollCandidates(spots, from, 7, [other])
  expect(strollCandidates(spots, from, 7, [other])).toEqual(first)
  expect(strollCandidates(spots, from, 8, [other])).not.toEqual(first)
  expect(first.length).toBeGreaterThan(10)
  for (const spot of first) {
    expect(Math.hypot(spot.x - from.x, spot.y - from.y)).toBeGreaterThan(96)
    expect(
      Math.hypot(spot.x - other.x, spot.y - other.y),
    ).toBeGreaterThanOrEqual(PERSONAL_SPACE * 1.5)
  }
})

test('막혔을 때 물러설 자리는 벽 없이 곧장 가고 막은 사람에게서 멀어지는 곳이다', () => {
  const from = { x: 100, y: 100 }
  const blocker = { x: 130, y: 100 }
  const open = () => true
  // 동쪽에서 막으면 서쪽으로 물러선다.
  const back = retreatSpot(from, [blocker], open)!
  expect(back.x).toBeLessThan(from.x)
  expect(Math.hypot(back.x - from.x, back.y - from.y)).toBeCloseTo(40)
  // 서쪽이 벽이면 벽을 넘지 않고 다른 쪽으로 멀어진다.
  const wallWest = (x: number) => x >= 90
  const side = retreatSpot(from, [blocker], wallWest)!
  expect(side.x).toBeGreaterThanOrEqual(90)
  expect(Math.hypot(side.x - blocker.x, side.y - blocker.y)).toBeGreaterThan(30)
  // 사방이 막혔으면 물러서지 않는다.
  expect(
    retreatSpot(
      from,
      [blocker],
      (x, y) => Math.hypot(x - from.x, y - from.y) < 5,
    ),
  ).toBeUndefined()
})

const DESIGNS = ['glass', 'brick', 'modern', 'tower', 'wood'] as const

// 나무 배치의 북·동쪽 벽을 등진 의자는 뒤에 설 곳이 없어, 그 자리 팀원이 사무실에서 빠졌다. 작업 자리(10석)를 받은
// 팀원은 배치와 상관없이 모두 사무실에 있고, 걸을 수 있는 바닥에서 서로 사람 사이 거리를 두고 출발한다.
test.each(DESIGNS)(
  '%s: 작업 자리를 받은 팀원은 모두 사무실에 있다',
  (design) => {
    const floor = makeDreamFloor(
      makeBuilding(ALL_KEYS, design, null, {}).floors[1],
      4,
    )
    const agents = officeSceneAgents([
      { id: 'member:21', name: '나', kind: 'human' },
      ...Array.from({ length: 10 }, (_, index) => ({
        id: `member:${30 + index}`,
        name: `팀원 ${index}`,
        kind: 'agent' as const,
      })),
    ])
    const homes = officeHomeSeats(floor, agents)
    const people = officeActors(floor, agents, 4, 'member:21', homes).filter(
      (actor) => actor.id.startsWith('member:3'),
    )
    expect(people.map((person) => person.id).sort()).toEqual(
      [...homes.keys()].filter((id) => id.startsWith('member:3')).sort(),
    )
    expect(people).toHaveLength(10)
    for (const [index, person] of people.entries()) {
      expect(officeWalkable(floor, person.x, person.y)).toBe(true)
      for (const other of people.slice(index + 1))
        expect(
          Math.hypot(person.x - other.x, person.y - other.y),
        ).toBeGreaterThanOrEqual(PERSONAL_SPACE)
    }
  },
)

test.each(DESIGNS)(
  '%s: 팀원과 할 일 없는 에이전트는 벽·가구를 지나지 않고 서로 겹치지 않게 돌아다니고, 작업 중 에이전트는 자리에 있다',
  (design) => {
    const floor = makeDreamFloor(
      makeBuilding(ALL_KEYS, design, null, {}).floors[1],
      4,
    )
    const agents = officeSceneAgents([
      { id: 'bibi', name: '비비', kind: 'lead' },
      { id: 'member:21', name: '나', kind: 'human' },
      { id: 'member:22', name: '김동료', kind: 'agent' },
      { id: 'member:23', name: '이동료', kind: 'agent' },
      { id: 'member:24', name: '박동료', kind: 'agent' },
      {
        id: 'agent:22',
        name: '김동료의 에이전트',
        kind: 'agent',
        owner: '22',
        status: 'working',
      },
      {
        id: 'agent:23',
        name: '이동료의 에이전트',
        kind: 'agent',
        owner: '23',
        status: 'online',
      },
      {
        id: 'agent:24',
        name: '박동료의 에이전트',
        kind: 'agent',
        owner: '24',
        status: 'offline',
      },
    ])
    const actors = officeActors(
      floor,
      agents,
      4,
      'member:21',
      officeHomeSeats(floor, agents),
    )
    const at = (id: string) => actors.find((actor) => actor.id === id)!
    // 팀원은 제자리 배우가 아니고, 일하는 상태가 없으며 가구를 쓰지 않는 걷기만 한다.
    const people = actors.filter(
      (actor) => actor.id.startsWith('member:') && actor.id !== 'member:21',
    )
    expect(people.map((person) => person.id)).toEqual([
      'member:22',
      'member:23',
      'member:24',
    ])
    for (const person of people) {
      expect(person).toMatchObject({ idle: 'stroll', status: 'idle' })
      expect(person.stationary).toBeFalsy()
    }
    expect(at('agent:23')).toMatchObject({ idle: 'stroll' })
    expect(at('agent:22').idle).toBeUndefined()
    expect(at('agent:24').stationary).toBe(true)

    const me = { x: floor.exit.x * 32 + 16, y: floor.exit.y * 32 + 27 }
    const simulation = new OfficeLifeSimulation(`wander-${design}`)
    const strollers = [...people.map((person) => person.id), 'agent:23']
    const walked = new Map<string, number>()
    const stops = new Map<string, Set<string>>()
    let previous = simulation.step(floor, actors, 0.05, { caller: me })
    let closest = Infinity
    for (let frame = 0; frame < 1800; frame++) {
      const current = simulation.step(floor, actors, 0.05, { caller: me })
      for (const [index, actor] of current.entries()) {
        const step = Math.hypot(
          actor.x - previous[index].x,
          actor.y - previous[index].y,
        )
        walked.set(actor.id, (walked.get(actor.id) ?? 0) + step)
        if (!strollers.includes(actor.id)) continue
        // 돌아다니는 사람은 앉지 않고, 언제나 걸을 수 있는 바닥에 있다.
        expect(actor.seated).toBeFalsy()
        expect(officeWalkable(floor, actor.x, actor.y)).toBe(true)
        if (
          getOfficeLifeActivity(`wander-${design}`, actor.id)?.activity ===
          'strolling'
        )
          (
            stops.get(actor.id) ?? stops.set(actor.id, new Set()).get(actor.id)!
          ).add(`${Math.round(actor.x)}/${Math.round(actor.y)}`)
      }
      // 처음 몇 프레임은 자리 곁에서 갈라진다. 그 뒤로는 나를 포함해 누구도 개인 거리 안으로 겹치지 않는다.
      if (frame > 40) {
        const people = [...current, me]
        for (let a = 0; a < people.length; a++)
          for (let b = a + 1; b < people.length; b++)
            closest = Math.min(
              closest,
              Math.hypot(people[a].x - people[b].x, people[a].y - people[b].y),
            )
      }
      previous = current
    }
    simulation.dispose()
    for (const id of strollers) {
      expect(walked.get(id)).toBeGreaterThan(10 * 32)
      // 걷다가 멈춰 쉬는 자리가 여러 곳이다.
      expect(stops.get(id)?.size ?? 0).toBeGreaterThanOrEqual(3)
    }
    // 작업 중 에이전트는 주인 의자에 앉아 있고, 연결 전 에이전트는 충전 자리에서 움직이지 않는다.
    const working = previous.find((actor) => actor.id === 'agent:22')!
    expect(
      Math.hypot(working.x - at('agent:22').x, working.y - at('agent:22').y),
    ).toBeLessThan(32)
    expect(working.seated).toBe(true)
    expect(walked.get('agent:24')).toBe(0)
    expect(closest).toBeGreaterThanOrEqual(PERSONAL_SPACE - 1)
  },
)
