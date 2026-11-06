import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Activity, Wallet } from 'lucide-react'
import { dashboardApi } from '@/api/services/dashboard'
import { unlessMissing } from '@/api/error'
import type { UsageBucket } from '@/api/contracts/common'
import { DashboardOverview } from './DashboardOverview'
import { RelationsView } from './RelationsView'
import { FEATURE_LABELS, costText, dashboardTabs, unknownText } from './labels'
import type { DashboardTab } from './labels'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import { membersQuery } from '@/features/members/membersQuery'

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
const usageViews = [
  ['by_feature', '기능별'],
  ['by_task', '작업별'],
  ['by_validation', '검증별'],
] as const
const clock = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))
const seoulDay = (value: string | number) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(
    new Date(value),
  )
// 한국시간 날짜로 묶는다: '오늘 · 10월 27일', '어제 · 10월 26일', '10월 23일'
function groupByDay<T extends { created_at: string }>(items: T[]) {
  const today = seoulDay(Date.now())
  const yesterday = seoulDay(Date.now() - 86_400_000)
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const day = seoulDay(item.created_at)
    const [, month, date] = day.split('-').map(Number)
    const name = `${day === today ? '오늘 · ' : day === yesterday ? '어제 · ' : ''}${month}월 ${date}일`
    groups.set(name, [...(groups.get(name) ?? []), item])
  }
  return [...groups]
}
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

