// 에이전트 상태 이름은 여기서만 정한다(캔버스 SCR-06-v2-settings, -settings-agent, -settings-agent-checked).
// 연결 끊김은 캔버스에 없고 AGT-A05의 화면 구분을 따른다(사용자 결정 2026-11-09, S15P31A108-341).
export const agentStatusLabels = {
  connected: '연결됨',
  recheck: '연결 미확인',
  failed: '연결 실패',
  disconnected: '연결 끊김',
  none: '연결 전',
} as const

export type AgentStatus = keyof typeof agentStatusLabels

// AGT-A05(-75) 화면 구분: 줄이 있고 connected면 연결됨, 그중 status_check_required(맡은 IN_PROGRESS 작업이 있는데
// 30분 넘게 통신 없음)면 연결 미확인, 줄이 있고 connected가 아니면(2시간 넘게 통신 없음·세션 종료) 연결 끊김,
// 줄이 없으면 연결 전이다. 서버는 끊긴 줄에 status_check_required를 주지 않지만, 와도 연결 미확인으로 보지 않는다.
// 연결 실패는 서버 값이 아니라 '연결 확인'을 눌렀는데 연결이 없을 때 화면이 정한다.
// 연결 현황은 세션 줄 단위다. 한 팀원의 줄이 여럿이면 연결된 줄 중 가장 최근에 통신한 것, 없으면 가장 최근 줄로 정한다
// (PR #195 리뷰). 서버(-75)는 팀원마다 최근 세션 한 줄만 주지만, 여러 줄이 와도 첫 줄에 기대지 않는다.
export function pickAgentRow<
  T extends { connected: boolean; last_report_at?: string },
>(rows: T[]): T | undefined {
  const pool = rows.some((row) => row.connected)
    ? rows.filter((row) => row.connected)
    : rows
  const time = (row: T) => Date.parse(row.last_report_at ?? '') || 0
  return pool.reduce<T | undefined>(
    (latest, row) => (!latest || time(row) > time(latest) ? row : latest),
    undefined,
  )
}

export function agentStatusOf(
  agent: { connected: boolean; status_check_required: boolean } | undefined,
  checkFailed = false,
): AgentStatus {
  if (agent?.connected)
    return agent.status_check_required ? 'recheck' : 'connected'
  if (checkFailed) return 'failed'
  return agent ? 'disconnected' : 'none'
}
