import type { Notification } from '@/api/contracts/common'
import type { WorkspacePane } from '@/stores/workspaceStore'

// 알림 대상별로 여는 창과 그 이름. 벨 팝오버와 '지금 할 일'이 같이 쓴다.
export const targetPane: Record<Notification['target_type'], WorkspacePane> = {
  CHANGE_PROPOSAL: 'changes',
  MEETING: 'meetings',
  BASELINE: 'documents',
  DOCUMENT_VERSION: 'documents',
  TASK: 'tasks',
  VALIDATION: 'tasks',
}
export const targetLabel: Record<Notification['target_type'], string> = {
  CHANGE_PROPOSAL: '변경 제안',
  MEETING: '회의실',
  BASELINE: '공용 문서함',
  DOCUMENT_VERSION: '공용 문서함',
  TASK: '작업 현황',
  VALIDATION: '작업 현황',
}

// 창에 넘길 대상 ID. 문서 알림의 target_id는 문서 버전 ID라 문서함이 여는 문서 ID(document_id)를 쓴다.
// 기준 알림의 target_id는 기준 ID라 문서함이 기준을 여는 'baseline:버전 번호'를 쓴다.
// 필요한 값이 빠지면 엉뚱한 문서 대신 문서 목록을 연다.
export function targetFocus(item: Notification) {
  if (item.target_type === 'DOCUMENT_VERSION') return item.document_id ?? null
  if (item.target_type === 'BASELINE')
    return item.baseline_version_no === undefined
      ? null
      : `baseline:${item.baseline_version_no}`
  return item.target_id
}
