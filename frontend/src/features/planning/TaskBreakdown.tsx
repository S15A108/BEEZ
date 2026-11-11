import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiErrorMessage,
  getApiErrorStatus,
  getErrorCodeMessage,
} from '@/api/error'
import { tasksApi } from '@/api/services'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import type { BreakdownJob } from '@/features/tasks/schemas'
import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import { useWorkspaceStore } from '@/stores/workspaceStore'

const isActive = (job: BreakdownJob | null | undefined) =>
  job?.status === 'PENDING' || job?.status === 'RUNNING'

export function TaskBreakdown({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const openTeamTasks = useWorkspaceStore((state) => state.openTeamTasks)
  const queryKey = ['projects', projectId, 'task-breakdown'] as const
  const status = useQuery({
    queryKey,
    queryFn: ({ signal }) => tasksApi.breakdownStatus(projectId, { signal }),
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
    refetchInterval: ({ state }) =>
      state.status !== 'error' && isActive(state.data) ? ACTIVE_POLL_MS : false,
  })
  const generation = useMutation({
    onMutate: () => queryClient.cancelQueries({ queryKey, exact: true }),
    mutationFn: () => tasksApi.generateBreakdown(projectId),
    onSuccess: async (job) => {
      await queryClient.cancelQueries({ queryKey, exact: true })
      queryClient.setQueryData(queryKey, job)
    },
    onError: async (error) => {
      // 응답을 받지 못해도 서버는 접수했을 수 있다. 새 생성을 제안하기 전에 상태를 확인한다.
      const code = getApiErrorStatus(error)
      if (code === undefined || code >= 500) await status.refetch()
    },
  })

  const job = status.data
  const completed = job?.status === 'COMPLETED' ? job : undefined
  const completedId = completed?.id
  const refreshed = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (!completedId || refreshed.current === completedId) return
    refreshed.current = completedId
    void queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'tasks'],
    })
    void queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'scene'],
    })
    void queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'hud', 'tasks'],
    })
    void queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'dashboard-counts', 'tasks'],
    })
    void queryClient.invalidateQueries({ queryKey: ['notifications'] })
  }, [completedId, projectId, queryClient])

  const unassigned = completed?.tasks.filter(
    (task) => task.assignee_member_id == null,
  ).length
  const failed = job?.status === 'FAILED' ? job : undefined
  const active = isActive(job)
  const canSubmit = job === null || failed?.retryable === true
  const submissionError = !active && generation.error

  return (
    <Card>
      <CardHeader>
        <CardTitle>승인 설계에서 작업 만들기</CardTitle>
        <CardDescription>
          비비가 문서와 설계 항목을 구현 단위로 나누고, 팀원의 역할과 담당
          도메인에 맞춰 배정해요.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center gap-3">
        {completed ? (
          <>
            <p role="status" className="text-sm">
              작업 {completed.tasks.length}건을 준비했어요.
              {unassigned ? ` 미배정 작업은 ${unassigned}건이에요.` : ''}
            </p>
            <Button variant="outline" onClick={openTeamTasks}>
              팀 전체 작업 보기
            </Button>
          </>
        ) : status.isError ? (
          <>
            <p role="alert" className="text-sm text-destructive">
              작업 상태를 불러오지 못했어요.
            </p>
            <Button
              variant="outline"
              disabled={status.isFetching || generation.isPending}
              onClick={() => void status.refetch()}
            >
              작업 상태 다시 불러오기
            </Button>
          </>
        ) : generation.isPending || active || status.isPending ? (
          <div role="status" className="space-y-1 text-sm">
            <p>
              {generation.isPending
                ? '작업 생성 요청 중…'
                : active
                  ? '작업을 만드는 중…'
                  : '작업 상태를 불러오는 중…'}
            </p>
            {active && (
              <p className="text-muted-foreground">
                이 화면을 닫아도 작업 생성은 계속돼요.
              </p>
            )}
          </div>
        ) : (
          <>
            {(failed || submissionError) && (
              <p role="alert" className="text-sm text-destructive">
                {submissionError
                  ? getApiErrorMessage(submissionError)
                  : (getErrorCodeMessage(failed?.error_code) ??
                    '작업을 만들지 못했어요.')}
              </p>
            )}
            {canSubmit && (
              <Button
                disabled={status.isFetching}
                onClick={() => generation.mutate()}
              >
                {failed ? '작업 생성 다시 시도' : '작업 만들고 배정하기'}
              </Button>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
