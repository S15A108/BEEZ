import { expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { moveContinuous } from './motion'
import { officeActors } from './office-actors'
import { OfficeLifeSimulation, PERSONAL_SPACE } from './office-life'
import {
  enterTarget,
  officeLanding,
  officeWalkable,
  officeWalkRoute,
} from './office-navigation'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

it.each([3, 4])(
  '준비 단계 %i에서도 가구와 숨겨진 바닥을 피해 걸을 수 있는 곳에 등장한다',
  (phase) => {
    const floor = makeDreamFloor(
      makeBuilding(ALL_KEYS, 'glass', undefined, {
        dev: 1,
        lead: 1,
        humandesk: 1,
      }).floors[1],
      phase,
    )
    const landing = officeLanding(floor, { x: 7.5 * 32, y: 4.5 * 32 })
    expect(landing).toBeDefined()
    if (!landing) return
    const free = (x: number, y: number) => officeWalkable(floor, x, y)
    expect(free(landing.x, landing.y)).toBe(true)
    const steps = [
      { x: 3, y: 0 },
      { x: -3, y: 0 },
      { x: 0, y: 3 },
      { x: 0, y: -3 },
    ]
    expect(
      steps.some((delta) => {
        const moved = moveContinuous(landing, delta, free)
        return Math.hypot(moved.x - landing.x, moved.y - landing.y) > 0
      }),
    ).toBe(true)
    expect(officeLanding(floor, landing)).toEqual(landing)
  },
)

it('공간이 아직 보이지 않는 단계에서는 잘못된 출발점을 만들지 않는다', () => {
  const floor = makeDreamFloor(makeBuilding(ALL_KEYS, 'glass').floors[1], 0)
  expect(officeLanding(floor, { x: 240, y: 144 })).toBeUndefined()
})

// S15P31A108-379: 산책에서 돌아오면 나가기 전 자리로 오는데, 그 사이 누가 그 자리에 와 있으면 겹치지 않게 비켜 선다.
it('돌아올 자리에 다른 사람이 서 있으면 그 사람과 떨어진 가장 가까운 빈 칸에 선다', () => {
  const floor = makeDreamFloor(makeBuilding(ALL_KEYS, 'glass').floors[1], 4)
  const spot = officeLanding(floor, { x: 7.5 * 32, y: 4.5 * 32 })
  expect(spot).toBeDefined()
  if (!spot) return
  expect(officeLanding(floor, spot, [{ x: spot.x + 200, y: spot.y }])).toEqual(
    spot,
  )
  const landing = officeLanding(floor, spot, [spot])
  expect(landing).toBeDefined()
  if (!landing) return
  expect(officeWalkable(floor, landing.x, landing.y)).toBe(true)
  const away = Math.hypot(landing.x - spot.x, landing.y - spot.y)
  expect(away).toBeGreaterThanOrEqual(PERSONAL_SPACE)
  expect(away).toBeLessThan(2 * 32)
})

// PR #319 리뷰: 팀원과 대기 에이전트가 출발 자리를 떠나 걷는데(D37) 3인칭 Enter는 출발 좌표로 대상을 골라, 걸어간
// 에이전트 곁에서 Enter를 누르면 비비가, 빈 출발 자리에서는 이미 떠난 사람이 골라졌다.
it('3인칭 Enter는 출발 자리가 아니라 지금 걷고 있는 좌표로 가장 가까운 사람을 고른다', () => {
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, {}).floors[1],
    4,
  )
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
    { id: 'member:23', name: '이동료', kind: 'agent' },
    {
      id: 'agent:23',
      name: '이동료의 에이전트',
      kind: 'agent',
      owner: '23',
      status: 'online',
    },
  ])
  const actors = officeActors(
    floor,
    agents,
    4,
    'member:21',
    officeHomeSeats(floor, agents),
  )
  const start = actors.find((actor) => actor.id === 'agent:23')!
  const simulation = new OfficeLifeSimulation('enter-target')
  let residents = simulation.step(floor, actors, 0.05)
  const now = () => residents.find((actor) => actor.id === 'agent:23')!
  // 에이전트가 출발 자리에서 Enter 거리(100px)보다 멀리 걸어갈 때까지 돌린다(최대 1분).
  for (
    let frame = 0;
    frame < 1200 && Math.hypot(now().x - start.x, now().y - start.y) < 140;
    frame++
  )
    residents = simulation.step(floor, actors, 0.05)
  const agent = now()
  expect(Math.hypot(agent.x - start.x, agent.y - start.y)).toBeGreaterThan(140)

  // 에이전트 40px 곁의 걸을 수 있는 곳에 서서 Enter를 누른다.
  const beside = Array.from({ length: 16 }, (_, index) => ({
    x: agent.x + 40 * Math.cos((index * Math.PI) / 8),
    y: agent.y + 40 * Math.sin((index * Math.PI) / 8),
  })).find((point) => officeWalkable(floor, point.x, point.y))!
  expect(beside).toBeDefined()
  expect(enterTarget(residents, beside)).toBe('agent:23')
  // 출발 좌표로 고르면 걸어간 에이전트를 놓친다(고치기 전 동작).
  expect(enterTarget(actors, beside)).not.toBe('agent:23')
  // 빈 출발 자리에서는 이미 떠난 에이전트를 고르지 않는다.
  expect(enterTarget(actors, start)).toBe('agent:23')
  expect(enterTarget(residents, start)).not.toBe('agent:23')
})

it('Enter 거리 안에 아무도 없으면 대상을 고르지 않는다', () => {
  const people = [
    { id: 'a', x: 0, y: 0 },
    { id: 'b', x: 60, y: 0 },
  ]
  expect(enterTarget(people, { x: 50, y: 0 })).toBe('b')
  expect(enterTarget(people, { x: 300, y: 0 })).toBeUndefined()
})

// PR #319 리뷰 확인 부탁: 내 캐릭터도 몸 반지름(16px)만큼 가구에서 떨어져 걷게 되며 시작 자리와 클릭 이동이 막히는
// 곳이 없는지. 가구 사이에 갇힌 틈을 누르면 길이 없어 제자리에 섰다(tower 19칸, wood 28칸).
it.each(['glass', 'brick', 'modern', 'tower', 'wood'])(
  '%s: 내 캐릭터는 엘리베이터 앞에서 시작하고, 사무실 어느 칸을 눌러도 걸어간다',
  (design) => {
    const floor = makeDreamFloor(
      makeBuilding(ALL_KEYS, design, null, {}).floors[1],
      4,
    )
    const exit = { x: floor.exit.x * 32 + 16, y: floor.exit.y * 32 + 27 }
    expect(officeLanding(floor, exit)).toEqual(exit)
    const stuck: string[] = []
    for (let y = 0; y < floor.H; y++)
      for (let x = 0; x < floor.W; x++) {
        if (floor.grid[y][x] === '#') continue
        const route = officeWalkRoute(floor, exit, {
          x: x * 32 + 16,
          y: y * 32 + 16,
        })
        if (!route.length) stuck.push(`${x},${y}`)
      }
    expect(stuck).toEqual([])
  },
)
