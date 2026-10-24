import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Activity, Network, Wallet } from 'lucide-react'
import { dashboardApi } from '@/api/services/dashboard'
import { membersApi } from '@/api/services/members'
import type { RelationNode, UsageBucket } from '@/api/contracts/common'
import type { RelationsResponse } from './schemas'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { statusLabel } from '@/lib/display'
import { useWorkspaceStore } from '@/stores/workspaceStore'

const calendarDay = (date: Date) =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
const localDate = (day: string, end = false) =>
  new Date(
    `${day}T${end ? '23:59:59.999' : '00:00:00.000'}+09:00`,
  ).toISOString()
const stamp = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))
const dateRangeSchema = z
  .object({
    from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '시작일을 선택해 주세요.'),
    to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '종료일을 선택해 주세요.'),
  })
  .refine((range) => range.from <= range.to, {
    path: ['to'],
    message: '종료일은 시작일 이후여야 해요.',
  })
type DateRange = z.infer<typeof dateRangeSchema>
const FEATURE_LABELS: Record<string, string> = {
  DOCUMENT_PARSE: '문서 분석',
  TASK_VALIDATION: '작업 검증',
  CHANGE_IMPACT: '변경 영향 분석',
  EMBEDDING: '검색 색인',
  BEE_CHAT: '비비 대화',
  BEE_DRAFT: '비비 초안',
  TASK_ASSIGNMENT: '작업 배정 추천',
  MEETING_ANALYSIS: '회의 분석',
}
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

