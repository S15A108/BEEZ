import { useQueries, useQuery } from '@tanstack/react-query'
import { tasksApi } from '@/api/services'
import type { RelatedTaskRef } from '@/api/contracts/common'
import { membersQuery } from '@/features/members/membersQuery'

// 검증이 함께 비교한 다른 작업(related_task_refs). 응답에는 작업·계획 번호만 있어 작업 상세와 팀원 목록에서 키·제목·담당자를 찾는다.
// 보통 한두 개라 작업마다 상세를 한 번 부르고, 작업 현황의 선택 작업 상세와 같은 키라 캐시를 함께 쓴다.
export function RelatedTaskRefs({
  projectId,
  refs,
}: {
  projectId: string
  refs: RelatedTaskRef[]
}) {
  const members = useQuery(membersQuery(projectId))
  const tasks = useQueries({
    queries: refs.map((ref) => ({
      queryKey: ['projects', projectId, 'task', ref.task_id],
      queryFn: () => tasksApi.get(projectId, ref.task_id),
    })),
  })
  const assignee = (memberId?: string) =>
    !memberId
      ? '담당자 없음'
      : (members.data?.items.find((member) => member.id === memberId)?.name ??
        (members.isPending ? '확인 중' : '확인할 수 없는 팀원'))
  return (
    <section
      aria-label="함께 비교한 작업"
      className="space-y-2 rounded-lg border p-4"
    >
      <div>
        <h3 className="text-sm font-semibold">함께 비교한 작업</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          같은 설계 항목을 다루는 다른 작업의 계획과 비교했어요.
        </p>
      </div>
      <ul className="space-y-1 text-sm">
        {refs.map((ref, index) => {
          const task = tasks[index]?.data
          return (
            <li key={ref.task_id}>
              {task ? (
                <>
                  <span className="text-xs text-muted-foreground">
                    {task.key}
                  </span>{' '}
                  {task.current_plan_revision.plan.title}
                  <span className="text-xs text-muted-foreground">
                    {' '}
                    · 담당자 {assignee(task.assignee_member_id)}
                    {task.current_plan_revision.id !== ref.plan_revision_id &&
                      ' · 비교한 뒤 계획이 바뀌었어요'}
                  </span>
                </>
              ) : (
                <span className="text-muted-foreground">
                  {tasks[index]?.isError
                    ? '작업 정보를 불러오지 못했어요.'
                    : '작업 정보를 불러오는 중이에요.'}
                </span>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
