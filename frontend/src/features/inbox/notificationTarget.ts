import type { Notification } from '@/api/contracts/common'
import type { WorkspacePane } from '@/stores/workspaceStore'

// 알림 대상별로 여는 창과 그 이름. 벨 팝오버와 '지금 할 일'이 같이 쓴다.
export const targetPane: Record<Notification['target_type'], WorkspacePane> = {
  CHANGE_PROPOSAL: 'changes',
  MEETING: 'meetings',
  BASELINE: 'documents',
  TASK: 'tasks',
  VALIDATION: 'tasks',
}
export const targetLabel: Record<Notification['target_type'], string> = {
  CHANGE_PROPOSAL: '변경 제안',
  MEETING: '회의실',
  BASELINE: '공용 문서함',
  TASK: '작업 현황',
  VALIDATION: '작업 현황',
}
