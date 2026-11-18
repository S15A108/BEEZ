import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { LOBBY_ENTRY, SPAWN_OUTSIDE, T, zoneAt, type Floor } from './floors'
import { clearSegment, gridWalkable, smoothRoute } from './motion'
import { findPath } from './path'
import { officeSeats, seatApproach } from './social'
import type { Interior } from '../types'

const feet = (x: number, y: number) => ({ x: x * T + 16, y: y * T + 27 })
function reachable(floor: Floor) {
  const seen = new Set<string>(),
    queue = [floor.exit]
  while (queue.length) {
    const p = queue.pop()!,
      key = `${p.x},${p.y}`
    if (seen.has(key) || floor.blocked[p.y]?.[p.x] !== false) continue
    seen.add(key)
    for (const [dx, dy] of [
      [1, 0],
      [-1, 0],
      [0, 1],
      [0, -1],
    ])
      queue.push({ x: p.x + dx, y: p.y + dy })
  }
  return seen
}

test('every configured team shares one interior with working zones and no elevator', () => {
  const building = makeBuilding(ALL_KEYS),
    floor = building.floors[1]
  assert.deepStrictEqual(building.ids, [1])
  assert.deepStrictEqual(floor.elevator, [])
  assert.isOk(!floor.grid.some((row) => row.includes('E')))
  assert.strictEqual(building.rooms.length, 9)
  const reached = reachable(floor)
  for (const zone of building.rooms) {
    assert.strictEqual(building.floorOfZone(zone), 1)
    const seats = floor.seats[zone]
    assert.isOk(seats?.length, `${zone}: usable team seats`)
    for (const seat of seats!) {
      assert.strictEqual(zoneAt(floor, seat.x, seat.y), zone)
      // NPCs may sit on solid sofa footprints, but must approach from open floor.
      assert.isOk(
        reached.has(`${seat.x},${seat.y}`) ||
          [
            [1, 0],
            [-1, 0],
            [0, 1],
            [0, -1],
          ].some(([dx, dy]) => reached.has(`${seat.x + dx},${seat.y + dy}`)),
        `${zone}: seat reachable`,
      )
    }
  }
  assert.isOk(reached.has(`${LOBBY_ENTRY.x},${LOBBY_ENTRY.y}`))
  assert.isOk(reached.has(`${SPAWN_OUTSIDE.x},${SPAWN_OUTSIDE.y}`))
  for (const point of floor.points ?? []) {
    assert.isOk(
      [...reached].some((key) => {
        const [x, y] = key.split(',').map(Number)
        return (
          x >= point.x - 1 &&
          x <= point.x + point.w &&
          y >= point.y - 1 &&
          y <= point.y + point.h
        )
      }),
      `${point.act}: reachable interaction`,
    )
  }
})

test('chairs and sofa cushions remain reachable by continuous click routes in the shared layout', () => {
  const floor = makeBuilding(ALL_KEYS).floors[1]
  const seats = officeSeats(floor)
  assert.isOk(seats.some((s) => s.kind === 'sofa'))
  assert.isOk(seats.length >= 15)
  const free = (x: number, y: number) =>
    gridWalkable({ x, y: y - 4 }, floor.blocked, T, 6)
  for (const seat of seats) {
    const landing = seatApproach(seat),
      from = feet(floor.exit.x, floor.exit.y)
    const path = findPath(
      floor.blocked,
      floor.exit.x,
      floor.exit.y,
      Math.floor(landing.x / T),
      Math.floor((landing.y - 4) / T),
    ).map(([x, y]) => feet(x, y))
    const route = smoothRoute(from, [...path, landing], free)
    assert.deepStrictEqual(
      route.at(-1),
      landing,
      `${seat.id}: reachable landing`,
    )
    let cursor = from
    for (const next of route) {
      assert.isOk(clearSegment(cursor, next, free), seat.id)
      cursor = next
    }
  }
})