// 단축 바 5번(설계 관계)과 6번(대시보드)이 같이 쓴다.
export function ActivityPanel({
  projectId,
  view = 'dashboard',
}: {
  projectId: string
  isLeader?: boolean
  view?: 'dashboard' | 'relations'
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [usageView, setUsageView] = useState(0)
  const [tab, setTab] = useState<DashboardTab>('overview')
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
  const activities = useQuery({
    queryKey: ['projects', projectId, 'activities', cursor],
    queryFn: () => dashboardApi.activities(projectId, { cursor, limit: 20 }),
  })
  const work = useQuery({
    queryKey: ['projects', projectId, 'agent-work', workCursor],
    // API 없음: 서버에 아직 없으면(404) 에이전트 현황은 '준비 중'으로 둔다(AGT-A04·A05 -178 구현 전과 같은 규칙).
    queryFn: () =>
      unlessMissing(
        dashboardApi.agentWork(projectId, { cursor: workCursor, limit: 20 }),
      ),
  })
  const members = useQuery(membersQuery(projectId))
  const statusOf = useAgentStatusOf(projectId)
  const memberName = (memberId: string) =>
    members.data?.items.find((member) => member.id === memberId)?.name ??
    `팀원 #${memberId}`
  const actorName = (accountId?: string) =>
    accountId
      ? (members.data?.items.find((member) => member.account_id === accountId)
          ?.name ?? `회원 #${accountId}`)
      : '시스템'
  if (view === 'relations') return <RelationsView projectId={projectId} />
  return (
    <div className="grid gap-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <h2 className="sr-only">대시보드</h2>
        {/* 대시보드 탭: 한눈에 보기 / 활동 타임라인 / 에이전트 현황 / 사용량·비용. 기간 선택은 탭 줄 오른쪽 한 곳에서 모든 숫자에 쓴다. */}
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
        <form
          aria-label="기간"
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
      </header>
      {tab === 'overview' && (
        <DashboardOverview
          onTab={setTab}
          projectId={projectId}
          usage={usage}
          activities={activities}
          work={work}
          members={members.data?.items ?? []}
          actorName={actorName}
        />
      )}
      {tab === 'timeline' && (
        <Card id="dashboard-timeline">
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
                    {/* API 없음: 활동에 사람·에이전트 구분이 없어 '보기 [전체][사람][에이전트]' 거르기는 아직 두지 않는다. */}
                    <div className="grid gap-3">
                      {groupByDay(data.items).map(([day, items]) => (
                        <section
                          key={day}
                          aria-label={day}
                          className="rounded-2xl border bg-card px-4 py-2"
                        >
                          <h4 className="py-1.5 text-[11.5px] text-muted-foreground">
                            {day}
                          </h4>
                          <ol>
                            {items.map((item) => (
                              <li
                                key={item.id}
                                className="flex items-center gap-3 border-t py-2.5 text-[13px]"
                              >
                                <time
                                  className="w-11 shrink-0 text-xs text-muted-foreground tabular-nums"
                                  dateTime={item.created_at}
                                >
                                  {clock(item.created_at)}
                                </time>
                                <span className="min-w-0 flex-1">
                                  <strong className="font-semibold">
                                    {actorName(item.actor_account_id)}
                                  </strong>{' '}
                                  {statusLabel(item.type)}
                                  {item.task_id && (
                                    <Button
                                      variant="link"
                                      className="ml-2 h-auto p-0 text-xs"
                                      onClick={() =>
                                        openPane('tasks', item.task_id)
                                      }
                                    >
                                      작업 #{item.task_id}
                                    </Button>
                                  )}
                                  {item.change_proposal_id && (
                                    <Button
                                      variant="link"
                                      className="ml-2 h-auto p-0 text-xs"
                                      onClick={() =>
                                        openPane(
                                          'changes',
                                          item.change_proposal_id,
                                        )
                                      }
                                    >
                                      변경 요청 #{item.change_proposal_id}
                                    </Button>
                                  )}
                                  {item.baseline_id && (
                                    <Button
                                      variant="link"
                                      className="ml-2 h-auto p-0 text-xs"
                                      onClick={() => openPane('documents')}
                                    >
                                      기준 설계 #{item.baseline_id}
                                    </Button>
                                  )}
                                </span>
                                <span
                                  className={cn(
                                    'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold',
                                    item.result === 'SUCCESS'
                                      ? 'bg-emerald-50 text-[#00753a]'
                                      : item.result === 'FAILED'
                                        ? 'bg-[#fdecec] text-[#a3141b]'
                                        : 'bg-muted text-muted-foreground',
                                  )}
                                >
                                  {statusLabel(item.result) || item.result}
                                </span>
                              </li>
                            ))}
                          </ol>
                        </section>
                      ))}
                    </div>
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
      )}
      {tab === 'agents' && (
        <Card id="dashboard-agents">
          <CardHeader>
            <CardTitle className="text-base">에이전트 현황</CardTitle>
            <p className="text-xs leading-5 text-muted-foreground">
              연결 여부, 작업 상태, 계획 검증 결과를 각각 확인해요.
            </p>
          </CardHeader>
          <CardContent>
            <AsyncState query={work}>
              {(data) =>
                !data ? (
                  <p className="text-sm text-muted-foreground">
                    에이전트 현황은 준비 중이에요. 서버가 연결 보고를 받기
                    시작하면 여기에 보여요.
                  </p>
                ) : data.items.length ? (
                  <>
                    {/* 캔버스 PNL-09-v2-agents: 에이전트마다 카드. API 없음: 에이전트별 사용량·최근 보고 줄과 '웹에서 직접 한 호출'은 응답에 없다. */}
                    {/* 줄마다 카드 모양이지만 표 구조(열 머리는 화면 밖)를 지켜 읽기 도구가 칸을 알 수 있게 한다. */}
                    <table className="block w-full text-left">
                      <thead className="sr-only">
                        <tr>
                          <th>에이전트·연결</th>
                          <th>현재 작업</th>
                          <th>작업 상태</th>
                          <th>계획 검증</th>
                          <th>대기 이유</th>
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
                                <span
                                  aria-hidden="true"
                                  className="grid size-6.5 shrink-0 place-items-center rounded-full bg-[#e9e4ff] text-[10px] font-bold text-[#4b3aa8]"
                                >
                                  AI
                                </span>
                                <strong className="text-sm font-bold">
                                  <span>{memberName(item.member_id)}</span>의
                                  에이전트
                                </strong>
                                <span
                                  className={cn(
                                    'ml-auto inline-flex items-center gap-1.5 text-xs',
                                    agentStatus === 'connected'
                                      ? 'text-[#00753a]'
                                      : 'text-[#c99e1e]',
                                  )}
                                >
                                  <span
                                    aria-hidden="true"
                                    className="size-1.75 rounded-full bg-current"
                                  />
                                  {agentStatusLabels[agentStatus]}
                                </span>
                              </td>
                              {(
                                [
                                  [
                                    '현재 작업',
                                    item.current_task_id ? (
                                      <Button
                                        variant="link"
                                        className="h-auto justify-start p-0 text-[13px] font-semibold"
                                        onClick={() =>
                                          openPane(
                                            'tasks',
                                            item.current_task_id,
                                          )
                                        }
                                      >
                                        작업 #{item.current_task_id}
                                      </Button>
                                    ) : (
                                      <span className="text-muted-foreground/70">
                                        보고된 작업 없음
                                      </span>
                                    ),
                                  ],
                                  [
                                    '작업 상태',
                                    item.task_status ? (
                                      <StatusPill value={item.task_status} />
                                    ) : (
                                      <span className="text-muted-foreground/70">
                                        미보고
                                      </span>
                                    ),
                                  ],
                                  [
                                    '계획 검증',
                                    item.validation_status ? (
                                      <StatusPill
                                        value={item.validation_status}
                                      />
                                    ) : (
                                      <span className="text-muted-foreground/70">
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
                                    className="text-[11.5px] text-muted-foreground/70"
                                  >
                                    {label}
                                  </span>
                                  <span className="text-[13px] font-semibold">
                                    {value}
                                  </span>
                                </td>
                              ))}
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                    <p className="mt-4 text-xs leading-[18px] text-muted-foreground/70">
                      에이전트가 보고하지 않은 실행 중·완료 상태는 추정하지
                      않아요. 연결 여부는 연결 보고, 작업 상태는 작업 보고를
                      따로 봐요.
                    </p>
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
      )}
      {tab === 'usage' && (
        <Card id="dashboard-usage">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Wallet className="size-4" />
              기간별 사용량
            </CardTitle>
          </CardHeader>
          <CardContent className="grid gap-5">
            <AsyncState query={usage}>
              {(data) => (
                <>
                  <dl className="grid grid-cols-2 gap-3 xl:grid-cols-4">
                    {[
                      {
                        label: '입력 토큰',
                        value: data.input_tokens.toLocaleString('ko-KR'),
                        unknown: data.tokens_unknown_call_count,
                      },
                      {
                        label: '출력 토큰',
                        value: data.output_tokens.toLocaleString('ko-KR'),
                        unknown: data.tokens_unknown_call_count,
                      },
                      {
                        label: '호출',
                        value: data.call_count.toLocaleString('ko-KR'),
                      },
                      {
                        label: '추정 비용',
                        // 호출이 없으면 서버가 비용을 생략한다. '미보고'가 아니라 비어 있음으로 본다.
                        value: data.call_count
                          ? costText(data.estimated_cost_amount, data.currency)
                          : '—',
                        unknown: data.cost_unknown_call_count,
                      },
                    ].map((metric) => (
                      <div
                        key={metric.label}
                        className="rounded-xl border bg-card p-4 shadow-xs"
                      >
                        <dt className="text-xs text-muted-foreground">
                          {metric.label}
                        </dt>
                        <dd className="mt-2 font-mono text-xl font-semibold">
                          <WithUnknown
                            value={metric.value}
                            unknown={metric.unknown}
                          />
                        </dd>
                      </div>
                    ))}
                  </dl>
                  <p className="text-xs leading-5 text-muted-foreground">
                    BEEZ에 기록된 호출을 집계한 값이에요. 외부 에이전트의 개인
                    사용량과 실제 청구액은 포함하지 않아요.
                  </p>
                  {/* API 없음: 사용량 응답에 에이전트별 묶음이 없어 기능별·작업별·검증별만 전환한다. */}
                  <section className="grid gap-1 rounded-[14px] border bg-card px-4 py-3.5 shadow-xs">
                    <div className="flex items-center justify-between">
                      <h3 className="text-sm font-semibold">
                        {usageViews[usageView][1]}
                      </h3>
                      <span role="tablist" className="flex gap-1 text-xs">
                        {usageViews.map(([key, name], index) => (
                          <button
                            key={key}
                            type="button"
                            role="tab"
                            aria-selected={usageView === index}
                            onClick={() => setUsageView(index)}
                            className={cn(
                              'rounded-full px-2.5 py-0.5 font-semibold',
                              usageView === index
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
                      key={usageViews[usageView][0]}
                      rows={data[usageViews[usageView][0]]}
                      currency={data.currency}
                      label={(key) =>
                        usageView === 0
                          ? (FEATURE_LABELS[key] ?? key)
                          : usageView === 1
                            ? `작업 #${key}`
                            : `검증 #${key}`
                      }
                      onSelect={
                        usageView === 1
                          ? (id) => openPane('tasks', id)
                          : undefined
                      }
                    />
                  </section>
                </>
              )}
            </AsyncState>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

// 묶음 하나를 막대 줄로 보여 준다. 막대 길이는 호출 수가 가장 많은 줄 기준이다.
function UsageBars({
  rows,
  currency,
  label,
  onSelect,
}: {
  rows: UsageBucket[]
  currency?: string
  label: (key: string) => string
  onSelect?: (id: string) => void
}) {
  const most = Math.max(1, ...rows.map((row) => row.call_count))
  // 서버는 기능별을 호출이 없어도 0으로 채워 보낸다. 모두 0이면 빈 안내를 보인다.
  if (!rows.some((row) => row.call_count > 0))
    return (
      <p className="py-2 text-xs text-muted-foreground">
        기록된 사용량이 없어요.
      </p>
    )
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
                {label(row.key)}
              </button>
            ) : (
              label(row.key)
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
function WithUnknown({ value, unknown }: { value: string; unknown?: number }) {
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
