import type { Agent } from '../types'
import { agentDisplayName, bibiOf } from '../agentIdentity'
import { characterOf } from '@/lib/characters'
import type { OfficeAppearance, OfficeEntity3D } from './OfficeRenderer3D'
import type { Floor } from './floors'
import { officeWorkZone } from './office-layout'
import { RECEPTIONIST } from './social'

// 계정 avatar(사람·로봇·벌)를 사무실 모양으로. 옛 bee-* 값은 회원 설정처럼 가장 가까운 새 캐릭터, 없으면 사람(D24).
// 회원 캐릭터 로봇(member-robot)은 팀원의 AI 에이전트(agent)와 다른 모양이다.
export function avatarAppearance(avatar?: string): OfficeAppearance {
  const key = avatar ? characterOf(avatar).key : 'human'
  return key === 'robot' ? 'member-robot' : key
}

export function officeActors(
  floor: Floor,
  agents: Agent[],
  phase: number,
  currentUserId: string | undefined,
  homeSeats: Map<
    string,
    { x: number; y: number; facing: OfficeEntity3D['dir'] }
  >,
) {
  const result: (OfficeEntity3D & { name: string; stationary?: boolean })[] = []
  const lead = bibiOf(agents) ?? RECEPTIONIST
  // 고정 사무실(D28)에서는 단계와 관계없이 라운지 가운데(lead 자리)에 서 있다(사용자 결정 '라운지 가운데에 세움').
  const bibiSpot = floor.seats.lead?.[0]
  if (phase < 3 || !bibiOf(agents) || floor.zoneSigns)
    result.push({
      id: lead.id,
      name: '비비 · AI 팀장',
      x: bibiSpot ? bibiSpot.x * 32 + 16 : 4.5 * 32,
      y: bibiSpot ? bibiSpot.y * 32 + 27 : 3.5 * 32,
      dir: 'down',
      moving: false,
      floor: 1,
      color: '#d5ad42',
      appearance: 'bibi',
      stationary: true,
    })
  if (phase < 3) return result
  if (floor.zoneSigns)
    return [
      ...result,
      ...fixedOfficeActors(floor, agents, currentUserId, homeSeats),
    ]
  const occupied = new Map<string, number>()
  for (const agent of agents) {
    if (agent.id === currentUserId || agent.duty_status === 'off') continue
    const zone = agent.kind === 'human' ? officeWorkZone(agent) : agent.zone
    const seats = floor.seats[zone] ?? floor.seats.lobby ?? []
    const index = occupied.get(zone) ?? 0
    occupied.set(zone, index + 1)
    const seat =
      (zone === officeWorkZone(agent) ? homeSeats.get(agent.id) : undefined) ??
      seats[index % seats.length]
    if (!seat) continue
    result.push({
      id: agent.id,
      name:
        agent.kind === 'lead'
          ? `${agentDisplayName(agent)} · AI 팀장`
          : agent.name,
      x: seat.x * 32 + 16,
      y: seat.y * 32 + 27,
      dir: seat.facing,
      moving: false,
      floor: 1,
      color: agent.color,
      status: agent.status,
      // 팀원의 AI 에이전트(hired_by = 주인)는 하늘색 로봇이다(D27). 팀원은 계정 avatar 모양, 모르면 사람이다(D24,
      // 명세 요청 MEM-A01 avatar).
      appearance:
        agent.kind === 'lead'
          ? 'bibi'
          : agent.hired_by
            ? 'agent'
            : avatarAppearance(agent.avatar),
      // 연결이 끊긴 에이전트는 자리에서 고개를 떨군 채 있는다.
      stationary: agent.kind === 'human' || agent.status === 'offline',
    })
  }
  return result
}

type Spot = { x: number; y: number; facing: OfficeEntity3D['dir'] }
// 사무실 에이전트 상태(D29) → 이름표 문구·로봇 모습(캔버스 상태 넷)·걷기 시뮬레이션 상태
const STATE: Record<string, { label: string; look: string; life: string }> = {
  offline: { label: '연결 끊김', look: 'offline', life: 'meeting' },
  working: { label: '작업 중', look: 'working', life: 'working' },
  validating: { label: '검증 중', look: 'working', life: 'meeting' },
  waiting: { label: '대기', look: 'idle', life: 'meeting' },
  online: { label: '연결됨', look: 'online', life: 'meeting' },
}

