import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { ClipboardCheck, Play, Plus, RefreshCw } from 'lucide-react'
import {
  contextsApi,
  membersApi,
  planningApi,
  tasksApi,
  validationsApi,
} from '@/api/services'
import {
  idSchema,
  structuredContentSchema,
  taskPlanInputSchema,
  type DesignItemVersion,
  type TaskDetail,
  type TaskPlanInput,
  type TaskStateInput,
} from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { lines } from '@/lib/display'

interface Props {
  projectId: string
  isLeader: boolean
}
type StateEvent = TaskStateInput['event']

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
  const entries = tasks.data?.pages.flatMap((page) => page.items) ?? []
  const selectedId =
    selected?.focus === focusId ? selected.id : (focusId ?? entries[0]?.id)
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
  const members = useQuery({
    queryKey: ['projects', projectId, 'members'],
    queryFn: () => membersApi.list(projectId, { limit: 100 }),
  })
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
  const memberId = members.data?.items.find(
    (member) => member.account_id === session.data?.id,
  )?.id
  const isOwner = !!task && memberId === task.assignee_member_id
  const active = !!task && !['COMPLETED', 'CANCELLED'].includes(task.status)
  const result = validation.data ?? task?.validation
  const canStart =
    isOwner &&
    active &&
    result?.status === 'PASS' &&
    result.plan_revision_id === task?.current_plan_revision.id &&
    ['READY', 'WAITING'].includes(task?.status ?? '')
  const designItems = approved.data?.pages.flatMap((page) => page.items) ?? []
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
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="mb-2 text-xs font-semibold tracking-[.18em] text-primary">
            PLAN BEFORE IMPLEMENTATION
          </p>
          <h2 className="text-2xl font-semibold tracking-tight">
            작업을 시작하기 전에, 설계부터
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            계획과 승인 설계를 대조하고 필요한 합의를 마친 뒤 시작하세요. 계획
            검증은 실제 코드 품질이나 테스트 통과를 뜻하지 않아요.
          </p>
        </div>
        <Button
          onClick={() => {
            setFormTask(undefined)
            setEditing('new')
          }}
        >
          <Plus />
          작업 계획 등록
        </Button>
      </header>
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
            <CardTitle>팀의 작업 계획</CardTitle>
          </CardHeader>
          <CardContent>
            <AsyncState query={tasks}>
              {() => (
                <div className="space-y-2">
                  {entries.length ? (
                    entries.map((entry) => (
                      <button
                        key={entry.id}
                        type="button"
                        className={`w-full rounded-lg border p-4 text-left transition-colors hover:bg-muted/50 ${entry.id === selectedId ? 'border-primary/30 bg-primary/5' : ''}`}
                        onClick={() =>
                          setSelected({ id: entry.id, focus: focusId })
                        }
                      >
                        <span className="block text-xs text-muted-foreground">
                          {entry.key}
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
                      맡을 작업의 목표와 범위부터 등록해 보세요.
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
                    <div className="flex flex-wrap gap-2 border-t pt-4">
                      {isOwner && active && (
                        <Button
                          variant="outline"
                          disabled={action.busy}
                          onClick={() => {
                            setFormTask(current)
                            setEditing('edit')
                          }}
                        >
                          계획 새 리비전 작성
                        </Button>
                      )}
                      {isLeader && active && (
                        <Button
                          variant="outline"
                          disabled={action.busy}
                          onClick={() => {
                            setFormTask(current)
                            setAssigning(true)
                          }}
                        >
                          담당자 변경
                        </Button>
                      )}
                      {isOwner && active && (
                        <Button
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
                          <ClipboardCheck />
                          계획 검증
                        </Button>
                      )}
                      {canStart && (
                        <Button
                          disabled={action.busy}
                          onClick={() =>
                            report(
                              current.status === 'WAITING' ? 'RESUME' : 'START',
                            )
                          }
                        >
                          <Play />
                          {current.status === 'WAITING' ? '재개' : '시작'}
                        </Button>
                      )}
                      {isOwner && current.status === 'IN_PROGRESS' && (
                        <>
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
                          <Button
                            disabled={action.busy}
                            onClick={() => {
                              setFormTask(current)
                              setReporting('COMPLETED')
                            }}
                          >
                            완료 보고
                          </Button>
                        </>
                      )}
                      {active && (isOwner || isLeader) && (
                        <Button
                          variant="ghost"
                          disabled={action.busy}
                          onClick={() => report('CANCEL')}
                        >
                          작업 취소
                        </Button>
                      )}
                    </div>
                    {!isOwner && (
                      <p className="text-xs text-muted-foreground">
                        계획 변경·검증·시작·완료 보고는 현재 담당자가 진행해요.
                      </p>
                    )}
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>설계와 계획의 검증 결과</CardTitle>
                      <StatusPill
                        value={result?.status ?? current.validation_status}
                      />
                    </div>
                    <CardDescription>
                      출처가 있는 규칙 검사와 의미 분석 후보를 함께 확인하세요.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {validation.error && (
                      <p role="alert" className="text-sm text-destructive">
                        검증 결과를 불러오지 못했어요.
                      </p>
                    )}
                    {current.waiting_reason && (
                      <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
                        대기 이유: <StatusPill value={current.waiting_reason} />
                      </p>
                    )}
                    {result?.stale_reason && (
                      <p className="text-sm text-amber-800">
                        {result.stale_reason}
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
                          분석을 완료하지 못했어요. 자동으로 통과 처리하지
                          않아요.
                        </p>
                        {isOwner && (
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
                            {designItems.find(
                              (item) =>
                                item.design_item_id ===
                                reference.design_item_id,
                            )?.key ??
                              `설계 항목 ${reference.design_item_id}`}{' '}
                            · 정확한 버전 {reference.design_item_version_id} ·{' '}
                            {reference.relation === 'READS'
                              ? '참고'
                              : reference.relation === 'IMPLEMENTS'
                                ? '구현'
                                : '계약 변경'}
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
                            계획 리비전 {data.items.length}개 보기
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
          title="작업 담당자 변경"
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
      {reporting && formTask && (
        <FormDialog
          title={reporting === 'WAIT' ? '대기 상태 보고' : '완료 결과 보고'}
          description={
            reporting === 'WAIT'
              ? '현재 작업을 멈추고 기다리는 이유를 남겨요.'
              : '완료는 담당자의 자기 보고예요. 계획 대비 변경이 있으면 재검증이 끝날 때까지 대기 상태로 남아요.'
          }
          fields={
            reporting === 'WAIT'
              ? [
                  {
                    name: 'waiting_reason',
                    label: '기다리는 이유',
                    type: 'select',
                    required: true,
                    options: [
                      { value: 'INPUT', label: '추가 입력 필요' },
                      { value: 'APPROVAL', label: '승인 필요' },
                      { value: 'DEPENDENCY', label: '선행 작업 대기' },
                    ],
                  },
                  { name: 'summary', label: '현재 상황', type: 'textarea' },
                ]
              : [
                  {
                    name: 'summary',
                    label: '완료한 내용',
                    type: 'textarea',
                    required: true,
                  },
                  {
                    name: 'completion_confirm',
                    label: '계획의 완료 기준을 직접 확인했나요?',
                    type: 'select',
                    required: true,
                    options: [
                      { value: '', label: '확인해 주세요' },
                      { value: 'yes', label: '모든 완료 기준을 확인했습니다' },
                    ],
                  },
                  {
                    name: 'delta',
                    label: '계획 대비 변경 내용 (구조화 목록, 없으면 [])',
                    type: 'textarea',
                    value: '[]',
                    required: true,
                  },
                ]
          }
          submitLabel="상태 보고"
          onClose={() => setReporting(null)}
          onSubmit={async (values) => {
            const delta =
              reporting === 'COMPLETED'
                ? z
                    .array(structuredContentSchema)
                    .parse(JSON.parse(values.delta))
                : undefined
            await action.run(
              () =>
                tasksApi.state(projectId, formTask.id, {
                  event: reporting,
                  plan_revision_id: formTask.current_plan_revision.id,
                  expected_lock_version: formTask.lock_version,
                  summary: values.summary,
                  ...(reporting === 'WAIT'
                    ? {
                        waiting_reason: values.waiting_reason as
                          'INPUT' | 'APPROVAL' | 'DEPENDENCY',
                      }
                    : {
                        delta,
                        completion_checks:
                          formTask.current_plan_revision.plan.completion_criteria.map(
                            (criterion) => ({
                              criterion,
                              met: values.completion_confirm === 'yes',
                            }),
                          ),
                      }),
                }),
              '보고를 접수했어요. 화면에서 최종 작업 상태를 확인해 주세요.',
            )
            setReporting(null)
          }}
        />
      )}
      {recording && formTask && (
        <FormDialog
          title="작업 기록 남기기"
          fields={[
            {
              name: 'kind',
              label: '기록 종류',
              type: 'select',
              value: 'PROGRESS',
              options: [
                { value: 'PROGRESS', label: '진행 상황' },
                { value: 'DECISION', label: '작업 판단' },
                { value: 'ISSUE', label: '문제와 이슈' },
                { value: 'HANDOVER', label: '인계' },
              ],
            },
            {
              name: 'visibility',
              label: '공유 범위',
              type: 'select',
              value: 'SHARED',
              options: [
                { value: 'SHARED', label: '프로젝트 팀에 공유' },
                { value: 'PRIVATE', label: '나만 보기' },
              ],
            },
            { name: 'summary', label: '요약', required: true },
            {
              name: 'detail',
              label: '상세 내용',
              type: 'textarea',
              required: true,
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

const editorSchema = z.object({
  title: z.string().trim().min(1),
  goal: z.string().trim().min(1),
  included_scope: z.string().trim().min(1),
  excluded_scope: z.string(),
  approach: z.string().trim().min(1),
  completion_criteria: z.string().trim().min(1),
  dependency_task_ids: idSchema.array(),
  context_packet_id: z.string(),
  no_contract_change: z.boolean(),
  unlinked: z.string(),
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

function PlanDialog({
  task,
  items,
  dependencies,
  moreItems,
  onMoreItems,
  onClose,
  onSubmit,
}: {
  task?: TaskDetail
  items: DesignItemVersion[]
  dependencies: TaskDetail[]
  moreItems: boolean
  onMoreItems: () => void
  onClose: () => void
  onSubmit: (plan: TaskPlanInput) => Promise<void>
}) {
  const plan = task?.current_plan_revision.plan
  const [error, setError] = useState('')
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
      unlinked: JSON.stringify(plan?.unlinked ?? [], null, 2),
      refs:
        plan?.design_refs.map((reference) => ({
          ...reference,
          proposed_content: JSON.stringify(
            reference.proposed_content ?? {},
            null,
            2,
          ),
        })) ?? [],
    },
  })
  const refs = useWatch({ control, name: 'refs' })
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
    <div className="grid gap-2">
      <Label htmlFor={`plan-${name}`}>{label}</Label>
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
    </div>
  )
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !isSubmitting) onClose()
      }}
    >
      <DialogContent
        className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl"
        onEscapeKeyDown={(event) => {
          if (isSubmitting) event.preventDefault()
        }}
        onInteractOutside={(event) => {
          if (isSubmitting) event.preventDefault()
        }}
      >
        <DialogHeader>
          <DialogTitle>
            {task ? '계획 새 리비전 작성' : '작업 계획 등록'}
          </DialogTitle>
          <DialogDescription>
            목표·범위·완료 기준과 정확한 승인 설계 버전을 연결하세요. 저장 뒤
            계획 검증을 진행할 수 있어요.
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-5"
          noValidate
          onSubmit={handleSubmit(async (values) => {
            setError('')
            try {
              const input = taskPlanInputSchema.parse({
                ...values,
                included_scope: lines(values.included_scope),
                excluded_scope: lines(values.excluded_scope),
                completion_criteria: lines(values.completion_criteria),
                context_packet_id: values.context_packet_id || undefined,
                unlinked: z
                  .array(structuredContentSchema)
                  .parse(JSON.parse(values.unlinked)),
                design_refs: values.refs.map((reference) => ({
                  design_item_id: reference.design_item_id,
                  design_item_version_id: reference.design_item_version_id,
                  relation: reference.relation,
                  ...(reference.relation === 'CHANGES'
                    ? {
                        proposed_content: structuredContentSchema.parse(
                          JSON.parse(reference.proposed_content),
                        ),
                      }
                    : {}),
                })),
              })
              await onSubmit(input)
            } catch (cause) {
              setError(getApiErrorMessage(cause))
            }
          })}
        >
          {field('title', '작업 제목', false)}
          {field('goal', '이번 작업의 목표')}
          {field('included_scope', '포함 범위 · 한 줄에 하나씩')}
          {field('excluded_scope', '제외 범위 · 한 줄에 하나씩')}
          {field('approach', '어떻게 진행하나요?')}
          {field('completion_criteria', '완료 기준 · 한 줄에 하나씩')}
          <fieldset className="space-y-3 rounded-lg border p-4">
            <legend className="px-2 text-sm font-medium">
              참조할 승인 설계
            </legend>
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
                                  proposed_content: JSON.stringify(
                                    item.content,
                                    null,
                                    2,
                                  ),
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
                        {item.key} · v{item.version_no}
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
                          참고하는 설계
                        </NativeSelectOption>
                        <NativeSelectOption value="IMPLEMENTS">
                          구현할 설계
                        </NativeSelectOption>
                        <NativeSelectOption value="CHANGES">
                          계약을 변경할 설계
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
                      {refs[index].relation === 'CHANGES' && (
                        <div className="grid gap-2">
                          <Label htmlFor={`change-${index}`}>
                            제안할 계약 내용
                          </Label>
                          <Textarea
                            id={`change-${index}`}
                            disabled={isSubmitting}
                            rows={4}
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
                <p
                  key={reference.design_item_id}
                  className="text-xs text-amber-800"
                >
                  이전 참조 항목 {reference.design_item_id}의 현재 승인본을
                  확인해 주세요. 기존 버전은 보존해요.
                </p>
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
          <label className="flex items-start gap-3 rounded-lg bg-muted/50 p-3 text-sm leading-6">
            <input
              type="checkbox"
              className="mt-1 size-4 accent-primary"
              disabled={isSubmitting}
              {...register('no_contract_change')}
            />
            승인된 계약을 변경하지 않는 작업입니다.
          </label>
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
                </label>
              ))}
          </fieldset>
          <details className="rounded-lg border p-4">
            <summary className="cursor-pointer text-sm font-medium">
              추가 근거와 아직 연결하지 못한 내용
            </summary>
            <div className="mt-4 space-y-4">
              <div className="grid gap-2">
                <Label htmlFor="plan-packet">
                  개발 컨텍스트 패킷 ID · 선택
                </Label>
                <Input
                  id="plan-packet"
                  {...register('context_packet_id')}
                  disabled={isSubmitting}
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="plan-unlinked">
                  검토가 필요한 구조화 내용 · 없으면 []
                </Label>
                <Textarea
                  id="plan-unlinked"
                  {...register('unlinked')}
                  rows={4}
                  disabled={isSubmitting}
                />
              </div>
            </div>
          </details>
          {error && (
            <p
              className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
              role="alert"
            >
              {error}
            </p>
          )}
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? '저장 중…' : '계획 저장'}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}
