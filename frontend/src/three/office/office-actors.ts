import type { Agent } from '../types'
import { agentDisplayName, bibiOf } from '../agentIdentity'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { characterOf } from '@/lib/characters'
import type { OfficeAppearance, OfficeEntity3D } from './OfficeRenderer3D'
import type { Floor } from './floors'
import { officeWorkZone } from './office-layout'
import { PERSONAL_SPACE } from './office-life'
import { officeLanding } from './office-navigation'
import { navOf, navReachable } from './office-nav'
import { RECEPTIONIST } from './social'

// 계정 avatar(사람·로봇·벌)를 사무실 모양으로. 옛 bee-* 값은 회원 설정처럼 가장 가까운 새 캐릭터, 없으면 사람(D34).
// 회원 캐릭터 로봇(member-robot)은 팀원의 AI 에이전트(agent)와 다른 모양이다.
export function avatarAppearance(avatar?: string): OfficeAppearance {
  const key = avatar ? characterOf(avatar).key : 'human'
  return key === 'robot' ? 'member-robot' : key
}

/** 비비 현재 상태(BEE-A04 activity) */
export type BibiActivity = 'DOCUMENT_REVIEW' | 'MEETING' | 'IDLE'

// 비비 상태 → 자리(D35). 문서 검토는 공용 문서함, 회의는 회의실이고, 그 밖(한가함·상태 없음·없는 API)은 라운지 가운데다.
export function bibiSpot(floor: Floor, activity?: BibiActivity | null) {
  const spot =
    activity === 'DOCUMENT_REVIEW'
      ? floor.bibiSpots?.documents
      : activity === 'MEETING'
        ? floor.bibiSpots?.meeting
        : undefined
  return spot ?? floor.seats.lead?.[0]
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
  /** undefined는 아직 읽지 못한 것이고, null은 서버에 상태 API가 없는 것(404)이다. */
  bibiActivity?: BibiActivity | null,
) {
  const result: (OfficeEntity3D & {
    name: string
    stationary?: boolean
    standing?: boolean
    idle?: 'stroll'
  })[] = []
  const lead = bibiOf(agents) ?? RECEPTIONIST
  const spot = bibiSpot(floor, phase < 3 ? undefined : bibiActivity)
  // 고정 사무실(D36)에서 상태를 읽은 뒤에는 상태가 바뀔 때만 새 자리로 걸어가 머문다(D37 에이전트와 같은 방식). 'meeting'은
  // 일하는 상태라 산책하지 않는다. 처음 자리는 걷지 않고 바로 선다.
  const walks = phase >= 3 && !!floor.zoneSigns && bibiActivity !== undefined
  if (phase < 3 || !bibiOf(agents) || floor.zoneSigns)
    result.push({
      id: lead.id,
      name: '비비 · AI 팀장',
      x: spot ? spot.x * 32 + 16 : 4.5 * 32,
      y: spot ? spot.y * 32 + 27 : 3.5 * 32,
      dir: spot?.facing ?? 'down',
      moving: false,
      floor: 1,
      color: '#d5ad42',
      appearance: 'bibi',
      ...(walks ? { status: 'meeting', standing: true } : { stationary: true }),
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
      // 팀원의 AI 에이전트(hired_by = 주인)는 하늘색 로봇이다(D35). 팀원은 계정 avatar 모양, 모르면 사람이다(D34,
      // 명세 요청 MEM-A06 avatar).
      appearance:
        agent.kind === 'lead'
          ? 'bibi'
          : agent.hired_by
            ? 'agent'
            : avatarAppearance(agent.avatar),
      // 연결이 끊긴 에이전트는 자리에서 고개를 떨군 채 있는다.
      stationary:
        agent.kind === 'human' || STATE[agent.status]?.look === 'offline',
    })
  }
  return result
}

