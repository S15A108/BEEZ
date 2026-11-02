import type { Agent, Zone } from '../types'
import type { Floor, Furniture, Seat, Tile } from './floors'

export type OfficePopulation = Partial<Record<Zone, number>>
export const officeObjectKey = (item: Furniture) =>
  `${item.kind}/${item.x}/${item.y}/${item.w}/${item.h}/${item.facing ?? ''}/${item.tag ?? ''}/${item.decorId ?? ''}`
export const officeWorkZone = (
  agent: Pick<Agent, 'kind' | 'home_zone'>,
): Zone => (agent.kind === 'human' ? 'humandesk' : agent.home_zone)
export function officePopulationOf(
  agents: readonly Pick<Agent, 'kind' | 'home_zone'>[],
): OfficePopulation {
  return agents.reduce<OfficePopulation>((counts, agent) => {
    const zone = officeWorkZone(agent)
    counts[zone] = (counts[zone] ?? 0) + 1
    return counts
  }, {})
}
const ZONE_TILE: Record<Zone, Tile> = {
  lobby: 'l',
  lead: 'L',
  meeting: 'm',
  dev: 'd',
  board: 'b',
  review: 'r',
  humandesk: 'u',
  control: 'c',
  hr: 'k',
}
const ZONE_TEAM: Partial<Record<Zone, string>> = {
  lead: 'planning',
  meeting: 'planning',
  board: 'planning',
  dev: 'dev',
  review: 'security',
  humandesk: 'human',
  control: 'ops',
  hr: 'hr',
}