export function ActivityPanel({
  projectId,
}: {
  projectId: string
  isLeader?: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [range, setRange] = useState<DateRange>(() => ({
    from: calendarDay(new Date(Date.now() - 6 * 86400000)),
    to: calendarDay(new Date()),
  }))
  const [cursor, setCursor] = useState<string>()
  const [workCursor, setWorkCursor] = useState<string>()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<DateRange>({
    resolver: zodResolver(dateRangeSchema),
    defaultValues: range,
  })
  const usage = useQuery({
    queryKey: ['projects', projectId, 'usage', range],
    queryFn: () =>
      dashboardApi.usage(projectId, {
        from: localDate(range.from),
        to: localDate(range.to, true),
      }),
  })
  const relations = useQuery({
    queryKey: ['projects', projectId, 'relations'],
    queryFn: () => dashboardApi.relations(projectId),
  })
  const activities = useQuery({
    queryKey: ['projects', projectId, 'activities', cursor],
    queryFn: () => dashboardApi.activities(projectId, { cursor, limit: 20 }),
  })
  const work = useQuery({
    queryKey: ['projects', projectId, 'agent-work', workCursor],
    queryFn: () =>
      dashboardApi.agentWork(projectId, { cursor: workCursor, limit: 20 }),
  })
  const members = useQuery({
    queryKey: ['projects', projectId, 'members'],
    queryFn: () => membersApi.list(projectId),
  })
  const memberName = (memberId: string) =>
    members.data?.items.find((member) => member.id === memberId)?.name ??
    `팀원 #${memberId}`
  const actorName = (accountId?: string) =>
    accountId
      ? (members.data?.items.find((member) => member.account_id === accountId)
          ?.name ?? `회원 #${accountId}`)
      : '시스템'
  return (
    <div className="grid gap-6">
      <header>
        <p className="text-xs font-medium tracking-widest text-muted-foreground">
          PROJECT PULSE
        </p>
        <h2 className="mt-2 text-2xl font-semibold">활동·비용</h2>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">
          팀의 작업 흐름과 설계 관계, 프로젝트에 기록된 사용량을 확인해요.
        </p>
      </header>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Wallet className="size-4" />
            기간별 사용량
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-5">
          <form
            className="flex flex-wrap items-end gap-3"
            noValidate
            onSubmit={handleSubmit((values) => setRange(values))}
          >
            <div className="grid gap-2">
              <Label htmlFor="usage-from">시작일 (한국시간)</Label>
              <Input id="usage-from" type="date" {...register('from')} />
              {errors.from && (
                <p role="alert" className="text-xs text-destructive">
                  {errors.from.message}
                </p>
              )}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="usage-to">종료일 (한국시간)</Label>
              <Input id="usage-to" type="date" {...register('to')} />
              {errors.to && (
                <p role="alert" className="text-xs text-destructive">
                  {errors.to.message}
                </p>
              )}
            </div>
            <Button type="submit" variant="outline">
              기간 적용
            </Button>
          </form>
          <AsyncState query={usage}>
            {(data) => (
              <>
                <dl className="grid grid-cols-2 gap-3 xl:grid-cols-4">
                  {[
                    {
                      label: '입력 토큰',
                      value: data.input_tokens.toLocaleString('ko-KR'),
                    },
                    {
                      label: '출력 토큰',
                      value: data.output_tokens.toLocaleString('ko-KR'),
                    },
                    {
                      label: '호출 횟수',
                      value: data.call_count.toLocaleString('ko-KR'),
                    },
                    {
                      label: '예상 비용',
                      value:
                        data.estimated_cost_amount === undefined
                          ? '미보고'
                          : `${data.estimated_cost_amount} ${data.currency ?? ''}`.trim(),
                    },
                  ].map((metric) => (
                    <div
                      key={metric.label}
                      className="rounded-xl border bg-muted/30 p-4"
                    >
                      <dt className="text-xs text-muted-foreground">
                        {metric.label}
                      </dt>
                      <dd className="mt-2 font-mono text-xl font-semibold">
                        {metric.value}
                      </dd>
                    </div>
                  ))}
                </dl>
                <p className="text-xs leading-5 text-muted-foreground">
                  BEEZ에 기록된 호출을 집계한 값이에요. 외부 에이전트의 개인
                  사용량과 실제 청구액은 포함하지 않아요.
                </p>
                <UsageTable
                  title="기능별 사용량"
                  rows={data.by_feature}
                  currency={data.currency}
                  label={(key) => FEATURE_LABELS[key] ?? key}
                />
                <div className="grid gap-5 xl:grid-cols-2">
                  <UsageTable
                    title="작업별 사용량"
                    rows={data.by_task}
                    currency={data.currency}
                    label={(key) => `작업 #${key}`}
                    onSelect={(id) => openPane('tasks', id)}
                  />
                  <UsageTable
                    title="검증별 사용량"
                    rows={data.by_validation}
                    currency={data.currency}
                    label={(key) => `검증 #${key}`}
                  />
                </div>
              </>
            )}
          </AsyncState>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">에이전트 작업 현황</CardTitle>
          <p className="text-xs leading-5 text-muted-foreground">
            연결 여부, 작업 상태, 계획 검증 결과를 각각 확인해요.
          </p>
        </CardHeader>
        <CardContent>
          <AsyncState query={work}>
            {(data) =>
              data.items.length ? (
                <>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="border-b text-xs text-muted-foreground">
                        <tr>
                          <th className="p-3 font-medium">팀원·연결</th>
                          <th className="p-3 font-medium">현재 작업</th>
                          <th className="p-3 font-medium">작업 상태</th>
                          <th className="p-3 font-medium">계획 검증</th>
                          <th className="p-3 font-medium">대기 이유</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.items.map((item) => {
                          const member = members.data?.items.find(
                            (entry) => entry.id === item.member_id,
                          )
                          return (
                            <tr
                              key={`${item.member_id}:${item.agent_session_id ?? ''}`}
                              className="border-b last:border-0"
                            >
                              <td className="p-3">
                                <strong className="block font-medium">
                                  {memberName(item.member_id)}
                                </strong>
                                <small className="mt-1 block text-muted-foreground">
                                  {member
                                    ? member.agent_connected
                                      ? '연결됨'
                                      : '미연결'
                                    : '연결 상태 미확인'}
                                </small>
                              </td>
                              <td className="p-3">
                                {item.current_task_id ? (
                                  <Button
                                    variant="link"
                                    className="h-auto p-0"
                                    onClick={() =>
                                      openPane('tasks', item.current_task_id)
                                    }
                                  >
                                    작업 #{item.current_task_id}
                                  </Button>
                                ) : (
                                  '보고된 작업 없음'
                                )}
                              </td>
                              <td className="p-3">
                                {item.task_status ? (
                                  <StatusPill value={item.task_status} />
                                ) : (
                                  '미보고'
                                )}
                              </td>
                              <td className="p-3">
                                {item.validation_status ? (
                                  <StatusPill value={item.validation_status} />
                                ) : (
                                  '미보고'
                                )}
                              </td>
                              <td className="p-3">
                                {item.waiting_reason
                                  ? statusLabel(item.waiting_reason)
                                  : '—'}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                  {data.has_more && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="mt-4"
                      onClick={() => setWorkCursor(data.next_cursor)}
                    >
                      다음 작업 현황
                    </Button>
                  )}
                  {workCursor && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="mt-4"
                      onClick={() => setWorkCursor(undefined)}
                    >
                      처음 현황
                    </Button>
                  )}
                </>
              ) : (
                <EmptyState>보고된 에이전트 작업이 없어요.</EmptyState>
              )
            }
          </AsyncState>
          {members.error && (
            <p className="mt-3 text-xs text-destructive">
              팀원 이름과 연결 상태를 불러오지 못했어요.{' '}
              <button
                type="button"
                className="underline"
                onClick={() => void members.refetch()}
              >
                다시 불러오기
              </button>
            </p>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Network className="size-4" />
            작업·설계 관계
          </CardTitle>
        </CardHeader>
        <CardContent>
          <AsyncState query={relations}>
            {(data) =>
              data.nodes.length ? (
                <RelationGraph data={data} />
              ) : (
                <EmptyState>등록된 작업과 설계 관계가 없어요.</EmptyState>
              )
            }
          </AsyncState>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Activity className="size-4" />
            활동 타임라인
          </CardTitle>
        </CardHeader>
        <CardContent>
          <AsyncState query={activities}>
            {(data) =>
              data.items.length ? (
                <>
                  <ol className="grid gap-5 border-l pl-5">
                    {data.items.map((item) => (
                      <li key={item.id} className="relative">
                        <span className="absolute top-1.5 -left-[1.53rem] size-2 rounded-full bg-primary" />
                        <div className="flex flex-wrap justify-between gap-2">
                          <strong className="text-sm font-medium">
                            {actorName(item.actor_account_id)} ·{' '}
                            {statusLabel(item.type)}
                          </strong>
                          <time
                            className="text-xs text-muted-foreground"
                            dateTime={item.created_at}
                          >
                            {stamp(item.created_at)}
                          </time>
                        </div>
                        <p className="mt-2 text-sm leading-6 whitespace-pre-wrap">
                          {item.result}
                        </p>
                        <div className="mt-2 flex flex-wrap gap-3">
                          {item.task_id && (
                            <Button
                              variant="link"
                              className="h-auto p-0 text-xs"
                              onClick={() => openPane('tasks', item.task_id)}
                            >
                              작업 #{item.task_id}
                            </Button>
                          )}
                          {item.change_proposal_id && (
                            <Button
                              variant="link"
                              className="h-auto p-0 text-xs"
                              onClick={() =>
                                openPane('changes', item.change_proposal_id)
                              }
                            >
                              변경 요청 #{item.change_proposal_id}
                            </Button>
                          )}
                          {item.baseline_id && (
                            <Button
                              variant="link"
                              className="h-auto p-0 text-xs"
                              onClick={() => openPane('planning')}
                            >
                              기준 설계 #{item.baseline_id}
                            </Button>
                          )}
                        </div>
                      </li>
                    ))}
                  </ol>
                  {data.has_more && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="mt-5"
                      onClick={() => setCursor(data.next_cursor)}
                    >
                      다음 활동
                    </Button>
                  )}
                  {cursor && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="mt-5"
                      onClick={() => setCursor(undefined)}
                    >
                      최신 활동
                    </Button>
                  )}
                </>
              ) : (
                <EmptyState>아직 기록된 프로젝트 활동이 없어요.</EmptyState>
              )
            }
          </AsyncState>
        </CardContent>
      </Card>
    </div>
  )
}

function UsageTable({
  title,
  rows,
  currency,
  label,
  onSelect,
}: {
  title: string
  rows: UsageBucket[]
  currency?: string
  label: (key: string) => string
  onSelect?: (id: string) => void
}) {
  return (
    <section className="min-w-0">
      <h3 className="mb-3 text-sm font-medium">{title}</h3>
      {rows.length ? (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="border-b text-muted-foreground">
              <tr>
                <th className="p-2 font-medium">구분</th>
                <th className="p-2 text-right font-medium">입력</th>
                <th className="p-2 text-right font-medium">출력</th>
                <th className="p-2 text-right font-medium">호출</th>
                <th className="p-2 text-right font-medium">예상 비용</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.key} className="border-b last:border-0">
                  <td className="p-2">
                    {onSelect ? (
                      <button
                        type="button"
                        className="text-primary underline-offset-4 hover:underline"
                        onClick={() => onSelect(row.key)}
                      >
                        {label(row.key)}
                      </button>
                    ) : (
                      label(row.key)
                    )}
                  </td>
                  <td className="p-2 text-right font-mono">
                    {row.input_tokens.toLocaleString('ko-KR')}
                  </td>
                  <td className="p-2 text-right font-mono">
                    {row.output_tokens.toLocaleString('ko-KR')}
                  </td>
                  <td className="p-2 text-right font-mono">
                    {row.call_count.toLocaleString('ko-KR')}
                  </td>
                  <td className="p-2 text-right font-mono">
                    {row.estimated_cost_amount === undefined
                      ? '미보고'
                      : `${row.estimated_cost_amount} ${currency ?? ''}`.trim()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">기록된 사용량이 없어요.</p>
      )}
    </section>
  )
}

function RelationGraph({ data }: { data: RelationsResponse }) {
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
      <p className="text-xs text-muted-foreground">
        실선은 확인된 관계, 점선은 검토할 후보예요. 후보는 확정된 의존 관계로
        보지 않아요.
      </p>
      <div className="overflow-x-auto rounded-xl border bg-muted/20 p-3">
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
          {visible.map((node) => (
            <g key={`${node.type}:${node.id}`}>
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
      <details className="rounded-xl border p-4">
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
