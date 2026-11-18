import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import {
  getOfficeLifeActivity,
  isOfficeWorkingStatus,
  OfficeLifeSimulation,
  type OfficeLifeActor,
} from './office-life'
import { gridWalkable } from './motion'

const floor = makeDreamFloor(makeBuilding(ALL_KEYS).floors[1], 4)
function actor(id: string, status = 'idle'): OfficeLifeActor {
  const seat = floor.seats.dev![0]
  return {
    id,
    x: seat.x * 32 + 16,
    y: seat.y * 32 + 27,
    dir: seat.facing,
    moving: false,
    status,
  }
}

test('an idle NPC walks around real furniture and visits the sofa, windows, and drink station', () => {
  const simulation = new OfficeLifeSimulation('life-tour'),
    input = actor('tour'),
    visited = new Set<string>()
  let previous = input,
    walked = false
  for (let frame = 0; frame < 2200; frame++) {
    const [current] = simulation.step(floor, [input], 0.1)
    const state = getOfficeLifeActivity('life-tour', input.id)!
    assert.isOk(
      Math.hypot(current.x - previous.x, current.y - previous.y) < 15,
      'movement and sitting use small continuous steps',
    )
    if (state.activity === 'walking' && current.moving) {
      walked = true
      // Cushion mounting/dismounting intentionally crosses its solid footprint.
      const atCushion = floor.furniture.some(
        (item) =>
          (item.kind === 'sofa' || item.kind === 'bench') &&
          current.x >= item.x * 32 &&
          current.x <= (item.x + item.w) * 32 &&
          current.y >= item.y * 32 &&
          current.y <= (item.y + item.h) * 32 + 14,
      )
      assert.isOk(
        atCushion ||
          gridWalkable(
            { x: current.x, y: current.y - 4 },
            floor.blocked,
            32,
            6,
          ),
        'walking route stays on free floor except while stepping on/off a cushion',
      )
    }
    if (state.activity !== 'walking') visited.add(state.activity)
    if (state.activity === 'resting' && current.seated)
      assert.strictEqual(current.seatLift, 0.2)
    previous = current
  }
  assert.isOk(walked)
  assert.deepStrictEqual([...visited].sort(), ['coffee', 'resting', 'window'])
  simulation.dispose()
  assert.strictEqual(getOfficeLifeActivity('life-tour', input.id), undefined)
})

test('work interrupts a sofa break with a walk to the desk, and completion sends the NPC back to rest', () => {
  const simulation = new OfficeLifeSimulation('life-work')
  let input = actor('working-cycle', 'working'),
    current = input
  for (let i = 0; i < 5; i++) [current] = simulation.step(floor, [input], 0.1)
  assert.strictEqual(
    getOfficeLifeActivity('life-work', input.id)?.activity,
    'working',
  )
  assert.strictEqual(current.seated, true)
  input = { ...input, status: 'idle' }
  ;[current] = simulation.step(floor, [input], 0.1)
  assert.strictEqual(
    getOfficeLifeActivity('life-work', input.id)?.destination,
    'resting',
  )
  for (let i = 0; i < 600 && !current.seated; i++)
    [current] = simulation.step(floor, [input], 0.1)
  assert.strictEqual(
    getOfficeLifeActivity('life-work', input.id)?.activity,
    'resting',
  )
  assert.strictEqual(current.seated, true)
  const sofa = { x: current.x, y: current.y }
  input = { ...input, status: 'working' }
  ;[current] = simulation.step(floor, [input], 0.1)
  assert.isOk(
    Math.hypot(current.x - sofa.x, current.y - sofa.y) < 15,
    'starting work never teleports to the desk',
  )
  assert.strictEqual(
    getOfficeLifeActivity('life-work', input.id)?.destination,
    'working',
  )
  for (
    let i = 0;
    i < 600 &&
    getOfficeLifeActivity('life-work', input.id)?.activity !== 'working';
    i++
  )
    [current] = simulation.step(floor, [input], 0.1)
  assert.strictEqual(
    getOfficeLifeActivity('life-work', input.id)?.activity,
    'working',
  )
  assert.isOk(Math.hypot(current.x - input.x, current.y - input.y) < 0.2)
  assert.strictEqual(current.seated, true)
  simulation.dispose()
})

test('snapshot updates and conversation preserve position and the actual current activity', () => {
  const simulation = new OfficeLifeSimulation('life-chat'),
    input = actor('chat', 'working')
  const [before] = simulation.step(floor, [input], 0.1)
  const [talking] = simulation.step(
    floor,
    [{ ...input, status: 'talking' }],
    0.1,
    { pausedId: input.id },
  )
  assert.deepStrictEqual(
    { x: talking.x, y: talking.y },
    { x: before.x, y: before.y },
  )
  assert.strictEqual(
    getOfficeLifeActivity('life-chat', input.id)?.activity,
    'working',
  )
  let [walking] = simulation.step(floor, [{ ...input, status: 'idle' }], 0.1)
  for (let i = 0; i < 10; i++)
    [walking] = simulation.step(floor, [{ ...input, status: 'idle' }], 0.1)
  const saved = { x: walking.x, y: walking.y },
    activity = getOfficeLifeActivity('life-chat', input.id)?.activity
  for (let i = 0; i < 100; i++)
    [walking] = simulation.step(floor, [{ ...input, status: 'talking' }], 0.1, {
      pausedId: input.id,
    })
  assert.deepStrictEqual({ x: walking.x, y: walking.y }, saved)
  assert.strictEqual(
    getOfficeLifeActivity('life-chat', input.id)?.activity,
    activity,
  )
  assert.strictEqual(
    getOfficeLifeActivity('life-chat', input.id)?.moving,
    false,
  )
  simulation.step(floor, [], 0.1)
  assert.strictEqual(getOfficeLifeActivity('life-chat', input.id), undefined)
  simulation.dispose()
})

