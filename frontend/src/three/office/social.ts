import type { Agent, Snapshot } from '../types'
import { agentDisplayName, BIBI_NAME, LEGACY_BIBI_ID } from '../agentIdentity'
import type { Dir, Floor } from './floors'
import { T } from './floors'
import { clearSegment, gridWalkable, type Point, type Walkable } from './motion'

export const RECEPTIONIST_ID = LEGACY_BIBI_ID
export const RECEPTIONIST_NAME = BIBI_NAME
export const RECEPTIONIST: Agent = {
  id: RECEPTIONIST_ID,
  name: RECEPTIONIST_NAME,
  role: 'guide',
  kind: 'agent',
  builtin: true,
  color: '#ffd24a',
  home_zone: 'lobby',
  zone: 'lobby',
  status: 'idle',
  bubble: null,
  bubble_at: null,
  session_no: 1,
  session_started_at: null,
  created_at: '2026-01-01T00:00:00Z',
  context_messages: 0,
  team: null,
  purpose: '사무실 안내',
  model: null,
  effort: null,
  max_tokens: null,
  token_budget_daily: null,
  hired_by: null,
  talking: false,
  talking_with: null,
  duty_status: 'on',
  off_since: null,
  back_at: null,
  usage: { calls: 0, failed: 0, input_tokens: 0, output_tokens: 0, last24h: 0 },
}
export interface OfficeSeat extends Point {
  id: string
  facing: Dir
  kind?: 'chair' | 'sofa' | 'bench'
  approach?: Point
}
export const seatApproach = (seat: OfficeSeat): Point => seat.approach ?? seat

/** Solid sofas keep their collision footprint; the robot approaches from the open front. */
export function officeSeats(floor: Floor): OfficeSeat[] {
  const seats = floor.furniture.flatMap((f, index): OfficeSeat[] => {
    if (f.kind === 'chair')
      return [
        {
          id: `${floor.id}/chair/${index}`,
          x: (f.x + f.w / 2) * T,
          y: (f.y + f.h / 2) * T,
          facing: f.facing ?? 'up',
        },
      ]
    if (f.kind === 'desk' || f.kind === 'execdesk')
      return [
        {
          id: `${floor.id}/desk/${index}`,
          x: (f.x + f.w / 2) * T,
          y: (f.y + f.h + 0.62) * T,
          facing: 'up',
        },
      ]
    if (f.kind === 'sofa' || f.kind === 'bench') {
      const count = Math.max(1, Math.floor(Math.max(0.65, f.w - 0.14)))
      return Array.from({ length: count }, (_, i) => {
        const x = (f.x + 0.63 + i) * T
        return {
          id: `${floor.id}/${f.kind}/${index}/${i}`,
          kind: f.kind as 'sofa' | 'bench',
          x,
          y: (f.y + f.h / 2 + 0.06) * T,
          facing: 'down',
          approach: { x, y: (f.y + f.h) * T + 14 },
        }
      })
    }
    return []
  })
  return seats.filter((seat) => {
    const p = seatApproach(seat)
    return gridWalkable({ x: p.x, y: p.y - 4 }, floor.blocked, T, 6)
  })
}

/** Only a reachable landing spot can trigger automatic seating, never the back of a sofa. */
export function automaticSeat(
  point: Point,
  seats: OfficeSeat[],
  free: Walkable,
  available: (seat: OfficeSeat) => boolean,
  blockedId?: string,
): OfficeSeat | undefined {
  return seats
    .filter((seat) => seat.id !== blockedId && available(seat))
    .sort(
      (a, b) =>
        Math.hypot(seatApproach(a).x - point.x, seatApproach(a).y - point.y) -
        Math.hypot(seatApproach(b).x - point.x, seatApproach(b).y - point.y),
    )
    .find((seat) => {
      const p = seatApproach(seat)
      return (
        Math.hypot(p.x - point.x, p.y - point.y) <= 16 &&
        clearSegment(point, p, free)
      )
    })
}

