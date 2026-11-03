import { expect, it } from 'vitest'
import { initialOffset, queueSlot, snapOffset } from './queue'

it('칸은 한 줄로 한 칸씩 떨어져 놓이고, 넘기면 끝 다음에 첫 칸이 이어진다', () => {
  // 프로젝트 6개 + 빈 부지 = 7칸, 가운데 칸 3
  const at = (index: number, offset: number) => queueSlot(index, offset, 7).d
  expect([0, 1, 2, 3, 4, 5, 6].map((index) => at(index, 3))).toEqual([
    -3, -2, -1, 0, 1, 2, 3,
  ])
  // 빈 부지(6)를 가운데로 넘기면 첫 칸(0)이 바로 오른쪽에 온다
  expect(at(6, 6)).toBe(0)
  expect(at(0, 6)).toBe(1)
  // 끝을 넘어도 위치가 이어진다(한 칸의 반만 넘겨도 반 칸씩 움직인다)
  expect(at(0, 6.5)).toBeCloseTo(0.5)
  expect(at(0, 7)).toBeCloseTo(0)
})

it('가운데 다섯 칸은 그대로, 양 끝 칸은 가라앉아 한 칸이 두 번 보이지 않는다', () => {
  expect(queueSlot(3, 3, 9).size).toBe(1)
  expect(queueSlot(5, 3, 9).size).toBe(1)
  expect(queueSlot(6, 3, 9).size).toBe(0)
  expect(queueSlot(0, 3, 9).size).toBe(0)
  // 칸이 셋뿐이면 가운데에 모이고, 끝(±1.5칸)에서 가라앉는다
  expect([0, 1, 2].map((index) => queueSlot(index, 1, 3))).toEqual([
    { d: -1, size: 1 },
    { d: 0, size: 1 },
    { d: 1, size: 1 },
  ])
  // 반 칸 넘기면 첫 칸은 왼쪽 끝(-1.5칸)에서 가라앉고, 같은 자리의 반대쪽 끝(+1.5칸)에서 올라온다
  expect(queueSlot(0, 1.5, 3)).toEqual({ d: -1.5, size: 0 })
})

it('칸 수가 짝수이고 다섯보다 적으면 반 칸 자리에 멈춰 이음매에 걸린 칸 없이 모두 보인다', () => {
  for (const count of [2, 4]) {
    const offset = initialOffset(count)
    expect(snapOffset(offset + 0.3, count)).toBe(offset)
    expect(snapOffset(offset - 0.3, count)).toBe(offset)
    expect(
      Array.from({ length: count }, (_, index) =>
        queueSlot(index, offset, count),
      ).every(({ size }) => size === 1),
    ).toBe(true)
  }
  // 프로젝트 하나와 빈 부지: 가운데 왼쪽 반 칸에 프로젝트, 오른쪽 반 칸에 빈 부지
  expect(
    [0, 1].map((index) => queueSlot(index, initialOffset(2), 2).d),
  ).toEqual([-0.5, 0.5])
  // 칸이 다섯 이상이면 정수 자리에 멈춘다
  expect(snapOffset(6.4, 7)).toBe(6)
  expect(snapOffset(5.6, 6)).toBe(6)
})

it('처음에는 마지막 프로젝트를 가운데, 빈 부지를 오른쪽에 둔다', () => {
  expect(initialOffset(3)).toBe(1)
  expect(initialOffset(1)).toBe(0)
})
