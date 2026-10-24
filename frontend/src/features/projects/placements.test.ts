import { describe, expect, it } from 'vitest'
import { projectPlacements } from './placements'

describe('프로젝트 부지 배치', () => {
  it('저장된 부지를 먼저 보존한 뒤 나머지를 빈 부지에 배치한다', () => {
    expect(
      projectPlacements(['101', '102', '103'], '11', {
        '11:103': { lot: 0, design: 'wood' },
      }),
    ).toEqual([
      { id: '101', lot: 1, design: 'modern' },
      { id: '102', lot: 2, design: 'modern' },
      { id: '103', lot: 0, design: 'wood' },
    ])
  })
  it('다른 계정의 배치를 섞지 않고 아홉 번째 프로젝트도 목록에서 보존한다', () => {
    const result = projectPlacements(
      Array.from({ length: 9 }, (_, index) => String(index + 1)),
      '22',
      { '11:1': { lot: 7, design: 'tower' } },
    )
    expect(result[0]).toEqual({ id: '1', lot: 0, design: 'modern' })
    expect(result[8]).toEqual({ id: '9', lot: null, design: 'modern' })
  })
})