export function timeGreeting(hour: number): string {
  return hour >= 5 && hour < 12
    ? '좋은 아침이에요!'
    : hour >= 12 && hour < 18
      ? '좋은 오후예요!'
      : hour >= 18 && hour < 23
        ? '좋은 저녁이에요!'
        : '늦은 시간까지 수고 많으세요!'
}

/** Keep spoken UI short; complete responses remain available in server history. */
export function oneSentence(text: string, limit = 90): string {
  const clean = text
    .replace(/^[\s#>*-]+/u, '')
    .replace(/\s+/gu, ' ')
    .trim()
  const first =
    clean.match(/^.*?(?:[!?。！？]|\.(?=\s|$|[가-힣]))/u)?.[0] ?? clean
  const chars = [...first]
  return chars.length > limit
    ? chars
        .slice(0, limit - 1)
        .join('')
        .trimEnd() + '…'
    : first
}

export function proximityGreeting(hour: number, guide: boolean): string {
  return `${timeGreeting(hour).replace(/[!！]$/u, '')}, ${guide ? '필요한 안내가 있으면 말씀해 주세요!' : '오늘도 함께 잘해 봐요!'}`
}

export function agentIntroduction(agent: Agent): string {
  const role =
    (
      {
        lead: '팀의 설계 결정과 작업 검토',
        fe: '프런트엔드 개발',
        be: '백엔드 개발',
        qa: '테스트와 품질 검증',
      } as Record<string, string>
    )[agent.role] || '프로젝트 업무'
  return oneSentence(`${role}을 맡고 있는 ${agentDisplayName(agent)}입니다.`)
}

export function receptionAnswer(
  question: string,
  snap: Pick<Snapshot, 'agents' | 'project'>,
  rooms: string[],
): string {
  const q = question.trim()
  const introduction = oneSentence(
    `저는 ‘${snap.project.name}’의 AI 팀장 비비예요.`,
  )
  if (/문서|설계도|기획서|기획안|자료|업로드/.test(q))
    return '공용 문서함에서 기획안·ERD·요구사항 명세서·API 명세서를 등록하고 Markdown으로 편집해요. 기존 설계가 없으면 ‘프로젝트 준비하기’로 함께 시작해요.'
  if (/초대|몇\s*명|인원/.test(q))
    return '‘팀원 초대’에서 참여 링크를 만들어요. 현재 프로토타입은 인간 팀장 포함 최대 6명으로 체험해요.'
  if (/MCP|연결|클로드|Claude/i.test(q))
    return '‘연결 설정’에서 Claude Code와 MCP 준비 상태를 확인해요. 실제 연결 여부도 구분해서 보여드려요.'
  if (/처음|시작|준비|프로젝트/.test(q))
    return '‘프로젝트 준비하기’를 눌러 목적과 팀 규모를 알려주세요. 기존 설계 문서가 있는지도 함께 확인해요.'
  if (/층|어디|공간|길|엘리베이터/.test(q))
    return oneSentence(`${rooms.join('·')} 모두 한 층에 있어 걸어서 이동해요.`)
  if (/의자|소파|앉/.test(q))
    return '빈 의자나 소파 앞에 멈추면 앉고 방향키를 누르면 일어나요.'
  if (/조작|이동|사용법/.test(q))
    return '방향키나 WASD로 걷고 Shift를 누르면 달릴 수 있어요.'
  if (/팀|동료|누구|에이전트/.test(q)) {
    const agents = snap.agents.filter((a) => a.kind !== 'human')
    return agents.length
      ? oneSentence(
          `${agents.map((a) => a.name).join(', ')}와 함께 일하고 있어요.`,
        )
      : '개인 에이전트는 에이전트 연결에서 등록할 수 있어요.'
  }
  if (/소개|어떤 곳|서비스|비비|BEEZ/i.test(q)) return introduction
  if (/안녕|아침|반가|고마|감사/.test(q))
    return proximityGreeting(new Date().getHours(), true)
  return introduction
}
