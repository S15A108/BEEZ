import type { AgentWork } from '@/api/contracts/common'

// 사무실 AI 에이전트의 상태(D37). 캔버스 SCR-06-rec-office-chars의 네 상태에 '검증 중'을 더해 자리를 나눈다.
// offline 연결 끊김(라운지 충전 자리) · working 작업 중(주인 옆 아일랜드) · validating 검증 중(칸반 워룸 화면 앞)
// · waiting 대기(허들 스툴) · online 연결됨·할 일 없음(주인 자리 근처에 서 있음)
export type OfficeAgentState =
  'offline' | 'working' | 'validating' | 'waiting' | 'online'

const WAITING_REASON: Record<string, string> = {
  INPUT: '입력 대기',
  APPROVAL: '승인 대기',
  DEPENDENCY: '선행 작업 대기',
  REVALIDATION: '재검증 대기',
}

/**
 * 팀원 한 명의 에이전트 상태. 작업 현황 API(MON-A05)가 있으면 그것을 따르고, 없으면(undefined) 팀원 목록의 연결
 * 여부와 주인의 진행 중 작업으로 어림한다.
 */
export function officeAgentState(
  member: { id: string; agent_connected: boolean },
  work: readonly AgentWork[] | undefined,
  ownerTasks: readonly { status: string }[],
): { state: OfficeAgentState; note?: string } {
  if (!work) {
    if (!member.agent_connected) return { state: 'offline' }
    return {
      state: ownerTasks.some((task) => task.status === 'IN_PROGRESS')
        ? 'working'
        : 'online',
    }
  }
  // 작업 현황이 있으면 그 세션으로 연결을 본다. 서버는 agent_connected와 이 행을 같은 조건(폐기되지 않은 토큰에
  // 에이전트 세션이 있음)으로 만들고, 작업 현황은 30초마다 새로 받으므로 사무실을 연 뒤의 연결·끊김도 반영된다(PR #128 리뷰).
  const mine = work.find((item) => item.member_id === member.id)
  if (!mine?.agent_session_id) return { state: 'offline' }
  if (mine.validation_status === 'RUNNING') return { state: 'validating' }
  if (mine.task_status === 'IN_PROGRESS') return { state: 'working' }
  if (mine.task_status === 'WAITING')
    return {
      state: 'waiting',
      note: mine.waiting_reason
        ? (WAITING_REASON[mine.waiting_reason] ?? mine.waiting_reason)
        : '대기',
    }
  return { state: 'online' }
}

/**
 * 사무실에 그릴 에이전트: 연결된 에이전트와 아직 연결하지 않은 내 에이전트. 작업 현황이 있으면 첫 조회의
 * agent_connected 대신 그 세션으로 연결을 본다(officeAgentState와 같은 기준, PR #128 리뷰).
 */
export const showsOfficeAgent = (
  member: { id: string; account_id: string; agent_connected: boolean },
  work: readonly AgentWork[] | undefined,
  accountId: string | undefined,
) =>
  member.account_id === accountId ||
  (work
    ? !!work.find((item) => item.member_id === member.id)?.agent_session_id
    : member.agent_connected)