type Spot = { x: number; y: number; facing: OfficeEntity3D['dir'] }
// 사무실 에이전트 상태(D37) → 이름표 문구·로봇 모습(캔버스 상태 넷)·걷기 시뮬레이션 상태
// 연결 전·연결 끊김·연결 실패는 이름표만 다르고 자리와 모습은 같다(S15P31A108-341).
const STATE: Record<string, { label: string; look: string; life: string }> = {
  offline: { label: agentStatusLabels.none, look: 'offline', life: 'meeting' },
  disconnected: {
    label: agentStatusLabels.disconnected,
    look: 'offline',
    life: 'meeting',
  },
  failed: { label: agentStatusLabels.failed, look: 'offline', life: 'meeting' },
  working: { label: '작업 중', look: 'working', life: 'working' },
  validating: { label: '검증 중', look: 'working', life: 'meeting' },
  waiting: { label: '대기', look: 'idle', life: 'idle' },
  online: {
    label: agentStatusLabels.connected,
    look: 'online',
    life: 'idle',
  },
}
/** 에이전트 상태의 이름표 문구와 모습. 책상 모니터의 상태 줄도 같은 문구다. 모르는 상태는 연결됨이다. */
export const officeAgentStyle = (state: string) => STATE[state] ?? STATE.online

