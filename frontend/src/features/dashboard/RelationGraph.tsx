import { useId } from 'react'
import {
  COLUMN_GAP,
  NODE_H,
  NODE_TYPES,
  NODE_W,
  edgeLabel,
  graphLabel,
  layoutEdges,
  lineLabel,
  nodeKind,
  nodeX,
  relationLabel,
} from '@/features/relations/relationDisplay'
import { cn } from '@/lib/utils'
import type { RelationsResponse } from './schemas'

// 모르는 종류는 맨 끝 '기타' 열에 모은다.
const columnOf = (type: string) => {
  const index = NODE_TYPES.indexOf(type)
  return index < 0 ? NODE_TYPES.length : index
}

export function RelationGraph({
  data,
  onOpen,
}: {
  data: RelationsResponse
  onOpen: (type: string, id: string) => void
}) {
  const markerId = useId()
  const columnCount = Math.max(
    NODE_TYPES.length,
    ...data.nodes.map((node) => columnOf(node.type) + 1),
  )
  const columns = Array.from({ length: columnCount }, (_, col) =>
    data.nodes.filter((node) => columnOf(node.type) === col).slice(0, 8),
  )
  const visible = columns.flatMap((nodes, col) =>
    nodes.map((node, row) => ({ ...node, col, row })),
  )
  const byKey = new Map(
    visible.map((node) => [`${node.type}:${node.id}`, node]),
  )
  const drawn = data.edges.flatMap((edge) => {
    const from = byKey.get(`${edge.from_type}:${edge.from_id}`),
      to = byKey.get(`${edge.to_type}:${edge.to_id}`)
    return from && to ? [{ ...edge, from, to, text: lineLabel(edge) }] : []
  })
  const rows = Math.max(1, ...columns.map((nodes) => nodes.length))
  // 관계 목록은 그래프에 못 그린 항목도 읽으므로 전체 항목에서 이름을 찾는다. DB 번호는 보이지 않는다.
  const labels = new Map(
    data.nodes.map((node) => [`${node.type}:${node.id}`, node.label]),
  )
  const nameOf = (type: string, id: string) =>
    [nodeKind(type).label, labels.get(`${type}:${id}`)]
      .filter(Boolean)
      .join(' ')
  const { routes, rowTops, height } = layoutEdges(drawn, rows, columnCount - 1)
  const edges = drawn.map((edge, index) => ({ ...edge, route: routes[index] }))
  const width = nodeX(columnCount) - COLUMN_GAP
  return (
    <div className="grid gap-4">
      {/* 범례(캔버스 PNL-09-v2-relations). 후보는 확정된 의존 관계로 보지 않는다. */}
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="w-6 border-t-2 border-current" />
          확인된 관계
        </li>
        <li className="flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className="w-6 border-t-2 border-dashed border-current"
          />
          비비가 찾은 후보 (확정 아님)
        </li>
        <li className="ml-auto">상자를 누르면 그 화면으로 가요</li>
      </ul>
      <div className="overflow-x-auto rounded-xl border bg-card p-3 shadow-xs">
        <svg
          role="img"
          aria-label={graphLabel(edges)}
          // 상자 테두리 두께만큼 양옆을 넓혀 맨 끝 열의 테두리가 잘리지 않게 한다.
          viewBox={`-2 0 ${width + 4} ${height}`}
          className="w-full min-w-[740px]"
        >
          <defs>
            <marker
              id={markerId}
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3"
              orient="auto"
            >
              <path d="M0,0 L0,6 L6,3 z" fill="context-stroke" />
            </marker>
          </defs>
          {columns.map((_, col) => (
            <text
              key={col}
              x={nodeX(col) + NODE_W / 2}
              y="20"
              textAnchor="middle"
              className="fill-muted-foreground text-[12px]"
            >
              {nodeKind(NODE_TYPES[col]).label}
            </text>
          ))}
          {/* 키보드로는 아래 '전체 항목과 관계 읽기' 목록의 버튼으로 같은 화면에 간다. */}
          {visible.map((node) => {
            const kind = nodeKind(node.type)
            const x = nodeX(node.col)
            const y = rowTops[node.row]
            return (
              <g
                key={`${node.type}:${node.id}`}
                className={kind.link && 'cursor-pointer'}
                onClick={
                  kind.link ? () => onOpen(node.type, node.id) : undefined
                }
              >
                <title>
                  {kind.label} {node.label}
                </title>
                <rect
                  x={x}
                  y={y}
                  width={NODE_W}
                  height={NODE_H}
                  rx="12"
                  stroke={kind.color}
                  strokeWidth="2"
                  className="fill-card"
                />
                <text
                  x={x + 12}
                  y={y + 17}
                  fill={kind.color}
                  className="text-[11px] font-bold"
                >
                  {kind.label}
                </text>
                <foreignObject
                  x={x + 12}
                  y={y + 22}
                  width={NODE_W - 24}
                  height={NODE_H - 26}
                >
                  <div className="line-clamp-2 text-xs/[15px] break-keep text-foreground">
                    {node.label}
                  </div>
                </foreignObject>
              </g>
            )
          })}
          {/* 선은 상자 면에서 시작하고 상자 사이로만 다녀서 상자 위에 그려도 상자를 가리지 않는다. */}
          {edges.map((edge, index) => (
            <g
              key={`${edge.from.type}:${edge.from.id}:${edge.to.type}:${edge.to.id}:${index}`}
            >
              <title>
                {edge.from.label} → {edge.to.label}: {edgeLabel(edge)}
              </title>
              <path
                d={edge.route.d}
                stroke={edge.confirmed ? '#57534e' : '#b8b3a5'}
                strokeWidth="1.5"
                strokeDasharray={edge.confirmed ? undefined : '6 5'}
                fill="none"
                markerEnd={`url(#${markerId})`}
              />
            </g>
          ))}
          {/* 이름은 다른 선에 가리지 않게 모든 선 뒤에 그린다. */}
          {edges.map((edge, index) => (
            <text
              key={`${edge.from.type}:${edge.from.id}:${edge.to.type}:${edge.to.id}:${index}`}
              x={edge.route.labelX}
              y={edge.route.labelY}
              textAnchor="middle"
              dominantBaseline="central"
              className={cn(
                'stroke-card stroke-[6px] text-[11px] font-medium [paint-order:stroke]',
                edge.confirmed ? 'fill-[#57534e]' : 'fill-muted-foreground',
              )}
            >
              {edge.text}
            </text>
          ))}
        </svg>
      </div>
      {visible.length < data.nodes.length && (
        <p className="text-xs text-muted-foreground">
          그래프는 종류별 최대 8개 항목을 표시해요. 전체 {data.nodes.length}개
          중 {visible.length}개와 그 사이의 관계 {edges.length}개를 보여요.
        </p>
      )}
      <details className="rounded-xl border bg-card p-4 shadow-xs">
        <summary className="cursor-pointer text-sm font-medium">
          전체 항목과 관계 읽기
        </summary>
        <ul className="mt-4 grid gap-2 text-sm">
          {data.nodes.map((node) => (
            <li
              key={`${node.type}:${node.id}`}
              className="flex flex-wrap items-center gap-2"
            >
              <span className="text-xs text-muted-foreground">
                {nodeKind(node.type).label}
              </span>
              {nodeKind(node.type).link ? (
                <button
                  type="button"
                  className="text-primary hover:underline"
                  onClick={() => onOpen(node.type, node.id)}
                >
                  {node.label}
                </button>
              ) : (
                <span>{node.label}</span>
              )}
            </li>
          ))}
        </ul>
        <ul className="mt-4 grid gap-2 border-t pt-4 text-xs text-muted-foreground">
          {data.edges.map((edge, index) => (
            <li key={index}>
              {nameOf(edge.from_type, edge.from_id)} →{' '}
              {nameOf(edge.to_type, edge.to_id)} ·{' '}
              {relationLabel(edge.relation)} ·{' '}
              {edge.confirmed ? '확인됨' : '후보'}
            </li>
          ))}
        </ul>
      </details>
    </div>
  )
}
