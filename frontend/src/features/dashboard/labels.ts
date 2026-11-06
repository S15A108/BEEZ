import { statusLabel } from '@/lib/display'

export const FEATURE_LABELS: Record<string, string> = {
  DOCUMENT_PARSE: '문서 분석',
  TASK_VALIDATION: '작업 검증',
  CHANGE_IMPACT: '변경 영향 분석',
  EMBEDDING: '검색 색인',
  BEE_CHAT: '비비 대화',
  BEE_DRAFT: '비비 초안',
  TASK_ASSIGNMENT: '작업 배정 추천',
  MEETING_ANALYSIS: '회의 분석',
}

export const dashboardTabs = [
  ['overview', '한눈에 보기'],
  ['timeline', '활동 타임라인'],
  ['agents', '에이전트 현황'],
  ['usage', '사용량·비용'],
] as const
export type DashboardTab = (typeof dashboardTabs)[number][0]

// 활동 결과 칩. 명세의 result는 SUCCESS·FAILURE뿐이라 검증 통과와 검토 시작은 유형으로 구분한다(공통 계약 6.2).
// notable: 한눈에 보기처럼 좁은 곳에서는 '성공'을 빼고 의미 있는 결과만 보인다.
export function activityChip(item: { type: string; result: string }) {
  if (item.result === 'FAILURE')
    return {
      label: '실패',
      className: 'bg-[#fdecec] text-[#a3141b]',
      notable: true,
    }
  if (item.type === 'VALIDATION_FINISHED')
    return {
      label: '통과',
      className: 'bg-emerald-50 text-[#00753a]',
      notable: true,
    }
  if (item.type === 'CHANGE_PROPOSAL_REVIEW_STARTED')
    return {
      label: '진행 중',
      className: 'bg-[#fff4cf] text-[#8a6a00]',
      // 보드: 타임라인에는 이 칩이 있고 한눈에 보기의 같은 줄에는 없다(PNL-09-v2-timeline, PNL-09-v2-dashboard).
      notable: false,
    }
  if (item.result === 'SUCCESS')
    return {
      label: '성공',
      className: 'bg-emerald-50 text-[#00753a]',
      notable: false,
    }
  return {
    label: statusLabel(item.result) || item.result,
    className: 'bg-muted text-muted-foreground',
    notable: true,
  }
}

// '민수의 에이전트'. 한 팀원의 토큰이 둘 이상이면 이름이 같아지므로 토큰 이름을 붙여 구분한다.
export function agentLabel(
  agent: { member_id: string; agent_session_id?: string; agent_name?: string },
  all: { member_id: string; agent_session_id?: string }[],
  memberName: (memberId: string) => string,
) {
  const base = `${memberName(agent.member_id)}의 에이전트`
  const sessions = all.filter(
    (other) => other.agent_session_id && other.member_id === agent.member_id,
  )
  return sessions.length > 1
    ? `${base} · ${agent.agent_name ?? `#${agent.agent_session_id}`}`
    : base
}

// 호출이 있는데 서버가 호출자별로 나누지 않아 에이전트별 줄이 비었을 때의 안내.
export const UNSPLIT_USAGE_NOTICE =
  '에이전트별로 나눈 기록이 없어요. 사용량·비용 탭의 기능별 보기에서 전체 호출을 볼 수 있어요.'

// 추정 비용 표시. 비용이 하나도 보고되지 않으면 missing을, 일부 호출의 비용을 모르면
// 그 건수를 붙여 0원·전체 금액처럼 보이지 않게 한다.
export function costText(
  amount: string | undefined,
  currency: string | undefined,
  unknownCalls = 0,
  missing = '미보고',
) {
  if (amount === undefined) return missing
  const known = `${amount} ${currency ?? ''}`.trim()
  const unknown = unknownText(unknownCalls)
  return unknown ? `${known} + ${unknown}` : known
}

// '27회 · 0.51 USD'. 호출이 없으면 서버가 비용을 생략하므로 '0회'만 쓴다.
export function callsText(
  row: {
    call_count: number
    estimated_cost_amount?: string
    cost_unknown_call_count?: number
  },
  currency: string | undefined,
) {
  const calls = `${row.call_count.toLocaleString('ko-KR')}회`
  return row.call_count
    ? `${calls} · ${costText(row.estimated_cost_amount, currency, row.cost_unknown_call_count, '비용 미보고')}`
    : calls
}

// 토큰·비용을 몰라 합계에 들어가지 못한 호출 수. 없으면 null.
export function unknownText(unknownCalls = 0) {
  return unknownCalls > 0
    ? `미보고 ${unknownCalls.toLocaleString('ko-KR')}건`
    : null
}
