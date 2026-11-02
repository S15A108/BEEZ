import { useId } from 'react'
import type { RelationNode } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { RelationsResponse } from './schemas'

const NODE_TYPES = [
  'TASK',
  'DESIGN_ITEM',
  'VALIDATION',
  'CHANGE_PROPOSAL',
] as const

const NODE_LABELS: Record<RelationNode['type'], string> = {
  TASK: '작업',
  DESIGN_ITEM: '설계 항목',
  VALIDATION: '검증',
  CHANGE_PROPOSAL: '변경 요청',
}

export function RelationGraph({ data }: { data: RelationsResponse }) {
  const markerId = useId()
  const openPane = useWorkspaceStore((state) => state.openPane)
  const visible = NODE_TYPES.flatMap((type, column) =>
    data.nodes
      .filter((node) => node.type === type)
      .slice(0, 8)
      .map((node, row) => ({
        ...node,
        x: 10 + column * 220,
        y: 45 + row * 82,
      })),
  )
  const byKey = new Map(
    visible.map((node) => [`${node.type}:${node.id}`, node]),
  )
  const edges = data.edges.flatMap((edge) => {
    const from = byKey.get(`${edge.from_type}:${edge.from_id}`),
      to = byKey.get(`${edge.to_type}:${edge.to_id}`)
    return from && to ? [{ ...edge, from, to }] : []
  })
  const height =
    60 +
    Math.max(
      1,
      ...NODE_TYPES.map(
        (type) => visible.filter((node) => node.type === type).length,
      ),
    ) *
      82
  const selectNode = (node: RelationNode) => {
    if (node.type === 'TASK') openPane('tasks', node.id)
    else if (node.type === 'CHANGE_PROPOSAL') openPane('changes', node.id)
    else if (node.type === 'DESIGN_ITEM') openPane('planning')
  }
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
          aria-label="작업·설계·검증·변경 요청 관계 그래프"
          viewBox={`0 0 880 ${height}`}
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
              <path d="M0,0 L0,6 L6,3 z" fill="currentColor" />
            </marker>
          </defs>
          {NODE_TYPES.map((type, index) => (
            <text
              key={type}
              x={100 + index * 220}
              y="20"
              textAnchor="middle"
              className="fill-muted-foreground text-[12px]"
            >
              {NODE_LABELS[type]}
            </text>
          ))}
          {edges.map((edge, index) => (
            <path
              key={`${edge.from.type}:${edge.from.id}:${edge.to.type}:${edge.to.id}:${index}`}
              d={`M${edge.from.x + 90},${edge.from.y + 25} L${edge.to.x + 90},${edge.to.y + 25}`}
              className="text-muted-foreground/60"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeDasharray={edge.confirmed ? undefined : '5 5'}
              fill="none"
              markerEnd={`url(#${markerId})`}
            >
              <title>
                {edge.from.label} → {edge.to.label}: {edge.relation} (
                {edge.confirmed ? '확인됨' : '후보'})
              </title>
            </path>
          ))}
          {/* 키보드로는 아래 '전체 항목과 관계 읽기' 목록의 버튼으로 같은 화면에 간다. */}
          {visible.map((node) => (
            <g
              key={`${node.type}:${node.id}`}
              className={
                node.type === 'VALIDATION' ? undefined : 'cursor-pointer'
              }
              onClick={
                node.type === 'VALIDATION' ? undefined : () => selectNode(node)
              }
            >
              <rect
                x={node.x}
                y={node.y}
                width="180"
                height="50"
                rx="10"
                className="fill-card stroke-border"
              />
              <text
                x={node.x + 90}
                y={node.y + 22}
                textAnchor="middle"
                className="fill-foreground text-[12px]"
              >
                {node.label.length > 17
                  ? `${node.label.slice(0, 16)}…`
                  : node.label}
              </text>
              <text
                x={node.x + 90}
                y={node.y + 38}
                textAnchor="middle"
                className="fill-muted-foreground text-[10px]"
              >
                #{node.id}
              </text>
              <title>{node.label}</title>
            </g>
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
                {NODE_LABELS[node.type]} #{node.id}
              </span>
              {node.type === 'VALIDATION' ? (
                <span>{node.label}</span>
              ) : (
                <button
                  type="button"
                  className="text-primary hover:underline"
                  onClick={() => selectNode(node)}
                >
                  {node.label}
                </button>
              )}
            </li>
          ))}
        </ul>
        <ul className="mt-4 grid gap-2 border-t pt-4 text-xs text-muted-foreground">
          {data.edges.map((edge, index) => (
            <li key={index}>
              {NODE_LABELS[edge.from_type]} #{edge.from_id} →{' '}
              {NODE_LABELS[edge.to_type]} #{edge.to_id} · {edge.relation} ·{' '}
              {edge.confirmed ? '확인됨' : '후보'}
            </li>
          ))}
        </ul>
      </details>
    </div>
  )
}
