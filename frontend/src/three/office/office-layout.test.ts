import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import {
  officeObjectKey,
  officePopulationOf,
  type OfficePopulation,
} from './office-layout'
import { gridWalkable, smoothRoute } from './motion'
import { findPath } from './path'
import { officeSeats, seatApproach } from './social'
import type { Floor } from './floors'
import {
  getOfficeLifeActivity,
  OfficeLifeSimulation,
  type OfficeLifeActor,
} from './office-life'
import { officeHomeSeats } from './office-work'
import { RECEPTIONIST } from './social'

const office = (population: OfficePopulation) =>
  makeBuilding(ALL_KEYS, 'glass', null, population).floors[1]
const feet = (x: number, y: number) => ({ x: x * 32 + 16, y: y * 32 + 27 })
// D36: 사무실은 고정 배치라 인원에 따라 넓어지거나 좁아지지 않는다. 늘고 주는 사무실을 확인하던
// 기대값을 고정 방 기준으로 바꿨다(사용자 승인 2026-11-03). 길찾기·좌석 도달·출구 검사는 그대로다.
const populations: OfficePopulation[] = [
  { dev: 1, lead: 1, humandesk: 1 },
  { dev: 4, lead: 1, humandesk: 2 },
  { dev: 12, lead: 1, humandesk: 6, review: 2, board: 2, hr: 1, control: 1 },
]

test('human owners and invited members get desks even when the server places them at the board or lobby', () => {
  const agents = [
    {
      ...RECEPTIONIST,
      id: 'owner',
      kind: 'human' as const,
      home_zone: 'board' as const,
    },
    {
      ...RECEPTIONIST,
      id: 'member',
      kind: 'human' as const,
      home_zone: 'lobby' as const,
    },
  ]
  const population = officePopulationOf(agents)
  assert.deepStrictEqual(population, { humandesk: 2 })
  const floor = office(population),
    assigned = officeHomeSeats(floor, agents)
  assert.strictEqual(assigned.size, 2)
  assert.deepStrictEqual(
    [...assigned.values()],
    floor.seats.humandesk!.slice(0, 2),
  )
})

// D39: 'glass' 외관은 캔버스 유리 오피스 배치(28 × 16 m)다. 테스트 뜻(인원과 상관없이 같은 방)은 그대로 두고 숫자를
// 유리 안으로 바꿨다(기대값 변경 사용자 승인 2026-11-04).
test('any team gets the same fixed room: team desks, a glass meeting room, a war room and the lounge', () => {
  const floors = populations.map(office)
  for (const floor of floors) {
    assert.strictEqual(floor.W, 30)
    assert.strictEqual(floor.furniture.length, 132)
    assert.deepStrictEqual(floor.furniture, floors[0].furniture)
    assert.strictEqual(
      floor.furniture.filter((item) => item.tag?.startsWith('workstation/'))
        .length,
      8,
    )
    assert.strictEqual(
      floor.furniture.some((item) => item.kind === 'glass-partition'),
      true,
    )
  }
  // 작업 자리는 팀 책상 8석, 넘는 인원은 유리 회의실 6석과 워룸 3석
  assert.strictEqual(floors[0].seats.dev?.length, 17)
  assert.strictEqual(floors[0].seats.meeting?.length, 6)
  assert.strictEqual(floors[0].seats.lobby?.length, 3)
})

test('every department gets an individual seat inside the room as the team grows', () => {
  for (const population of populations) {
    const floor = office(population)
    for (const [zone, count] of Object.entries(population))
      assert.isOk(
        (floor.seats[zone as keyof typeof population]?.length ?? 0) >= count,
        zone,
      )
    const seats = [
      ...floor.seats.dev!,
      ...floor.seats.humandesk!,
      ...floor.seats.lead!,
      ...floor.seats.lobby!,
    ]
    assert.strictEqual(
      new Set(seats.map((seat) => `${seat.x}/${seat.y}`)).size,
      seats.length,
    )
    for (const seat of seats) {
      const x = Math.floor(seat.x + 0.5),
        y = Math.floor(seat.y + 23 / 32)
      assert.notStrictEqual(floor.grid[y][x], '#')
      assert.strictEqual(floor.blocked[y][x], false, `${x},${y}`)
    }
    assert.strictEqual(floor.blocked[floor.exit.y][floor.exit.x], false)
  }
})

