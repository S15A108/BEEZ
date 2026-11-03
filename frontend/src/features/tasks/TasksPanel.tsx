import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { ClipboardCheck, Ellipsis, Play, Plus, RefreshCw } from 'lucide-react'
import {
  contextsApi,
  planningApi,
  tasksApi,
  validationsApi,
} from '@/api/services'
import {
  idSchema,
  taskPlanInputSchema,
  type DesignItemVersion,
  type TaskDetail,
  type TaskPlanInput,
  type TaskStateInput,
} from '@/api/contracts/common'
import {
  getApiErrorDetail,
  getApiErrorMessage,
  isMissingApi,
} from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { PaneHeaderAction } from '@/components/PaneHeaderAction'
import { PaneSheet, SheetSubmit } from '@/components/PaneSheet'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { describeContent, LIST_SELECTED, lines } from '@/lib/display'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { membersQuery } from '@/features/members/membersQuery'

interface Props {
  projectId: string
  isLeader: boolean
}
type StateEvent = TaskStateInput['event']
const relationLabel = {
  READS: '참고만',
  IMPLEMENTS: '구현',
  CHANGES: '계약 변경',
} as const

const taskSteps = ['계획 작성', '계획 검증', '작업 시작', '완료 보고']
// 서버가 검증을 무효로 만든 이유 코드(stale_reason). 모르는 값은 그대로 보여 준다.
const staleReasonLabel: Record<string, string> = {
  ASSIGNEE_CHANGED: '담당자가 바뀌어 새 담당자가 다시 검증받아야 해요.',
}
const policyStateLabel = {
  SUPPORTED: '승인 근거 확인',
  EXPLICITLY_UNDECIDED: '정책 미확정',
  CONTRADICTORY: '정책 불일치',
  INSUFFICIENT: '근거 부족',
} as const