/**
 * 고정 사무실(D36·D37)의 사람과 에이전트.
 * - 팀원: API 없음, 접속·위치(presence, MON-A07)가 생기면 위치를 받는다. 지금은 각자 아일랜드 자리 옆에서 출발해
 *   빈 바닥을 돌아다니다 멈춰 쉰다(화면 연출). 팀원은 가구를 쓰지 않는다(사용자 결정 "가구랑 상호작용하는 건 에이전트만
 *   가능한 일"). 의자·스툴·리클라이너는 에이전트와 비비만 쓴다.
 * - 에이전트: 작업 현황(MON-A05) 상태별 자리에서 출발한다. 연결 전은 라운지 충전 자리(리클라이너)에서 움직이지 않고,
 *   작업 중은 주인의 빈 아일랜드 의자, 검증 중은 칸반 워룸 화면 앞에 가서 머문다. 대기는 허들 스툴, 연결됨(할 일 없음)은
 *   주인 자리 뒤에서 출발해 팀원처럼 돌아다닌다.
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
    idle?: 'stroll'
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
  // 자리(의자)에서 앉는 방향 반대쪽으로 back칸, 옆으로 side칸 떨어진 빈 곳. 막혔거나 이미 누가 선 곳이면 없다.
  const feet = (spot: Spot) => ({ x: spot.x * 32 + 16, y: spot.y * 32 + 27 })
  /** 서서 출발하는 사람(팀원, 주인 뒤 에이전트)의 발 위치 */
  const standing: { x: number; y: number }[] = []
  // 비비가 서는 세 자리(라운지 가운데·공용 문서함·회의실, D35). 팀원은 그 곁에서 출발하지 않는다. 팀원이 돌아다니다
  // 돌아오는 자리라, 그 곁이면 비비가 자리에 닿지 못한다.
  const bibiSpots = [
    floor.seats.lead?.[0],
    floor.bibiSpots?.documents,
    floor.bibiSpots?.meeting,
  ].flatMap((spot) => (spot ? [feet(spot)] : []))
  const standAt = (
    seat: Spot,
    back: number,
    side: number,
    avoid: readonly { x: number; y: number }[] = [],
  ): Spot | undefined => {
    const [dx, dy] = {
      down: [0, -1],
      up: [0, 1],
      left: [1, 0],
      right: [-1, 0],
    }[seat.facing]
    const spot = {
      x: seat.x + dx * back - dy * side,
      y: seat.y + dy * back + dx * side,
      facing: seat.facing,
    }
    // 몸 둘레까지 걸을 수 있는 바닥이고, 먼저 선 사람과 사람 사이 거리만큼 떨어진 곳이어야 한다. 소수점 좌표가
    // 똑같은지만 보면 등을 맞댄 두 의자의 팀원이 같은 곳에 겹쳐 섰다.
    const at = feet(spot)
    const free =
      navReachable(navOf(floor), at) &&
      !taken.has(key(spot)) &&
      [...standing, ...avoid].every(
        (other) => Math.hypot(other.x - at.x, other.y - at.y) >= PERSONAL_SPACE,
      )
    return free ? (taken.add(key(spot)), standing.push(at), spot) : undefined
  }
  for (const agent of people) {
    const seat = homeSeats.get(agent.id)
    if (seat) taken.add(key(seat))
  }
  const landNear = (seat: Spot): Spot | undefined => {
    const point = officeLanding(floor, feet(seat), [
      ...standing,
      ...bibiSpots,
      ...[...homeSeats.values()].map(feet),
    ])
    if (!point) return undefined
    standing.push(point)
    return {
      x: (point.x - 16) / 32,
      y: (point.y - 27) / 32,
      facing: seat.facing,
    }
  }
  for (const agent of people) {
    const seat = homeSeats.get(agent.id)
    if (!seat || agent.id === currentUserId) continue
    // 자기 의자 뒤 1.5칸, 이웃 의자 사이(옆 0.9칸)에 서서 책상을 본다. 의자 바로 뒤 0.9칸은 주인의 에이전트(연결됨)
    // 자리라 비워 두고, 둘 사이는 사람 사이 거리(PERSONAL_SPACE)보다 멀다. 빈 의자에는 그 팀원의 작업 중 에이전트만 앉는다.
    // 벽을 등진 의자(나무 배치의 북·동쪽 벽 자리)는 뒤가 벽이라, 의자에서 가장 가까운 빈 칸 중 이미 선 사람과 모든
    // 작업 의자에서 사람 사이 거리만큼 떨어진 곳에 선다. 그전에는 그 팀원이 사무실에서 빠졌다.
    const spot =
      standAt(seat, 1.5, 0.9, bibiSpots) ??
      standAt(seat, 1.5, -0.9, bibiSpots) ??
      standAt(seat, 1.5, 0, bibiSpots) ??
      landNear(seat)
    if (!spot) continue
    result.push({
      id: agent.id,
      name: agent.name,
      tag: agent.name,
      x: spot.x * 32 + 16,
      y: spot.y * 32 + 27,
      dir: spot.facing,
      moving: false,
      floor: 1,
      color: agent.color,
      // 팀원은 일하는 상태가 없고, 가구를 쓰지 않고 빈 바닥만 돌아다닌다(D37).
      status: 'idle',
      idle: 'stroll',
      appearance: avatarAppearance(agent.avatar),
      standing: true,
    })
  }
  // 주인 자리 뒤(앉는 방향 반대쪽) 0.9칸에 선다. 막혔으면 자리 옆에 앉는다.
  const behind = (seat: Spot) => standAt(seat, 0.9, 0)
  const ownerChairs = new Set<string>()
  for (const agent of agents.filter(
    (item) => item.hired_by && item.duty_status !== 'off',
  )) {
    const state = STATE[agent.status] ? agent.status : 'online'
    const owner = homeSeats.get(`member:${agent.hired_by}`)
    let standing = false
    let spot: Spot | undefined
    // 작업 중: 주인의 빈 아일랜드 의자에 앉는다(주인은 옆에 서 있다, 사용자 결정). 주인 의자를 모르거나 이미 다른
    // 에이전트가 앉았으면 주인과 가까운 빈 아일랜드 의자.
    if (state === 'working')
      spot =
        owner && !ownerChairs.has(key(owner))
          ? (ownerChairs.add(key(owner)), owner)
          : take(island, owner)
    else if (state === 'validating')
      spot = take(floor.seats.board) ?? take(floor.seats.meeting)
    else if (state === 'waiting') spot = take(floor.seats.meeting)
    else if (STATE[state].look === 'offline')
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
      // 할 일이 없거나 기다리는 에이전트는 첫 자리에서 출발해 돌아다닌다(D37).
      ...(life === 'idle' ? { idle: 'stroll' as const } : {}),
      // 연결이 끊긴 에이전트는 충전 자리에서 고개를 떨군 채 움직이지 않는다.
      ...(look === 'offline' ? { stationary: true, seated: true } : {}),
    })
  }
  return result
}
