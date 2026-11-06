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
export function agentStatusOf(
  agent: { connected: boolean; status_check_required: boolean } | undefined,
  checkFailed = false,
): AgentStatus {
  if (agent?.connected && !agent.status_check_required) return 'connected'
  if (checkFailed && !agent?.connected) return 'failed'
  return agent?.status_check_required ? 'recheck' : 'none'
}
