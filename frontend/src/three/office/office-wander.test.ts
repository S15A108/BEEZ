import { expect, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import { BODY_RADIUS } from './office-nav'
import { bodyGap } from './office-nav.test-helpers'
import {
  getOfficeLifeActivity,
  OfficeLifeSimulation,
  PERSONAL_SPACE,
  retreatSpot,
  SEATED_SPACE,
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
    const name = `wander-${design}`
    const simulation = new OfficeLifeSimulation(name)
    const strollers = [...people.map((person) => person.id), 'agent:23']
    const walked = new Map<string, number>()
    const stops = new Map<string, Set<string>>()
    const rests: number[] = []
    const resting = new Map<string, number>()
    const reversals = new Map<string, number>()
    const moves = new Map<string, { x: number; y: number }>()
    let previous = simulation.step(floor, actors, 0.05, { caller: me })
    let closest = Infinity,
      closestSeated = Infinity,
      closestWalkers = Infinity,
      mostWalkers = 0,
      sharpest = 0
    // 4분 동안 지켜본다.
    for (let frame = 0; frame < 4800; frame++) {
      const current = simulation.step(floor, actors, 0.05, { caller: me })
      let walking = 0
      for (const [index, actor] of current.entries()) {
        const before = previous[index]
        const step = Math.hypot(actor.x - before.x, actor.y - before.y)
        walked.set(actor.id, (walked.get(actor.id) ?? 0) + step)
        if (!strollers.includes(actor.id)) continue
        const activity = getOfficeLifeActivity(name, actor.id)?.activity
        // 돌아다니는 사람은 앉지 않고, 몸이 벽·가구·의자에 닿지 않는다(office-nav와 따로 계산한 장애물로 잰다).
        expect(actor.seated).toBeFalsy()
        expect(bodyGap(floor, actor)).toBeGreaterThanOrEqual(BODY_RADIUS - 0.5)
        // 길을 비켜 한 걸음 물러서는 것(그 걸음이 닿는 순간 포함)은 걷는 사람으로 세지 않는다.
        const snapshot = getOfficeLifeActivity(name, actor.id)
        if (
          actor.moving &&
          snapshot?.activity !== 'pause' &&
          snapshot?.destination !== 'pause'
        )
          walking++
        // 방향은 한꺼번에 꺾지 않는다(초당 6라디안 넘게 돌지 않는다).
        if (before.heading !== undefined && actor.heading !== undefined)
          sharpest = Math.max(
            sharpest,
            Math.abs(
              Math.atan2(
                Math.sin(actor.heading - before.heading),
                Math.cos(actor.heading - before.heading),
              ),
            ),
          )
        // 쉬지 않고 걷는 도중에 오던 쪽으로 되돌아가는 걸음(왔다 갔다)을 센다. 멈추거나 제자리에서 돈 뒤 다른 쪽으로
        // 출발하는 것은 세지 않는다.
        if (!actor.moving) moves.delete(actor.id)
        const last = moves.get(actor.id)
        if (actor.moving && step > 0.1) {
          const move = {
            x: (actor.x - before.x) / step,
            y: (actor.y - before.y) / step,
          }
          if (last && last.x * move.x + last.y * move.y < -0.5)
            reversals.set(actor.id, (reversals.get(actor.id) ?? 0) + 1)
          moves.set(actor.id, move)
        }
        // 멈춘 곳에서 머문 시간
        const stopped =
          !actor.moving &&
          ['window', 'coffee', 'dropby', 'home'].includes(activity ?? '')
        if (stopped) {
          resting.set(actor.id, (resting.get(actor.id) ?? 0) + 0.05)
          ;(
            stops.get(actor.id) ?? stops.set(actor.id, new Set()).get(actor.id)!
          ).add(`${Math.round(actor.x / 16)}/${Math.round(actor.y / 16)}`)
        } else if (resting.has(actor.id)) {
          if (frame > 600) rests.push(resting.get(actor.id)!)
          resting.delete(actor.id)
        }
      }
      // 할 일 없이 다니는 사람은 한 번에 둘까지만 걷는다.
      mostWalkers = Math.max(mostWalkers, walking)
      // 처음부터 나를 포함해 누구도 겹치지 않는다. 의자에 앉은 사람 뒤로는 의자 반 폭과 몸 반지름만큼 떨어져 지나간다.
      const everyone = [...current, { ...me, seated: false, moving: false }]
      for (let a = 0; a < everyone.length; a++)
        for (let b = a + 1; b < everyone.length; b++) {
          const gap = Math.hypot(
            everyone[a].x - everyone[b].x,
            everyone[a].y - everyone[b].y,
          )
          if (everyone[a].seated || everyone[b].seated)
            closestSeated = Math.min(closestSeated, gap)
          else closest = Math.min(closest, gap)
          if (everyone[a].moving && everyone[b].moving)
            closestWalkers = Math.min(closestWalkers, gap)
        }
      previous = current
    }
    simulation.dispose()
    for (const id of strollers) {
      expect(walked.get(id)).toBeGreaterThan(3 * 32)
      // 이유 있는 곳(창가·음료·동료 자리 곁·자기 자리 곁) 두 곳 넘게 멈췄다.
      expect(stops.get(id)?.size ?? 0).toBeGreaterThanOrEqual(2)
      // 제자리에서 왔다 갔다 하지 않는다(비켜 주며 물러서는 몇 번은 있을 수 있다).
      expect(reversals.get(id) ?? 0).toBeLessThanOrEqual(3)
    }
    expect(mostWalkers).toBeLessThanOrEqual(2)
    expect(sharpest).toBeLessThanOrEqual(6 * 0.05 + 1e-6)
    // 멈추면 대개 15초 넘게 머문다(길을 비켜 주느라 일찍 떠나는 때만 짧다).
    expect(rests.length).toBeGreaterThan(0)
    expect(
      rests.filter((seconds) => seconds >= 15).length / rests.length,
    ).toBeGreaterThanOrEqual(0.75)
    // 작업 중 에이전트는 주인 의자에 앉아 있고, 연결 전 에이전트는 충전 자리에서 움직이지 않는다.
    const working = previous.find((actor) => actor.id === 'agent:22')!
    expect(
      Math.hypot(working.x - at('agent:22').x, working.y - at('agent:22').y),
    ).toBeLessThan(32)
    expect(working.seated).toBe(true)
    expect(walked.get('agent:24')).toBe(0)
    expect(closest).toBeGreaterThanOrEqual(PERSONAL_SPACE - 0.5)
    expect(closestSeated).toBeGreaterThanOrEqual(SEATED_SPACE - 0.5)
    expect(closestWalkers).toBeGreaterThanOrEqual(PERSONAL_SPACE - 0.5)
  },
)
