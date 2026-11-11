import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { Check, TriangleAlert } from 'lucide-react'
import { changesApi } from '@/api/services/changes'
import { dashboardApi } from '@/api/services/dashboard'
import { tasksApi } from '@/api/services/tasks'
import type { Activity, AgentWork, Member } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import { openTaskCount, openTasksQuery } from '@/features/tasks/openTasksQuery'
import {
  ACTOR_PARTICLE,
  activityText,
  countText,
  statusLabel,
} from '@/lib/display'
import { formatShort } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { AsyncState } from '@/components/AsyncState'
import { ActorAvatar } from './ActorAvatar'
import { DailyCallsChart } from './DailyCallsChart'
import {
  FEATURE_LABELS,
  activityChip,
  agentTokenSuffix,
  allUnreported,
  callsText,
  costText,
  unknownText,
} from './labels'
import { rangeText, seoulClock, seoulDay } from './period'
import type { DashboardTab } from './labels'
import type {
  ActivitiesResponse,
  AgentWorkResponse,
  UsageResponse,
} from './schemas'

const LINK = 'ml-auto text-[12.5px] font-semibold text-[#8a6a00]'
// 최근 활동의 시각. 오늘이면 '10:42', 아니면 '10/27'
const recentTime = (value: string) => {
  const day = seoulDay(value)
  const [, month, date] = day.split('-').map(Number)
  return day === seoulDay(Date.now()) ? seoulClock(value) : `${month}/${date}`
}

