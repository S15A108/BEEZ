import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { dashboardApi } from '@/api/services/dashboard'
import type { Activity, AgentWork, Member } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { countText, statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { AsyncState } from '@/components/AsyncState'
import { DailyCallsChart } from './DailyCallsChart'
import { FEATURE_LABELS, costText } from './labels'
import { rangeText } from './period'
import type { DashboardTab } from './labels'
import type {
  ActivitiesResponse,
  AgentWorkResponse,
  UsageResponse,
} from './schemas'

// 대시보드 · 한눈에 보기. 숫자는 위에서 고른 기간의 사용량·비용과 같은 값이다.
export function DashboardOverview({
  projectId,
  usage,
  activities,
  work,
  members,
  actorName,
  onTab,
}: {
  projectId: string
  usage: UseQueryResult<UsageResponse>
  activities: UseQueryResult<ActivitiesResponse>
  // 서버에 아직 없으면(404) data가 undefined(unlessMissing)
  work: UseQueryResult<AgentWorkResponse | undefined>
  members: Member[]
  actorName: (item: Activity) => string
  onTab: (tab: DashboardTab) => void
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
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
  const inProgress =
    tasks.data?.by_status.find((entry) => entry.status === 'IN_PROGRESS')
      ?.count ?? 0
  const review = changes.data?.review_required_count ?? 0
  const calls = usage.data?.call_count ?? 0
  const connected = members.filter((member) => member.agent_connected).length
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
    !inProgress &&
    !review &&
    !calls &&
    !recent.length
  const cost = costText(
    usage.data?.estimated_cost_amount,
    usage.data?.currency,
    usage.data?.cost_unknown_call_count,
    '비용 미보고',
  )
  const metrics = [
    {
      label: '진행 중 작업',
      value: countText(tasks, inProgress),
      onClick: () => openPane('tasks'),
    },
    {
      label: '검토할 변경 제안',
      value: countText(changes, review),
      onClick: () => openPane('changes'),
      todo: review > 0 ? '검토하기 ›' : undefined,
    },
    {
      label: '선택 기간 AI 호출',
      // 호출이 없으면 서버가 비용을 생략한다. '비용 미보고'를 붙이지 않는다. 받는 중이거나 못 받았으면 0회로 보이지 않는다.
      value: usage.isError
        ? '—'
        : usage.isPending
          ? '…'
          : calls
            ? `${calls.toLocaleString('ko-KR')}회 · ${cost}`
            : '0회',
      onClick: () => onTab('usage'),
    },
    {
      label: '에이전트 연결',
      value: `${connected} / ${members.length}`,
      onClick: () => onTab('agents'),
      todo: connected < members.length ? '연결 확인 ›' : undefined,
    },
  ]
  return (
    <section
      id="dashboard-overview"
      aria-labelledby="dashboard-overview-title"
      className="grid gap-4"
    >
      <h3 id="dashboard-overview-title" className="text-base font-semibold">
        한눈에 보기
      </h3>
      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        {metrics.map((metric) => {
          const body = (
            <>
              <span className="block text-xs text-muted-foreground">
                {metric.label}
              </span>
              <span className="mt-2 block text-lg font-semibold">
                {metric.value}
              </span>
              {metric.todo && (
                <span className="mt-1 block text-xs font-semibold">
                  {metric.todo}
                </span>
              )}
            </>
          )
          // 할 일이 있는 칸(검토할 변경, 연결 안 된 에이전트)만 노란 바탕으로 먼저 보이게 한다(PNL-09 수정안).
          const className = cn(
            'rounded-xl border bg-card p-4 text-left shadow-xs hover:bg-muted/40',
            metric.todo &&
              'border-primary/50 bg-primary/10 hover:bg-primary/20',
            empty && 'opacity-60',
          )
          return (
            <button
              key={metric.label}
              type="button"
              className={className}
              onClick={metric.onClick}
            >
              {body}
            </button>
          )
        })}
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
        <div className="grid gap-4 xl:grid-cols-2">
          <div className="grid content-start gap-2 rounded-xl border bg-card p-4 shadow-xs">
            <div className="flex items-baseline gap-2">
              <h4 className="text-sm font-semibold">날짜별 AI 호출</h4>
              {days.length > 0 && (
                <span className="text-xs text-muted-foreground">
                  {rangeText(days[0].key, days[days.length - 1].key)}
                </span>
              )}
              <button
                type="button"
                className="ml-auto text-xs underline"
                onClick={() => onTab('usage')}
              >
                사용량·비용에서 더 보기
              </button>
            </div>
            <AsyncState query={usage}>
              {(data) => (
                <DailyCallsChart rows={data.by_day} currency={data.currency} />
              )}
            </AsyncState>
          </div>
          <div className="grid content-start gap-2 rounded-xl border bg-card p-4 shadow-xs">
            <h4 className="text-sm font-semibold">기능별 AI 사용</h4>
            {features.length
              ? features.map((row) => (
                  <div key={row.key} className="grid gap-1 text-xs">
                    <span className="flex justify-between">
                      {FEATURE_LABELS[row.key] ?? row.key}
                      <span className="text-muted-foreground">
                        {row.call_count}회
                      </span>
                    </span>
                    <span className="h-2 rounded-full bg-muted">
                      <span
                        className="block h-2 rounded-full bg-primary"
                        style={{ width: `${(row.call_count / most) * 100}%` }}
                      />
                    </span>
                  </div>
                ))
              : // 받는 중이거나 못 받았으면 호출이 없다고 말하지 않는다. 상태는 옆 그래프 칸에 보인다.
                usage.data && (
                  <p className="text-xs text-muted-foreground">
                    이 기간에 기록된 AI 호출이 없어요.
                  </p>
                )}
          </div>
          <div className="grid content-start gap-1.5 rounded-xl border bg-card p-4 shadow-xs">
            <h4 className="text-sm font-semibold">최근 활동</h4>
            {recent.length ? (
              recent.map((item) => (
                <p key={item.id} className="text-xs">
                  {`${actorName(item)} · ${statusLabel(item.type) || item.type}`}
                </p>
              ))
            ) : (
              <p className="text-xs text-muted-foreground">
                최근 활동이 없어요.
              </p>
            )}
            <button
              type="button"
              className="justify-self-start text-xs underline"
              onClick={() => onTab('timeline')}
            >
              활동 타임라인에서 더 보기
            </button>
          </div>
          <div className="grid content-start gap-1.5 rounded-xl border bg-card p-4 shadow-xs">
            <h4 className="text-sm font-semibold">에이전트</h4>
            {agents.length ? (
              agents.map((agent) => (
                <p key={agent.member_id} className="text-xs">
                  {`${members.find((member) => member.id === agent.member_id)?.name ?? '팀원'}의 에이전트 · ${
                    agent.current_task_id
                      ? `${agent.task_title ?? `작업 #${agent.current_task_id}`} ${statusLabel(agent.task_status)}`
                      : '맡은 작업 없음'
                  }`}
                </p>
              ))
            ) : (
              <p className="text-xs text-muted-foreground">
                연결된 에이전트가 없어요.
              </p>
            )}
            <button
              type="button"
              className="justify-self-start text-xs underline"
              onClick={() => onTab('agents')}
            >
              에이전트 현황에서 더 보기
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
