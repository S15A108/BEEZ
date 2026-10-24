import type { Agent } from '../types'
import { agentDisplayName, bibiOf } from '../agentIdentity'
import type { OfficeEntity3D } from './OfficeRenderer3D'
import type { Floor } from './floors'
import { officeWorkZone } from './office-layout'
import { RECEPTIONIST } from './social'

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
      appearance:
        agent.kind === 'human'
          ? 'human'
          : agent.kind === 'lead'
            ? 'bibi'
            : 'robot',
      stationary: agent.kind === 'human',
    })
  }
  return result
}
