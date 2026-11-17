import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import {
  keepsSpace,
  OfficeLifeSimulation,
  PERSONAL_SPACE,
  SEATED_SPACE,
  type OfficeLifeActor,
} from './office-life'

const floor = makeDreamFloor(
  makeBuilding(ALL_KEYS, 'glass', null, { dev: 4, lead: 1, humandesk: 3 })
    .floors[1],
  4,
)

test('사람이 다가가는 걸음만 막고, 이미 붙어 있으면 멀어지는 걸음은 허락한다', () => {
  const other = { x: 100, y: 100 }
  const at = (gap: number) => ({ x: 100 + gap, y: 100 })
  // 개인 거리 밖에서 다가가되 밖에 머무는 걸음
  const space = PERSONAL_SPACE
  assert.isOk(keepsSpace(at(space + 12), at(space + 4), [other]))
  // 개인 거리 안으로 들어가는 걸음
  assert.isNotOk(keepsSpace(at(space + 4), at(space - 8), [other]))
  // 이미 붙어 있으면 멀어지는 걸음
  assert.isOk(keepsSpace(at(10), at(14), [other]))
})

test('여러 명이 1분 동안 돌아다녀도 서로의 거리가 개인 거리 아래로 줄지 않는다', () => {
  // 실제 사무실처럼 저마다 자기 책상에서 시작한다.
  const seats = [
    ...(floor.seats.dev ?? []),
    ...(floor.seats.humandesk ?? []),
    ...(floor.seats.lead ?? []),
  ]
  const actors: OfficeLifeActor[] = seats.slice(0, 8).map((seat, index) => ({
    id: `crowd-${index}`,
    x: seat.x * 32 + 16,
    y: seat.y * 32 + 27,
    dir: seat.facing,
    moving: false,
    // 둘은 책상에서 일하고, 나머지는 소파·창가·음료대를 오가며 길에서 마주친다.
    status: index < 2 ? 'working' : 'idle',
  }))
  const simulation = new OfficeLifeSimulation('crowd')
  let closest = Infinity,
    closestSeated = Infinity,
    walked = 0
  for (let frame = 0; frame < 1200; frame++) {
    const people = simulation.step(floor, actors, 0.05)
    walked += people.filter((person) => person.moving).length
    // 처음 몇 프레임은 같은 자리에서 일어나 갈라진다.
    if (frame < 40) continue
    // 의자에 앉은 사람 뒤로는 의자 반 폭과 몸 반지름만큼만 떨어져 지나간다(D37 부딪힘, 2026-11-17). 의자 자체가 막아
    // 준다. 서 있거나 걷는 사람끼리는 사람 사이 거리를 지킨다.
    for (let a = 0; a < people.length; a++)
      for (let b = a + 1; b < people.length; b++) {
        const gap = Math.hypot(
          people[a].x - people[b].x,
          people[a].y - people[b].y,
        )
        if (people[a].seated || people[b].seated)
          closestSeated = Math.min(closestSeated, gap)
        else closest = Math.min(closest, gap)
      }
  }
  simulation.dispose()
  assert.isOk(walked > 1000, 'people actually move around')
  assert.isAtLeast(closest, PERSONAL_SPACE - 1)
  assert.isAtLeast(closestSeated, SEATED_SPACE - 1)
})
