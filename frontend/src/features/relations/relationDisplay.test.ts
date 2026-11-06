import { describe, expect, it } from 'vitest'
import { relationsResponseSchema } from '@/features/dashboard/schemas'
import {
  COLUMN_GAP,
  NODE_H,
  NODE_W,
  edgeLabel,
  edgeRoute,
  graphLabel,
  lineLabel,
  nodeKind,
  nodeX,
  nodeY,
  relationLabel,
} from './relationDisplay'

describe('nodeKind', () => {
  it('노드 종류마다 다른 테두리 색과 글로 쓴 이름을 준다', () => {
    const kinds = ['TASK', 'DESIGN_ITEM', 'VALIDATION', 'CHANGE_PROPOSAL'].map(
      nodeKind,
    )
    expect(kinds.map((kind) => kind.label)).toEqual([
      '작업',
      '설계 항목',
      '검증',
      '변경 제안',
    ])
    expect(new Set(kinds.map((kind) => kind.color)).size).toBe(4)
  })

  it('모르는 종류는 기타로 보여 주고 이동할 화면을 주지 않는다', () => {
    expect(nodeKind('NEW_KIND')).toMatchObject({ label: '기타' })
    expect(nodeKind('NEW_KIND').color).toMatch(/^#/)
    expect(nodeKind('NEW_KIND').link).toBeUndefined()
  })

  it('객체 기본 속성과 이름이 같은 종류도 기타로 본다', () => {
    expect(nodeKind('constructor').label).toBe('기타')
  })
})

describe('relationLabel', () => {
  it.each([
    ['IMPLEMENTS', '구현'],
    ['CHANGES', '고침'],
    ['UPDATE', '고침'],
    ['VALIDATED_BY', '검증'],
    ['READS', '참고'],
    ['RETIRE', '없앰'],
    ['BLOCKS', '선행'],
    ['RELATES', '관련'],
    ['COMPARED_WITH', '대조'],
    ['DEPENDS_ON', '의존'],
    ['DEPENDENCY', '의존'],
    ['PROPOSED_FROM', '이 작업에서 나온 제안'],
  ])('%s는 %s로 보여 준다', (relation, label) => {
    expect(relationLabel(relation)).toBe(label)
  })

  it('모르는 관계는 받은 값 그대로 보여 준다', () => {
    expect(relationLabel('NEW_RELATION')).toBe('NEW_RELATION')
    expect(relationLabel('constructor')).toBe('constructor')
  })
})

describe('edgeLabel', () => {
  it('확정 전 관계는 관계 이름 대신 후보로 보여 준다', () => {
    expect(edgeLabel({ relation: 'CHANGES', confirmed: true })).toBe('고침')
    expect(edgeLabel({ relation: 'CHANGES', confirmed: false })).toBe('후보')
  })
})

describe('lineLabel', () => {
  it('선 위에는 긴 관계 이름을 짧게 줄여 쓴다', () => {
    expect(lineLabel({ relation: 'PROPOSED_FROM', confirmed: true })).toBe(
      '나온 제안',
    )
    expect(lineLabel({ relation: 'IMPLEMENTS', confirmed: true })).toBe('구현')
  })

  it('모르는 긴 관계도 선 사이 칸을 넘지 않게 자른다', () => {
    expect(
      lineLabel({ relation: 'SOME_VERY_LONG_RELATION', confirmed: true }),
    ).toBe('SOME_VE…')
  })

  it('후보는 관계 이름과 상관없이 후보로 쓴다', () => {
    expect(lineLabel({ relation: 'PROPOSED_FROM', confirmed: false })).toBe(
      '후보',
    )
  })
})

describe('graphLabel', () => {
  const task = { type: 'TASK', label: 'TASK-001 목록 화면' }
  const item = { type: 'DESIGN_ITEM', label: 'REQ-001' }

  it('색을 보지 않아도 종류와 관계를 알 수 있는 문장을 만든다', () => {
    const label = graphLabel([
      { from: task, to: item, relation: 'IMPLEMENTS', confirmed: true },
      {
        from: { type: 'CHANGE_PROPOSAL', label: '빈 화면 안내' },
        to: item,
        relation: 'CHANGES',
        confirmed: false,
      },
    ])
    expect(label).toContain('작업 TASK-001 목록 화면 → 설계 항목 REQ-001: 구현')
    expect(label).toContain(
      '변경 제안 빈 화면 안내 → 설계 항목 REQ-001: 후보, 확정 아님',
    )
  })

  it('이어진 관계가 없으면 없다고 말한다', () => {
    expect(graphLabel([])).toContain('이어진 관계 없음')
  })
})

describe('edgeRoute', () => {
  const lastCol = 3
  const inGap = (x: number, col: number) =>
    x > nodeX(col) + NODE_W && x < nodeX(col) + NODE_W + COLUMN_GAP

  it('이웃한 열을 잇는 선의 이름은 두 열 사이 빈 칸에 온다', () => {
    const route = edgeRoute({ col: 0, row: 0 }, { col: 1, row: 2 }, lastCol)
    expect(inGap(route.labelX, 0)).toBe(true)
    expect(route.d.startsWith(`M${NODE_W},`)).toBe(true)
    expect(route.d.endsWith(`${nodeX(1)},${nodeY(2) + NODE_H / 2}`)).toBe(true)
  })

  it('왼쪽으로 가는 선은 상자의 왼쪽에서 나와 오른쪽 면으로 들어간다', () => {
    const route = edgeRoute({ col: 2, row: 0 }, { col: 1, row: 0 }, lastCol)
    expect(route.d.startsWith(`M${nodeX(2)},`)).toBe(true)
    expect(route.d.endsWith(`${nodeX(1) + NODE_W},${nodeY(0) + NODE_H / 2}`)).toBe(
      true,
    )
  })

  it('두 칸 이상 건너는 선은 사이 열의 상자를 피해 상자 위 빈 줄로 간다', () => {
    const route = edgeRoute({ col: 0, row: 1 }, { col: 2, row: 1 }, lastCol)
    expect(route.labelY).toBeLessThan(nodeY(1))
    expect(route.labelY).toBeGreaterThan(nodeY(0) + NODE_H)
  })

  it('건너는 칸 수가 다른 선은 다른 높이로 가서 겹치지 않는다', () => {
    const two = edgeRoute({ col: 0, row: 0 }, { col: 2, row: 0 }, lastCol)
    const three = edgeRoute({ col: 3, row: 0 }, { col: 0, row: 0 }, lastCol)
    expect(two.labelY).not.toBe(three.labelY)
    expect(three.labelY).toBeGreaterThan(0)
  })

  it('같은 두 열을 오가는 선은 높이와 이름 자리가 달라 이름이 겹치지 않는다', () => {
    const going = edgeRoute({ col: 0, row: 0 }, { col: 2, row: 0 }, lastCol)
    const coming = edgeRoute({ col: 2, row: 0 }, { col: 0, row: 1 }, lastCol)
    expect(going.labelY).not.toBe(coming.labelY)
    expect(Math.abs(going.labelX - coming.labelX)).toBeGreaterThanOrEqual(40)
  })

  it('같은 열의 상자끼리는 옆 빈 칸으로 돌아서 잇는다', () => {
    const route = edgeRoute({ col: 0, row: 0 }, { col: 0, row: 2 }, lastCol)
    expect(inGap(route.labelX, 0)).toBe(true)
    expect(route.labelY).toBeGreaterThan(nodeY(0))
    expect(route.labelY).toBeLessThan(nodeY(2) + NODE_H)
  })

  it('같은 열 관계의 이름은 옆 열로 가는 선의 이름과 겹치지 않는다', () => {
    const same = edgeRoute({ col: 0, row: 0 }, { col: 0, row: 1 }, lastCol)
    const next = edgeRoute({ col: 0, row: 0 }, { col: 1, row: 1 }, lastCol)
    expect(Math.abs(same.labelY - next.labelY)).toBeGreaterThanOrEqual(14)
  })

  it('마지막 열의 같은 열 관계는 그림 밖으로 나가지 않게 왼쪽으로 돈다', () => {
    const route = edgeRoute({ col: 3, row: 0 }, { col: 3, row: 1 }, lastCol)
    expect(inGap(route.labelX, 2)).toBe(true)
  })
})

describe('relationsResponseSchema', () => {
  it('모르는 노드 종류와 관계가 와도 응답을 거절하지 않는다', () => {
    const data = relationsResponseSchema.parse({
      nodes: [{ id: '1', type: 'NEW_KIND', label: '새 종류' }],
      edges: [
        {
          from_id: '1',
          from_type: 'NEW_KIND',
          to_id: '2',
          to_type: 'TASK',
          relation: 'NEW_RELATION',
          confirmed: true,
        },
      ],
    })
    expect(data.nodes[0].type).toBe('NEW_KIND')
  })
})
