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
// 문서 알림 중 승인(DOCUMENT_APPROVED)만 승인본이 있는 상세로 열고, 비비 검토 끝·승인 요청·반려는 검토 화면
// ('review:문서 ID')으로 연다. 첫 승인 전 문서는 상세(DOC-A03)로 열 수 없고, 승인 뒤 새 버전의 승인 요청을 상세로 열면
// 승인된 옛 버전이 보인다(PR #150 리뷰). 문서함 목록도 승인된 문서는 상세, 아니면 검토 화면으로 연다.
// 기준 알림의 target_id는 기준 ID라 문서함이 기준을 여는 'baseline:버전 번호'를 쓴다.
// 필요한 값이 빠지면 엉뚱한 문서 대신 문서 목록을 연다.
export function targetFocus(item: Notification) {
  if (item.target_type === 'DOCUMENT_VERSION') {
    if (item.document_id === undefined) return null
    return item.type === 'DOCUMENT_APPROVED'
      ? item.document_id
      : `review:${item.document_id}`
  }
  if (item.target_type === 'BASELINE')
    return item.baseline_version_no === undefined
      ? null
      : `baseline:${item.baseline_version_no}`
  return item.target_id
}
