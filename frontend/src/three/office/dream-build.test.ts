import { assert, test } from 'vitest'
import { dropSupport, stepDrop, type DropBody } from './drop-physics'
import { makeDreamFloor } from './dream-floor'
import { makePocketOffice } from './pocketOffice'

test('a dream starts empty and reveals supported furniture in expanding phases', () => {
  const base = makePocketOffice(
    ['lobby', 'planning', 'human', 'dev', 'security', 'ops', 'hr'],
    2,
  )
  let count = 0
  for (let phase = 0; phase <= 4; phase++) {
    const floor = makeDreamFloor(base, phase)
    const tiles = floor.grid.flat().filter((tile) => tile !== '#').length
    if (!phase) {
      assert.strictEqual(tiles, 0)
      assert.strictEqual(floor.furniture.length, 0)
    } else assert.isOk(tiles > count)
    for (const f of floor.furniture)
      for (let y = f.y; y < f.y + f.h; y++)
        for (let x = f.x; x < f.x + f.w; x++)
          assert.notStrictEqual(
            floor.grid[y][x],
            '#',
            `${f.kind} supported at phase ${phase}`,
          )
    count = tiles
  }
  assert.strictEqual(
    makeDreamFloor(base, 4).furniture.length,
    base.furniture.length,
  )
})
test('falling furniture accelerates, bounces without penetrating the floor and comes to rest', () => {
  const body: DropBody = {
    y: 12,
    vy: 0,
    angle: 0.3,
    omega: 0.6,
    halfHeight: 0.8,
    radius: 1.6,
    impacts: 0,
    sleeping: false,
  }
  stepDrop(body, 1 / 120)
  assert.isOk(body.vy < 0)
  assert.isOk(body.y < 12)
  let bounced = false
  for (let i = 0; i < 1800; i++) {
    stepDrop(body, 1 / 120)
    assert.isOk(body.y >= dropSupport(body) + 0.035 - 1e-8)
    assert.isOk(Number.isFinite(body.y))
    if (body.impacts && body.vy > 0) bounced = true
  }
  assert.isOk(bounced)
  assert.isOk(body.impacts > 0)
  assert.strictEqual(body.sleeping, true)
  assert.strictEqual(body.angle, 0)
  assert.isOk(Math.abs(body.y - (body.halfHeight + 0.035)) < 1e-8)
})
