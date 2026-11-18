import { expect, it } from 'vitest'
import {
  BUS_SPOTS,
  initialRotation,
  laneSpots,
  ringOrder,
  nearestStop,
  RING_PITCH,
  RING_RADIUS,
  RING_SLOTS,
  ringSlot,
  startRotation,
  stepStop,
} from './ring'

it('이웃 칸 사이 호의 길이는 칸 간격과 같다', () => {
  const step = ringSlot(1, 0).phi - ringSlot(0, 0).phi
  expect(step * RING_RADIUS).toBeCloseTo(RING_PITCH)
})

// 앞쪽 호만 보이게 바꾸며 v474의 줄어들기(76~92°)를 뺐다. 기대값을 새 동작에 맞게 고쳤다(사용자 승인 2026-11-03).
it('칸은 가라앉지 않고 고리를 따라 돈다: 뒤쪽 칸도 크기 비율 없이 각도만 바뀐다', () => {
  expect(ringSlot(4, 4).away).toBe(0)
  expect(ringSlot(6, 4).away).toBeCloseTo(60)
  expect(ringSlot(1, 4).away).toBeCloseTo(90)
  expect(ringSlot(4 + RING_SLOTS / 2, 4).away).toBeCloseTo(180)
  expect(ringSlot(4 + RING_SLOTS / 2, 4)).not.toHaveProperty('size')
  // 고리를 한 바퀴 돌리면 제자리다
  expect(ringSlot(2, 0.5).away).toBeCloseTo(ringSlot(2, 0.5 + RING_SLOTS).away)
})

it('처음에는 가장 최근 칸을 가운데에 두고 빈 부지를 그 오른쪽에 둔다', () => {
  expect(initialRotation(2)).toBe(1)
  expect(initialRotation(0)).toBe(0)
  expect(initialRotation(null)).toBe(0)
})

// 사용자 결정 '꾸밈 구간 한 번에 넘기기': 멈춤 자리는 프로젝트 칸과 빈 부지뿐이다. 프로젝트 6개(0~5)와 빈 부지(6)면
// 7~11은 꾸밈 칸이다.
it('휠 한 번은 꾸밈 칸을 건너 다음 멈춤 자리로, 끌고 놓으면 가장 가까운 멈춤 자리로 간다', () => {
  const stops = [0, 1, 2, 3, 4, 5, 6]
  // 빈 부지에서 앞으로 한 번이면 꾸밈 칸 다섯을 지나 1번(칸 0, 한 바퀴 돈 12)이 가운데로 온다
  expect(stepStop(6, stops, 1)).toBe(12)
  // 1번에서 거꾸로 한 번이면 빈 부지(칸 6, 한 바퀴 거꾸로 -6)
  expect(stepStop(0, stops, -1)).toBe(-6)
  expect(stepStop(12, stops, -1)).toBe(6)
  // 프로젝트 사이는 한 칸씩
  expect(stepStop(2, stops, 1)).toBe(3)
  expect(stepStop(2, stops, -1)).toBe(1)
  // 꾸밈 칸 위(8.4)에서 놓으면 가까운 빈 부지(6)로, 10.2에서 놓으면 한 바퀴 돈 1번(12)으로
  expect(nearestStop(8.4, stops)).toBe(6)
  expect(nearestStop(10.2, stops)).toBe(12)
  expect(nearestStop(-0.3, stops)).toBe(0)
  // 칸이 비어 있는 자리(부지 번호에 틈)도 건너뛴다
  expect(stepStop(1, [1, 4, 5], 1)).toBe(4)
})

// 사용자 요청 '가장 마지막에 방문한 프로젝트 건물이 가운데로'(2026-11-03)
it('도시에 들어오면 마지막으로 연 프로젝트 칸을 가운데에 두고, 모르면 처음 화면 규칙을 쓴다', () => {
  expect(startRotation(0, 2)).toBe(0)
  expect(startRotation(5, 2)).toBe(5)
  // 기록이 없거나 그 프로젝트를 지웠거나 나가 도시에 없으면 가장 최근 칸
  expect(startRotation(undefined, 2)).toBe(initialRotation(2))
  expect(startRotation(null, null)).toBe(0)
})

// 리뷰(PR #305): 차 각도가 순수 무작위라 시드 7에서 같은 차로의 두 쌍이 13.9·17.7단위 거리로 파고들었다(차 길이 20).
it('순환 도로 차는 차로마다 이웃 차 사이가 차 길이보다 넓고 버스 자리를 비운다', () => {
  const CAR = 20
  const BUS = 46
  for (let seed = 1; seed <= 200; seed++) {
    let state = seed
    const rnd = () => (state = (state * 16807) % 2147483647) / 2147483647
    for (const [radius, avoid] of [
      [RING_RADIUS + 116, []],
      [RING_RADIUS + 138, BUS_SPOTS],
    ] as const) {
      const unit = (2 * Math.PI * radius) / RING_SLOTS
      const spots = laneSpots(RING_SLOTS * 0.75, rnd, avoid).sort(
        (a, b) => a - b,
      )
      spots.forEach((th, i) => {
        const next =
          spots[(i + 1) % spots.length] +
          (i + 1 === spots.length ? RING_SLOTS : 0)
        expect((next - th) * unit).toBeGreaterThan(CAR * 2)
      })
      for (const at of avoid)
        for (const th of spots)
          expect(
            (ringSlot(th, at).away / 360) * RING_SLOTS * unit,
          ).toBeGreaterThan((BUS + CAR) / 2)
    }
  }
})

// 사용자 요청 '빈공터를 제거하고 … 1-2-3-4-새프로젝트-1-2-3.. 이런식으로'(2026-11-16)
it('프로젝트와 빈 부지가 둘 이상이면 고리를 따라 차례를 되풀이해 꾸밈 칸 없이 이어 놓는다', () => {
  const lots = [0, 1, 2, 3, 4] // 프로젝트 넷과 빈 부지
  for (const rot of [0, 2, 2.4, 7, 13, -5]) {
    // 앞(rot)에서 반 바퀴 안의 칸은 v번째 차례(v mod 5)를 놓아 1-2-3-4-새-1-…로 이어진다
    for (let v = Math.ceil(rot) - 5; v <= Math.floor(rot) + 5; v++) {
      const s = ((v % RING_SLOTS) + RING_SLOTS) % RING_SLOTS
      expect(ringOrder(s, rot, lots)).toBe(((v % 5) + 5) % 5)
    }
    // 모든 칸이 부지다
    for (let s = 0; s < RING_SLOTS; s++)
      expect(ringOrder(s, rot, lots)).toBeGreaterThanOrEqual(0)
  }
  // 빈 부지 하나뿐이면 제 칸에만 두고 나머지는 꾸밈 칸이다
  expect(ringOrder(0, 3, [0])).toBe(0)
  expect(ringOrder(1, 3, [0])).toBe(-1)
})
