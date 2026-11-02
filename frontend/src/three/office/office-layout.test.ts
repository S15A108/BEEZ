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
import { zoneAt, type Floor } from './floors'
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
  assert.deepStrictEqual([...assigned.values()], floor.seats.humandesk)
})

test('a small team has only occupied desks, one shared meeting table and a projector', () => {
  const floor = office(populations[0])
  assert.strictEqual(floor.W, 30)
  assert.strictEqual(
    floor.furniture.filter(
      (item) => item.kind === 'desk' || item.kind === 'execdesk',
    ).length,
    3,
  )
  assert.strictEqual(floor.seats.dev?.length, 1)
  assert.strictEqual(floor.seats.humandesk?.length, 1)
  assert.strictEqual(
    floor.furniture.filter((item) => item.kind === 'projector').length,
    1,
  )
  assert.strictEqual(
    floor.furniture.some((item) => item.kind === 'designboard'),
    false,
  )
  assert.strictEqual(
    floor.furniture.filter((item) => item.tag === 'meeting').length,
    1,
  )
  assert.strictEqual(
    floor.furniture.filter((item) => item.kind === 'glass-partition').length,
    2,
  )
})

test('AI and human arrivals grow real office space and every department gets an individual seat', () => {
  let width = 0
  for (const population of populations) {
    const floor = office(population)
    assert.isOk(floor.W > width)
    width = floor.W
    for (const [zone, count] of Object.entries(population))
      assert.strictEqual(
        floor.seats[zone as keyof typeof population]?.length,
        count,
        zone,
      )
    const seats = Object.values(floor.seats).flat()
    assert.strictEqual(
      new Set(seats.map((seat) => `${seat!.x}/${seat!.y}`)).size,
      seats.length,
    )
    for (const [zone, list] of Object.entries(floor.seats))
      for (const seat of list!)
        assert.strictEqual(zoneAt(floor, seat.x, seat.y), zone)
    assert.isOk(
      floor.furniture.filter((item) => item.kind === 'glass-partition')
        .length <= 3,
    )
    assert.strictEqual(floor.blocked[floor.exit.y][floor.exit.x], false)
  }
  assert.isOk(
    office({ dev: 1, lead: 1, humandesk: 6 }).W > office(populations[0]).W,
    'human teammates also trigger expansion',
  )
  assert.isOk(
    office({ dev: 1, lead: 1, review: 8 }).W > office(populations[0]).W,
    'support agents also trigger expansion',
  )
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

test('short glass partitions leave continuous paths to all workstations, sofa and meeting seats', () => {
  for (const population of populations) {
    const base = office(population),
      floor = makeDreamFloor(base, 4)
    checkRoutes(floor)
    assert.strictEqual(
      floor.grid[17][1],
      '#',
      'outdoor pavement does not turn into extra office',
    )
    const projector = floor.furniture.find((item) => item.kind === 'projector')!
    assert.strictEqual(floor.blocked[projector.y][projector.x], true)
    assert.strictEqual(
      floor.blocked[projector.y + 4][projector.x + 3],
      false,
      'ceiling projector does not block the floor beneath it',
    )
    for (const item of floor.furniture.filter(
      (item) => item.kind === 'glass-partition',
    ))
      assert.strictEqual(floor.blocked[item.y][item.x], true)
  }
})

test('shrinking the office removes obsolete desk identities and a seated NPC can return inside', () => {
  const large = makeDreamFloor(office(populations[2]), 4),
    small = makeDreamFloor(office(populations[0]), 4)
  const oldKeys = new Set(large.furniture.map(officeObjectKey)),
    newKeys = new Set(small.furniture.map(officeObjectKey))
  assert.isOk([...oldKeys].filter((key) => !newKeys.has(key)).length > 10)
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

test('idle agents use the expanded windows and do not mistake the projection screen for a window', () => {
  const floor = makeDreamFloor(office(populations[2]), 4),
    simulation = new OfficeLifeSimulation('expanded-breaks')
  const actors = floor.seats.dev!.map((seat, index): OfficeLifeActor => ({
    id: `idle-${index}`,
    ...feet(seat.x, seat.y),
    dir: seat.facing,
    moving: false,
    status: 'idle',
  }))
  const screen = floor.furniture.find((item) => item.kind === 'projector')!
  let expandedWindow = false
  for (let frame = 0; frame < 1800; frame++)
    for (const current of simulation.step(floor, actors, 0.1)) {
      if (
        getOfficeLifeActivity('expanded-breaks', current.id)?.activity !==
        'window'
      )
        continue
      if (current.x > 30 * 32) expandedWindow = true
      assert.isOk(
        current.x < screen.x * 32 || current.x > (screen.x + screen.w) * 32,
        'the screen obscures this view of the window',
      )
    }
  assert.strictEqual(expandedWindow, true)
  simulation.dispose()
})