// 대시보드 · 한눈에 보기. 숫자는 위에서 고른 기간의 사용량·비용과 같은 값이다.
export function DashboardOverview({
  projectId,
  usage,
  activities,
  work,
  members,
  membersStatus = 'success',
  actorName,
  onTab,
  periodName = '선택 기간',
}: {
  projectId: string
  usage: UseQueryResult<UsageResponse>
  activities: UseQueryResult<ActivitiesResponse>
  // 서버에 아직 없으면(404) data가 undefined(unlessMissing)
  work: UseQueryResult<AgentWorkResponse | undefined>
  members: Member[]
  // 팀원을 받는 중이거나 못 받았으면 연결 상태를 모른다. 0 / 0이나 연결 미확인으로 단정하지 않는다.
  membersStatus?: 'pending' | 'error' | 'success'
  actorName: (item: Activity) => string
  onTab: (tab: DashboardTab) => void
  // 고른 기간의 이름: '11월', '최근 7일', '선택 기간'
  periodName?: string
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const statusOf = useAgentStatusOf(projectId)
  // API 없음: 대시보드 숫자를 한 번에 주는 요약 응답(백엔드 메모 8). 지금은 작업·변경 제안 집계를 따로 부른다.
  // 하나가 실패해도 다른 집계는 보이도록 따로 받고, 실패한 칸은 0 대신 '—'로 둔다(PR #72 전수 리뷰 TC-5).
  const changes = useQuery({
    queryKey: ['projects', projectId, 'dashboard-counts', 'changes'],
    queryFn: () => dashboardApi.changes(projectId),
  })
  const tasks = useQuery({
    queryKey: ['projects', projectId, 'dashboard-counts', 'tasks'],
    queryFn: () => dashboardApi.tasks(projectId),
  })
  // 담당자가 나가거나 제외돼 빈 열린 작업(PNL-09-v2-dashboard-unassigned). HUD와 같은 키다.
  const unassigned = useQuery(openTasksQuery(projectId, { unassigned: true }))
  const orphans = unassigned.data?.items ?? []
  const countOf = (status: string) =>
    tasks.data?.by_status.find((entry) => entry.status === status)?.count ?? 0
  const inProgress = countOf('IN_PROGRESS')
  const todoCount = countOf('DRAFT') + countOf('READY')
  const review = changes.data?.review_required_count ?? 0
  // 숫자 칸 보조 줄에 쓸 첫 제안과 첫 작업. 집계에는 이름이 없어 있을 때만 목록을 부른다. 제안은 HUD와 같은 키다.
  const reviewList = useQuery({
    queryKey: ['projects', projectId, 'hud-review'],
    queryFn: () =>
      changesApi.list(projectId, { group: 'IN_PROGRESS', limit: 20 }),
    enabled: review > 0,
  })
  // 건수가 0이면 쿼리는 꺼져도 캐시에 지난 목록이 남아 있으니 쓰지 않는다.
  const firstReview =
    review > 0
      ? reviewList.data?.items.find((item) =>
          ['PROPOSED', 'IN_REVIEW'].includes(item.status),
        )
      : undefined
  const runningTasks = useQuery({
    queryKey: ['projects', projectId, 'dashboard-counts', 'running-task'],
    queryFn: () =>
      tasksApi.list(projectId, { status: ['IN_PROGRESS'], limit: 1 }),
    enabled: inProgress > 0,
  })
  const running = inProgress > 0 ? runningTasks.data?.items[0] : undefined
  const memberName = (memberId?: string) =>
    members.find((member) => member.id === memberId)?.name
  const runningAgent = work.data?.items.find(
    (agent) => running && agent.current_task_id === running.id,
  )
  const calls = usage.data?.call_count ?? 0
  const membersKnown = membersStatus === 'success'
  // agent_connected는 보고가 끊겨 '연결 미확인'인 에이전트도 참이다. 이름과 같은 기준으로 '연결됨'만 센다.
  const disconnected = membersKnown
    ? members.filter((member) => statusOf(member).status !== 'connected')
    : []
  const connected = members.length - disconnected.length
  const recent: Activity[] = activities.data?.items.slice(0, 4) ?? []
  const agents: AgentWork[] = work.data?.items.slice(0, 2) ?? []
  // 서버는 기능별을 호출이 없어도 0으로 채워 보낸다. 호출이 있는 기능만 보인다.
  const features = (usage.data?.by_feature ?? [])
    .filter((row) => row.call_count > 0)
    .sort((a, b) => b.call_count - a.call_count)
  const days = usage.data?.by_day ?? []
  const most = features[0]?.call_count || 1
  // 집계를 모두 받아 0건일 때만 '아직 기록이 없어요'로 본다.
  const empty =
    changes.isSuccess &&
    tasks.isSuccess &&
    usage.data !== undefined &&
    activities.data !== undefined &&
    !inProgress &&
    !todoCount &&
    !review &&
    !calls &&
    !recent.length
  const cost = costText(
    usage.data?.estimated_cost_amount,
    usage.data?.currency,
    usage.data?.cost_unknown_call_count,
    '비용 미보고',
    usage.data?.call_count,
  )
  // 비용을 하나도 모르면 '추정'을 붙이지 않는다.
  const costNote =
    usage.data?.estimated_cost_amount === undefined ||
    allUnreported(usage.data.call_count, usage.data.cost_unknown_call_count)
      ? cost
      : `추정 ${cost}`
  const metrics: {
    label: string
    value: string
    onClick: () => void
    todo?: string
    note?: string
    warn?: boolean
  }[] = [
    {
      label: '진행 중 작업',
      value: countText(tasks, inProgress),
      onClick: () => openPane('tasks', orphans[0]?.id),
      todo:
        unassigned.data && orphans.length
          ? `담당자 없는 작업 ${openTaskCount(unassigned.data)} ›`
          : undefined,
      note: [
        running?.key,
        running &&
          (runningAgent
            ? `${memberName(runningAgent.member_id) ?? '팀원'}의 에이전트`
            : // 이름을 못 찾은 것과 담당자가 없는 것은 다르다. 모르면 적지 않는다.
              running.assignee_member_id
              ? memberName(running.assignee_member_id)
              : '담당자 없음'),
        todoCount ? `할 것 ${todoCount}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
    },
    {
      label: '검토할 변경 제안',
      value: countText(changes, review),
      onClick: () => openPane('changes'),
      todo: review > 0 ? '검토하기 ›' : undefined,
      note:
        firstReview &&
        (review > 1
          ? `'${firstReview.title}' 외 ${review - 1}건`
          : `'${firstReview.title}' ${firstReview.status === 'IN_REVIEW' ? '검토 중' : '검토 대기'}`),
    },
    {
      label: `${periodName} AI 호출`,
      // 호출이 없으면 서버가 비용을 생략한다. '비용 미보고'를 붙이지 않는다. 받는 중이거나 못 받았으면 0회로 보이지 않는다.
      value: usage.isError
        ? '—'
        : usage.isPending
          ? '…'
          : `${calls.toLocaleString('ko-KR')}회`,
      onClick: () => onTab('usage'),
      note: calls ? costNote : undefined,
    },
    {
      label: '에이전트 연결',
      value:
        membersStatus === 'error'
          ? '—'
          : membersStatus === 'pending'
            ? '…'
            : `${connected} / ${members.length}`,
      onClick: () => onTab('agents'),
      todo: disconnected.length ? '연결 확인 ›' : undefined,
      note:
        disconnected[0] &&
        `${disconnected[0].name}의 에이전트 ${agentStatusLabels[statusOf(disconnected[0]).status]}`,
      warn: true,
    },
  ]
  // 할 일이 있는 칸(검토할 변경, 연결 안 된 에이전트)을 노란 바탕으로 앞에 둔다. 없으면 원래 순서다(PNL-09 수정안).
  // 담당자 없는 작업이 있어도 진행 중 작업 칸은 그 뒤다(PNL-09-v2-dashboard-unassigned).
  const rank = (metric: (typeof metrics)[number]) =>
    !metric.todo ? 2 : metric.label === '진행 중 작업' ? 1 : 0
  const ordered = [...metrics].sort((a, b) => rank(a) - rank(b))
  return (
    <section
      id="dashboard-overview"
      aria-labelledby="dashboard-overview-title"
      className="grid gap-3"
    >
      <h3 id="dashboard-overview-title" className="sr-only">
        한눈에 보기
      </h3>
      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        {ordered.map((metric) => (
          // 읽는 순서는 이름, 값, 할 일, 보조 줄이고 할 일은 이름 줄 오른쪽에 그린다.
          <button
            key={metric.label}
            type="button"
            className={cn(
              'grid grid-cols-[minmax(0,1fr)_auto] content-start gap-x-2 gap-y-0.5 rounded-[14px] border bg-card px-4 py-3 text-left shadow-xs hover:bg-muted/40',
              metric.todo && 'border-[#f3d77a] bg-[#fffaeb] hover:bg-[#fff4cf]',
              empty && 'opacity-60',
            )}
            onClick={metric.onClick}
          >
            <span className="col-start-1 row-start-1 text-xs text-muted-foreground">
              {metric.label}
            </span>
            <span className="col-span-2 row-start-2 text-[26px] leading-8 font-bold tabular-nums">
              {metric.value}
            </span>
            {metric.todo && (
              <span className="col-start-2 row-start-1 text-xs font-bold whitespace-nowrap text-[#8a6a00]">
                {metric.todo}
              </span>
            )}
            {metric.note && (
              <span
                className={cn(
                  'col-span-2 row-start-3 flex min-w-0 items-center gap-1 text-xs',
                  metric.warn ? 'text-[#8a6a00]' : 'text-muted-foreground',
                )}
              >
                {metric.warn && (
                  <TriangleAlert
                    aria-hidden="true"
                    className="size-3 shrink-0"
                  />
                )}
                <span className="truncate">{metric.note}</span>
              </span>
            )}
          </button>
        ))}
      </div>
      {empty ? (
        <div className="grid gap-3 rounded-xl border border-dashed bg-card p-5 text-sm">
          <p className="font-medium">
            아직 기록이 없어요. 이렇게 시작해 보세요.
          </p>
          <ol className="grid gap-2">
            {(
              [
                ['공용 문서함에 문서 올리기', () => openPane('documents')],
                ['내 에이전트 연결', () => openPane('settings', 'agent')],
                ['첫 작업 계획 등록', () => openPane('tasks')],
              ] as const
            ).map(([label, open], index) => (
              <li key={label} className="flex items-center gap-3">
                <span className="grid size-6 place-items-center rounded-full bg-muted text-xs font-semibold">
                  {index + 1}
                </span>
                <Button size="sm" variant="outline" onClick={open}>
                  {label}
                </Button>
              </li>
            ))}
          </ol>
        </div>
      ) : (
        <div className="grid gap-3 xl:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
          <div className="grid content-start gap-2 rounded-2xl border bg-card px-4 py-3.5 shadow-xs">
            <div className="flex items-baseline gap-2">
              <h4 className="text-sm font-bold">날짜별 AI 호출</h4>
              {days.length > 0 && (
                <span className="text-xs text-muted-foreground">
                  {rangeText(days[0].key, days[days.length - 1].key)}
                </span>
              )}
              <button
                type="button"
                className={LINK}
                onClick={() => onTab('usage')}
              >
                사용량·비용 ›
              </button>
            </div>
            <AsyncState query={usage}>
              {(data) => (
                <DailyCallsChart rows={data.by_day} currency={data.currency} />
              )}
            </AsyncState>
          </div>
          <div className="flex flex-col gap-2.5 rounded-2xl border bg-card px-4 py-3.5 shadow-xs">
            <div className="flex items-baseline gap-2">
              <h4 className="text-sm font-bold">기능별 사용</h4>
              <span className="text-xs text-muted-foreground">
                {periodName}
              </span>
            </div>
            {features.length ? (
              <>
                <ul className="grid gap-2.5">
                  {features.map((row) => (
                    <li
                      key={row.key}
                      className="grid grid-cols-[92px_minmax(0,1fr)_40px_64px] items-center gap-2.5 text-[12.5px]"
                    >
                      <span className="truncate">
                        {FEATURE_LABELS[row.key] ?? row.key}
                      </span>
                      <span
                        aria-hidden="true"
                        className="block h-2.5 rounded-r-[4px] bg-[#2a78d6]"
                        style={{ width: `${(row.call_count / most) * 100}%` }}
                      />
                      <span className="text-right tabular-nums">
                        {row.call_count}회
                      </span>
                      <span className="text-right whitespace-nowrap text-muted-foreground tabular-nums">
                        {/* 호출이 모두 비용 미보고면 '미보고'만, 일부만 모르면 전체 금액처럼 보이지 않게 건수를 값 아래에 붙인다. */}
                        {allUnreported(
                          row.call_count,
                          row.cost_unknown_call_count,
                        ) ? (
                          '미보고'
                        ) : (
                          <>
                            {costText(
                              row.estimated_cost_amount,
                              usage.data?.currency,
                              0,
                              '—',
                            )}
                            {unknownText(row.cost_unknown_call_count) && (
                              <span className="block text-[11px]">
                                {unknownText(row.cost_unknown_call_count)}
                              </span>
                            )}
                          </>
                        )}
                      </span>
                    </li>
                  ))}
                </ul>
                <span className="mt-auto text-[11.5px] leading-4 text-muted-foreground">
                  {`합계 ${calls.toLocaleString('ko-KR')}회 · ${costNote}`}
                </span>
              </>
            ) : (
              // 받는 중이거나 못 받았으면 호출이 없다고 말하지 않는다. 상태는 옆 그래프 칸에 보인다.
              usage.data && (
                <p className="text-xs text-muted-foreground">
                  이 기간에 기록된 AI 호출이 없어요.
                </p>
              )
            )}
          </div>
          <div className="flex flex-col gap-1.5 rounded-2xl border bg-card px-4 py-3.5 shadow-xs">
            <div className="flex items-baseline gap-2">
              <h4 className="text-sm font-bold">최근 활동</h4>
              <button
                type="button"
                className={LINK}
                onClick={() => onTab('timeline')}
              >
                활동 타임라인 ›
              </button>
            </div>
            {recent.length ? (
              <ul>
                {recent.map((item) => {
                  const chip = activityChip(item)
                  const open = item.task_id
                    ? () => openPane('tasks', item.task_id)
                    : item.change_proposal_id
                      ? () => openPane('changes', item.change_proposal_id)
                      : item.baseline_id
                        ? () => openPane('documents')
                        : undefined
                  const row = (
                    <>
                      <span className="text-[11.5px] text-muted-foreground tabular-nums">
                        {recentTime(item.created_at)}
                      </span>
                      <ActorAvatar
                        agent={item.actor_type === 'AGENT'}
                        label={
                          item.actor_type === 'AGENT'
                            ? 'AI'
                            : actorName(item).slice(0, 1)
                        }
                      />
                      <span className="truncate">
                        <strong className="font-semibold">
                          {actorName(item)}
                        </strong>
                        {ACTOR_PARTICLE}{' '}
                        <span className="text-[#57534e]">
                          {activityText(item, members)}
                        </span>
                      </span>
                      {chip.notable ? (
                        <span
                          className={cn(
                            'rounded-full px-2 py-0.5 text-[11px] font-bold',
                            chip.className,
                          )}
                        >
                          {chip.label}
                        </span>
                      ) : (
                        <span />
                      )}
                    </>
                  )
                  const rowClass =
                    'grid w-full grid-cols-[40px_26px_minmax(0,1fr)_auto] items-center gap-2.5 py-1.5 text-left text-[12.5px]'
                  return (
                    <li key={item.id} className="border-b last:border-0">
                      {open ? (
                        <button
                          type="button"
                          className={cn(rowClass, 'hover:bg-muted/40')}
                          onClick={open}
                        >
                          {row}
                        </button>
                      ) : (
                        <div className={rowClass}>{row}</div>
                      )}
                    </li>
                  )
                })}
              </ul>
            ) : (
              <p className="text-xs text-muted-foreground">
                {activities.isError
                  ? '최근 활동을 불러오지 못했어요.'
                  : activities.isPending
                    ? '불러오는 중…'
                    : '최근 활동이 없어요.'}
              </p>
            )}
          </div>
          <div className="flex flex-col gap-1.5 rounded-2xl border bg-card px-4 py-3.5 shadow-xs">
            <div className="flex items-baseline gap-2">
              <h4 className="text-sm font-bold">에이전트</h4>
              {!!work.data?.items.length && (
                <span className="text-xs text-muted-foreground">
                  {work.data.items.length}개
                </span>
              )}
              <button
                type="button"
                className={LINK}
                onClick={() => onTab('agents')}
              >
                에이전트 현황 ›
              </button>
            </div>
            {agents.length ? (
              <ul>
                {agents.map((agent) => {
                  // 팀원을 못 찾으면 연결 상태를 모른다(에이전트 현황 탭과 같은 표기).
                  const member = members.find(
                    (entry) => entry.id === agent.member_id,
                  )
                  const agentStatus = member
                    ? statusOf(member).status
                    : 'recheck'
                  const isConnected = agentStatus === 'connected'
                  const row = usage.data?.by_agent.find(
                    (entry) => entry.key === agent.agent_session_id,
                  )
                  return (
                    <li
                      key={`${agent.member_id}:${agent.agent_session_id ?? ''}`}
                      className="grid grid-cols-[minmax(0,1fr)] gap-0.5 border-b py-2 last:border-0"
                    >
                      <span className="flex items-center gap-2 text-[13px]">
                        <ActorAvatar agent label="AI" />
                        <strong className="font-semibold">
                          {`${memberName(agent.member_id) ?? '팀원'}의 에이전트${agentTokenSuffix(agent, work.data?.items ?? [])}`}
                        </strong>
                        <span
                          className={cn(
                            'ml-auto inline-flex items-center gap-1 text-xs font-semibold whitespace-nowrap',
                            !member
                              ? 'text-muted-foreground'
                              : isConnected
                                ? 'text-[#00753a]'
                                : 'text-[#8a6a00]',
                          )}
                        >
                          {member &&
                            (isConnected ? (
                              <Check aria-hidden="true" className="size-3" />
                            ) : (
                              <TriangleAlert
                                aria-hidden="true"
                                className="size-3"
                              />
                            ))}
                          {agentStatusLabels[agentStatus]}
                        </span>
                      </span>
                      <span className="flex justify-between gap-2 pl-8.5 text-xs text-muted-foreground">
                        <span className="min-w-0 truncate">
                          {agent.current_task_id ? (
                            `${agent.task_title ?? '이름 없는 작업'} ${statusLabel(agent.task_status)}${
                              agent.reported_at
                                ? ` · ${formatShort(agent.reported_at)} 보고`
                                : ''
                            }`
                          ) : isConnected || !member ? (
                            '보고된 작업 없음'
                          ) : (
                            <>
                              연결을 확인해요 ·{' '}
                              <button
                                type="button"
                                className="font-semibold text-[#8a6a00]"
                                onClick={() => openPane('settings', 'agent')}
                              >
                                연결 확인
                              </button>
                            </>
                          )}
                        </span>
                        {/* 서버가 호출자별로 나누지 않으면(빈 배열) 사용량을 적지 않는다. 0회로 단정하지 않는다. */}
                        {!!usage.data?.by_agent.length && (
                          <span className="whitespace-nowrap tabular-nums">
                            {row ? callsText(row, usage.data.currency) : '0회'}
                          </span>
                        )}
                      </span>
                    </li>
                  )
                })}
              </ul>
            ) : (
              <p className="text-xs text-muted-foreground">
                {work.isPending
                  ? '불러오는 중…'
                  : work.isError
                    ? '에이전트 현황을 불러오지 못했어요.'
                    : work.data
                      ? '연결된 에이전트가 없어요.'
                      : '에이전트 현황은 준비 중이에요.'}
              </p>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