function checkRoutes(floor: Floor) {
  const free = (x: number, y: number) =>
      gridWalkable({ x, y: y - 4 }, floor.blocked, 32, 6),
    from = feet(floor.exit.x, floor.exit.y)
  for (const seat of officeSeats(floor)) {
    const landing = seatApproach(seat)
    const path = findPath(
      floor.blocked,
      floor.exit.x,
      floor.exit.y,
      Math.floor(landing.x / 32),
      Math.floor((landing.y - 4) / 32),
    ).map(([x, y]) => feet(x, y))
    assert.deepStrictEqual(
      smoothRoute(from, [...path, landing], free).at(-1),
      landing,
      `partition leaves ${seat.id} reachable`,
    )
  }
}

test('every chair, stool and lounge seat is reachable from the elevator; boards block the floor, wall screens do not', () => {
  for (const population of populations) {
    const base = office(population),
      floor = makeDreamFloor(base, 4)
    checkRoutes(floor)
    assert.strictEqual(
      floor.grid[17][1],
      '#',
      'outdoor pavement does not turn into extra office',
    )
    for (const item of floor.furniture.filter(
      (item) => item.kind === 'designboard' || item.kind === 'projector',
    ))
      assert.strictEqual(
        floor.blocked[Math.floor(item.kit!.z)][Math.floor(item.kit!.x)],
        item.kind === 'designboard',
        'standing boards block the floor; wall screens do not',
      )
  }
})

test('changing the team keeps every desk, and a seated NPC stays at its desk', () => {
  const large = makeDreamFloor(office(populations[2]), 4),
    small = makeDreamFloor(office(populations[0]), 4)
  assert.deepStrictEqual(
    small.furniture.map(officeObjectKey),
    large.furniture.map(officeObjectKey),
  )
  const oldSeat = large.seats.dev!.at(-1)!,
    newSeat = small.seats.dev![0]
  const simulation = new OfficeLifeSimulation('office-resize')
  let actor: OfficeLifeActor = {
    id: 'remaining',
    ...feet(oldSeat.x, oldSeat.y),
    dir: oldSeat.facing,
    moving: false,
    status: 'working',
  }
  for (let i = 0; i < 10; i++) simulation.step(large, [actor], 0.1)
  actor = { ...actor, ...feet(newSeat.x, newSeat.y), dir: newSeat.facing }
  let current = actor
  for (let i = 0; i < 1000; i++)
    [current] = simulation.step(small, [actor], 0.1)
  assert.strictEqual(
    getOfficeLifeActivity('office-resize', actor.id)?.activity,
    'working',
  )
  assert.isOk(Math.hypot(current.x - actor.x, current.y - actor.y) < 0.3)
  simulation.dispose()
})

test('idle agents walk to the windows along the glass wall', () => {
  const floor = makeDreamFloor(office(populations[2]), 4),
    simulation = new OfficeLifeSimulation('fixed-breaks')
  const actors = floor.seats.dev!.map((seat, index): OfficeLifeActor => ({
    id: `idle-${index}`,
    ...feet(seat.x, seat.y),
    dir: seat.facing,
    moving: false,
    status: 'idle',
  }))
  let window = false
  for (let frame = 0; frame < 1800; frame++)
    for (const current of simulation.step(floor, actors, 0.1))
      if (
        getOfficeLifeActivity('fixed-breaks', current.id)?.activity === 'window'
      ) {
        window = true
        assert.isOk(current.y < 4 * 32, 'windows are on the north glass wall')
      }
  assert.strictEqual(window, true)
  simulation.dispose()
})
