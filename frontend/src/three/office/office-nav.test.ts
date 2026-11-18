import { expect, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import { OfficeLifeSimulation } from './office-life'
import {
  BODY_RADIUS,
  navChairAt,
  navClear,
  navOf,
  navRoute,
} from './office-nav'
import { bodyGap, routePoints } from './office-nav.test-helpers'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

// 걷기 판정(S15P31A108-463): 칸 단위 막기(60% 규칙)는 가구가 칸에 반쯤 걸치면 비어, 사람이 테이블 위를 지나가거나
// 가구에 반쯤 묻혀 섰다. 걷는 판정은 가구·의자의 실제 바닥 사각형에서 몸 반지름(16px)만큼 떨어지고, 길은 1/4칸
// 격자에서 찾는다. 의자는 그 의자에 앉으러 가는 사람만 지나간다.

const DESIGNS = ['glass', 'brick', 'modern', 'tower', 'wood'] as const
const floorOf = (design: string) =>
  makeDreamFloor(makeBuilding(ALL_KEYS, design, null, {}).floors[1], 4)
const feet = (spot: { x: number; y: number }) => ({
  x: spot.x * 32 + 16,
  y: spot.y * 32 + 27,
})
// 나와 비비, 팀원 10명, 팀원마다 연결됨 에이전트 하나
const team = officeSceneAgents([
  { id: 'bibi', name: '비비', kind: 'lead' },
  { id: 'member:21', name: '나', kind: 'human' },
  ...Array.from({ length: 10 }, (_, index) => ({
    id: `member:${30 + index}`,
    name: `팀원 ${index}`,
    kind: 'agent' as const,
  })),
  ...Array.from({ length: 10 }, (_, index) => ({
    id: `agent:${30 + index}`,
    name: `팀원 ${index}의 에이전트`,
    kind: 'agent' as const,
    owner: `${30 + index}`,
    status: 'online',
  })),
])

test('가구 사각형에서 몸 반지름 안은 막히고, 의자는 앉으러 가는 사람만 지난다', () => {
  const floor = floorOf('modern')
  const nav = navOf(floor)
  // 모던 화이트 가운데 테이블(4.2 × 1.0 m)은 두 칸에 53%·47%씩 걸쳐 칸 단위로는 막히지 않았다.
  const table = floor.furniture.find(
    (item) => item.solid && item.kind === 'table' && item.w > 4,
  )!
  const x = (table.x + table.w / 2) * 32
  expect(floor.blocked[Math.floor(table.y + 0.6)][Math.floor(x / 32)]).toBe(
    false,
  )
  expect(navClear(nav, x, (table.y + table.h / 2) * 32)).toBe(false)
  const below = (table.y + table.h) * 32
  expect(navClear(nav, x - 48, below + BODY_RADIUS - 1)).toBe(false)
  // 작업 의자 가운데는 지나가는 사람에게 막혀 있고, 그 의자에 앉으러 가는 사람에게는 열려 있다.
  const chair = floor.furniture.find(
    (item) => item.kind === 'chair' && item.kit,
  )!
  const center = {
    x: (chair.x + chair.w / 2) * 32,
    y: (chair.y + chair.h / 2) * 32,
  }
  const index = navChairAt(nav, center)
  expect(index).toBeGreaterThanOrEqual(0)
  expect(navClear(nav, center.x, center.y)).toBe(false)
  // 의자 곁에는 그 의자에 앉으러 가는 사람만 설 수 있는 곳이 있다(다른 의자로는 열리지 않는다).
  let onlyForSitter = 0
  for (const [chairIndex, box] of nav.chairs.entries())
    for (let px = box.x0 - 8; px <= box.x1 + 8; px += 2)
      for (let py = box.y0 - 8; py <= box.y1 + 8; py += 2)
        if (navClear(nav, px, py, chairIndex)) {
          expect(
            navClear(nav, px, py, (chairIndex + 1) % nav.chairs.length),
          ).toBe(navClear(nav, px, py))
          if (!navClear(nav, px, py)) onlyForSitter++
        }
  expect(onlyForSitter).toBeGreaterThan(0)
})

test.each(DESIGNS)(
  '%s: 비비 자리·팀원과 에이전트가 서는 자리가 가구와 겹치지 않는다',
  (design) => {
    const floor = floorOf(design)
    // 비비가 서는 세 자리(D35)
    for (const spot of [
      floor.seats.lead![0],
      floor.bibiSpots!.documents,
      floor.bibiSpots!.meeting,
    ])
      expect(bodyGap(floor, feet(spot))).toBeGreaterThanOrEqual(BODY_RADIUS)
    const actors = officeActors(
      floor,
      team,
      4,
      'member:21',
      officeHomeSeats(floor, team),
      'MEETING',
    )
    const standing = actors.filter((item) => item.standing)
    expect(standing.length).toBeGreaterThan(10)
    for (const actor of standing)
      expect(bodyGap(floor, actor)).toBeGreaterThanOrEqual(BODY_RADIUS - 0.01)
    // 자리로 간 에이전트(연결됨은 주인 자리 뒤에 선다)도 가구에 묻혀 서지 않는다.
    const simulation = new OfficeLifeSimulation(`nav-${design}`)
    let current = actors
    for (let frame = 0; frame < 200; frame++)
      current = simulation.step(floor, actors, 0.1)
    for (const actor of current.filter((item) => item.standing && !item.moving))
      expect(bodyGap(floor, actor)).toBeGreaterThanOrEqual(BODY_RADIUS - 0.01)
    simulation.dispose()
  },
)

test.each(DESIGNS)(
  '%s: 고정 지점 사이의 모든 길은 몸이 가구·의자·벽에 닿지 않는다',
  (design) => {
    const floor = floorOf(design)
    const nav = navOf(floor)
    const members = officeActors(
      floor,
      team,
      4,
      'member:21',
      officeHomeSeats(floor, team),
      'MEETING',
    ).filter((item) => item.standing && item.id.startsWith('member:'))
    const spots = [
      feet(floor.seats.lead![0]),
      feet(floor.bibiSpots!.documents),
      feet(floor.bibiSpots!.meeting),
      ...(floor.outdoorExit ? [floor.outdoorExit.landing] : []),
      ...members,
    ]
    let routes = 0,
      tried = 0
    for (const [index, from] of spots.entries())
      for (const to of spots.slice(index + 1, index + 6)) {
        tried++
        const route = navRoute(nav, from, to)
        if (!route) continue
        routes++
        // 칸 사이 직선 위를 2px마다 짚은 점까지 몸 반지름만큼 떨어져 있다. 길찾기는 3px마다 재므로 모서리 곁을 지날 때
        // 그 사이에서 0.07px(16 - √(16² - 1.5²))까지 덜 떨어질 수 있다.
        for (const point of routePoints(from, route))
          expect(bodyGap(floor, point)).toBeGreaterThanOrEqual(
            BODY_RADIUS - 0.1,
          )
      }
    // 방 안의 모든 자리는 서로 오갈 수 있다.
    expect(routes).toBe(tried)
  },
)
