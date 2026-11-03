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
  // 도시 12칸(D26, 당시 원형 도시)에 맞춰 부지 상한을 8에서 12로 바꾸며 기대값을 고쳤다(사용자 승인 2026-11-03).
  it('다른 계정의 배치를 섞지 않고, 부지 12칸을 넘는 열세 번째 프로젝트도 부지 없이 목록에서 보존한다', () => {
    const result = projectPlacements(
      Array.from({ length: 13 }, (_, index) => String(index + 1)),
      '22',
      { '11:1': { lot: 7, design: 'tower' } },
    )
    expect(result[0]).toEqual({ id: '1', lot: 0, design: 'modern' })
    expect(result[8]).toEqual({ id: '9', lot: 8, design: 'modern' })
    expect(result[11]).toEqual({ id: '12', lot: 11, design: 'modern' })
    expect(result[12]).toEqual({ id: '13', lot: null, design: 'modern' })
  })
})