export function TasksPanel({ projectId, isLeader }: Props) {
  const session = useSession()
  const focusId = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [selected, setSelected] = useState<{
    id: string
    focus: string | null
  } | null>(null)
  const [editing, setEditing] = useState<'new' | 'edit' | null>(null)
  const [formTask, setFormTask] = useState<TaskDetail | undefined>()
  const [reporting, setReporting] = useState<'WAIT' | 'COMPLETED' | null>(null)
  const [assigning, setAssigning] = useState(false)
  const [recording, setRecording] = useState(false)
  const action = useProjectAction(projectId)
  const tasks = useInfiniteQuery({
    queryKey: ['projects', projectId, 'tasks'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      tasksApi.list(projectId, { cursor: pageParam, limit: 50 }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const members = useQuery(membersQuery(projectId))
  const memberId = members.data?.items.find(
    (member) => member.account_id === session.data?.id,
  )?.id
  const [tab, setTab] = useState<'mine' | 'team'>('mine')
  const entries = tasks.data?.pages.flatMap((page) => page.items) ?? []
  // ponytail: 불러온 목록을 화면에서 거른다. 목록이 길어지면 assignee_member_id 파라미터로 옮긴다.
  const shown =
    tab === 'mine'
      ? entries.filter((entry) => entry.assignee_member_id === memberId)
      : entries
  const selectedId =
    selected?.focus === focusId ? selected.id : (focusId ?? shown[0]?.id)
  const detail = useQuery({
    queryKey: ['projects', projectId, 'task', selectedId],
    queryFn: () => tasksApi.get(projectId, selectedId!),
    enabled: !!selectedId,
    refetchInterval: (query) =>
      query.state.data?.validation_status === 'RUNNING' ||
      query.state.data?.waiting_reason === 'REVALIDATION'
        ? 1500
        : false,
  })
  const task = detail.data
  const approved = useInfiniteQuery({
    queryKey: ['projects', projectId, 'approved-design-items'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.items(projectId, {
        status: 'APPROVED',
        cursor: pageParam,
        limit: 100,
      }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const revisions = useQuery({
    queryKey: ['projects', projectId, 'task-revisions', selectedId],
    queryFn: () =>
      tasksApi.planRevisions(projectId, selectedId!, { limit: 100 }),
    enabled: !!selectedId,
  })
  const validationId = task?.delta_validation_id ?? task?.validation?.id
  const validation = useQuery({
    queryKey: ['projects', projectId, 'validation', validationId],
    queryFn: () => validationsApi.get(projectId, validationId!),
    enabled: !!validationId,
    refetchInterval: (query) =>
      query.state.data?.status === 'RUNNING' ? 1500 : false,
  })
  const records = useQuery({
    queryKey: ['projects', projectId, 'task-records', selectedId],
    queryFn: () =>
      contextsApi.list(projectId, { task_id: selectedId!, limit: 100 }),
    enabled: !!selectedId,
  })
  const packet = useQuery({
    queryKey: [
      'projects',
      projectId,
      'context-packet',
      task?.current_plan_revision.plan.context_packet_id,
    ],
    queryFn: () =>
      contextsApi.packet(
        projectId,
        task!.current_plan_revision.plan.context_packet_id!,
      ),
    enabled: !!task?.current_plan_revision.plan.context_packet_id,
  })
  const isOwner = !!task && memberId === task.assignee_member_id
  const active = !!task && !['COMPLETED', 'CANCELLED'].includes(task.status)
  const result = validation.data ?? task?.validation
  const needsHumanReview = result?.findings.some(
    (finding) =>
      finding.kind === 'ERROR' && finding.resolution === 'HUMAN_REVIEW',
  )
  const canStart =
    isOwner &&
    active &&
    result?.status === 'PASS' &&
    result.plan_revision_id === task?.current_plan_revision.id &&
    ['READY', 'WAITING'].includes(task?.status ?? '')
  const blocked = ['NEEDS_INPUT', 'NEEDS_REVIEW'].includes(result?.status ?? '')
  const primary =
    task?.status === 'IN_PROGRESS'
      ? 'complete'
      : canStart
        ? 'start'
        : blocked
          ? 'revise'
          : 'validate'
  // 오른쪽 단계 표시(PNL-05 수정안): 계획 작성 → 계획 검증 → 작업 시작 → 완료 보고
  const step =
    task?.status === 'COMPLETED'
      ? 4
      : task?.status === 'IN_PROGRESS'
        ? 3
        : canStart
          ? 2
          : 1
  const startBlocked =
    result?.status === 'RUNNING'
      ? '계획 검증 중이라 시작할 수 없어요.'
      : blocked
        ? '검증에서 막힌 항목을 고쳐야 시작할 수 있어요.'
        : result?.status === 'STALE'
          ? (staleReasonLabel[result.stale_reason ?? ''] ??
            '기준이 바뀌어 다시 검증해야 해요.')
          : result?.status === 'ERROR'
            ? '검증을 마치지 못했어요. 다시 실행해 주세요.'
            : result?.status === 'PASS'
              ? '지금 계획으로 다시 검증해 주세요.'
              : '계획 검증을 통과해야 시작할 수 있어요.'
  const designItems = approved.data?.pages.flatMap((page) => page.items) ?? []
  // 근거는 '정확한 버전 311' 대신 'REQ-001 승인 v1 · 구현'처럼 사람 말로(M7).
  const refLabel = (reference: {
    design_item_id: string
    design_item_version_id: string
    relation: string
  }) => {
    const key =
      designItems.find(
        (item) => item.design_item_id === reference.design_item_id,
      )?.key ?? '설계 항목'
    const version = designItems.find(
      (item) => item.id === reference.design_item_version_id,
    )?.version_no
    return `${key} ${version ? `승인 v${version}` : '이전 승인본'} · ${relationLabel[reference.relation as keyof typeof relationLabel]}`
  }
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  const report = (event: StateEvent) => {
    if (!task) return
    perform(
      () =>
        tasksApi.state(projectId, task.id, {
          event,
          plan_revision_id: task.current_plan_revision.id,
          expected_lock_version: task.lock_version,
          ...(['START', 'RESUME'].includes(event)
            ? { validation_id: result?.id }
            : {}),
        }),
      event === 'CANCEL'
        ? '작업을 취소했어요. 계획과 검증 이력은 유지해요.'
        : '현재 기준을 확인하고 작업 상태를 반영했어요.',
    )
  }

  return (
    <section className="space-y-6">
      {/* 창 머리에 이미 '작업 현황'이 있어 안쪽 제목을 두지 않고, 설명 문구도 캔버스 PNL-05-v2-tasks(v461 이후)처럼 뺀다.
          노란 '+ 작업 계획' 하나는 캔버스처럼 창 머리 줄에 둔다(PNL-05 수정안 10-29). */}
      <PaneHeaderAction>
        <Button
          onClick={() => {
            setFormTask(undefined)
            setEditing('new')
          }}
        >
          <Plus />
          작업 계획
        </Button>
      </PaneHeaderAction>
      {action.message && (
        <p role="status" className="rounded-lg bg-primary/5 p-3 text-sm">
          {action.message}
        </p>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-[.65fr_1.35fr]">
        <Card>
          <CardHeader>
            <CardTitle className="flex gap-1" role="tablist">
              {(
                [
                  ['mine', '내 작업'],
                  ['team', '팀 전체'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  role="tab"
                  aria-selected={tab === value}
                  onClick={() => {
                    setTab(value)
                    setSelected(null)
                  }}
                  className={`rounded-full px-3 py-1 text-sm ${tab === value ? 'bg-foreground text-background' : 'bg-muted'}`}
                >
                  {label}
                </button>
              ))}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <AsyncState query={tasks}>
              {() => (
                <div className="space-y-2">
                  {shown.length ? (
                    shown.map((entry) => (
                      <button
                        key={entry.id}
                        type="button"
                        aria-current={entry.id === selectedId}
                        className={cn(
                          'w-full rounded-xl border p-4 text-left transition-colors hover:bg-muted/50',
                          entry.id === selectedId && LIST_SELECTED,
                        )}
                        onClick={() =>
                          setSelected({ id: entry.id, focus: focusId })
                        }
                      >
                        <span className="block text-xs text-muted-foreground">
                          {entry.key}
                          {tab === 'team' &&
                            ` · ${
                              entry.assignee_member_id === memberId
                                ? '나'
                                : (members.data?.items.find(
                                    (member) =>
                                      member.id === entry.assignee_member_id,
                                  )?.name ?? '담당자 미정')
                            }`}
                        </span>
                        <strong className="my-2 block text-sm font-medium">
                          {entry.current_plan_revision.plan.title}
                        </strong>
                        <span className="flex flex-wrap gap-2">
                          <StatusPill value={entry.status} />
                          <StatusPill value={entry.validation_status} />
                        </span>
                      </button>
                    ))
                  ) : (
                    <EmptyState>
                      {tab === 'mine'
                        ? '아직 맡은 작업이 없어요. 첫 작업 계획을 등록하거나 팀 전체를 보세요.'
                        : '맡을 작업의 목표와 범위부터 등록해 보세요.'}
                    </EmptyState>
                  )}
                  {tasks.hasNextPage && (
                    <Button
                      variant="outline"
                      disabled={tasks.isFetchingNextPage}
                      onClick={() => void tasks.fetchNextPage()}
                    >
                      작업 더 보기
                    </Button>
                  )}
                </div>
              )}
            </AsyncState>
          </CardContent>
        </Card>
        {!selectedId ? (
          <EmptyState>
            작업을 선택하면 계획과 검증 결과를 확인할 수 있어요.
          </EmptyState>
        ) : (
          <AsyncState query={detail}>
            {(current) => (
              <div className="space-y-5">
                <Card>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>
                        {current.current_plan_revision.plan.title}
                      </CardTitle>
                      <StatusPill value={current.status} />
                    </div>
                    <CardDescription>
                      {current.key} · 담당자{' '}
                      {members.data?.items.find(
                        (member) => member.id === current.assignee_member_id,
                      )?.name ?? '확인 중'}{' '}
                      · 계획 r{current.current_plan_revision.revision_no}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <p className="text-sm leading-6 whitespace-pre-wrap">
                      {current.current_plan_revision.plan.goal}
                    </p>
                    <div className="grid gap-4 sm:grid-cols-2">
                      <div>
                        <p className="mb-2 text-xs font-medium text-muted-foreground">
                          포함하는 범위
                        </p>
                        <ul className="list-inside list-disc space-y-1 text-sm">
                          {current.current_plan_revision.plan.included_scope.map(
                            (value, index) => (
                              <li key={index}>{value}</li>
                            ),
                          )}
                        </ul>
                      </div>
                      <div>
                        <p className="mb-2 text-xs font-medium text-muted-foreground">
                          이번에 제외하는 범위
                        </p>
                        <ul className="list-inside list-disc space-y-1 text-sm">
                          {current.current_plan_revision.plan.excluded_scope.map(
                            (value, index) => (
                              <li key={index}>{value}</li>
                            ),
                          )}
                        </ul>
                      </div>
                    </div>
                    <p className="rounded-lg bg-muted/40 p-3 text-sm leading-6 whitespace-pre-wrap">
                      {current.current_plan_revision.plan.approach}
                    </p>
                    <div>
                      <p className="mb-2 text-xs font-medium text-muted-foreground">
                        완료 기준
                      </p>
                      <ul className="list-inside list-disc space-y-1 text-sm">
                        {current.current_plan_revision.plan.completion_criteria.map(
                          (value, index) => (
                            <li key={index}>{value}</li>
                          ),
                        )}
                      </ul>
                    </div>
                    {current.status === 'IN_PROGRESS' && (
                      <p className="rounded-lg bg-muted/40 p-3 text-sm">
                        시작 직전에 참조 설계와 선행 작업을 다시 확인했어요.
                      </p>
                    )}
                    {current.status === 'COMPLETED' && (
                      <div className="grid gap-2 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
                        <p className="font-semibold">완료 보고를 마쳤어요</p>
                        <ul className="grid gap-1">
                          {current.current_plan_revision.plan.completion_criteria.map(
                            (value, index) => (
                              <li key={index}>✓ {value}</li>
                            ),
                          )}
                        </ul>
                        {/* API 없음: 완료 보고의 요약과 계획과 달라진 점이 작업 상세 응답에 없어 완료 기준만 다시 보여 준다. */}
                        {isOwner && (
                          <Button
                            className="justify-self-start"
                            onClick={() => {
                              setFormTask(undefined)
                              setEditing('new')
                            }}
                          >
                            다음 작업 계획 등록
                          </Button>
                        )}
                      </div>
                    )}
                    <ol
                      aria-label="작업 단계"
                      className="flex flex-wrap items-center gap-1.5 border-t pt-4 text-xs"
                    >
                      {taskSteps.map((label, index) => (
                        <li
                          key={label}
                          aria-current={index === step ? 'step' : undefined}
                          className={cn(
                            'rounded-full px-2.5 py-1',
                            index < step && 'bg-muted text-muted-foreground',
                            index === step && 'bg-primary font-semibold',
                            index > step && 'border text-muted-foreground',
                          )}
                        >
                          {index < step ? '✓ ' : ''}
                          {label}
                        </li>
                      ))}
                    </ol>
                    {/* 주 버튼은 상태마다 하나(M5): 진행 중이면 완료 보고, 시작할 수 있으면 시작, 막혔으면 계획 수정, 그 밖에는 계획 검증. */}
                    <div
                      className={cn(
                        'flex flex-wrap items-start gap-2',
                        isOwner &&
                          active &&
                          'rounded-xl border border-primary/40 bg-primary/5 p-4',
                      )}
                    >
                      {isOwner && active && (
                        <p className="w-full text-xs font-semibold">
                          다음 할 일
                        </p>
                      )}
                      {isOwner && active && primary === 'revise' && (
                        <Button
                          disabled={action.busy}
                          onClick={() => {
                            setFormTask(current)
                            setEditing('edit')
                          }}
                        >
                          계획 수정
                        </Button>
                      )}
                      {isOwner && active && (
                        <Button
                          variant={primary === 'validate' ? 'default' : 'link'}
                          size={primary === 'validate' ? 'default' : 'sm'}
                          disabled={action.busy || result?.status === 'RUNNING'}
                          onClick={() =>
                            perform(
                              () =>
                                validationsApi.create(projectId, current.id, {
                                  plan_revision_id:
                                    current.current_plan_revision.id,
                                  expected_lock_version: current.lock_version,
                                }),
                              '계획 검증을 접수했어요.',
                            )
                          }
                        >
                          {primary === 'validate' && <ClipboardCheck />}
                          {primary === 'validate' ? '계획 검증' : '다시 검증'}
                        </Button>
                      )}
                      {isOwner &&
                        active &&
                        ['DRAFT', 'READY', 'WAITING'].includes(
                          current.status,
                        ) && (
                          <span className="grid gap-1">
                            <Button
                              variant={
                                primary === 'start' ? 'default' : 'outline'
                              }
                              disabled={action.busy || !canStart}
                              onClick={() =>
                                report(
                                  current.status === 'WAITING'
                                    ? 'RESUME'
                                    : 'START',
                                )
                              }
                            >
                              <Play />
                              {current.status === 'WAITING'
                                ? '재개'
                                : '작업 시작'}
                            </Button>
                            {!canStart && (
                              <span className="text-xs text-muted-foreground">
                                {startBlocked}
                              </span>
                            )}
                          </span>
                        )}
                      {isOwner && current.status === 'IN_PROGRESS' && (
                        <>
                          <Button
                            disabled={action.busy}
                            onClick={() => {
                              setFormTask(current)
                              setReporting('COMPLETED')
                            }}
                          >
                            완료 보고
                          </Button>
                          <Button
                            variant="outline"
                            disabled={action.busy}
                            onClick={() => {
                              setFormTask(current)
                              setReporting('WAIT')
                            }}
                          >
                            대기 보고
                          </Button>
                        </>
                      )}
                      {/* 새 리비전·담당자 변경·작업 취소는 '더 보기'로 묶는다. */}
                      {active && (isOwner || isLeader) && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="icon"
                              aria-label="더 보기"
                              disabled={action.busy}
                            >
                              <Ellipsis />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            {isOwner && primary !== 'revise' && (
                              <DropdownMenuItem
                                onSelect={() => {
                                  setFormTask(current)
                                  setEditing('edit')
                                }}
                              >
                                계획 수정
                              </DropdownMenuItem>
                            )}
                            {isLeader && (
                              <DropdownMenuItem
                                onSelect={() => {
                                  setFormTask(current)
                                  setAssigning(true)
                                }}
                              >
                                담당자 바꾸기
                              </DropdownMenuItem>
                            )}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              variant="destructive"
                              onSelect={() => report('CANCEL')}
                            >
                              작업 취소
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}
                    </div>
                    {!isOwner && (
                      <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                        <span className="rounded-full bg-muted px-2 py-0.5 font-medium text-foreground">
                          보기만 · 담당자{' '}
                          {members.data?.items.find(
                            (member) =>
                              member.id === current.assignee_member_id,
                          )?.name ?? '확인 중'}
                        </span>
                        계획 변경·검증·시작·완료 보고는 담당자가 해요. 의견은
                        변경 제안으로 남겨 주세요.
                        {/* 열린 질문 Q5: '회의로 묻기'는 사람이 회의를 만들지 않는 결정과 부딪혀 두지 않는다. */}
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => openPane('changes')}
                        >
                          변경 제안
                        </Button>
                      </p>
                    )}
                  </CardContent>
                </Card>
                <Card className={blocked ? 'border-destructive/60' : ''}>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>설계와 계획의 검증 결과</CardTitle>
                      <StatusPill
                        value={result?.status ?? current.validation_status}
                      />
                    </div>
                    <CardDescription>
                      {result &&
                        `r${
                          revisions.data?.items.find(
                            (revision) =>
                              revision.id === result.plan_revision_id,
                          )?.revision_no ??
                          current.current_plan_revision.revision_no
                        } 기준 · ${formatDate(result.finished_at ?? result.created_at)} · `}
                      계획 검증은 코드 품질이나 테스트 통과를 보장하지 않아요.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {validation.error &&
                      (isMissingApi(validation.error) ? (
                        <p
                          role="status"
                          className="text-sm text-muted-foreground"
                        >
                          검증 결과는 서버에서 아직 준비 중이에요.
                        </p>
                      ) : (
                        <p role="alert" className="text-sm text-destructive">
                          검증 결과를 불러오지 못했어요.
                        </p>
                      ))}
                    {current.waiting_reason && (
                      <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
                        대기 이유: <StatusPill value={current.waiting_reason} />
                      </p>
                    )}
                    {result?.stale_reason && (
                      <p className="text-sm text-amber-800">
                        {staleReasonLabel[result.stale_reason] ??
                          result.stale_reason}
                      </p>
                    )}
                    {result?.status === 'RUNNING' && (
                      <p
                        role="status"
                        className="text-sm text-muted-foreground"
                      >
                        같은 검증의 분석 결과를 확인하고 있어요.
                      </p>
                    )}
                    {result?.status === 'ERROR' && (
                      <div className="flex flex-wrap items-center gap-3">
                        <p role="alert" className="text-sm text-destructive">
                          {needsHumanReview
                            ? '근거를 확인하고 보완한 뒤 새 검증을 요청해 주세요.'
                            : '분석을 완료하지 못했어요. 자동으로 통과 처리하지 않아요.'}
                        </p>
                        {isOwner && !needsHumanReview && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={action.busy}
                            onClick={() =>
                              perform(
                                () =>
                                  validationsApi.retry(projectId, result.id, {
                                    expected_attempt: result.current_attempt,
                                  }),
                                '검증 재시도를 접수했어요.',
                              )
                            }
                          >
                            <RefreshCw />
                            실행 재시도
                          </Button>
                        )}
                      </div>
                    )}
                    {result?.policy_analysis && (
                      <section
                        className="space-y-3 rounded-lg border p-4"
                        aria-label="정책 분석"
                      >
                        <div>
                          <h3 className="text-sm font-semibold">정책 분석</h3>
                          <p className="mt-1 text-xs text-muted-foreground">
                            계획과 승인 근거를 비교한 분석 후보예요. 내용을
                            검토해 주세요.
                          </p>
                        </div>
                        {result.policy_analysis
                          .no_policy_requirements_reason && (
                          <p className="text-sm">
                            {
                              result.policy_analysis
                                .no_policy_requirements_reason
                            }
                          </p>
                        )}
                        {result.policy_analysis
                          .no_policy_requirements_evidence && (
                          <p className="text-xs text-muted-foreground">
                            계획 근거: “
                            {
                              result.policy_analysis
                                .no_policy_requirements_evidence.quote
                            }
                            ”
                          </p>
                        )}
                        {result.policy_analysis
                          .policy_assessments_truncated && (
                          <p className="text-sm text-destructive">
                            분석할 정책이 많아 일부만 확인했어요.
                          </p>
                        )}
                        {result.policy_analysis.policy_assessments.map(
                          (policy) => (
                            <article
                              key={policy.need_id}
                              className="space-y-1 border-t pt-3 text-sm"
                            >
                              <div className="flex flex-wrap items-center gap-2">
                                <strong>{policy.topic}</strong>
                                <span className="text-xs text-muted-foreground">
                                  {policyStateLabel[policy.state]}
                                </span>
                              </div>
                              <p>{policy.explanation}</p>
                              <p className="text-xs text-muted-foreground">
                                계획 근거: “{policy.plan_evidence.quote}”
                              </p>
                              {policy.approved_content_refs.map(
                                (ref, index) => (
                                  <p
                                    key={index}
                                    className="text-xs text-muted-foreground"
                                  >
                                    승인 설계 버전 {ref.design_item_version_id}:
                                    “{ref.quote}”
                                  </p>
                                ),
                              )}
                            </article>
                          ),
                        )}
                      </section>
                    )}
                    {result?.findings.map((finding) => (
                      <article
                        key={finding.id}
                        className="rounded-lg border p-4"
                      >
                        <div className="mb-2 flex flex-wrap gap-2">
                          <StatusPill value={finding.severity} />
                          <span className="text-xs text-muted-foreground">
                            {finding.source === 'RULE'
                              ? '규칙 검사'
                              : '분석 후보'}
                          </span>
                        </div>
                        <p className="text-sm leading-6">{finding.message}</p>
                        {finding.next_action && (
                          <p className="mt-2 text-sm text-muted-foreground">
                            {finding.next_action}
                          </p>
                        )}
                        {finding.sources.map((source, index) => (
                          <p
                            key={index}
                            className="mt-2 text-xs text-muted-foreground"
                          >
                            문서 {source.document_id} v{source.version_no} ·{' '}
                            {source.location}
                          </p>
                        ))}
                        <div className="mt-3">
                          {finding.resolution === 'FIX_PLAN' && isOwner ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => {
                                setFormTask(current)
                                setEditing('edit')
                              }}
                            >
                              계획 보완
                            </Button>
                          ) : finding.resolution === 'PROPOSE_CHANGE' ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => openPane('changes')}
                            >
                              설계 변경 제안
                            </Button>
                          ) : (
                            <p className="text-xs text-muted-foreground">
                              {finding.resolution === 'HUMAN_REVIEW'
                                ? '관련 담당자와 인간 팀장의 확인이 필요해요.'
                                : finding.resolution === 'RETRY'
                                  ? '입력과 참조를 확인한 뒤 다시 검증해 주세요.'
                                  : ''}
                            </p>
                          )}
                        </div>
                      </article>
                    ))}
                    {!result?.findings.length && (
                      <p className="text-sm text-muted-foreground">
                        {result?.status === 'PASS'
                          ? '현재 계획과 입력에서 시작을 막는 항목을 찾지 못했어요. 시작 직전에 서버가 참조와 선행 작업을 다시 확인해요.'
                          : '계획 검증 후 근거와 보완 항목이 이곳에 표시돼요.'}
                      </p>
                    )}
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <CardTitle>참조 설계와 계획 이력</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div className="space-y-2">
                      {current.current_plan_revision.plan.design_refs.map(
                        (reference) => (
                          <p
                            key={reference.design_item_id}
                            className="rounded-lg bg-muted/40 p-3 text-sm"
                          >
                            {refLabel(reference)}
                          </p>
                        ),
                      )}
                      {!current.current_plan_revision.plan.design_refs
                        .length && (
                        <p className="text-sm text-muted-foreground">
                          연결된 설계 항목이 없어요.
                        </p>
                      )}
                    </div>
                    {current.current_plan_revision.plan.context_packet_id ? (
                      <AsyncState query={packet}>
                        {(data) => (
                          <div className="rounded-lg border p-3">
                            <p className="mb-2 text-sm font-medium">
                              당시 개발 컨텍스트
                            </p>
                            <p className="text-sm">{data.request}</p>
                            {data.unresolved.map((value, index) => (
                              <p
                                key={index}
                                className="mt-1 text-xs text-muted-foreground"
                              >
                                미정: {value}
                              </p>
                            ))}
                          </div>
                        )}
                      </AsyncState>
                    ) : (
                      <p className="text-xs text-muted-foreground">
                        근거 패킷이 연결되지 않은 계획이에요.
                      </p>
                    )}
                    <AsyncState query={revisions}>
                      {(data) => (
                        <details className="text-sm">
                          <summary className="cursor-pointer font-medium">
                            계획 이력 · 리비전 {data.items.length}개
                          </summary>
                          <div className="mt-3 space-y-3">
                            {data.items.map((revision) => (
                              <div
                                key={revision.id}
                                className="rounded-lg border p-3"
                              >
                                <strong>
                                  r{revision.revision_no} ·{' '}
                                  {revision.plan.title}
                                </strong>
                                <p className="mt-1 whitespace-pre-wrap text-muted-foreground">
                                  {revision.plan.goal}
                                </p>
                                {revision.plan.design_refs.length > 0 && (
                                  <p className="mt-1 text-xs">
                                    근거:{' '}
                                    {revision.plan.design_refs
                                      .map(refLabel)
                                      .join(', ')}
                                  </p>
                                )}
                                {/* API 없음: 리비전마다 당시 개발 맥락은 지금 계획의 패킷만 불러올 수 있다. */}
                              </div>
                            ))}
                          </div>
                        </details>
                      )}
                    </AsyncState>
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <div className="flex items-center justify-between gap-3">
                      <CardTitle>공유 작업 기록</CardTitle>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => {
                          setFormTask(current)
                          setRecording(true)
                        }}
                      >
                        기록 남기기
                      </Button>
                    </div>
                    <CardDescription>
                      직접 제출한 판단과 진행 상황만 공유해요. 공유 판단은 승인
                      설계와 구분해요.
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <AsyncState query={records}>
                      {(data) => (
                        <div className="space-y-3">
                          {data.items.map((entry) => (
                            <div
                              key={entry.id}
                              className="rounded-lg border p-3"
                            >
                              <p className="text-sm font-medium">
                                {entry.summary}
                              </p>
                              <p className="mt-1 text-xs text-muted-foreground">
                                리비전 {entry.revision_no} ·{' '}
                                {entry.visibility === 'PRIVATE'
                                  ? '나만 보기'
                                  : '팀 공유'}
                                {!entry.references_current
                                  ? ' · 참조 최신성 확인 필요'
                                  : ''}
                              </p>
                            </div>
                          ))}
                          {!data.items.length && (
                            <p className="text-sm text-muted-foreground">
                              남겨진 작업 기록이 없어요.
                            </p>
                          )}
                        </div>
                      )}
                    </AsyncState>
                  </CardContent>
                </Card>
              </div>
            )}
          </AsyncState>
        )}
      </div>
      {editing && (
        <PlanDialog
          key={editing === 'edit' ? formTask?.current_plan_revision.id : 'new'}
          task={editing === 'edit' ? formTask : undefined}
          missing={
            editing === 'edit' && result?.status === 'NEEDS_INPUT'
              ? missingDetails(result.findings)
              : []
          }
          items={designItems}
          dependencies={entries}
          moreItems={approved.hasNextPage}
          onMoreItems={() => void approved.fetchNextPage()}
          onClose={() => setEditing(null)}
          onSubmit={async (plan) => {
            await action.run(async () => {
              const result =
                editing === 'edit' && formTask
                  ? await tasksApi.savePlan(projectId, formTask.id, {
                      ...plan,
                      expected_revision_no:
                        formTask.current_plan_revision.revision_no,
                      expected_lock_version: formTask.lock_version,
                    })
                  : await tasksApi.create(projectId, plan)
              setSelected({ id: result.id, focus: focusId })
            }, '계획을 새 리비전으로 저장했어요. 현재 입력으로 검증해 주세요.')
            setEditing(null)
          }}
        />
      )}
      {assigning && formTask && (
        <FormDialog
          back={formTask.key}
          title="담당자 바꾸기"
          // 진행 중이거나 대기 중인 작업은 넘기면 새 담당자가 다시 검증받는다(PR #50 d4db914, 보드에 없는 안내).
          description={`바뀐 담당자에게 알림이 가요. 계획과 기록은 그대로 남아요.${
            formTask.status === 'IN_PROGRESS'
              ? ' 진행 중인 작업이라 넘기면 대기(재검증)로 바뀌고, 새 담당자가 다시 검증받아야 해요.'
              : formTask.status === 'WAITING'
                ? ' 넘기면 새 담당자가 다시 검증받아야 해요.'
                : ''
          }`}
          fields={[
            {
              name: 'member_id',
              label: '새 담당자',
              type: 'select',
              required: true,
              value: formTask.assignee_member_id,
              options: members.data?.items.map((member) => ({
                value: member.id,
                label: member.name,
              })),
            },
          ]}
          onClose={() => setAssigning(false)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                tasksApi.assign(projectId, formTask.id, {
                  assignee_member_id: values.member_id,
                  expected_lock_version: formTask.lock_version,
                }),
              '담당자를 변경했어요.',
            )
            setAssigning(false)
          }}
        />
      )}
      {reporting === 'WAIT' && formTask && (
        <FormDialog
          back={formTask.key}
          title="대기로 보고"
          description="작업을 멈추고 기다리는 이유를 남겨요. 팀 활동에도 보여요."
          columns
          fields={[
            {
              name: 'waiting_reason',
              label: '기다리는 이유',
              type: 'chips',
              required: true,
              value: '',
              options: [
                { value: 'INPUT', label: '추가 입력 필요' },
                { value: 'APPROVAL', label: '승인 필요' },
                { value: 'DEPENDENCY', label: '선행 작업 대기' },
              ],
            },
            {
              name: 'summary',
              label: '지금 상황',
              type: 'textarea',
              column: 2,
            },
          ]}
          submitLabel="대기로 보고"
          onClose={() => setReporting(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                tasksApi.state(projectId, formTask.id, {
                  event: 'WAIT',
                  plan_revision_id: formTask.current_plan_revision.id,
                  expected_lock_version: formTask.lock_version,
                  summary: values.summary,
                  waiting_reason: values.waiting_reason as
                    'INPUT' | 'APPROVAL' | 'DEPENDENCY',
                }),
              '대기 보고를 남겼어요.',
            )
            setReporting(null)
          }}
        />
      )}
      {reporting === 'COMPLETED' && formTask && (
        <CompleteDialog
          back={formTask.key}
          criteria={formTask.current_plan_revision.plan.completion_criteria}
          onClose={() => setReporting(null)}
          onSubmit={async (report) => {
            await action.run(
              () =>
                tasksApi.state(projectId, formTask.id, {
                  event: 'COMPLETED',
                  plan_revision_id: formTask.current_plan_revision.id,
                  expected_lock_version: formTask.lock_version,
                  ...report,
                }),
              '완료 보고를 접수했어요.',
            )
            setReporting(null)
          }}
        />
      )}
      {recording && formTask && (
        <FormDialog
          back={formTask.key}
          title="작업 기록 남기기"
          submitLabel="기록 남기기"
          columns
          fields={[
            {
              name: 'kind',
              label: '기록 종류',
              type: 'chips',
              value: 'PROGRESS',
              options: [
                { value: 'PROGRESS', label: '진행' },
                { value: 'DECISION', label: '판단' },
                { value: 'ISSUE', label: '중단·이슈' },
                { value: 'HANDOVER', label: '인수인계' },
              ],
            },
            { name: 'summary', label: '요약', required: true },
            {
              name: 'detail',
              label: '상세 내용',
              type: 'textarea',
              required: true,
              column: 2,
              hint: '비밀값과 코드 원문은 넣지 마세요.',
            },
            {
              name: 'visibility',
              label: '공유 범위',
              type: 'chips',
              value: 'SHARED',
              column: 2,
              options: [
                { value: 'SHARED', label: '팀 공유' },
                { value: 'PRIVATE', label: '나만 보기' },
              ],
            },
          ]}
          onClose={() => setRecording(false)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                contextsApi.create(projectId, {
                  task_id: formTask.id,
                  plan_revision_id: formTask.current_plan_revision.id,
                  source_packet_id:
                    formTask.current_plan_revision.plan.context_packet_id,
                  kind: values.kind as
                    'DECISION' | 'PROGRESS' | 'ISSUE' | 'HANDOVER',
                  visibility:
                    values.visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
                  summary: values.summary,
                  detail: values.detail,
                  design_refs:
                    formTask.current_plan_revision.plan.design_refs.map(
                      (reference) => ({
                        design_item_id: reference.design_item_id,
                        design_item_version_id:
                          reference.design_item_version_id,
                      }),
                    ),
                }),
              '기록을 남겼어요.',
            )
            setRecording(false)
          }}
        />
      )}
    </section>
  )
}

