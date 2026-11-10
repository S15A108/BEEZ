import { describe, expect, it } from 'vitest'
import { designBoard, progressRows, proposalWall } from './office-screens'

const proposal = (id: string, status: string) => ({
  id,
  key: `CP-${id}`,
  title: `제안 ${id}`,
  status,
})

describe('변경 제안 메모 벽', () => {
  it('제안 상태를 변경 제안 창의 상태 이름 칸으로 나누고 칸마다 세 장까지 붙인다', () => {
    const wall = proposalWall([
      proposal('1', 'PROPOSED'),
      proposal('2', 'IN_REVIEW'),
      proposal('3', 'IN_REVIEW'),
      proposal('4', 'IN_REVIEW'),
      proposal('5', 'IN_REVIEW'),
      proposal('6', 'APPROVED'),
      proposal('7', 'PUBLISHED'),
      proposal('8', 'REJECTED'),
      proposal('9', 'WITHDRAWN'),
    ])
    expect(wall.map((column) => column.label)).toEqual([
      '제안',
      '검토 중',
      '승인',
      '발행 완료',
      '반려·거둠',
    ])
    expect(wall.map((column) => column.count)).toEqual([1, 4, 1, 1, 2])
    expect(wall[1].notes.map((note) => note.key)).toEqual([
      'CP-2',
      'CP-3',
      'CP-4',
    ])
    expect(wall[4].notes).toEqual([
      { id: '8', key: 'CP-8', title: '제안 8', status: 'REJECTED' },
      { id: '9', key: 'CP-9', title: '제안 9', status: 'WITHDRAWN' },
    ])
  })

  it('목록 첫 쪽보다 많으면 상태별 집계로 센다', () => {
    const wall = proposalWall(
      [proposal('1', 'PUBLISHED')],
      [
        { status: 'PUBLISHED', count: 12 },
        { status: 'REJECTED', count: 2 },
        { status: 'WITHDRAWN', count: 1 },
      ],
    )
    expect(wall[3]).toMatchObject({ count: 12, notes: [{ key: 'CP-1' }] })
    expect(wall[4].count).toBe(3)
    expect(wall[0].count).toBe(0)
  })
})

describe('설계 관계 TV', () => {
  it('기준 설계 항목을 종류별로 세고 진행 중 제안이 바꾸는 항목을 겹치지 않게 센다', () => {
    const board = designBoard(
      3,
      [
        { design_item_id: '11', kind: 'REQUIREMENT' },
        { design_item_id: '12', kind: 'REQUIREMENT' },
        { design_item_id: '21', kind: 'API_CONTRACT' },
        { design_item_id: '31', kind: 'POLICY' },
      ],
      false,
      [
        {
          items: [
            { operation: 'UPDATE', design_item_id: '11' },
            { operation: 'RETIRE', design_item_id: '21' },
          ],
        },
        {
          items: [
            { operation: 'UPDATE', design_item_id: '11' },
            { operation: 'CREATE', kind: 'DATA_CONTRACT' },
          ],
        },
      ],
    )
    expect(board).toMatchObject({
      version: 3,
      total: 4,
      more: false,
      proposals: 2,
    })
    expect(
      board.kinds.map(({ label, count, touched }) => [label, count, touched]),
    ).toEqual([
      ['요구사항', 2, 1],
      ['API 계약', 1, 1],
      ['데이터 계약', 0, 1],
      ['정책', 1, 0],
      ['제약조건', 0, 0],
    ])
  })
})

describe('프로젝트 진행 화면', () => {
  it('받은 집계만 줄로 만들고 못 받은 줄은 지어내지 않는다', () => {
    expect(progressRows()).toEqual([])
    expect(
      progressRows(
        {
          by_status: [
            { status: 'PUBLISHED', count: 3 },
            { status: 'IN_REVIEW', count: 1 },
          ],
          review_required_count: 1,
        },
        {
          by_kind: [
            { kind: 'PLAN', approved_count: 1 },
            { kind: 'ERD', approved_count: 0 },
            { kind: 'REQUIREMENTS', approved_count: 2 },
            { kind: 'API_SPEC', approved_count: 0 },
          ],
        },
      ),
    ).toEqual([
      { label: '기준 문서 승인', done: 2, total: 4, tone: 'done' },
      { label: '변경 제안 발행', done: 3, total: 4, tone: 'done' },
      { label: '변경 제안 검토', done: 1, total: 4, tone: 'review' },
    ])
  })
})