/**
 * 고정 사무실(D28·D29)의 사람과 에이전트.
 * - 팀원: API 없음, 접속·위치(presence, MON-A07)가 생기면 위치를 받는다. 지금은 각자 아일랜드 자리에 앉아 있다.
 * - 에이전트: 작업 현황(MON-A05) 상태별 자리로 걸어가 머문다. 연결 끊김은 라운지 충전 자리(리클라이너),
 *   작업 중은 주인 옆 아일랜드 자리, 검증 중은 칸반 워룸 화면 앞, 대기는 허들 스툴, 연결됨(할 일 없음)은 주인 자리 뒤에 선다.
 *   상태가 바뀔 때만 새 자리로 걷고, 산책은 하지 않는다.
 */
function fixedOfficeActors(
  floor: Floor,
  agents: Agent[],
  currentUserId: string | undefined,
  homeSeats: Map<string, Spot>,
) {
  const result: (OfficeEntity3D & {
    name: string
    stationary?: boolean
    standing?: boolean
  })[] = []
  const taken = new Set<string>()
  const key = (spot: Spot) => `${spot.x}/${spot.y}`
  const take = (list: readonly Spot[] | undefined, near?: Spot) => {
    const spot = (list ?? [])
      .filter((item) => !taken.has(key(item)))
      .sort((a, b) =>
        near
          ? Math.hypot(a.x - near.x, a.y - near.y) -
            Math.hypot(b.x - near.x, b.y - near.y)
          : 0,
      )[0]
    if (spot) taken.add(key(spot))
    return spot
  }
  const island = (floor.seats.dev ?? []).slice(0, 12)
  const people = agents.filter(
    (agent) =>
      agent.kind !== 'lead' && !agent.hired_by && agent.duty_status !== 'off',
  )
  for (const agent of people) {
    const seat = homeSeats.get(agent.id)
    if (seat) taken.add(key(seat))
    if (!seat || agent.id === currentUserId) continue
    result.push({
      id: agent.id,
      name: agent.name,
      tag: agent.name,
      x: seat.x * 32 + 16,
      y: seat.y * 32 + 27,
      dir: seat.facing,
      moving: false,
      floor: 1,
      color: agent.color,
      status: agent.status,
      appearance: avatarAppearance(agent.avatar),
      seated: true,
      stationary: true,
    })
  }
  // 주인 자리 뒤(앉는 방향 반대쪽) 0.9칸에 선다. 막혔으면 자리 옆에 앉는다.
  const behind = (seat: Spot): Spot | undefined => {
    const [dx, dy] = {
      down: [0, -1],
      up: [0, 1],
      left: [1, 0],
      right: [-1, 0],
    }[seat.facing]
    const spot = {
      x: seat.x + dx * 0.9,
      y: seat.y + dy * 0.9,
      facing: seat.facing,
    }
    const free =
      !floor.blocked[Math.floor(spot.y + 23 / 32)]?.[Math.floor(spot.x + 0.5)]
    return free && !taken.has(key(spot))
      ? (taken.add(key(spot)), spot)
      : undefined
  }
  for (const agent of agents.filter(
    (item) => item.hired_by && item.duty_status !== 'off',
  )) {
    const state = STATE[agent.status] ? agent.status : 'online'
    const owner = homeSeats.get(`member:${agent.hired_by}`)
    let standing = false
    let spot: Spot | undefined
    if (state === 'working') spot = take(island, owner)
    else if (state === 'validating')
      spot = take(floor.seats.board) ?? take(floor.seats.meeting)
    else if (state === 'waiting') spot = take(floor.seats.meeting)
    else if (state === 'offline')
      spot = take(floor.seats.lobby) ?? take(floor.seats.humandesk)
    else {
      spot = owner ? behind(owner) : undefined
      standing = !!spot
      spot ??= take(island, owner)
    }
    if (!spot) continue
    const { label, look, life } = STATE[state]
    result.push({
      id: agent.id,
      name: agent.name,
      tag: `${agent.name} · ${label}`,
      note: agent.bubble ?? undefined,
      x: spot.x * 32 + 16,
      y: spot.y * 32 + 27,
      dir: spot.facing,
      moving: false,
      floor: 1,
      color: agent.color,
      status: life,
      agentStatus: look,
      appearance: 'agent',
      standing,
      // 연결이 끊긴 에이전트는 충전 자리에서 고개를 떨군 채 움직이지 않는다.
      ...(state === 'offline' ? { stationary: true, seated: true } : {}),
    })
  }
  return result
}
