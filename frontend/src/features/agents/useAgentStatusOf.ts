import { queryOptions, useQuery } from '@tanstack/react-query'
import { unlessMissing } from '@/api/error'
import { agentsApi } from '@/api/services'
import { agentStatusOf } from './agentStatus'

// API 없음: AGT-A05가 404면 undefined다. 이때 상태는 팀원 목록의 agent_connected로 정한다.
export const agentConnectionsQuery = (projectId: string) =>
  queryOptions({
    queryKey: ['projects', projectId, 'agent-connections'],
    queryFn: () => unlessMissing(agentsApi.list(projectId)),
  })

// 팀원 한 명의 에이전트 상태. 설정 메뉴·에이전트 화면·팀 목록·HUD가 같은 연결 현황 캐시로 같은 이름을 보인다.
export function useAgentStatusOf(projectId: string) {
  const agents = useQuery(agentConnectionsQuery(projectId))
  return (
    member: { id?: string; agent_connected: boolean },
    checkFailed?: boolean,
  ) => {
    const row = agents.data?.items.find(
      (agent) => agent.member_id === member.id,
    )
    return {
      status: agentStatusOf(
        row ?? {
          connected: member.agent_connected,
          status_check_required: false,
        },
        checkFailed,
      ),
      lastReportAt: row?.last_report_at,
    }
  }
}