test('the whole team reserves separate idle destinations and disabled onboarding stays still', () => {
  const simulation = new OfficeLifeSimulation('life-team'),
    actors = Array.from({ length: 8 }, (_, i) => actor(`npc-${i}`))
  assert.deepStrictEqual(
    simulation.step(floor, actors, 0.1, { enabled: false }),
    actors,
  )
  assert.strictEqual(
    getOfficeLifeActivity('life-team', actors[0].id),
    undefined,
  )
  let current = actors
  for (let i = 0; i < 800; i++) current = simulation.step(floor, actors, 0.1)
  const settled = current.filter((item) => !item.moving)
  const positions = new Set(
    settled.map((item) => `${Math.round(item.x)},${Math.round(item.y)}`),
  )
  assert.strictEqual(
    positions.size,
    settled.length,
    'idle NPCs do not pile up on the same cushion or window',
  )
  simulation.dispose()
})

test('working statuses include external execution phases while disconnected or waiting agents can rest', () => {
  for (const status of [
    'working',
    'thinking',
    'testing',
    'reviewing',
    'running',
    'fixing',
    'briefing',
    'meeting',
  ])
    assert.strictEqual(isOfficeWorkingStatus(status), true)
  for (const status of [
    'idle',
    'waiting',
    'disconnected',
    'off_duty',
    undefined,
  ])
    assert.strictEqual(isOfficeWorkingStatus(status), false)
})

test('meeting and briefing agents stay at the server-assigned seat and humans stay still', () => {
  const simulation = new OfficeLifeSimulation('life-meeting'),
    input = actor('meeting', 'meeting'),
    person = { ...actor('human'), stationary: true }
  let current = [input, person]
  for (let i = 0; i < 500; i++)
    current = simulation.step(floor, [input, person], 0.1)
  assert.deepStrictEqual(
    { x: current[0].x, y: current[0].y },
    { x: input.x, y: input.y },
  )
  assert.strictEqual(
    getOfficeLifeActivity('life-meeting', input.id)?.label,
    '회의에 참여하는 중',
  )
  assert.deepStrictEqual(current[1], person)
  assert.strictEqual(
    getOfficeLifeActivity('life-meeting', person.id),
    undefined,
  )
  simulation.dispose()
})

test('work can interrupt the cushion mounting animation and still reach the desk continuously', () => {
  const simulation = new OfficeLifeSimulation('life-interrupt')
  let input = actor('interrupt', 'working'),
    current = simulation.step(floor, [input], 0.1)[0]
  input = { ...input, status: 'idle' }
  for (let i = 0; i < 600; i++) {
    ;[current] = simulation.step(floor, [input], 0.1)
    if (
      getOfficeLifeActivity('life-interrupt', input.id)?.activity ===
        'resting' &&
      !current.seated
    )
      break
  }
  assert.strictEqual(
    getOfficeLifeActivity('life-interrupt', input.id)?.activity,
    'resting',
  )
  assert.strictEqual(current.seated, false)
  input = { ...input, status: 'working' }
  for (let i = 0; i < 600; i++) {
    const before = current
    ;[current] = simulation.step(floor, [input], 0.1)
    assert.isOk(Math.hypot(current.x - before.x, current.y - before.y) < 15)
    if (current.seated) break
  }
  assert.strictEqual(
    getOfficeLifeActivity('life-interrupt', input.id)?.activity,
    'working',
  )
  assert.isOk(Math.hypot(current.x - input.x, current.y - input.y) < 0.2)
  simulation.dispose()
})

test('unreachable pathfinder fallbacks never send an NPC through a wall', () => {
  const partitioned = {
    ...floor,
    blocked: floor.blocked.map((row) =>
      row.map((blocked, x) => blocked || x === 10),
    ),
  }
  const simulation = new OfficeLifeSimulation('life-wall'),
    initial = { ...actor('wall', 'working'), x: 8 * 32 + 16, y: 6 * 32 + 27 }
  simulation.step(partitioned, [initial], 0.1)
  const assigned = { ...initial, x: 15 * 32 + 16 }
  for (let i = 0; i < 100; i++) {
    const [current] = simulation.step(partitioned, [assigned], 0.1)
    assert.isOk(
      current.x < 10 * 32,
      'no direct fallback through the sealed partition',
    )
  }
  simulation.dispose()
})
