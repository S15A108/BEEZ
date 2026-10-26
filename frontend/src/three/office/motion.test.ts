import { assert, test } from 'vitest'
import {
  clearSegment,
  gridWalkable,
  hasMovementKeys,
  movementInput,
  movementSpeed,
  moveContinuous,
  RUN_MULTIPLIER,
  smoothRoute,
} from './motion'
import { findPath } from './path'
import { FLOORS, T, LOBBY_ENTRY, SPAWN_OUTSIDE } from './floors'

const near = (a: number, b: number) =>
  assert.isOk(Math.abs(a - b) < 1e-7, `${a} != ${b}`)
test('simultaneous directions support diagonals without faster or duplicated input', () => {
  const diagonal = movementInput(new Set(['ArrowRight', 'w']))
  near(Math.hypot(diagonal.x, diagonal.y), 1)
  near(diagonal.x, -diagonal.y)
  assert.deepStrictEqual(movementInput(new Set(['W', 'ArrowUp'])), {
    x: 0,
    y: -1,
  })
  assert.deepStrictEqual(movementInput(new Set(['w', 's', 'ArrowLeft', 'd'])), {
    x: 0,
    y: 0,
  })
  assert.deepStrictEqual(movementInput(new Set()), { x: 0, y: 0 })
})

test('movement stops between cells and equal time has equal cardinal/diagonal distance', () => {
  const from = { x: 48, y: 55 }
  const step = 118 / 60
  const direction = movementInput(new Set(['d', 's']))
  const diagonal = moveContinuous(
    from,
    { x: direction.x * step, y: direction.y * step },
    () => true,
  )
  const straight = moveContinuous(from, { x: step, y: 0 }, () => true)
  near(
    Math.hypot(diagonal.x - from.x, diagonal.y - from.y),
    straight.x - from.x,
  )
  assert.notStrictEqual(diagonal.x % 32, 16)
  assert.deepStrictEqual(
    moveContinuous(diagonal, { x: 0, y: 0 }, () => true),
    diagonal,
  )
})

test('holding Shift boosts walking and click routes without becoming a direction or bypassing walls', () => {
  assert.strictEqual(hasMovementKeys(new Set(['Shift'])), false)
  assert.strictEqual(hasMovementKeys(new Set(['Shift', 'W'])), true)
  assert.deepStrictEqual(movementInput(new Set(['Shift'])), { x: 0, y: 0 })
  for (const speed of [118, 130]) {
    const keys = new Set(['w', 'Shift'])
    near(movementSpeed(speed, keys), speed * RUN_MULTIPLIER)
    keys.delete('Shift')
    near(movementSpeed(speed, keys), speed)
    const run = movementSpeed(speed, new Set(['Shift']))
    const direction = movementInput(new Set(['d', 's', 'Shift']))
    const diagonal = moveContinuous(
      { x: 20, y: 20 },
      { x: direction.x * run, y: direction.y * run },
      () => true,
    )
    near(Math.hypot(diagonal.x - 20, diagonal.y - 20), run)
    const stopped = moveContinuous(
      { x: 20, y: 20 },
      { x: run, y: 0 },
      (x) => x < 40,
    )
    assert.isOk(stopped.x < 40, 'running cannot tunnel through a wall')
  }
})

test('swept movement blocks walls and furniture, slides and cannot cut a closed diagonal corner', () => {
  const map = Array.from({ length: 5 }, () => Array(5).fill(false))
  for (let y = 0; y < 5; y++) map[y][2] = true
  const free = (x: number, y: number) => gridWalkable({ x, y }, map, 32, 6)
  const next = moveContinuous({ x: 48, y: 48 }, { x: 100, y: 30 }, free)
  assert.isOk(next.x <= 58)
  near(next.y, 78)
  assert.isOk(free(next.x, next.y))
  const corner = [
    [false, true, false],
    [true, false, false],
    [false, false, false],
  ]
  const blocked = moveContinuous({ x: 16, y: 16 }, { x: 40, y: 40 }, (x, y) =>
    gridWalkable({ x, y }, corner, 32, 6),
  )
  assert.isOk(blocked.x < 32 && blocked.y < 32)
  assert.strictEqual(gridWalkable({ x: 1, y: 20 }, map, 32, 6), false)
})

test('click routes skip grid centres when visible and reject an unreachable direct fallback', () => {
  const from = { x: 16.7, y: 23.4 },
    goal = { x: 110.2, y: 116.9 }
  const empty = Array.from({ length: 5 }, () => Array(5).fill(false))
  const free = (x: number, y: number) => gridWalkable({ x, y }, empty, 32, 6)
  const route = findPath(empty, 0, 0, 3, 3).map(([x, y]) => ({
    x: x * 32 + 16,
    y: y * 32 + 23,
  }))
  assert.deepStrictEqual(smoothRoute(from, [...route, goal], free), [goal])
  const wall = empty.map((r) => [...r])
  wall.forEach((r) => {
    r[2] = true
  })
  assert.deepStrictEqual(
    smoothRoute(from, [goal], (x, y) => gridWalkable({ x, y }, wall, 32, 6)),
    [],
  )
})

test('real floor entrances, elevator exits and smoothed paths remain walkable', () => {
  const feet = (x: number, y: number) => ({ x: x * T + 16, y: y * T + 27 })
  for (const f of Object.values(FLOORS)) {
    const free = (x: number, y: number) =>
      gridWalkable({ x, y: y - 4 }, f.blocked, T, 6)
    const start = feet(f.exit.x, f.exit.y),
      elevator = feet(14, 1)
    assert.isOk(free(start.x, start.y), `${f.name} elevator exit`)
    assert.isOk(free(elevator.x, elevator.y), `${f.name} elevator`)
    const route = smoothRoute(
      start,
      findPath(f.blocked, f.exit.x, f.exit.y, 14, 1).map(([x, y]) =>
        feet(x, y),
      ),
      free,
    )
    assert.isOk(route.length, `${f.name} elevator route`)
    let cursor = start
    for (const waypoint of route) {
      assert.isOk(clearSegment(cursor, waypoint, free))
      cursor = waypoint
    }
    assert.deepStrictEqual(route.at(-1), elevator)
  }
  const f = FLOORS[1]
  const start = feet(SPAWN_OUTSIDE.x, SPAWN_OUTSIDE.y)
  const free = (x: number, y: number) =>
    gridWalkable({ x, y: y - 4 }, f.blocked, T, 6)
  const route = smoothRoute(
    start,
    findPath(
      f.blocked,
      SPAWN_OUTSIDE.x,
      SPAWN_OUTSIDE.y,
      LOBBY_ENTRY.x,
      LOBBY_ENTRY.y,
    ).map(([x, y]) => feet(x, y)),
    free,
  )
  assert.deepStrictEqual(route.at(-1), feet(LOBBY_ENTRY.x, LOBBY_ENTRY.y))
})
