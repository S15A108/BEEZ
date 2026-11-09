import { useQuery } from '@tanstack/react-query'
import { tasksApi } from '@/api/services'
import { Button } from '@/components/ui/button'
import { membersQuery } from '@/features/members/membersQuery'
import { useWorkspaceStore } from '@/stores/workspaceStore'

// 검증 문제가 가리키는 다른 작업(finding.related_task_id). 서버가 확인한 작업이라 키·제목·지금 담당자로 협의 상대를 보여 준다.
// 작업 상세는 작업 현황·함께 비교한 작업과 같은 키라 캐시를 함께 쓴다.
export function FindingRelatedTask({
  projectId,
  taskId,
}: {
  projectId: string
  taskId: string
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const members = useQuery(membersQuery(projectId))
  const task = useQuery({
    queryKey: ['projects', projectId, 'task', taskId],
    queryFn: () => tasksApi.get(projectId, taskId),
  })
  if (!task.data)
    return (
      <p className="mt-2 text-xs text-muted-foreground">
        {task.isError
          ? '협의할 작업 정보를 불러오지 못했어요.'
          : '협의할 작업 정보를 불러오는 중이에요.'}
      </p>
    )
  const memberId = task.data.assignee_member_id
  const assignee = !memberId
    ? '담당자 없음'
    : `담당자 ${
        members.data?.items.find((member) => member.id === memberId)?.name ??
        (members.isPending ? '확인 중' : '확인할 수 없는 팀원')
      }`
  return (
    <p className="mt-2 text-xs text-muted-foreground">
      협의할 작업: {task.data.key} {task.data.current_plan_revision.plan.title}{' '}
      · {assignee}{' '}
      <Button
        variant="link"
        size="sm"
        className="h-auto p-0 text-xs"
        onClick={() => openPane('tasks', taskId)}
      >
        작업 보기
      </Button>
    </p>
  )
}
