import { useCallback } from 'react'
import { queryOptions, useQuery } from '@tanstack/react-query'
import { unlessMissing } from '@/api/error'
import { readAllPages } from '@/api/readAllPages'
import { agentsApi } from '@/api/services'
import { BACKGROUND_POLL_MS } from '@/lib/pollIntervals'
import { useAgentCheckStore } from './agentCheckStore'
import { agentStatusOf, pickAgentRow } from './agentStatus'

// API 없음: AGT-A05가 404면 null이다. 이때 상태는 팀원 목록의 agent_connected로 정한다.
// undefined를 돌려주면 TanStack Query가 조회 오류로 처리해, 없는 API와 진짜 조회 실패를 가를 수 없다.
// 팀원마다 한 줄이라 팀원 수만큼 마지막 쪽까지 읽고, 사무실의 작업 현황(agentWorkQuery)처럼 30초마다 다시 받는다
// (S15P31A108-341). 없는 API(null)면 더 부르지 않는다.
// ponytail: 30초 폴링. 서버에 연결 변경 알림이 생기면 그 신호로 이 쿼리를 무효화한다.
export const agentConnectionsQuery = (projectId: string) =>
  queryOptions({
    queryKey: ['projects', projectId, 'agent-connections'],
    queryFn: async () =>
      (await unlessMissing(
        readAllPages((cursor) =>
          agentsApi.list(projectId, { cursor, limit: 100 }),
        ),
      )) ?? null,
    refetchInterval: (query) =>
      query.state.data === null ? false : BACKGROUND_POLL_MS,
  })

// 팀원 한 명의 에이전트 상태. 설정 메뉴·에이전트 화면·팀 목록·HUD·대시보드·사무실이 같은 값으로 같은 이름을 보인다.
// 연결 현황을 받았으면 그 줄로만 정하고(줄이 없으면 연결 전), 없는 API이거나 아직 못 받았으면 팀원 목록의
// agent_connected로 연결됨·연결 전만 어림한다. 이때는 끊김과 미확인을 가를 수 없다.
export function useAgentStatusOf(projectId: string) {
  const agents = useQuery(agentConnectionsQuery(projectId))
  const checkedMemberId = useAgentCheckStore((state) => state.checkedMemberId)
  const { data, isError } = agents
  // 사무실처럼 useMemo에 넣는 쪽이 매 렌더 다시 계산하지 않게 같은 함수를 돌려준다.
  return useCallback(
    (member: { id?: string; agent_connected: boolean }) => {
      const row = pickAgentRow(
        data?.items.filter((agent) => agent.member_id === member.id) ?? [],
      )
      const guess =
        !data && member.agent_connected
          ? { connected: true, status_check_required: false }
          : undefined
      return {
        status: agentStatusOf(
          row ?? guess,
          // 조회가 실패한 동안은 연결을 확인하지 못한 것이라 연결 실패로 보이지 않는다(PR #195 리뷰).
          member.id === checkedMemberId && !isError,
        ),
        lastReportAt: row?.last_report_at,
      }
    },
    [data, isError, checkedMemberId],
  )
}