// 필수는 제목·목표·접근 방법·완료 기준(백엔드 TaskPlanInput·AddPlanRevisionRequest의 approach는 @NotBlank).
// 접은 칸은 비워 두면 기본값([], false)으로 보낸다.
const editorSchema = z.object({
  title: z.string().trim().min(1),
  goal: z.string().trim().min(1),
  included_scope: z.string(),
  excluded_scope: z.string(),
  approach: z.string().trim().min(1),
  completion_criteria: z.string().trim().min(1),
  dependency_task_ids: idSchema.array(),
  context_packet_id: z.string(),
  no_contract_change: z.boolean(),
  // 연결하지 않은 내용은 객체 하나씩 고친다. source는 원래 계획의 unlinked 순번이라 지우거나 새로 더해도 짝이 맞는다.
  unlinked: z.array(
    z.object({ description: z.string(), source: z.number().optional() }),
  ),
  refs: z.array(
    z.object({
      design_item_id: idSchema,
      design_item_version_id: idSchema,
      relation: z.enum(['READS', 'IMPLEMENTS', 'CHANGES']),
      proposed_content: z.string(),
    }),
  ),
})
type Editor = z.infer<typeof editorSchema>
type DetailField =
  | 'included_scope'
  | 'excluded_scope'
  | 'approach'
  | 'dependency_task_ids'
  | 'unlinked'
  | 'no_contract_change'

