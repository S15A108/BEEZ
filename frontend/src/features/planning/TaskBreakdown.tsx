import { useMutation, useQueryClient } from '@tanstack/react-query'
import { getApiErrorMessage } from '@/api/error'
import { tasksApi } from '@/api/services'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useWorkspaceStore } from '@/stores/workspaceStore'

export function TaskBreakdown({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const openTeamTasks = useWorkspaceStore((state) => state.openTeamTasks)
  const generation = useMutation({
    mutationFn: () => tasksApi.generateBreakdown(projectId),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['projects', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['notifications'] }),
      ]),
  })
  const unassigned = generation.data?.tasks.filter(
    (task) => task.assignee_member_id == null,
  ).length

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
        {generation.data ? (
          <>
            <p role="status" className="text-sm">
              작업 {generation.data.tasks.length}건을 준비했어요.
              {unassigned ? ` 미배정 작업은 ${unassigned}건이에요.` : ''}
            </p>
            <Button variant="outline" onClick={openTeamTasks}>
              팀 전체 작업 보기
            </Button>
          </>
        ) : (
          <Button
            disabled={generation.isPending}
            onClick={() => generation.mutate()}
          >
            {generation.isPending
              ? '작업을 만드는 중…'
              : '작업 만들고 배정하기'}
          </Button>
        )}
        {generation.error && (
          <p role="alert" className="text-sm text-destructive">
            {getApiErrorMessage(generation.error)}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
