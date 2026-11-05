import { describe, expect, it } from 'vitest'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { projectPlacements } from './placements'

const saved: Record<string, { lot: number; design: BuildingDesign }> = {
  '11:101': { lot: 0, design: 'glass' },
  '11:102': { lot: 1, design: 'brick' },
  '11:103': { lot: 2, design: 'wood' },
  '11:104': { lot: 3, design: 'tower' },
}

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
  // 원형 도시 12칸(D33)에 맞춰 부지 상한을 8에서 12로 바꾸며 기대값을 고쳤다(사용자 승인 2026-11-03).
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

  // S15P31A108-256 사용자 요청 "프로젝트를 삭제하거나 나가면 중간이 비어지게 되는데 당겨지도록 수정해"
  it('중간 프로젝트를 지우거나 나가면 남은 프로젝트를 원래 순서대로 당기고 외관은 프로젝트를 따라간다', () => {
    // 102를 지움(목록은 최근순이라 저장 순서와 다르다)
    expect(projectPlacements(['104', '103', '101'], '11', saved)).toEqual([
      { id: '104', lot: 2, design: 'tower' },
      { id: '103', lot: 1, design: 'wood' },
      { id: '101', lot: 0, design: 'glass' },
    ])
    // 이어서 첫 칸(101)에서 나감
    expect(projectPlacements(['104', '103'], '11', saved)).toEqual([
      { id: '104', lot: 1, design: 'tower' },
      { id: '103', lot: 0, design: 'wood' },
    ])
  })

  it('12칸이 찬 도시에서 하나를 지우면 부지 밖이던 열세 번째 프로젝트가 마지막 칸으로 들어온다', () => {
    const ids = Array.from({ length: 13 }, (_, index) => String(index + 1))
    const full = Object.fromEntries(
      ids
        .slice(0, 12)
        .map((id, lot) => [`11:${id}`, { lot, design: 'wood' as const }]),
    )
    expect(projectPlacements(ids, '11', full)[12].lot).toBeNull()
    const result = projectPlacements(
      ids.filter((id) => id !== '5'),
      '11',
      full,
    )
    expect(result.find((item) => item.id === '6')).toEqual({
      id: '6',
      lot: 4,
      design: 'wood',
    })
    expect(result.at(-1)).toEqual({ id: '13', lot: 11, design: 'modern' })
  })

  it('지운 뒤 새로 만든 프로젝트는 마지막 프로젝트 다음 칸에 놓이고 앞 프로젝트와 순서가 바뀌지 않는다', () => {
    useWorkspaceStore.setState({ placements: { ...saved } })
    // 101을 지운 도시: 102·103·104가 0~2칸, 빈 부지는 3칸
    const assigned = projectPlacements(
      ['104', '103', '102'],
      '11',
      useWorkspaceStore.getState().placements,
    )
    useWorkspaceStore
      .getState()
      .placeProjects('11', [
        ...assigned,
        { id: '105', lot: 3, design: 'modern' },
      ])
    expect(
      projectPlacements(
        ['105', '104', '103', '102'],
        '11',
        useWorkspaceStore.getState().placements,
      ),
    ).toEqual([
      { id: '105', lot: 3, design: 'modern' },
      { id: '104', lot: 2, design: 'tower' },
      { id: '103', lot: 1, design: 'wood' },
      { id: '102', lot: 0, design: 'brick' },
    ])
  })
})
