import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { CircleAlert, Search } from 'lucide-react'
import type { RelationNode } from '@/api/contracts/common'
import { dashboardApi } from '@/api/services/dashboard'
import { readAllPages } from '@/api/readAllPages'
import { changesApi, tasksApi } from '@/api/services'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { edgeLabel, nodeKind } from '@/features/relations/relationDisplay'
import { cn } from '@/lib/utils'
import { LIST_SELECTED } from '@/lib/display'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { RelationGraph } from './RelationGraph'

// 작업·설계 관계 · 단축 바 5번 단독 창. 왼쪽에서 작업을 고르면 그 작업 기준으로 설계 항목·검증·변경 제안 관계를 그린다.
// 관계 API(MON-A03)는 기준 작업이나 설계 항목이 있어야 조회돼서 작업 목록에서 골라 그린다(백엔드 메모 4).
export function RelationsView({
  projectId,
  hasBaseline,
}: {
  projectId: string
  /** 기준 설계가 발행됐는지. 모르면(undefined) 기준 안내를 붙이지 않는다. */
  hasBaseline?: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<string | null>(null)
  const tasks = useQuery({
    queryKey: ['projects', projectId, 'relation-tasks'],
    // 작업 목록 API에는 검색 조건이 없어 마지막 페이지까지 읽고 화면에서 찾는다. 100개만 읽으면 101번째 이후 작업의
    // 관계를 볼 수 없었다(PR #72 재전수 R2-TC-02).
    queryFn: () =>
      readAllPages((cursor) =>
        tasksApi.list(projectId, { cursor, limit: 100 }),
      ),
  })
  const all = tasks.data?.items ?? []
  const shown = all.filter((task) =>
    `${task.key} ${task.current_plan_revision.plan.title}`.includes(
      search.trim(),
    ),
  )
  const selected = picked ?? shown[0]?.id
  const selectedTask = all.find((task) => task.id === selected)
  const relations = useQuery({
    queryKey: ['projects', projectId, 'relations', selected],
    queryFn: () => dashboardApi.relations(projectId, { task_id: selected! }),
    enabled: !!selected,
  })
  // 관계 응답에는 변경 제안 상태가 없어 진행 중 제안 목록과 맞춰 경고 띠를 띄운다.
  const open = useQuery({
    queryKey: ['projects', projectId, 'relation-changes'],
    queryFn: () =>
      changesApi.list(projectId, { group: 'IN_PROGRESS', limit: 50 }),
  })
  // 영향 경고: 진행 중 제안이 이 작업이 참조하는 설계 항목을 바꿀 때만 띄운다. 작업 기준 응답의
  // PROPOSED_FROM(제안 → 작업)은 '이 작업에서 나온 제안'이라 영향이 아니다. 서버는 제안 → 설계 항목(UPDATE·RETIRE)을
  // 설계 항목 기준 조회에서만 주므로, 참조 항목마다 항목 기준 관계도 받는다(PR #72 mock/live 전수 대조 LA-TC-10).
  const referenced = [
    ...new Set(
      relations.data?.edges
        .filter(
          (edge) =>
            edge.from_type === 'TASK' &&
            edge.from_id === selected &&
            edge.to_type === 'DESIGN_ITEM',
        )
        .map((edge) => edge.to_id) ?? [],
    ),
  ]
  const itemRelations = useQueries({
    queries: referenced.map((id) => ({
      queryKey: ['projects', projectId, 'relations', 'item', id],
      queryFn: () => dashboardApi.relations(projectId, { design_item_id: id }),
    })),
  })
  const warning = [relations.data, ...itemRelations.map((query) => query.data)]
    .flatMap((data) =>
      (data?.edges ?? [])
        .filter(
          (edge) =>
            edge.from_type === 'CHANGE_PROPOSAL' &&
            edge.to_type === 'DESIGN_ITEM' &&
            referenced.includes(edge.to_id) &&
            open.data?.items.some((entry) => entry.id === edge.from_id),
        )
        .map((edge) => ({ edge, nodes: data!.nodes })),
    )
    .at(0)
  const nodeLabel = (nodes: RelationNode[], type: string, id: string) =>
    nodes.find((node) => node.type === type && node.id === id)?.label ?? id
  const label = (type: string, id: string) =>
    relations.data?.nodes.find((node) => node.type === type && node.id === id)
      ?.label ?? id
  const go = (type: RelationNode['type'], id: string) =>
    type === 'CHANGE_PROPOSAL'
      ? openPane('changes', id)
      : type === 'DESIGN_ITEM'
        ? openPane('planning')
        : // 선행 작업 같은 작업 대상은 누른 작업을, 검증은 지금 고른 작업을 연다.
          openPane('tasks', type === 'TASK' ? id : selected)
  return (
    <div className="grid gap-5 lg:grid-cols-[240px_minmax(0,1fr)]">
      <div className="grid content-start gap-2">
        <label className="relative">
          <span className="sr-only">작업 찾기</span>
          <Search className="absolute top-2 left-2.5 size-4 text-muted-foreground" />
          <Input
            className="pl-8"
            placeholder="작업 번호·제목"
            disabled={!all.length}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <AsyncState query={tasks}>
          {() =>
            shown.length ? (
              <ul className="grid gap-1.5">
                {shown.map((task) => (
                  <li key={task.id}>
                    <button
                      type="button"
                      aria-current={task.id === selected}
                      onClick={() => setPicked(task.id)}
                      className={cn(
                        'grid w-full gap-0.5 rounded-xl border bg-card px-3 py-2.5 text-left text-sm shadow-xs hover:bg-muted/40',
                        task.id === selected && LIST_SELECTED,
                      )}
                    >
                      <span className="text-xs text-muted-foreground">
                        {task.key}
                      </span>
                      <strong className="font-semibold">
                        {task.current_plan_revision.plan.title}
                      </strong>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">
                {all.length ? '찾는 작업이 없어요.' : '아직 작업이 없어요.'}
              </p>
            )
          }
        </AsyncState>
      </div>
      <div className="grid min-w-0 content-start gap-4">
        {/* 캔버스 PNL-09-v2-relations 오른쪽 위. 관계를 못 읽어도 작업 현황으로 갈 수 있게 관계 응답 밖에 둔다. */}
        {selectedTask && (
          <div className="flex items-start justify-between gap-4">
            <h3 className="flex min-w-0 flex-wrap items-center gap-1.5 text-lg/[26px] font-bold">
              {selectedTask.current_plan_revision.plan.title}
              <StatusPill value={selectedTask.status} />
            </h3>
            <Button
              variant="outline"
              className="shrink-0"
              onClick={() => openPane('tasks', selectedTask.id)}
            >
              작업 현황에서 보기
            </Button>
          </div>
        )}
        {!all.length && !tasks.isPending ? (
          <EmptyState>
            <strong className="text-base text-foreground">
              아직 그릴 관계가 없어요
            </strong>
            작업 계획을 올리면 그 작업이 쓰는 설계 항목, 계획 검증, 관련 변경
            제안을 이어서 그려요.
            {/* 기준이 이미 있으면 이 안내는 틀린 말이라 기준이 없을 때만 붙인다(S15P31A108-346). */}
            {hasBaseline === false &&
              ' 기준 v1이 생기기 전에는 작업 계획을 올릴 수 없어요.'}
            <Button size="sm" onClick={() => openPane('tasks')}>
              작업 계획 등록
            </Button>
          </EmptyState>
        ) : (
          selected && (
            <AsyncState query={relations}>
              {(data) => (
                <div className="grid gap-4">
                  {warning &&
                    [warning].map(({ edge, nodes }) => (
                      <div
                        key={edge.from_id}
                        className="flex items-center gap-3 rounded-[14px] bg-[#fdf8e6] px-3.5 py-3"
                      >
                        <span className="grid flex-1 gap-0.5">
                          <strong className="flex items-center gap-1.5 text-sm">
                            <CircleAlert className="size-4 shrink-0" />
                            검토 중인 변경 제안이 이 작업의 근거 설계를 고쳐요
                          </strong>
                          <span className="text-[13px] leading-[19px] text-muted-foreground">
                            ‘{nodeLabel(nodes, edge.from_type, edge.from_id)}’
                            제안이 승인되면{' '}
                            {nodeLabel(nodes, edge.to_type, edge.to_id)}의 새
                            버전이 기준이 되고, 이 작업은 재검증 대기로
                            넘어가요.
                          </span>
                        </span>
                        <Button
                          variant="outline"
                          className="shrink-0"
                          onClick={() => openPane('changes', edge.from_id)}
                        >
                          제안 보기
                        </Button>
                      </div>
                    ))}
                  <RelationGraph data={data} onOpen={go} />
                  <section
                    aria-labelledby="relation-list"
                    className="grid gap-2"
                  >
                    <h3 id="relation-list" className="text-sm font-semibold">
                      관계 목록
                    </h3>
                    {data.edges.length ? (
                      <ul className="grid gap-1.5 text-[13px]">
                        {data.edges.map((edge, index) => (
                          <li
                            key={index}
                            className={cn(
                              'grid grid-cols-[auto_minmax(0,1fr)_auto_auto] items-center gap-2.5 rounded-xl border bg-card px-3.5 py-2.5',
                              !edge.confirmed && 'border-dashed',
                            )}
                          >
                            <span className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] font-semibold">
                              {edgeLabel(edge)}
                            </span>
                            {label(edge.from_type, edge.from_id)} →{' '}
                            {label(edge.to_type, edge.to_id)}
                            <span
                              className={cn(
                                'rounded-full px-2 py-0.5 text-[11px] font-semibold',
                                edge.confirmed
                                  ? 'bg-[#e8f3ec] text-[#00753a]'
                                  : 'bg-muted text-muted-foreground',
                              )}
                            >
                              {edge.confirmed
                                ? '확인된 관계'
                                : '검토할 후보 · 확정 아님'}
                            </span>
                            {/* API 없음: 비비가 이 후보를 본 이유는 관계 응답(MON-A03)에 아직 없다. */}
                            {edge.confirmed ? (
                              nodeKind(edge.to_type).link && (
                                <button
                                  type="button"
                                  className="text-xs font-semibold whitespace-nowrap hover:underline"
                                  onClick={() => go(edge.to_type, edge.to_id)}
                                >
                                  {nodeKind(edge.to_type).link} ›
                                </button>
                              )
                            ) : (
                              <Button
                                size="xs"
                                variant="outline"
                                title="계획에 근거 설계로 넣으면 확정돼요"
                                onClick={() => openPane('tasks')}
                              >
                                작업 계획 등록
                              </Button>
                            )}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="text-sm text-muted-foreground">
                        이 작업에 이어진 관계가 아직 없어요.
                      </p>
                    )}
                  </section>
                </div>
              )}
            </AsyncState>
          )
        )}
      </div>
    </div>
  )
}
