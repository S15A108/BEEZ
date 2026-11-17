import { expect, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeActors } from './office-actors'
import { officeLifeSpots } from './office-life'
import {
  BODY_RADIUS,
  navChairAt,
  navClear,
  navOf,
  navRoute,
  STAND_CLEARANCE,
} from './office-nav'
import { bodyGap, routePoints } from './office-nav.test-helpers'
import { officeHomeSeats } from './office-work'
import { officeSceneAgents } from '../sceneData'

// D37 부딪힘(2026-11-17): 칸 단위 막기(60% 규칙)는 가구가 칸에 반쯤 걸치면 비어, 사람과 비비가 테이블 위를 지나가거나
// 테이블에 반쯤 묻혀 섰다. 걷는 판정은 가구·의자의 실제 바닥 사각형에서 몸 반지름(16px)만큼 떨어지고, 길은 1/4칸
// 격자에서 찾는다. 의자는 그 의자에 앉으러 가는 사람만 지나간다.

const DESIGNS = ['glass', 'brick', 'modern', 'tower', 'wood'] as const
const floorOf = (design: string) =>
  makeDreamFloor(makeBuilding(ALL_KEYS, design, null, {}).floors[1], 4)
const feet = (spot: { x: number; y: number }) => ({
  x: spot.x * 32 + 16,
  y: spot.y * 32 + 27,
})

test('가구 사각형에서 몸 반지름 안은 막히고, 의자는 앉으러 가는 사람만 지난다', () => {
  const floor = floorOf('modern')
  const nav = navOf(floor)
  // 모던 화이트 가운데 테이블(4.2 × 1.0 m)은 두 칸에 53%·47%씩 걸쳐 칸 단위로는 막히지 않았다.
  const table = floor.furniture.find(
    (item) => item.solid && item.kind === 'table' && item.w > 4,
  )!
  const x = (table.x + table.w / 2) * 32
  expect(
    floor.blocked[Math.floor((table.y + 0.6) * 1)][Math.floor(x / 32)],
  ).toBe(false)
  expect(navClear(nav, x, (table.y + table.h / 2) * 32)).toBe(false)
  const below = (table.y + table.h) * 32
  expect(navClear(nav, x - 48, below + BODY_RADIUS - 1)).toBe(false)
  // 의자 가운데는 지나가는 사람에게 막혀 있고, 그 의자에 앉으러 가는 사람에게는 열려 있다.
  const seat = feet(floor.seats.lead![0])
  const chair = navChairAt(nav, seat)
  if (chair >= 0) {
    expect(navClear(nav, seat.x, seat.y)).toBe(false)
  }
})

test.each(DESIGNS)(
  '%s: 비비 자리·팀원 출발 자리·들르는 곳이 가구와 겹치지 않는다',
  (design) => {
    const floor = floorOf(design)
    // 비비가 서는 세 자리(D35)
    for (const spot of [
      floor.seats.lead![0],
      floor.bibiSpots!.documents,
      floor.bibiSpots!.meeting,
    ])
      expect(bodyGap(floor, feet(spot))).toBeGreaterThanOrEqual(BODY_RADIUS)
    // 팀원 10명과 연결됨 에이전트가 처음 서는 자리
    const agents = officeSceneAgents([
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
    const actors = officeActors(
      floor,
      agents,
      4,
      'member:21',
      officeHomeSeats(floor, agents),
    )
    for (const actor of actors.filter((item) => item.standing))
      expect(bodyGap(floor, actor)).toBeGreaterThanOrEqual(BODY_RADIUS)
    // 창가·음료 앞·동료 자리 곁. 동료 자리 곁은 서 있어도 다른 사람이 지나갈 만큼 넓다.
    const { visits } = officeLifeSpots(floor)
    expect(visits.length).toBeGreaterThan(3)
    for (const spot of visits) {
      expect(bodyGap(floor, spot.landing)).toBeGreaterThanOrEqual(BODY_RADIUS)
      if (spot.activity === 'dropby')
        expect(bodyGap(floor, spot.landing)).toBeGreaterThanOrEqual(
          STAND_CLEARANCE,
        )
    }
  },
)

test.each(DESIGNS)(
  '%s: 고정 지점 사이의 모든 길은 몸이 가구·의자·벽에 닿지 않는다',
  (design) => {
    const floor = floorOf(design)
    const nav = navOf(floor)
    const spots = [
      feet(floor.seats.lead![0]),
      feet(floor.bibiSpots!.documents),
      feet(floor.bibiSpots!.meeting),
      ...(floor.outdoorExit ? [floor.outdoorExit.landing] : []),
      ...officeLifeSpots(floor).visits.map((spot) => spot.landing),
    ]
    let routes = 0,
      tried = 0
    for (const [index, from] of spots.entries())
      for (const to of spots.slice(index + 1, index + 6)) {
        tried++
        const route = navRoute(nav, from, to)
        if (!route) continue
        routes++
        // 칸 사이 직선 위를 2px마다 짚은 점까지 몸 반지름만큼 떨어져 있다.
        for (const point of routePoints(from, route))
          expect(bodyGap(floor, point)).toBeGreaterThanOrEqual(
            BODY_RADIUS - 0.01,
          )
      }
    // 방 안의 모든 자리는 서로 오갈 수 있다.
    expect(routes).toBe(tried)
  },
)
