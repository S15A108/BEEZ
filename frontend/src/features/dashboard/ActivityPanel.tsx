import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { dashboardApi } from '@/api/services/dashboard'
import { unlessMissing } from '@/api/error'
import type { Activity, UsageBucket } from '@/api/contracts/common'
import { ActivityTimeline } from './ActivityTimeline'
import { ActorAvatar } from './ActorAvatar'
import { DashboardOverview } from './DashboardOverview'
import { RelationsView } from './RelationsView'
import {
  FEATURE_LABELS,
  UNSPLIT_USAGE_NOTICE,
  agentLabel,
  agentTokenSuffix,
  callsText,
  costText,
  dashboardTabs,
  unknownText,
  allUnreported,
} from './labels'
import type { DashboardTab } from './labels'
import {
  MAX_RANGE_DAYS,
  periodNameOf,
  periodRange,
  periods,
  rangeDays,
  usageQueryOf,
} from './period'
import type { DayRange, Period } from './period'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { statusLabel } from '@/lib/display'
import { formatShort } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import { membersQuery } from '@/features/members/membersQuery'

const usageViews = [
  ['by_agent', '에이전트별'],
  ['by_feature', '기능별'],
  ['by_task', '작업별'],
  ['by_validation', '검증별'],
] as const
type UsageView = (typeof usageViews)[number][0]
const dateRangeSchema = z
  .object({
    from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '시작일을 선택해 주세요.'),
    to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '종료일을 선택해 주세요.'),
  })
  .refine((range) => range.from <= range.to, {
    path: ['to'],
    message: '종료일은 시작일 이후여야 해요.',
  })
  .refine((range) => rangeDays(range) <= MAX_RANGE_DAYS, {
    path: ['to'],
    message: `기간은 ${MAX_RANGE_DAYS}일 이하로 골라 주세요.`,
  })

const agentWorkQuery = (projectId: string, cursor?: string) => ({
  queryKey: ['projects', projectId, 'agent-work', cursor],
  // API 없음: 서버에 아직 없으면(404) 에이전트 현황은 '준비 중'으로 둔다(AGT-A04·A05 -178 구현 전과 같은 규칙).
  queryFn: () =>
    unlessMissing(dashboardApi.agentWork(projectId, { cursor, limit: 20 })),
})