/** A small shared office grows by occupied workstations, including people and every AI department. */
export function makeTeamOffice(
  keys: readonly string[],
  population: OfficePopulation,
): Floor {
  const count = (zone: Zone) =>
    (zone === 'humandesk' || keys.includes(ZONE_TEAM[zone] ?? 'lobby')) &&
    Number.isFinite(population[zone])
      ? Math.max(0, Math.ceil(population[zone]!))
      : 0
  const total = Object.keys(ZONE_TILE).reduce(
    (sum, zone) => sum + count(zone as Zone),
    0,
  )
  const furniture: Furniture[] = [],
    seats: Floor['seats'] = {},
    points: NonNullable<Floor['points']> = []
  const chair = (x: number, y: number, facing: Seat['facing'] = 'up') =>
    furniture.push({ kind: 'chair', x, y, w: 1, h: 1, solid: false, facing })
  const seat = (x: number, y: number, facing: Seat['facing'] = 'up'): Seat => ({
    x,
    y,
    facing,
  })
  const stations: { x: number; y: number; zone: Zone; executive?: boolean }[] =
    []
  const overflow: { zone: Zone; executive?: boolean }[] = []
  const developers = keys.includes('dev') ? Math.max(1, count('dev')) : 0
  for (let i = 0; i < developers; i++) {
    if (i < 6)
      stations.push({
        x: 1 + (i % 2) * 6,
        y: 8 + Math.floor(i / 2) * 3,
        zone: 'dev',
      })
    else overflow.push({ zone: 'dev' })
  }
  if (count('lead'))
    stations.push({ x: 14, y: 1, zone: 'lead', executive: true })
  for (let i = 1; i < count('lead'); i++)
    overflow.push({ zone: 'lead', executive: true })
  const support = (
    ['humandesk', 'review', 'control', 'hr', 'board'] as Zone[]
  ).flatMap((zone) => Array.from({ length: count(zone) }, () => ({ zone })))
  support.forEach((item, index) => {
    if (index === 0) stations.push({ x: 14, y: 12, ...item })
    else if (index < 3)
      stations.push({ x: 21, y: index === 1 ? 8 : 12, ...item })
    else overflow.push(item)
  })
  overflow.forEach((item, index) =>
    stations.push({
      x: 30 + Math.floor(index / 4) * 6,
      y: 3 + (index % 4) * 3,
      ...item,
    }),
  )
  const W = Math.max(
      total > 5 ? 36 : 30,
      ...stations.map((station) => station.x + 5),
    ),
    H = 20
  const grid: Tile[][] = Array.from({ length: H }, () =>
    Array<Tile>(W).fill('#'),
  )
  const fill = (x: number, y: number, w: number, h: number, tile: Tile) => {
    for (let row = y; row < Math.min(H, y + h); row++)
      for (let col = x; col < Math.min(W, x + w); col++) grid[row][col] = tile
  }
  fill(1, 1, W - 2, 15, 'h')
  fill(15, 16, 6, 1, 'l')
  fill(0, 17, W, 2, 's')
  fill(0, 19, W, 1, 'o')
  fill(1, 1, 8, 6, 'l')
  furniture.push(
    { kind: 'reception', x: 3, y: 2, w: 3, h: 1, solid: true },
    { kind: 'sofa', x: 2, y: 5, w: 3, h: 1, solid: true },
    { kind: 'table', x: 6, y: 5, w: 2, h: 1, solid: true, tag: 'coffee-table' },
    { kind: 'cooler', x: 1, y: 2, w: 1, h: 1, solid: true },
  )
  chair(4, 4)
  chair(7, 3)
  seats.lobby = [seat(4, 4), seat(7, 3), seat(3, 5, 'down')]
  points.push({
    x: 3,
    y: 2,
    w: 3,
    h: 1,
    act: 'architecture',
    label: '비비에게 말 걸기',
  })
  if (keys.includes('planning')) {
    fill(13, 4, 6, 5, 'm')
    fill(19, 1, 7, 5, 'b')
    furniture.push(
      { kind: 'rug', x: 13, y: 4, w: 6, h: 5, solid: false },
      { kind: 'table', x: 14, y: 5, w: 4, h: 2, solid: true, tag: 'meeting' },
      { kind: 'projector', x: 19, y: 1, w: 6, h: 1, solid: true },
    )
    const meetingSeats = [
      seat(14, 4, 'down'),
      seat(16, 7),
      seat(16, 4, 'down'),
      seat(14, 7),
      seat(13, 5, 'right'),
      seat(18, 5, 'left'),
    ].slice(0, Math.max(2, Math.min(6, total)))
    meetingSeats.forEach((item) => chair(item.x, item.y, item.facing))
    seats.meeting = meetingSeats
    points.push({
      x: 19,
      y: 1,
      w: 6,
      h: 1,
      act: 'decisions',
      label: '프로젝터 작업 현황 보기',
    })
  }
  for (const station of stations) {
    fill(station.x, station.y, 4, 3, ZONE_TILE[station.zone])
    furniture.push({
      kind: station.executive ? 'execdesk' : 'desk',
      x: station.x,
      y: station.y,
      w: 3,
      h: 1,
      solid: true,
      tag: `workstation-${station.zone}`,
    })
    ;(seats[station.zone] ??= []).push(seat(station.x + 1, station.y + 1))
  }
  // Two short screens distinguish the lounge and meeting nook, leaving both ends open.
  furniture.push({
    kind: 'glass-partition',
    x: 9,
    y: 2,
    w: 1,
    h: 3,
    solid: true,
  })
  if (keys.includes('planning'))
    furniture.push({
      kind: 'glass-partition',
      x: 14,
      y: 10,
      w: 4,
      h: 1,
      solid: true,
    })
  if (W >= 41)
    furniture.push({
      kind: 'glass-partition',
      x: 26,
      y: 9,
      w: 1,
      h: 3,
      solid: true,
    })
  for (const [x, y] of [
    [1, 1],
    [W - 5, 15],
  ])
    furniture.push({ kind: 'plant', x, y, w: 1, h: 1, solid: true })
  const blocked = grid.map((row) =>
    row.map((tile) => tile === '#' || tile === 'g' || tile === 'o'),
  )
  for (const item of furniture)
    if (item.solid)
      for (let y = item.y; y < item.y + item.h; y++)
        for (let x = item.x; x < item.x + item.w; x++) blocked[y][x] = true
  const outdoorExit = {
    x: W - 3.4,
    y: 15.2,
    w: 2.2,
    h: 0.7,
    landing: { x: (W - 2.3) * 32, y: 14.2 * 32 },
    label: '엘리베이터 · 1층 거리로 나가기',
  }
  for (
    let y = Math.floor(outdoorExit.y);
    y < Math.ceil(outdoorExit.y + outdoorExit.h);
    y++
  )
    for (
      let x = Math.floor(outdoorExit.x);
      x < Math.ceil(outdoorExit.x + outdoorExit.w);
      x++
    )
      blocked[y][x] = true
  return {
    id: 1,
    name: '우리 팀 사무실',
    short: 'OFFICE',
    W,
    H,
    grid,
    blocked,
    furniture,
    seats,
    points,
    signs: [],
    decor: [],
    elevator: [],
    exit: { x: 17, y: 14 },
    outdoorExit,
    footprint: { left: 1, back: 1, right: W - 1, front: 16 },
  }
}
