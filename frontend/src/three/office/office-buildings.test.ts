import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { gridWalkable, smoothRoute } from './motion'
import { findPath } from './path'
import { officeSeats, seatApproach } from './social'

// D31: 건물 외관별 배치(캔버스 SCR-06-rec-office-buildings, 28 × 16 m, 기둥 없음). 안마다 D29 자리(충전·워룸·주인 의자·
// 허들·비비)가 있고, 모든 자리는 엘리베이터에서 걸어갈 수 있다. 옮긴 외관을 하나씩 늘린다.
const PORTED = ['brick', 'modern', 'tower'] as const
// 작업 화면(모니터·방 가운데 노트북)이 있는 자리 수. 캔버스 안마다 다르다: 벽돌 긴 테이블 6+6, 모던 팀 책상 8 + 공용 테이블 노트북 2, 타워 창가 집중석 7 + 팀 테이블 노트북 2.
const SCREENS: Record<(typeof PORTED)[number], number> = {
  brick: 12,
  modern: 10,
  tower: 9,
}
const feet = (x: number, y: number) => ({ x: x * 32 + 16, y: y * 32 + 27 })
const office = (design: string) =>
  makeDreamFloor(
    makeBuilding(ALL_KEYS, design, null, { dev: 12, lead: 1 }).floors[1],
    4,
  )

test.each(PORTED)(
  '%s 사무실은 28 × 16 m 방에 D29 자리가 모두 있다',
  (design) => {
    const floor = office(design)
    assert.strictEqual(floor.design, design)
    assert.strictEqual(floor.W, 30)
    assert.strictEqual(floor.H, 20)
    assert.isAtLeast(floor.seats.dev!.length, 12, '작업 자리')
    assert.isAtLeast(floor.seats.meeting!.length, 1, '허들')
    assert.isAtLeast(floor.seats.board!.length, 1, '워룸')
    assert.isAtLeast(floor.seats.lobby!.length, 1, '충전 자리')
    assert.strictEqual(floor.seats.lead!.length, 1, '비비')
    assert.strictEqual(
      floor.furniture.filter((item) => item.tag?.startsWith('workstation/'))
        .length,
      SCREENS[design],
      '작업 화면이 있는 자리',
    )
  },
)

test.each(PORTED)(
  '%s 사무실의 모든 자리는 엘리베이터에서 걸어갈 수 있다',
  (design) => {
    const floor = office(design)
    const free = (x: number, y: number) =>
        gridWalkable({ x, y: y - 4 }, floor.blocked, 32, 6),
      from = feet(floor.exit.x, floor.exit.y)
    const seats = officeSeats(floor)
    assert.strictEqual(
      new Set(seats.map((seat) => `${seat.x}/${seat.y}`)).size,
      seats.length,
    )
    for (const seat of seats) {
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
        `${design} ${seat.id}`,
      )
    }
  },
)
