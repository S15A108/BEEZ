import type { Agent } from '../types'
import { agentDisplayName, bibiOf } from '../agentIdentity'
import { characterOf } from '@/lib/characters'
import type { OfficeAppearance, OfficeEntity3D } from './OfficeRenderer3D'
import type { Floor } from './floors'
import { officeWorkZone } from './office-layout'
import { RECEPTIONIST } from './social'

// 계정 avatar(사람·로봇·벌)를 사무실 모양으로. 옛 bee-* 값은 회원 설정처럼 가장 가까운 새 캐릭터, 없으면 사람(D24).
// 회원 캐릭터 로봇(member-robot)은 avatar를 모르는 팀원의 기본 로봇(robot)과 다른 모양이다.
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
  if (phase < 3 || !bibiOf(agents))
    result.push({
      id: lead.id,
      name: '비비 · AI 팀장',
      x: 4.5 * 32,
      y: 3.5 * 32,
      dir: 'down',
      moving: false,
      floor: 1,
      color: '#d5ad42',
      appearance: 'bibi',
      stationary: true,
    })
  if (phase < 3) return result
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
      // 팀원 목록에 avatar가 오면 그 모양, 없으면 지금처럼 사람은 사람·에이전트는 로봇(명세 요청 MEM-A01 avatar).
      appearance:
        agent.kind === 'lead'
          ? 'bibi'
          : agent.avatar
            ? avatarAppearance(agent.avatar)
            : agent.kind === 'human'
              ? 'human'
              : 'robot',
      stationary: agent.kind === 'human',
    })
  }
  return result
}
