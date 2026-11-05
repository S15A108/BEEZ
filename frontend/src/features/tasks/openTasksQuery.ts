import { queryOptions } from '@tanstack/react-query'
import { tasksApi } from '@/api/services/tasks'

// 열린 작업: 나가기·제외 경고(MEM-A09·A10)와 '담당자 없는 작업'(HUD·대시보드)이 센다(11-04 사용자 결정).
export const OPEN_TASK_STATUSES = [
  'DRAFT',
  'READY',
  'IN_PROGRESS',
  'WAITING',
] as const

// 한 사람의 열린 작업 또는 담당자 없는 열린 작업. 명세대로 TSK-A02를 한 번 불러 센다.
// ponytail: 첫 100개만 받는다. 더 있으면 openTaskCount가 '100개 이상'으로만 알린다.
export const openTasksQuery = (
  projectId: string,
  filter: { assignee_member_id: string } | { unassigned: true },
) =>
  queryOptions({
    queryKey: ['projects', projectId, 'open-tasks', filter],
    queryFn: () =>
      tasksApi.list(projectId, {
        ...filter,
        status: [...OPEN_TASK_STATUSES],
        limit: 100,
      }),
    // 11-04 필터(unassigned·여러 status)는 수정안이라 서버가 아직 무시할 수 있다. 무시하면 모든 작업이 와서
    // '담당자 없는 작업' 수가 틀리므로 받은 목록을 한 번 더 거른다.
    select: (page) => ({
      ...page,
      items: page.items.filter(
        (task) =>
          OPEN_TASK_STATUSES.some((status) => status === task.status) &&
          ('unassigned' in filter
            ? !task.assignee_member_id
            : task.assignee_member_id === filter.assignee_member_id),
      ),
    }),
  })

export const openTaskCount = (page: { items: unknown[]; has_more: boolean }) =>
  `${page.items.length}개${page.has_more ? ' 이상' : ''}`
