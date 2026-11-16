import { describe, expect, it } from 'vitest'
import { stackTags, tagParts } from './nameTag'

describe('tagParts', () => {
  it('마지막 가운뎃점 앞은 이름, 뒤는 상태로 나눈다', () => {
    expect(tagParts('박팀원의 에이전트 · 작업 중')).toEqual({
      name: '박팀원의 에이전트',
      status: ' · 작업 중',
    })
  })

  it('가운뎃점이 없으면 모두 이름이다', () => {
    expect(tagParts('김동료')).toEqual({ name: '김동료', status: '' })
  })
})

describe('stackTags', () => {
  const box = (x: number, bottom: number, depth: number) => ({
    x,
    bottom,
    width: 100,
    height: 28,
    depth,
  })

  it('겹치지 않는 이름표는 제자리에 둔다', () => {
    expect(stackTags([box(100, 200, 5), box(300, 200, 3)])).toEqual([0, 0])
  })

  it('겹치면 가까운 이름표는 그대로 두고 먼 이름표를 그 위로 올린다', () => {
    // 먼 것(depth 9)이 가까운 것(depth 2) 위, 사이 2px.
    expect(stackTags([box(100, 200, 9), box(140, 205, 2)])).toEqual([
      200 - (205 - 28 - 2),
      0,
    ])
  })

  it('세 개가 한자리에 모이면 거리순으로 층층이 쌓는다', () => {
    const lifts = stackTags([box(0, 100, 3), box(0, 100, 1), box(0, 100, 2)])
    expect(lifts).toEqual([60, 0, 30])
  })

  it('위로 올린 이름표가 다른 이름표에 다시 걸리면 그 위로 더 올린다', () => {
    // 가까운 둘이 세로로 붙어 있고, 먼 하나가 아래 것과 겹친다.
    const lifts = stackTags([box(0, 100, 1), box(0, 70, 2), box(20, 110, 3)])
    expect(lifts[0]).toBe(0)
    expect(lifts[1]).toBe(0)
    expect(110 - lifts[2]).toBe(70 - 28 - 2)
  })
})
