import { formatAgo } from '@/lib/formatDate'
import type { WorkspacePane } from '@/stores/workspaceStore'
import type { PresenceItem, PresenceReport } from './schemas'

const zoneLabels: Record<string, string> = {
  meeting: '회의실',
  board: '기획 보드',
  lead: '팀장석',
  dev: '개발팀',
  review: '보안·검증팀',
  lobby: '라운지',
  humandesk: '사람 데스크',
  control: '관제실',
  hr: '인사팀',
}

/**
 * 지금 내 위치 보고 값. 실외 산책 중에는 사무실 안 위치가 없어 zone 을 비운다.
 * 사무실의 팀원은 자기 아일랜드 옆에 서 있어(D37) 실내는 개발팀(dev)으로 보고한다.
 */
export function presenceReportOf(state: {
  outdoor: boolean
  pane: WorkspacePane | null
}): PresenceReport {
  return {
    ...(state.outdoor ? {} : { zone: 'dev' as const }),
    ...(state.pane ? { panel: state.pane } : {}),
  }
}

/**
 * HUD 팀 목록 한 줄의 접속 표시. 접속 중이면 위치, 오프라인이면 '오프라인 · 1시간 전'(마지막 위치는 title 에 둔다).
 * 접속한 적 없는 사람(online=false, 시각 없음)은 표시할 것이 없다. 아직 못 받았으면(undefined) 점도 없다.
 */
export function presenceView(item?: PresenceItem): {
  online?: boolean
  place?: string
  detail?: string
} {
  if (!item) return {}
  if (item.online)
    return {
      online: true,
      place: (item.zone && zoneLabels[item.zone]) || '접속 중',
    }
  return {
    online: false,
    detail: item.last_seen_at
      ? `오프라인 · ${formatAgo(item.last_seen_at)}`
      : undefined,
  }
}