// 검증 NEEDS_INPUT 메시지에서 빠진 칸을 찾는다. finding에 칸 이름(field)이 없어 문구로 고른다.
const detailHints: [DetailField, RegExp][] = [
  ['included_scope', /포함 범위/],
  ['excluded_scope', /제외 범위/],
  ['approach', /접근 (방법|방식)/],
  ['dependency_task_ids', /선행 작업/],
  ['unlinked', /연결하지 않은|미연결/],
  ['no_contract_change', /계약 변경/],
]
function missingDetails(findings: { kind: string; message: string }[]) {
  return detailHints
    .filter(([, pattern]) =>
      findings.some(
        (finding) =>
          finding.kind === 'MISSING_INPUT' && pattern.test(finding.message),
      ),
    )
    .map(([name]) => name)
}

function PlanDialog({
  task,
  items,
  dependencies,
  moreItems,
  onMoreItems,
  onClose,
  onSubmit,
  missing = [],
}: {
  task?: TaskDetail
  items: DesignItemVersion[]
  dependencies: TaskDetail[]
  /** 검증에서 더 필요하다고 한 접힌 칸. 열 때 펼치고 강조한다. */
  missing?: DetailField[]
  moreItems: boolean
  onMoreItems: () => void
  onClose: () => void
  onSubmit: (plan: TaskPlanInput) => Promise<void>
}) {
  const plan = task?.current_plan_revision.plan
  const [error, setError] = useState('')
  // 저장이 서버에서 막히면 오류 문구의 design_item_id=·task_id=로 어느 항목 때문인지 그 줄에 표시한다(PR #72 3차 조사 THIRD-TC-01).
  const [blame, setBlame] = useState<{ item?: string; task?: string }>({})
  const blameNote = (text: string) => (
    <span role="alert" className="block text-xs text-destructive">
      {text}
    </span>
  )
  // 고치기에서 이미 값이 있거나 검증이 더 필요하다고 한 칸이 있으면 '더 자세히'를 펼친다.
  const [detailsOpen, setDetailsOpen] = useState(
    missing.length > 0 ||
      (!!plan &&
        (plan.included_scope.length > 0 ||
          plan.excluded_scope.length > 0 ||
          plan.dependency_task_ids.length > 0 ||
          plan.unlinked.length > 0 ||
          plan.no_contract_change)),
  )
  const flagged = (name: DetailField) =>
    missing.includes(name) &&
    'rounded-lg ring-2 ring-destructive/60 ring-offset-4 ring-offset-background'
  const flagNote = (name: DetailField) =>
    missing.includes(name) && (
      <p className="text-xs text-destructive">
        검증에서 이 칸이 더 필요하다고 했어요.
      </p>
    )
  const {
    register,
    control,
    setValue,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Editor>({
    resolver: zodResolver(editorSchema),
    defaultValues: {
      title: plan?.title ?? '',
      goal: plan?.goal ?? '',
      included_scope: plan?.included_scope.join('\n') ?? '',
      excluded_scope: plan?.excluded_scope.join('\n') ?? '',
      approach: plan?.approach ?? '',
      completion_criteria: plan?.completion_criteria.join('\n') ?? '',
      dependency_task_ids: plan?.dependency_task_ids ?? [],
      context_packet_id: plan?.context_packet_id ?? '',
      no_contract_change: plan?.no_contract_change ?? false,
      unlinked:
        plan?.unlinked.map((item, source) => ({
          description: describeContent(item),
          source,
        })) ?? [],
      refs:
        plan?.design_refs.map((reference) => ({
          ...reference,
          proposed_content: describeContent(reference.proposed_content ?? ''),
        })) ?? [],
    },
  })
  const refs = useWatch({ control, name: 'refs' })
  const unlinkedRows = useFieldArray({ control, name: 'unlinked' })
  const dependencyIds = useWatch({ control, name: 'dependency_task_ids' })
  const selectedRefs = new Set(
    refs.map((reference) => reference.design_item_id),
  )
  const field = (
    name:
      | 'title'
      | 'goal'
      | 'included_scope'
      | 'excluded_scope'
      | 'approach'
      | 'completion_criteria',
    label: string,
    multiline = true,
  ) => (
    <div className={cn('grid gap-2', flagged(name as DetailField))}>
      {/* 필수 표시는 라벨 밖에 둔다(L4). 라벨 이름은 그대로 남아 화면 읽기 프로그램에 같은 이름으로 읽힌다. */}
      <span className="flex gap-1">
        <Label htmlFor={`plan-${name}`}>{label}</Label>
        {['title', 'goal', 'approach', 'completion_criteria'].includes(
          name,
        ) && (
          <span aria-hidden="true" className="text-sm text-destructive">
            *
          </span>
        )}
      </span>
      {multiline ? (
        <Textarea
          id={`plan-${name}`}
          rows={3}
          disabled={isSubmitting}
          {...register(name)}
        />
      ) : (
        <Input
          id={`plan-${name}`}
          disabled={isSubmitting}
          {...register(name)}
        />
      )}
      {errors[name] && (
        <p className="text-xs text-destructive" role="alert">
          내용을 입력해 주세요.
        </p>
      )}
      {flagNote(name as DetailField)}
    </div>
  )
  return (
    <PaneSheet
      back={task ? task.key : '작업 현황'}
      title={
        task
          ? `계획 수정 · r${task.current_plan_revision.revision_no + 1}`
          : '작업 계획 등록'
      }
      description={
        task
          ? `r${task.current_plan_revision.revision_no}은 그대로 남고, 등록하면 r${task.current_plan_revision.revision_no + 1}로 계획 검증을 다시 해요.`
          : '제목, 목표, 관련 설계, 완료 기준, 접근 방법만 적으면 등록돼요. 등록하면 바로 계획 검증을 할 수 있어요.'
      }
      busy={isSubmitting}
      onClose={onClose}
      className="sm:max-w-2xl"
    >
      {/* 캔버스 PNL-05-v2-plan: 왼쪽 제목·관련 설계, 오른쪽 목표·완료 기준 */}
      <form
        className="grid gap-5 md:grid-cols-2 md:items-start"
        noValidate
        onSubmit={handleSubmit(async (values) => {
          setError('')
          setBlame({})
          try {
            const input = taskPlanInputSchema.parse({
              ...values,
              included_scope: lines(values.included_scope),
              excluded_scope: lines(values.excluded_scope),
              completion_criteria: lines(values.completion_criteria),
              context_packet_id: values.context_packet_id || undefined,
              // 계획 수정은 기존 계획의 객체에서 시작해 고친 것만 바꾼다(PR #72 리뷰). 객체마다 원래 순번(source)으로
              // 짝을 맞춰, 설명이 그대로면 원래 객체를 두고 고쳤으면 다른 필드(method·path 등)는 두고 description만
              // 바꾼다. 여러 줄 설명도 한 객체로 남고, 설명이 같은 두 객체도 따로 지킨다(PR #72 재전수 R2-TC-01).
              // 새로 더한 것만 description 하나짜리 구조로 보낸다(API 미정 H2).
              unlinked: values.unlinked
                .filter((entry) => entry.description.trim())
                .map((entry) => {
                  const original =
                    entry.source === undefined
                      ? undefined
                      : plan?.unlinked[entry.source]
                  if (!original)
                    return { description: entry.description.trim() }
                  return describeContent(original) === entry.description
                    ? original
                    : { ...original, description: entry.description.trim() }
                }),
              design_refs: values.refs.map((reference) => {
                const before = plan?.design_refs.find(
                  (item) => item.design_item_id === reference.design_item_id,
                )?.proposed_content
                // CHANGES의 제안 내용: 원래 제안이 있으면 그 객체에서 시작해(제안한 method·path 등 유지) 문장을 고쳤을
                // 때만 description을 바꾸고, 새로 CHANGES가 된 참조만 승인본 content에서 시작한다.
                const proposed =
                  before &&
                  describeContent(before) === reference.proposed_content
                    ? before
                    : {
                        ...(before ??
                          items.find(
                            (item) =>
                              item.design_item_id === reference.design_item_id,
                          )?.content),
                        description: reference.proposed_content,
                      }
                return {
                  design_item_id: reference.design_item_id,
                  design_item_version_id: reference.design_item_version_id,
                  relation: reference.relation,
                  ...(reference.relation === 'CHANGES'
                    ? { proposed_content: proposed }
                    : {}),
                }
              }),
            })
            await onSubmit(input)
          } catch (cause) {
            setError(getApiErrorMessage(cause))
            const detail = getApiErrorDetail(cause) ?? ''
            const culprit = {
              item: /design_item_id=(\d+)/.exec(detail)?.[1],
              task: /task_id=(\d+)/.exec(detail)?.[1],
            }
            setBlame(culprit)
            if (culprit.task) setDetailsOpen(true)
          }
        })}
      >
        {field('title', '작업 제목', false)}
        {field('goal', '이번 작업의 목표', false)}
        <fieldset className="space-y-3 rounded-lg border p-4">
          <legend className="px-2 text-sm font-medium">관련 설계 항목</legend>
          {items.map((item) => {
            const index = refs.findIndex(
              (reference) => reference.design_item_id === item.design_item_id,
            )
            const selected = index >= 0
            return (
              <div
                key={item.design_item_id}
                className="space-y-2 border-b pb-3 last:border-0 last:pb-0"
              >
                <label className="flex items-start gap-3 text-sm">
                  <input
                    className="mt-1 size-4 accent-primary"
                    type="checkbox"
                    checked={selectedRefs.has(item.design_item_id)}
                    disabled={isSubmitting}
                    onChange={(event) =>
                      setValue(
                        'refs',
                        event.target.checked
                          ? [
                              ...refs,
                              {
                                design_item_id: item.design_item_id,
                                design_item_version_id: item.id,
                                relation: 'IMPLEMENTS',
                                proposed_content: describeContent(item.content),
                              },
                            ]
                          : refs.filter(
                              (reference) =>
                                reference.design_item_id !==
                                item.design_item_id,
                            ),
                      )
                    }
                  />
                  <span>
                    <strong>
                      {item.key} · 승인 v{item.version_no}
                    </strong>
                    <span className="mt-1 block text-xs text-muted-foreground">
                      {typeof item.content.description === 'string'
                        ? item.content.description
                        : JSON.stringify(item.content)}
                    </span>
                  </span>
                </label>
                {selected && (
                  <div className="ml-7 space-y-2">
                    <NativeSelect
                      aria-label={`${item.key}의 참조 관계`}
                      disabled={isSubmitting}
                      {...register(`refs.${index}.relation`)}
                    >
                      <NativeSelectOption value="READS">
                        {relationLabel.READS}
                      </NativeSelectOption>
                      <NativeSelectOption value="IMPLEMENTS">
                        {relationLabel.IMPLEMENTS}
                      </NativeSelectOption>
                      <NativeSelectOption value="CHANGES">
                        {relationLabel.CHANGES}
                      </NativeSelectOption>
                    </NativeSelect>
                    {refs[index].design_item_version_id !== item.id && (
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="text-xs text-amber-800">
                          이전 버전을 참조하고 있어요.
                        </p>
                        <Button
                          size="sm"
                          variant="outline"
                          type="button"
                          onClick={() =>
                            setValue(
                              `refs.${index}.design_item_version_id`,
                              item.id,
                            )
                          }
                        >
                          현재 승인본으로 갱신
                        </Button>
                      </div>
                    )}
                    {blame.item === item.design_item_id &&
                      blameNote(
                        '이 항목 때문에 저장하지 못했어요. 현재 승인본으로 갱신하거나 빼 주세요.',
                      )}
                    {refs[index].relation === 'CHANGES' && (
                      <div className="grid gap-2">
                        <Label htmlFor={`change-${index}`}>
                          바꿀 계약 문장
                        </Label>
                        <Input
                          id={`change-${index}`}
                          disabled={isSubmitting}
                          {...register(`refs.${index}.proposed_content`)}
                        />
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
          {refs
            .filter(
              (reference) =>
                !items.some(
                  (item) => item.design_item_id === reference.design_item_id,
                ),
            )
            .map((reference) => (
              // 승인 목록에 없는 참조는 고르는 칸이 없어 따로 보이고 뺄 수 있게 한다. 목록을 다 불러왔는데도 없으면
              // 폐기된 설계다(PR #72 3차 조사 THIRD-TC-01). 서버는 폐기된 참조가 든 계획을 400으로 거절한다.
              <div key={reference.design_item_id} className="grid gap-1">
                <div className="flex flex-wrap items-center gap-2 text-xs text-amber-800">
                  <span>
                    설계 항목 {reference.design_item_id} ·{' '}
                    {moreItems
                      ? '불러온 승인 목록에 없어요. 더 불러와 확인하거나 빼 주세요.'
                      : '폐기된 설계예요. 빼고 저장해 주세요.'}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    type="button"
                    aria-label={`설계 항목 ${reference.design_item_id} 빼기`}
                    disabled={isSubmitting}
                    onClick={() =>
                      setValue(
                        'refs',
                        refs.filter(
                          (other) =>
                            other.design_item_id !== reference.design_item_id,
                        ),
                      )
                    }
                  >
                    빼기
                  </Button>
                </div>
                {blame.item === reference.design_item_id &&
                  blameNote('이 항목 때문에 저장하지 못했어요.')}
              </div>
            ))}
          {!items.length && (
            <p className="text-sm text-muted-foreground">
              현재 조회된 승인 설계 항목이 없어요.
            </p>
          )}
          {moreItems && (
            <Button type="button" variant="outline" onClick={onMoreItems}>
              설계 항목 더 보기
            </Button>
          )}
        </fieldset>
        {field('completion_criteria', '완료 기준 · 한 줄에 하나씩')}
        {/* 접근 방법은 서버 필수값이라 기본 칸에 둔다(PR #72 리뷰, 사용자가 줄인 4칸 구성에서 1칸 늘어남). */}
        {field('approach', '어떻게 진행하나요?')}
        <details
          className="rounded-lg border p-4 md:col-span-2"
          open={detailsOpen}
          onToggle={(event) => setDetailsOpen(event.currentTarget.open)}
        >
          <summary className="cursor-pointer text-sm font-medium">
            더 자세히 (선택)
          </summary>
          <div className="mt-4 space-y-5">
            {field('included_scope', '포함 범위 · 한 줄에 하나씩')}
            {field('excluded_scope', '제외 범위 · 한 줄에 하나씩')}
            <div className={cn('space-y-2', flagged('dependency_task_ids'))}>
              <fieldset className="space-y-2">
                <legend className="mb-2 text-sm font-medium">
                  먼저 완료되어야 하는 작업
                </legend>
                {dependencies
                  .filter(
                    (entry) =>
                      entry.id !== task?.id && entry.status !== 'CANCELLED',
                  )
                  .map((entry) => (
                    <label
                      key={entry.id}
                      className="flex items-center gap-3 text-sm"
                    >
                      <input
                        type="checkbox"
                        className="size-4 accent-primary"
                        disabled={isSubmitting}
                        checked={dependencyIds.includes(entry.id)}
                        onChange={(event) =>
                          setValue(
                            'dependency_task_ids',
                            event.target.checked
                              ? [...dependencyIds, entry.id]
                              : dependencyIds.filter((id) => id !== entry.id),
                          )
                        }
                      />
                      {entry.key} · {entry.current_plan_revision.plan.title}
                      {blame.task === entry.id &&
                        blameNote(
                          '이 작업 때문에 저장하지 못했어요(선행 관계가 순환해요).',
                        )}
                    </label>
                  ))}
                {/* 고른 선행 작업이 취소되면 고르는 칸에서 사라지므로 따로 보이고 뺄 수 있게 한다. 취소된 선행 작업이
                    남아 있으면 작업을 시작할 수 없다(PR #72 3차 조사 THIRD-TC-01). */}
                {dependencyIds
                  .filter(
                    (id) =>
                      !dependencies.some(
                        (entry) =>
                          entry.id === id &&
                          entry.id !== task?.id &&
                          entry.status !== 'CANCELLED',
                      ),
                  )
                  .map((id) => {
                    const entry = dependencies.find((item) => item.id === id)
                    const name = entry
                      ? `${entry.key} · ${entry.current_plan_revision.plan.title}`
                      : `작업 ${id}`
                    return (
                      <div key={id} className="grid gap-1">
                        <div className="flex flex-wrap items-center gap-2 text-sm text-amber-800">
                          <span>
                            {name} ·{' '}
                            {entry?.status === 'CANCELLED'
                              ? '취소된 작업'
                              : '불러온 목록에 없는 작업'}
                          </span>
                          <Button
                            size="sm"
                            variant="outline"
                            type="button"
                            aria-label={`${entry?.key ?? `작업 ${id}`} 선행에서 빼기`}
                            disabled={isSubmitting}
                            onClick={() =>
                              setValue(
                                'dependency_task_ids',
                                dependencyIds.filter((other) => other !== id),
                              )
                            }
                          >
                            빼기
                          </Button>
                        </div>
                        {blame.task === id &&
                          blameNote('이 작업 때문에 저장하지 못했어요.')}
                      </div>
                    )
                  })}
              </fieldset>
              {flagNote('dependency_task_ids')}
            </div>
            <fieldset className={cn('grid gap-2', flagged('unlinked'))}>
              <legend className="mb-2 text-sm font-medium">
                아직 연결하지 못한 내용 · 하나씩
              </legend>
              {unlinkedRows.fields.map((row, index) => (
                <div key={row.id} className="flex items-start gap-2">
                  <Textarea
                    aria-label={`연결하지 못한 내용 ${index + 1}`}
                    {...register(`unlinked.${index}.description`)}
                    rows={2}
                    disabled={isSubmitting}
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`연결하지 못한 내용 ${index + 1} 지우기`}
                    disabled={isSubmitting}
                    onClick={() => unlinkedRows.remove(index)}
                  >
                    지우기
                  </Button>
                </div>
              ))}
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="justify-self-start"
                disabled={isSubmitting}
                onClick={() => unlinkedRows.append({ description: '' })}
              >
                <Plus />
                내용 더하기
              </Button>
              {flagNote('unlinked')}
            </fieldset>
            <div className="grid gap-2">
              <Label htmlFor="plan-packet">개발 컨텍스트 패킷 ID · 선택</Label>
              <Input
                id="plan-packet"
                {...register('context_packet_id')}
                disabled={isSubmitting}
              />
            </div>
            <div className={cn('grid gap-2', flagged('no_contract_change'))}>
              <label className="flex items-start gap-3 rounded-lg bg-muted/50 p-3 text-sm leading-6">
                <input
                  type="checkbox"
                  className="mt-1 size-4 accent-primary"
                  disabled={isSubmitting}
                  {...register('no_contract_change')}
                />
                승인된 계약을 변경하지 않는 작업입니다.
              </label>
              {flagNote('no_contract_change')}
            </div>
          </div>
        </details>
        <p className="text-xs text-muted-foreground md:col-span-2">
          나머지는 비워 둬도 돼요. 검증에서 더 필요하면 알려 줘요.
        </p>
        {error && (
          <p
            className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive md:col-span-2"
            role="alert"
          >
            {error}
          </p>
        )}
        <SheetSubmit disabled={isSubmitting}>
          {isSubmitting ? '저장 중…' : '계획 저장'}
        </SheetSubmit>
      </form>
    </PaneSheet>
  )
}

const completeSchema = z
  .object({
    summary: z.string().trim().min(1, '완료한 내용을 입력해 주세요.'),
    checks: z
      .boolean()
      .array()
      .refine(
        (values) => values.every(Boolean),
        '모든 완료 기준을 확인해야 완료로 보고할 수 있어요.',
      ),
    changed: z.enum(['no', 'yes']),
    delta: z.string(),
  })
  .superRefine((values, context) => {
    if (values.changed === 'yes' && !lines(values.delta).length)
      context.addIssue({
        code: 'custom',
        path: ['delta'],
        message: '달라진 점을 한 줄 이상 써 주세요.',
      })
  })
type Complete = z.infer<typeof completeSchema>

// 완료 보고 · 완료 기준을 하나씩 체크하고, 계획과 달라진 점은 없음/있음을 고른 뒤 줄마다 쓴다(H2: JSON 입력 없앰).
function CompleteDialog({
  back,
  criteria,
  onClose,
  onSubmit,
}: {
  /** 하위 화면 머리줄에 둘 작업 번호 */
  back: string
  criteria: string[]
  onClose: () => void
  onSubmit: (
    report: Pick<TaskStateInput, 'summary' | 'delta' | 'completion_checks'>,
  ) => Promise<void>
}) {
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Complete>({
    resolver: zodResolver(completeSchema),
    defaultValues: {
      summary: '',
      checks: criteria.map(() => false),
      changed: 'no',
      delta: '',
    },
  })
  const changed = useWatch({ control, name: 'changed' })
  // 실패 이유는 열린 폼 안에 보인다. 부모 쪽 오류 줄은 하위 화면이 열린 동안 가려져 있다(PR #72 전수 리뷰 TC-4).
  const [error, setError] = useState('')
  return (
    <PaneSheet
      back={back}
      title="완료 보고"
      description="완료는 담당자가 스스로 보고해요. 계획과 달라진 점이 있으면 다시 검증할 때까지 대기로 남아요."
      busy={isSubmitting}
      onClose={onClose}
    >
      <form
        noValidate
        className="grid gap-4"
        onSubmit={handleSubmit((values) => {
          setError('')
          return onSubmit({
            summary: values.summary,
            completion_checks: criteria.map((criterion) => ({
              criterion,
              met: true,
            })),
            // API 미정(H2): 줄마다 쓴 달라진 점을 description 하나짜리 구조로 보낸다.
            delta:
              values.changed === 'yes'
                ? lines(values.delta).map((description) => ({ description }))
                : [],
          }).catch((cause: unknown) => setError(getApiErrorMessage(cause)))
        })}
      >
        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium">완료 기준 확인</legend>
          {criteria.map((criterion, index) => (
            <label key={index} className="flex items-start gap-3 text-sm">
              <input
                type="checkbox"
                className="mt-1 size-4 accent-primary"
                {...register(`checks.${index}`)}
              />
              {criterion}
            </label>
          ))}
          {errors.checks && (
            <p role="alert" className="text-xs text-destructive">
              {errors.checks.message ?? errors.checks.root?.message}
            </p>
          )}
        </fieldset>
        <div className="grid gap-1.5">
          <Label htmlFor="complete-summary">완료한 내용</Label>
          <Textarea
            id="complete-summary"
            rows={3}
            aria-invalid={!!errors.summary}
            {...register('summary')}
          />
          {errors.summary && (
            <p role="alert" className="text-xs text-destructive">
              {errors.summary.message}
            </p>
          )}
        </div>
        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium">계획과 달라진 점</legend>
          <div className="flex gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input type="radio" value="no" {...register('changed')} />
              없음
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" value="yes" {...register('changed')} />
              있음
            </label>
          </div>
          {changed === 'yes' && (
            <>
              <Textarea
                aria-label="달라진 점 · 한 줄에 하나씩"
                rows={3}
                aria-invalid={!!errors.delta}
                {...register('delta')}
              />
              {errors.delta && (
                <p role="alert" className="text-xs text-destructive">
                  {errors.delta.message}
                </p>
              )}
            </>
          )}
        </fieldset>
        {error && (
          <p
            role="alert"
            className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
          >
            {error}
          </p>
        )}
        <SheetSubmit disabled={isSubmitting}>
          {isSubmitting ? '처리 중…' : '완료 보고'}
        </SheetSubmit>
      </form>
    </PaneSheet>
  )
}
