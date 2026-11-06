// 설계 관계 그래프(MON-A03)의 표시 변환과 선 경로. 서버가 새 type·relation을 보내도 화면이 깨지지 않게 기본값을 여기서 정한다.
// 객체 대신 Map을 쓴다. 'constructor' 같은 값이 와도 객체 기본 속성을 꺼내지 않는다.

type NodeKind = { label: string; color: string; link?: string }

// 테두리 색은 캔버스 PNL-09-v2-relations 값이다. 글자로도 쓰므로 흰 바탕에서 4.5:1을 넘는 색만 쓴다.
const NODE_KINDS = new Map<string, NodeKind>([
  ['TASK', { label: '작업', color: '#2c291f', link: '작업 보기' }],
  ['DESIGN_ITEM', { label: '설계 항목', color: '#8a6a00', link: '설계 보기' }],
  ['VALIDATION', { label: '검증', color: '#00753a', link: '검증 보기' }],
  [
    'CHANGE_PROPOSAL',
    { label: '변경 제안', color: '#4b3aa8', link: '제안 보기' },
  ],
])
const OTHER_KIND: NodeKind = { label: '기타', color: '#737373' }

// 그래프의 열 순서. 여기 없는 종류는 맨 끝 '기타' 열에 모은다.
export const NODE_TYPES = [...NODE_KINDS.keys()]

export const nodeKind = (type: string) => NODE_KINDS.get(type) ?? OTHER_KIND

const RELATION_LABELS = new Map([
  ['IMPLEMENTS', '구현'],
  ['READS', '참고'],
  ['CHANGES', '고침'],
  ['DEPENDENCY', '의존'],
  ['VALIDATED_BY', '검증'],
  // 서버(RelationGraphService) 관계 이름
  ['PROPOSED_FROM', '이 작업에서 나온 제안'],
  ['UPDATE', '고침'],
  ['RETIRE', '없앰'],
  ['BLOCKS', '선행'],
  ['RELATES', '관련'],
  ['COMPARED_WITH', '대조'],
  ['DEPENDS_ON', '의존'],
])

export const relationLabel = (relation: string) =>
  RELATION_LABELS.get(relation) ?? relation

type EdgeState = { relation: string; confirmed: boolean }

export const edgeLabel = ({ relation, confirmed }: EdgeState) =>
  confirmed ? relationLabel(relation) : '후보'

// 선 위 글자는 열 사이 빈 칸에 들어가야 한다. 다 못 쓴 이름은 선에 올린 title로 본다.
const LINE_LABEL_MAX = 8
export function lineLabel(edge: EdgeState) {
  const label =
    edge.confirmed && edge.relation === 'PROPOSED_FROM'
      ? '나온 제안'
      : edgeLabel(edge)
  return label.length > LINE_LABEL_MAX
    ? `${label.slice(0, LINE_LABEL_MAX - 1)}…`
    : label
}

type NamedNode = { type: string; label: string }

// 그래프 전체의 접근성 이름. 색과 선 모양을 못 봐도 같은 내용을 읽을 수 있게 종류와 관계를 글로 쓴다.
export function graphLabel(
  edges: (EdgeState & { from: NamedNode; to: NamedNode })[],
) {
  const name = (node: NamedNode) => `${nodeKind(node.type).label} ${node.label}`
  const sentences = edges.map(
    (edge) =>
      `${name(edge.from)} → ${name(edge.to)}: ${
        edge.confirmed ? relationLabel(edge.relation) : '후보, 확정 아님'
      }.`,
  )
  return `작업·설계·검증·변경 제안 관계 그래프. ${
    sentences.join(' ') || '이어진 관계 없음.'
  }`
}

export const NODE_W = 172
export const NODE_H = 56
export const COLUMN_GAP = 64
const ROW_GAP = 32
const TOP = 64

export const nodeX = (col: number) => col * (NODE_W + COLUMN_GAP)
export const nodeY = (row: number) => TOP + row * (NODE_H + ROW_GAP)

type Cell = { col: number; row: number }

// 상자 면에서 면으로 잇는 선 경로와 이름 자리. 상자가 없는 열 사이 칸과 행 사이 줄로만 다녀서 이름이 상자에 가리지 않는다.
// ponytail: 같은 두 상자를 잇는 관계가 여럿이면 이름이 겹친다. 그런 응답이 실제로 나오면 이름을 묶는다.
export function edgeRoute(from: Cell, to: Cell, lastCol: number) {
  const fromY = nodeY(from.row) + NODE_H / 2
  const toY = nodeY(to.row) + NODE_H / 2
  const span = Math.abs(to.col - from.col)

  if (span === 0) {
    const side = from.col === lastCol ? -1 : 1
    const x = nodeX(from.col) + (side > 0 ? NODE_W : 0)
    const bulge = x + side * COLUMN_GAP
    return {
      d: `M${x},${fromY} C${bulge},${fromY} ${bulge},${toY} ${x},${toY}`,
      // 곡선의 3/4 지점(t = 0.75). 가운데에 두면 옆 열로 가는 선의 이름과 같은 높이에서 겹친다.
      labelX: x + side * COLUMN_GAP * 0.5625,
      labelY: fromY + (toY - fromY) * 0.84375,
    }
  }

  const dir = Math.sign(to.col - from.col)
  const startX = nodeX(from.col) + (dir > 0 ? NODE_W : 0)
  const endX = nodeX(to.col) + (dir > 0 ? 0 : NODE_W)
  const labelX = (startX + endX) / 2

  if (span === 1) {
    return {
      d: `M${startX},${fromY} C${labelX},${fromY} ${labelX},${toY} ${endX},${toY}`,
      labelX,
      labelY: (fromY + toY) / 2,
    }
  }

  // 사이 열을 건너면 위쪽 상자 바로 위의 빈 줄로 간다. 작업 → 검증과 검증 → 다른 작업처럼 같은 두 열을 오가거나,
  // 제안 → 작업처럼 더 멀리 가는 선이 겹치지 않게 줄 높이와 상자 면의 시작점을 나눈다.
  const lane = span > 2 ? 3 : dir > 0 ? 1 : 2
  const laneY = nodeY(Math.min(from.row, to.row)) - lane * 8
  const startY = fromY - lane * 6
  const endY = toY - lane * 6
  const leaveX = startX + (dir * COLUMN_GAP) / 2
  const enterX = endX - (dir * COLUMN_GAP) / 2
  return {
    d:
      `M${startX},${startY} C${leaveX},${startY} ${leaveX},${laneY} ${startX + dir * COLUMN_GAP},${laneY} ` +
      `L${endX - dir * COLUMN_GAP},${laneY} C${enterX},${laneY} ${enterX},${endY} ${endX},${endY}`,
    // 이름을 시작점 쪽으로 조금 옮겨, 오가는 두 선의 이름이 같은 자리에 오지 않게 한다.
    labelX: labelX - dir * 24,
    labelY: laneY,
  }
}
