import { assert, test } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { gridWalkable, smoothRoute } from './motion'
import { findPath } from './path'
import { BRICK_PLAN } from './plans/brick'
import { GLASS_PLAN } from './plans/glass'
import { MODERN_PLAN } from './plans/modern'
import { TOWER_PLAN } from './plans/tower'
import { WOOD_PLAN } from './plans/wood'
import type { OfficePiece } from './officePlan'
import { officeSeats, seatApproach } from './social'

// D39: 건물 외관별 배치(캔버스 SCR-06-rec-office-buildings, 기둥 없음). 캔버스 28 × 16 m를 26 × 14 m로 좁혀 쓴다(D42, 기대값 변경 사용자 요청 2026-11-06). 안마다 D37 자리(충전·워룸·주인 의자·
// 허들·비비)가 있고, 모든 자리는 엘리베이터에서 걸어갈 수 있다. 옮긴 외관을 하나씩 늘린다.
const PORTED = ['brick', 'modern', 'tower', 'wood', 'glass'] as const
// 작업 화면(모니터·방 가운데 노트북)이 있는 자리 수. 캔버스 안마다 다르다: 벽돌 긴 테이블 6+6, 모던 팀 책상 8 + 공용 테이블 노트북 2, 타워 창가 집중석 7 + 팀 테이블 노트북 2, 목조 나무 작업대 6, 유리 팀 책상 8.
const SCREENS: Record<(typeof PORTED)[number], number> = {
  brick: 12,
  modern: 10,
  tower: 9,
  wood: 6,
  glass: 8,
}
const feet = (x: number, y: number) => ({ x: x * 32 + 16, y: y * 32 + 27 })
const office = (design: string) =>
  makeDreamFloor(
    makeBuilding(ALL_KEYS, design, null, { dev: 12, lead: 1 }).floors[1],
    4,
  )

test.each(PORTED)(
  '%s 사무실은 26 × 14 m 방에 D37 자리가 모두 있다',
  (design) => {
    const floor = office(design)
    assert.strictEqual(floor.design, design)
    assert.strictEqual(floor.W, 28)
    assert.strictEqual(floor.H, 18)
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

const PLANS = {
  brick: BRICK_PLAN,
  modern: MODERN_PLAN,
  tower: TOWER_PLAN,
  wood: WOOD_PLAN,
  glass: GLASS_PLAN,
}
type Box = Pick<OfficePiece, 'x' | 'z' | 'w' | 'd' | 'ry'>
const overlaps = (a: Box, b: Box) => {
  const span = (box: Box) =>
    Math.abs(Math.sin(box.ry)) > 0.5 ? [box.d, box.w] : [box.w, box.d]
  const [aw, ad] = span(a),
    [bw, bd] = span(b)
  return (
    Math.abs(a.x - b.x) < (aw + bw) / 2 - 0.02 &&
    Math.abs(a.z - b.z) < (ad + bd) / 2 - 0.02
  )
}

test.each(PORTED)(
  '%s 사무실을 좁혀도 단단한 가구끼리 새로 겹치지 않고 묶음은 그대로다',
  (design) => {
    const floor = office(design),
      pieces = PLANS[design].pieces
    const kits = pieces.map((_, index) => floor.furniture[index].kit!)
    pieces.forEach((a, i) =>
      pieces.forEach((b, j) => {
        if (j <= i || a.y > 0.2 || b.y > 0.2) return
        if (!floor.furniture[i].solid || !floor.furniture[j].solid) return
        if (overlaps(a, b)) return
        assert.isFalse(
          overlaps(kits[i], kits[j]),
          `${design} ${a.model ?? a.ui}/${i} ${b.model ?? b.ui}/${j}`,
        )
      }),
    )
    // 책상 위 소품은 그 책상과 같이 옮겨 간다.
    pieces.forEach((item, i) => {
      if (item.y <= 0.2 || item.y > 1.2) return
      const table = pieces.findIndex(
        (other) =>
          other.y <= 0.2 &&
          (other.kind === 'table' || other.kind === 'desk') &&
          overlaps(other, { ...item, w: 0, d: 0 }),
      )
      if (table < 0) return
      assert.closeTo(
        kits[i].x - kits[table].x,
        item.x - pieces[table].x,
        1e-9,
        `${design} ${item.model ?? item.ui}/${i}`,
      )
    })
  },
)

test.each(PORTED)(
  '%s 사무실은 3인칭에서 남·동쪽 벽 가까이의 화면과 보드만 숨긴다',
  (design) => {
    const floor = office(design)
    const hidden = floor.furniture.filter((item) => item.firstPersonOnly)
    assert.includeMembers(
      hidden.map((item) => item.kit?.ui),
      ['notes', 'dash1', 'dash2', 'graph'],
    )
    const south = floor.footprint!.front,
      east = floor.footprint!.right
    for (const item of hidden)
      assert.isOk(
        south - item.kit!.z < 3 || east - item.kit!.x < 3,
        `${item.kit?.ui}`,
      )
  },
)