// 단축 바 5번(설계 관계)과 6번(대시보드)이 같이 쓴다.
export function ActivityPanel({
  projectId,
  view = 'dashboard',
  hasBaseline,
}: {
  projectId: string
  isLeader?: boolean
  view?: 'dashboard' | 'relations'
  /** 기준 설계가 발행됐는지. 설계 관계 빈 화면 안내에 쓴다. */
  hasBaseline?: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [usageView, setUsageView] = useState<UsageView>('by_agent')
  const [tab, setTab] = useState<DashboardTab>('overview')
  // 한눈에 보기와 사용량 탭이 같은 기간을 쓴다. 이름으로 고른 기간은 날이 바뀌어도 그 뜻을 지키고, 직접 넣은 기간은 날짜 그대로 둔다.
  const [period, setPeriod] = useState<Period | DayRange>('this_month')
  const range = typeof period === 'string' ? periodRange(period) : period
  const [workCursor, setWorkCursor] = useState<string>()
  const usage = useQuery({
    queryKey: ['projects', projectId, 'usage', range],
    queryFn: () => dashboardApi.usage(projectId, usageQueryOf(range)),
  })
  // 에이전트 현황의 사용량은 고른 기간과 상관없이 이번 달이다.
  const thisMonth = periodRange('this_month')
  const monthUsage = useQuery({
    queryKey: ['projects', projectId, 'usage', thisMonth],
    queryFn: () => dashboardApi.usage(projectId, usageQueryOf(thisMonth)),
    enabled: tab === 'agents',
  })
  const activities = useQuery({
    queryKey: ['projects', projectId, 'activities', 'recent'],
    queryFn: () => dashboardApi.activities(projectId, { limit: 4 }),
  })
  // 에이전트 현황 탭의 쪽 넘김이 다른 탭의 칩·라벨·요약을 바꾸지 않게 첫 쪽을 따로 둔다. 첫 쪽을 볼 때는 같은 요청 하나다.
  const work = useQuery(agentWorkQuery(projectId, workCursor))
  const firstWork = useQuery(agentWorkQuery(projectId))
  const members = useQuery(membersQuery(projectId))
  const statusOf = useAgentStatusOf(projectId)
  const memberName = (memberId: string) =>
    members.data?.items.find((member) => member.id === memberId)?.name ??
    '알 수 없는 팀원'
  const actorName = (item: Activity) => {
    if (item.actor_type === 'BEE') return '비비'
    if (item.actor_type === 'SYSTEM') return '시스템'
    const owner = members.data?.items.find(
      (member) => member.account_id === item.actor_account_id,
    )?.name
    if (item.actor_type === 'AGENT')
      return owner ? `${owner}의 에이전트` : (item.actor_name ?? '에이전트')
    return owner ?? item.actor_name ?? '알 수 없는 팀원'
  }
  // 호출이 있는데 에이전트별 줄이 하나도 없으면 서버가 호출자별로 나누지 않은 것이라 0회로 단정하지 않는다.
  const usageUnsplit =
    !!monthUsage.data?.call_count && !monthUsage.data.by_agent.length
  const agentUsageText = (key?: string) => {
    if (monthUsage.isError) return '—'
    if (!monthUsage.data) return '…'
    if (usageUnsplit) return '나눈 기록 없음'
    const { by_agent: rows, currency } = monthUsage.data
    const row = rows.find((entry) => entry.key === key)
    return row ? callsText(row, currency) : '0회'
  }
  // 에이전트별 줄: 호출 없는 세션도 보이고 웹 직접은 맨 아래다. 서버가 나누지 않으면(빈 배열) 줄을 지어내지 않는다.
  const agentRows = (rows: UsageBucket[]) => {
    if (!rows.length) return rows
    const quiet = (firstWork.data?.items ?? []).flatMap((item) =>
      item.agent_session_id &&
      !rows.some((row) => row.key === item.agent_session_id)
        ? [
            {
              key: item.agent_session_id,
              input_tokens: 0,
              output_tokens: 0,
              call_count: 0,
            },
          ]
        : [],
    )
    return [
      ...rows.filter((row) => row.key !== 'WEB'),
      ...quiet,
      ...rows.filter((row) => row.key === 'WEB'),
    ]
  }
  const rowLabels: Record<UsageView, (row: UsageBucket) => ReactNode> = {
    by_agent: (row) => {
      const web = row.key === 'WEB'
      const agent = firstWork.data?.items.find(
        (item) => item.agent_session_id === row.key,
      )
      return (
        <span className="flex min-w-0 items-center gap-2">
          <ActorAvatar agent={!web} label={web ? '웹' : 'AI'} />
          <span className="truncate">
            {web
              ? '웹에서 직접'
              : agent
                ? agentLabel(agent, firstWork.data?.items ?? [], memberName)
                : (row.label ?? '알 수 없는 에이전트')}
          </span>
        </span>
      )
    },
    by_feature: (row) => FEATURE_LABELS[row.key] ?? row.key,
    // 서버는 작업 키를 label로 준다. 없으면 프로젝트에서 그 작업을 찾지 못한 것이다.
    by_task: (row) => row.label ?? '알 수 없는 작업',
    // 서버가 검증별 줄에 이름(label)을 주지 않는다. 번호 대신 무슨 호출인지만 보인다.
    by_validation: () => '계획 검증',
  }
  if (view === 'relations')
    return <RelationsView projectId={projectId} hasBaseline={hasBaseline} />
  return (
    <div className="grid gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="sr-only">대시보드</h2>
        {/* 대시보드 탭: 한눈에 보기 / 활동 타임라인 / 에이전트 현황 / 사용량·비용. 기간은 한눈에 보기의 드롭다운으로 고르고, 날짜 직접 입력은 사용량 탭에만 둔다. */}
        <div
          role="tablist"
          aria-label="대시보드"
          className="flex flex-wrap gap-1 text-sm"
        >
          {dashboardTabs.map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={tab === value}
              onClick={() => setTab(value)}
              className={cn(
                'rounded-full px-3 py-1',
                tab === value ? 'bg-foreground text-background' : 'bg-muted',
              )}
            >
              {label}
            </button>
          ))}
        </div>
        {tab === 'overview' && (
          <NativeSelect
            aria-label="기간"
            value={typeof period === 'string' ? period : 'custom'}
            onChange={(event) => setPeriod(event.target.value as Period)}
          >
            {periods.map(([value, label]) => (
              <NativeSelectOption key={value} value={value}>
                {`기간 · ${label}`}
              </NativeSelectOption>
            ))}
            {typeof period !== 'string' && (
              <NativeSelectOption value="custom" disabled>
                기간 · 직접 입력
              </NativeSelectOption>
            )}
          </NativeSelect>
        )}
      </header>
      {tab === 'overview' && (
        <DashboardOverview
          onTab={setTab}
          projectId={projectId}
          usage={usage}
          activities={activities}
          work={firstWork}
          members={members.data?.items ?? []}
          membersStatus={members.data ? 'success' : members.status}
          actorName={actorName}
          periodName={periodNameOf(period)}
        />
      )}
      {tab === 'timeline' && (
        <ActivityTimeline
          projectId={projectId}
          agents={firstWork.data?.items ?? []}
          members={members.data?.items ?? []}
          memberName={memberName}
          actorName={actorName}
        />
      )}
      {tab === 'agents' && (
        <div id="dashboard-agents">
          <AsyncState query={work}>
            {(data) =>
              !data ? (
                <p className="text-sm text-muted-foreground">
                  에이전트 현황은 준비 중이에요. 서버가 연결 보고를 받기
                  시작하면 여기에 보여요.
                </p>
              ) : data.items.length ? (
                <>
                  {/* 캔버스 PNL-09-v2-agents: 에이전트마다 카드. 최근 활동은 활동 타임라인의 에이전트 보기로 본다(11/5 결정). */}
                  {/* 줄마다 카드 모양이지만 표 구조(열 머리는 화면 밖)를 지켜 읽기 도구가 칸을 알 수 있게 한다. */}
                  <table className="block w-full text-left">
                    <thead className="sr-only">
                      <tr>
                        <th>에이전트·연결</th>
                        <th>현재 작업</th>
                        <th>작업 상태</th>
                        <th>계획 검증</th>
                        <th>대기 이유</th>
                        <th>이번 달 사용량</th>
                      </tr>
                    </thead>
                    <tbody className="grid gap-3.5 md:grid-cols-2">
                      {data.items.map((item) => {
                        const member = members.data?.items.find(
                          (entry) => entry.id === item.member_id,
                        )
                        // 팀원 목록을 못 받았으면 연결 여부를 모른다.
                        const agentStatus = member
                          ? statusOf(member).status
                          : 'recheck'
                        return (
                          <tr
                            key={`${item.member_id}:${item.agent_session_id ?? ''}`}
                            className="grid grid-cols-2 gap-x-4 gap-y-2.5 rounded-2xl border bg-card px-4.5 py-4 shadow-xs"
                          >
                            <td className="col-span-2 flex items-center gap-2.5 pb-0.5">
                              <ActorAvatar agent label="AI" />
                              <strong className="text-sm font-bold">
                                <span>{memberName(item.member_id)}</span>의
                                에이전트
                                {agentTokenSuffix(item, data.items)}
                              </strong>
                              <span
                                className={cn(
                                  'ml-auto inline-flex items-center gap-1.5 text-xs',
                                  agentStatus === 'connected'
                                    ? 'text-[#00753a]'
                                    : 'text-[#8a6a00]',
                                )}
                              >
                                <span
                                  aria-hidden="true"
                                  className="size-1.75 rounded-full bg-current"
                                />
                                {agentStatusLabels[agentStatus]}
                                {item.reported_at &&
                                  ` · ${formatShort(item.reported_at)} 보고`}
                              </span>
                            </td>
                            {(
                              [
                                [
                                  '현재 작업',
                                  item.current_task_id ? (
                                    <Button
                                      variant="link"
                                      className="h-auto justify-start p-0 text-left text-[13px] font-semibold whitespace-normal text-foreground"
                                      onClick={() =>
                                        openPane('tasks', item.current_task_id)
                                      }
                                    >
                                      {item.task_title ?? '이름 없는 작업'}
                                    </Button>
                                  ) : (
                                    <span className="text-muted-foreground">
                                      보고된 작업 없음
                                    </span>
                                  ),
                                ],
                                [
                                  '작업 상태',
                                  item.task_status ? (
                                    <span
                                      className={
                                        item.task_status === 'IN_PROGRESS'
                                          ? 'text-[#8a6a00]'
                                          : undefined
                                      }
                                    >
                                      {statusLabel(item.task_status)}
                                    </span>
                                  ) : (
                                    <span className="text-muted-foreground">
                                      미보고
                                    </span>
                                  ),
                                ],
                                [
                                  '계획 검증',
                                  item.validation_status === 'PASS' ? (
                                    <span className="text-[#00753a]">통과</span>
                                  ) : item.validation_status ? (
                                    statusLabel(item.validation_status)
                                  ) : (
                                    <span className="text-muted-foreground">
                                      미보고
                                    </span>
                                  ),
                                ],
                                [
                                  '대기 이유',
                                  item.waiting_reason
                                    ? statusLabel(item.waiting_reason)
                                    : '—',
                                ],
                              ] as const
                            ).map(([label, value]) => (
                              <td key={label} className="grid gap-0.5">
                                <span
                                  aria-hidden="true"
                                  className="text-[11.5px] text-muted-foreground"
                                >
                                  {label}
                                </span>
                                <span className="text-[13px] font-semibold">
                                  {value}
                                </span>
                              </td>
                            ))}
                            <td className="col-span-2 flex items-center justify-between rounded-lg bg-muted/60 px-3 py-1.5 text-xs">
                              <span
                                aria-hidden="true"
                                className="text-muted-foreground"
                              >
                                이번 달 사용량
                              </span>
                              <span className="font-semibold">
                                {agentUsageText(item.agent_session_id)}
                              </span>
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
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
          <p className="mt-3.5 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-2xl border bg-card px-4 py-3 text-xs shadow-xs">
            <span className="min-w-0 text-muted-foreground">
              <strong className="font-semibold text-foreground">
                웹에서 직접 한 호출
              </strong>{' '}
              · 문서 분석, 비비 질문, 회의 분석처럼 에이전트를 거치지 않은 호출
            </span>
            <span className="font-semibold">{agentUsageText('WEB')}</span>
          </p>
          {usageUnsplit && (
            <p className="mt-2 text-xs text-muted-foreground">
              {UNSPLIT_USAGE_NOTICE}
            </p>
          )}
          <p className="mt-4 text-xs leading-[18px] text-muted-foreground">
            에이전트가 보고하지 않은 실행 중·완료 상태는 추정하지 않아요. 연결
            여부는 연결 보고, 작업 상태는 작업 보고를 따로 봐요.
          </p>
        </div>
      )}
      {tab === 'usage' && (
        <div id="dashboard-usage" className="grid gap-3.5">
          <UsageRangeForm range={range} onApply={setPeriod} />
          <AsyncState query={usage}>
            {(data) => (
              <>
                <dl className="grid grid-cols-2 gap-3 xl:grid-cols-4">
                  {[
                    {
                      label: '입력 토큰',
                      value: data.input_tokens.toLocaleString('ko-KR'),
                      unknown: data.tokens_unknown_call_count,
                      calls: data.call_count,
                    },
                    {
                      label: '출력 토큰',
                      value: data.output_tokens.toLocaleString('ko-KR'),
                      unknown: data.tokens_unknown_call_count,
                      calls: data.call_count,
                    },
                    {
                      label: '호출',
                      value: `${data.call_count.toLocaleString('ko-KR')}회`,
                    },
                    {
                      label: '추정 비용',
                      // 호출이 없으면 서버가 비용을 생략한다. '미보고'가 아니라 비어 있음으로 본다.
                      value: data.call_count
                        ? costText(data.estimated_cost_amount, data.currency)
                        : '—',
                      unknown: data.cost_unknown_call_count,
                      calls: data.call_count,
                      note: '단가가 없으면 숨겨요',
                    },
                  ].map((metric) => (
                    <div
                      key={metric.label}
                      className="rounded-xl border bg-card p-4 shadow-xs"
                    >
                      <dt className="text-xs text-muted-foreground">
                        {metric.label}
                      </dt>
                      <dd className="mt-1 text-[22px] leading-7 font-bold">
                        <WithUnknown
                          value={metric.value}
                          unknown={metric.unknown}
                          calls={metric.calls}
                        />
                        {metric.note && (
                          <span className="block text-[11.5px] leading-4 font-normal text-muted-foreground">
                            {metric.note}
                          </span>
                        )}
                      </dd>
                    </div>
                  ))}
                </dl>
                <section className="grid gap-1 rounded-[14px] border bg-card px-4 py-3.5 shadow-xs">
                  <div className="flex items-center justify-between">
                    <h3 className="text-sm font-semibold">
                      {usageViews.find(([key]) => key === usageView)![1]}
                    </h3>
                    <span role="tablist" className="flex gap-1 text-xs">
                      {usageViews.map(([key, name]) => (
                        <button
                          key={key}
                          type="button"
                          role="tab"
                          aria-selected={usageView === key}
                          onClick={() => setUsageView(key)}
                          className={cn(
                            'rounded-full px-2.5 py-0.5 font-semibold',
                            usageView === key
                              ? 'bg-foreground text-background'
                              : 'bg-muted',
                          )}
                        >
                          {name}
                        </button>
                      ))}
                    </span>
                  </div>
                  <UsageBars
                    key={usageView}
                    rows={
                      usageView === 'by_agent'
                        ? agentRows(data.by_agent)
                        : data[usageView]
                    }
                    currency={data.currency}
                    label={rowLabels[usageView]}
                    // 호출이 있어도 서버가 호출자를 적지 않으면 에이전트별 줄이 비어 있다. 호출이 없다고 말하지 않는다.
                    empty={
                      usageView === 'by_agent' && data.call_count > 0
                        ? UNSPLIT_USAGE_NOTICE
                        : undefined
                    }
                    onSelect={
                      usageView === 'by_task'
                        ? (id) => openPane('tasks', id)
                        : undefined
                    }
                  />
                </section>
                <p className="text-xs leading-5 text-muted-foreground">
                  BEEZ에 기록된 AI 호출만 모아요. 외부 에이전트가 자기 계정으로
                  쓴 사용량과 실제 청구액은 포함하지 않아요.
                </p>
              </>
            )}
          </AsyncState>
        </div>
      )}
      {/* 한눈에 보기와 에이전트 현황이 같이 쓰는 안내 */}
      {(tab === 'overview' || tab === 'agents') && members.error && (
        <p className="text-xs text-destructive">
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
    </div>
  )
}

// 사용량 탭의 날짜 직접 입력. 탭을 열 때마다 지금 고른 기간으로 채운다.
function UsageRangeForm({
  range,
  onApply,
}: {
  range: DayRange
  onApply: (range: DayRange) => void
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<DayRange>({
    resolver: zodResolver(dateRangeSchema),
    defaultValues: range,
  })
  return (
    <form
      aria-label="기간"
      className="flex flex-wrap items-end gap-3"
      noValidate
      onSubmit={handleSubmit(onApply)}
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
  )
}

// 묶음 하나를 막대 줄로 보여 준다. 막대 길이는 호출 수가 가장 많은 줄 기준이다.
function UsageBars({
  rows,
  currency,
  label,
  empty = '기록된 사용량이 없어요.',
  onSelect,
}: {
  rows: UsageBucket[]
  currency?: string
  label: (row: UsageBucket) => ReactNode
  empty?: string
  onSelect?: (id: string) => void
}) {
  const most = Math.max(1, ...rows.map((row) => row.call_count))
  // 서버는 기능별을 호출이 없어도 0으로 채워 보낸다. 모두 0이면 빈 안내를 보인다.
  if (!rows.some((row) => row.call_count > 0))
    return <p className="py-2 text-xs text-muted-foreground">{empty}</p>
  return (
    <ul>
      {rows.map((row) => (
        <li
          key={row.key}
          className="grid grid-cols-[150px_minmax(0,1fr)_64px_110px_90px] items-center gap-3 border-b py-2.5 text-[13px] last:border-0"
        >
          <span className="truncate font-semibold">
            {onSelect ? (
              <button
                type="button"
                className="underline-offset-4 hover:underline"
                onClick={() => onSelect(row.key)}
              >
                {label(row)}
              </button>
            ) : (
              label(row)
            )}
          </span>
          <span aria-hidden="true" className="h-2 rounded-full bg-muted">
            <span
              className="block h-2 rounded-full bg-[#e0b400]"
              style={{ width: `${(row.call_count / most) * 100}%` }}
            />
          </span>
          <span className="text-right text-[#57534e]">
            {row.call_count.toLocaleString('ko-KR')}회
          </span>
          <span className="text-right text-muted-foreground">
            {row.call_count ? (
              <WithUnknown
                value={`${(row.input_tokens + row.output_tokens).toLocaleString('ko-KR')} 토큰`}
                unknown={row.tokens_unknown_call_count}
                calls={row.call_count}
              />
            ) : (
              '—'
            )}
          </span>
          <strong className="text-right font-semibold">
            {row.call_count ? (
              <WithUnknown
                value={costText(row.estimated_cost_amount, currency)}
                unknown={row.cost_unknown_call_count}
                calls={row.call_count}
              />
            ) : (
              '—'
            )}
          </strong>
        </li>
      ))}
    </ul>
  )
}

// 값 아래에 합계에 못 들어간 호출 수를 작게 붙인다. 한 줄에 이으면 좁은 칸에서 꺾인다.
// 호출이 모두 미보고면 합계 대신 '미보고'만 보인다(allUnreported).
function WithUnknown({
  value,
  unknown,
  calls,
}: {
  value: string
  unknown?: number
  calls?: number
}) {
  if (allUnreported(calls, unknown)) return <>미보고</>
  const note = unknownText(unknown)
  return (
    <>
      {value}
      {note && (
        <span className="block text-xs font-normal text-muted-foreground">
          {note}
        </span>
      )}
    </>
  )
}
