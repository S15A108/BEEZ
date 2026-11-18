import type { TaskDetail } from '@/api/contracts/common'

// 작업 목록의 상태 타일과 머리글(캔버스 PNL-05-v2-tasks, 11-05 결정). 작업 현황과 작업·설계 관계 왼쪽 목록이 같이 쓴다.
// TSK-A02에 개수·검색 파라미터가 없어 불러온 목록으로 센다.
export const statusGroups: {
  label: string
  heading: string
  statuses: TaskDetail['status'][]
}[] = [
  { label: '할 것', heading: '지금 할 것', statuses: ['DRAFT', 'READY'] },
  { label: '진행 중', heading: '진행 중', statuses: ['IN_PROGRESS'] },
  { label: '대기', heading: '대기', statuses: ['WAITING'] },
  { label: '끝남', heading: '끝남', statuses: ['COMPLETED', 'CANCELLED'] },
]

export const statusGroupOf = (status?: TaskDetail['status']) =>
  statusGroups.find((group) => !!status && group.statuses.includes(status))