test('only configured departments are furnished, and adding teams does not move the entrance', () => {
  for (const keys of [
    ['lobby'],
    ['lobby', 'dev'],
    ['lobby', 'planning', 'human'],
    ALL_KEYS,
  ]) {
    const building = makeBuilding(keys),
      floor = building.floors[1]
    assert.strictEqual(building.hasZone('dev'), keys.includes('dev'))
    assert.strictEqual(building.hasZone('lead'), keys.includes('planning'))
    assert.strictEqual(building.hasZone('control'), keys.includes('ops'))
    assert.strictEqual(
      building.floorOfKey('ops'),
      keys.includes('ops') ? 1 : null,
    )
    assert.deepStrictEqual(floor.exit, { x: LOBBY_ENTRY.x, y: LOBBY_ENTRY.y })
    assert.strictEqual(floor.blocked[floor.exit.y][floor.exit.x], false)
  }
})

test('legacy decorations reflow across rooms without deleting records or blocking team routes', () => {
  const interior: Interior = {
    themes: { lobby: 'wood', dev: 'mint' },
    expanded_at: null,
    decor: [
      {
        id: 'old-dev',
        floor_key: 'dev',
        kind: 'plant',
        x: 11,
        y: 2,
        created_at: '',
      },
      {
        id: 'old-planning',
        floor_key: 'planning',
        kind: 'shelf',
        x: 11,
        y: 2,
        created_at: '',
      },
      {
        id: 'old-hr',
        floor_key: 'hr',
        kind: 'sofa',
        x: 14,
        y: 14,
        created_at: '',
      },
    ],
  }
  const before = structuredClone(interior)
  const floor = makeBuilding(ALL_KEYS, 'glass', interior).floors[1]
  assert.deepStrictEqual(interior, before)
  assert.strictEqual(floor.theme, 'wood')
  assert.deepStrictEqual(
    floor.furniture.filter((f) => f.decorId).map((f) => f.decorId),
    interior.decor.map((d) => d.id),
  )
  const reached = reachable(floor)
  for (const seat of Object.values(floor.seats).flat())
    if (!floor.blocked[seat!.y][seat!.x])
      assert.isOk(
        reached.has(`${seat!.x},${seat!.y}`),
        `saved decor leaves seat ${seat!.x},${seat!.y} reachable`,
      )
  assert.isOk(reached.has(`${SPAWN_OUTSIDE.x},${SPAWN_OUTSIDE.y}`))
})

test('the office grows an annex to the right for more than six developers and shrinks back', () => {
  const small = makeBuilding(ALL_KEYS, 'glass', null, 6).floors[1]
  const grown = makeBuilding(ALL_KEYS, 'glass', null, 11).floors[1]
  const full = makeBuilding(ALL_KEYS, 'glass', null, 30).floors[1]
  assert.strictEqual(small.W, 30)
  assert.strictEqual(grown.W, 41)
  // 기존 좌표는 그대로라 저장된 장식 위치가 어긋나지 않는다
  for (let y = 0; y < small.H; y++)
    for (let x = 0; x < 29; x++)
      assert.strictEqual(grown.grid[y][x], small.grid[y][x], `${x},${y}`)
  assert.strictEqual(grown.seats.dev?.length, 6 + 5)
  assert.strictEqual(full.seats.dev?.length, 6 + 10)
  const reached = reachable(grown)
  for (const seat of grown.seats.dev!.slice(6)) {
    assert.strictEqual(zoneAt(grown, seat.x, seat.y), 'dev')
    assert.isOk(
      [
        [0, 0],
        [1, 0],
        [-1, 0],
        [0, 1],
        [0, -1],
      ].some(([dx, dy]) => reached.has(`${seat.x + dx},${seat.y + dy}`)),
      `annex seat ${seat.x},${seat.y} reachable`,
    )
  }
  // 아직 열리지 않은 별관 칸은 바깥 잔디로 남는다
  assert.strictEqual(grown.grid[16][32], 'o')
  assert.strictEqual(makeBuilding(ALL_KEYS, 'glass', null, 2).floors[1].W, 30)
})
