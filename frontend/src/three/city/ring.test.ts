import { expect, it } from 'vitest'
import {
  initialRotation,
  RING_PITCH,
  RING_RADIUS,
  RING_SLOTS,
  ringSlot,
} from './ring'

it('이웃 칸 사이 호의 길이는 칸 간격과 같다', () => {
  const step = ringSlot(1, 0).phi - ringSlot(0, 0).phi
  expect(step * RING_RADIUS).toBeCloseTo(RING_PITCH)
})

it('앞 반원의 칸은 그대로, 반원 끝을 넘으면 줄어들고 뒤쪽 칸은 사라진다', () => {
  expect(ringSlot(4, 4).size).toBe(1)
  expect(ringSlot(6, 4).size).toBe(1)
  expect(ringSlot(1, 4).size).toBeCloseTo(1 - (90 - 76) / 16)
  expect(ringSlot(4 + RING_SLOTS / 2, 4).size).toBe(0)
  // 고리를 한 바퀴 돌리면 제자리다
  expect(ringSlot(2, 0.5).away).toBeCloseTo(ringSlot(2, 0.5 + RING_SLOTS).away)
})

it('처음에는 가장 최근 칸을 가운데에 두고 빈 부지를 그 오른쪽에 둔다', () => {
  expect(initialRotation(2)).toBe(1)
  expect(initialRotation(0)).toBe(0)
  expect(initialRotation(null)).toBe(0)
})
