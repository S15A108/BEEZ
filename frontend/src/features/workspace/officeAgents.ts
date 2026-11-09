import type { AgentWork } from '@/api/contracts/common'
import {
  agentStatusLabels,
  type AgentStatus,
} from '@/features/agents/agentStatus'

// 사무실 AI 에이전트의 상태(D37). 캔버스 SCR-06-rec-office-chars의 네 상태에 '검증 중'을 더해 자리를 나눈다.
// offline 연결 전 · disconnected 연결 끊김 · failed 연결 실패(셋 다 라운지 충전 자리) · working 작업 중(주인 옆
// 아일랜드) · validating 검증 중(칸반 워룸 화면 앞) · waiting 대기(허들 스툴) · online 연결됨·할 일 없음(주인 자리 근처)
export type OfficeAgentState =
  | 'offline'
  | 'disconnected'
  | 'failed'
  | 'working'
  | 'validating'
  | 'waiting'
  | 'online'

const live = (connection: AgentStatus) =>
  connection === 'connected' || connection === 'recheck'

const WAITING_REASON: Record<string, string> = {
  INPUT: '입력 대기',
  APPROVAL: '승인 대기',
  DEPENDENCY: '선행 작업 대기',
  REVALIDATION: '재검증 대기',
}

// 연결된 에이전트의 자리: 작업 현황(MON-A05)의 지금 작업
function activityOf(mine: AgentWork | undefined): {
  state: OfficeAgentState
  note?: string
} {
  if (mine?.validation_status === 'RUNNING') return { state: 'validating' }
  if (mine?.task_status === 'IN_PROGRESS') return { state: 'working' }
  if (mine?.task_status === 'WAITING')
    return {
      state: 'waiting',
      note: mine.waiting_reason
        ? (WAITING_REASON[mine.waiting_reason] ?? mine.waiting_reason)
        : '대기',
    }
  return { state: 'online' }
}

/**
 * 팀원 한 명의 에이전트 상태. 연결 여부는 연결 현황(AGT-A05)의 상태(connection)를 받았으면 그것으로 정한다. 작업
 * 현황에는 확인 필요 표시가 없어 다른 화면과 같은 연결 상태를 보이려면 연결 현황을 함께 읽어야 한다(S15P31A108-341).
 * 연결 현황이 없으면 작업 현황의 세션으로, 작업 현황도 없으면(undefined) 팀원 목록의 연결 여부로 어림한다.
 * 자리는 작업 현황을 따르고, 작업 현황이 없으면 주인의 진행 중 작업으로 어림한다. 연결 미확인은 자리를 그대로 두고
 * 이름표 아래(note)에 단다.
 */
export function officeAgentState(
  member: { id: string; agent_connected: boolean },
  work: readonly AgentWork[] | undefined,
  ownerTasks: readonly { status: string }[],
  connection?: AgentStatus,
): { state: OfficeAgentState; note?: string } {
  const mine = work?.find((item) => item.member_id === member.id)
  // 작업 현황의 세션: 서버는 agent_connected와 이 행을 같은 조건으로 만들고, 작업 현황은 30초마다 새로 받으므로
  // 사무실을 연 뒤의 연결·끊김도 반영된다(PR #128 리뷰).
  const connected = connection
    ? live(connection)
    : work
      ? !!mine?.agent_session_id
      : member.agent_connected
  if (!connected)
    return {
      state:
        connection === 'disconnected' || connection === 'failed'
          ? connection
          : 'offline',
    }
  const { state, note } = work
    ? activityOf(mine)
    : {
        state: ownerTasks.some((task) => task.status === 'IN_PROGRESS')
          ? ('working' as const)
          : ('online' as const),
      }
  const notes = [connection === 'recheck' && agentStatusLabels.recheck, note]
    .filter(Boolean)
    .join(' · ')
  return notes ? { state, note: notes } : { state }
}

/**
 * 사무실에 그릴 에이전트: 연결된 에이전트(연결 미확인 포함)와 아직 연결하지 않은 내 에이전트. 연결 현황이 있으면 그
 * 상태로, 없고 작업 현황이 있으면 첫 조회의 agent_connected 대신 그 세션으로 연결을 본다(officeAgentState와 같은
 * 기준, PR #128 리뷰).
 */
export const showsOfficeAgent = (
  member: { id: string; account_id: string; agent_connected: boolean },
  work: readonly AgentWork[] | undefined,
  accountId: string | undefined,
  connection?: AgentStatus,
) =>
  member.account_id === accountId ||
  (connection
    ? live(connection)
    : work
      ? !!work.find((item) => item.member_id === member.id)?.agent_session_id
      : member.agent_connected)
