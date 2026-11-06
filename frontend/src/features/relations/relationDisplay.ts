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
const HEADER = 32 // 열 제목 자리
const LANE = 14 // 선 하나와 그 이름이 들어가는 줄 높이

export const nodeX = (col: number) => col * (NODE_W + COLUMN_GAP)

type Cell = { col: number; row: number }

// 이름이 들어갈 폭의 어림값(11px 글자와 양옆 테두리).
const labelWidth = (text: string) =>
  [...text].reduce((width, char) => width + (char > '⹿' ? 11 : 7), 6)

// 상자 면에서 면으로 잇는 선 경로와 이름 자리. 상자가 없는 열 사이 칸과 행 사이 줄로만 다닌다.
function edgeRoute(
  from: Cell,
  to: Cell,
  lastCol: number,
  rowTops: number[],
  lane = 0,
) {
  const fromY = rowTops[from.row] + NODE_H / 2
  const toY = rowTops[to.row] + NODE_H / 2
  const span = Math.abs(to.col - from.col)

  if (span === 0) {
    const side = from.col === lastCol ? -1 : 1
    const x = nodeX(from.col) + (side > 0 ? NODE_W : 0)
    const bulge = x + side * COLUMN_GAP
    return {
      d: `M${x},${fromY} C${bulge},${fromY} ${bulge},${toY} ${x},${toY}`,
      // 곡선의 3/4 지점(t = 0.75)
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

  // 줄마다 상자 면의 시작점도 달리해 나오는 곳에서부터 선이 갈린다.
  const laneY = rowTops[Math.min(from.row, to.row)] - 9 - lane * LANE
  const anchor = Math.min((lane + 1) * 6, NODE_H / 2 - 6)
  const startY = fromY - anchor
  const endY = toY - anchor
  const leaveX = startX + (dir * COLUMN_GAP) / 2
  const enterX = endX - (dir * COLUMN_GAP) / 2
  return {
    d:
      `M${startX},${startY} C${leaveX},${startY} ${leaveX},${laneY} ${startX + dir * COLUMN_GAP},${laneY} ` +
      `L${endX - dir * COLUMN_GAP},${laneY} C${enterX},${laneY} ${enterX},${endY} ${endX},${endY}`,
    labelX,
    labelY: laneY,
  }
}

// 그래프의 행 높이와 선 경로, 이름 자리를 함께 정한다. text는 선 위에 쓸 이름이다.
export function layoutEdges(
  edges: { from: Cell; to: Cell; text: string }[],
  rows: number,
  lastCol: number,
) {
  // 사이 열을 건너는 선은 위쪽 상자 바로 위의 빈 줄을 지난다. 짧게 건너는 선부터 상자 가까운 쪽에 한 줄씩 줘서,
  // 같은 작업에서 나온 제안 두 개처럼 같은 길을 가는 선도 선과 이름이 겹치지 않게 한다.
  const crossing = edges
    .map((edge, index) => ({
      index,
      span: Math.abs(edge.to.col - edge.from.col),
      gutter: Math.min(edge.from.row, edge.to.row),
    }))
    .filter(({ span }) => span > 1)
    .sort((a, b) => a.span - b.span)
  const laneCounts = Array.from({ length: rows }, () => 0)
  const lanes = new Map(
    crossing.map(({ index, gutter }) => [index, laneCounts[gutter]++]),
  )

  // 줄이 많으면 그 빈 줄을 넓힌다.
  const rowTops: number[] = []
  let bottom = HEADER
  for (const count of laneCounts) {
    rowTops.push(bottom + Math.max(ROW_GAP, count * LANE + 4))
    bottom = rowTops[rowTops.length - 1] + NODE_H
  }

  const routes = edges.map((edge, index) => ({
    ...edgeRoute(edge.from, edge.to, lastCol, rowTops, lanes.get(index)),
    labelW: labelWidth(edge.text),
  }))

  // 빈 줄의 이름은 자기 줄에 두고, 열 사이 칸의 이름은 다른 이름과 겹치면 위아래로 비킨다. x는 그대로라 상자에 닿지 않는다.
  const placed = routes.filter((_, index) => lanes.has(index))
  const clashes = (route: (typeof routes)[number]) =>
    route.labelY - 7 < HEADER ||
    placed.some(
      (other) =>
        Math.abs(route.labelX - other.labelX) <
          (route.labelW + other.labelW) / 2 &&
        Math.abs(route.labelY - other.labelY) < LANE,
    )
  routes.forEach((route, index) => {
    if (lanes.has(index)) return
    const start = route.labelY
    for (let step = 1; clashes(route); step++)
      route.labelY = start + (step % 2 ? LANE : -LANE) * Math.ceil(step / 2)
    placed.push(route)
  })

  const height =
    Math.max(bottom, ...routes.map((route) => route.labelY + 7)) + 8
  return { routes, rowTops, height }
}
