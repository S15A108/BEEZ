// 에이전트 상태 이름은 여기서만 정한다(캔버스 SCR-06-v2-settings, -settings-agent, -settings-agent-checked).
export const agentStatusLabels = {
  connected: '연결됨',
  recheck: '연결 미확인',
  failed: '연결 실패',
  none: '연결 전',
} as const

export type AgentStatus = keyof typeof agentStatusLabels

// 명세의 agent_status(00-common)와 같은 기준이다: 보고 지연으로 확인이 필요하면 미확인, 관측이 없으면 연결 전.
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
  if (agent?.connected && !agent.status_check_required) return 'connected'
  if (checkFailed && !agent?.connected) return 'failed'
  return agent?.status_check_required ? 'recheck' : 'none'
}
