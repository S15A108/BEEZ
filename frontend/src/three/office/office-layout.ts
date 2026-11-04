import type { Agent, Zone } from '../types'
import type { Dir, Floor, Furniture, FurnitureKind, Seat, Tile } from './floors'
import {
  OFFICE_BIBI,
  OFFICE_ENTRANCE,
  OFFICE_LOUNGE,
  OFFICE_PIECES,
  OFFICE_ROOM,
  OFFICE_ZONES,
  type OfficePiece,
} from './officePlan'
import { officeDesignOf } from './officeLook'

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

// 캔버스 배치(m, 방 가운데 원점)를 타일로 옮긴다. 1 m = 1타일이고 서쪽·북쪽 벽이 0번 칸이다.
const ROOM_W = OFFICE_ROOM.w
const ROOM_D = OFFICE_ROOM.d
const W = ROOM_W + 2
const H = ROOM_D + 4
const tx = (x: number) => x + ROOM_W / 2 + 1
const ty = (z: number) => z + ROOM_D / 2 + 1
// 앉은 사람 발끝 위치(px)는 seat.x * 32 + 16, seat.y * 32 + 27이다. 의자 가운데에 앉도록 자리를 그만큼 당긴다.
const seatAt = (x: number, z: number, facing: Dir): Seat => ({
  x: tx(x) - 0.5,
  y: ty(z) - 27 / 32,
  facing,
})
// 의자 방향(ry, 모델 앞 = +z)을 앉은 사람이 보는 쪽으로
const facingOf = (ry: number): Dir => {
  const turn = Math.round(ry / (Math.PI / 2))
  return (['down', 'right', 'up', 'left'] as const)[((turn % 4) + 4) % 4]
}
const toward = (from: OfficePiece, x: number, z: number): Dir =>
  Math.abs(x - from.x) > Math.abs(z - from.z)
    ? x > from.x
      ? 'right'
      : 'left'
    : z > from.z
      ? 'down'
      : 'up'
const KIND: Record<string, FurnitureKind> = {
  table: 'table',
  chair: 'chair',
  stool: 'chair',
  monitor: 'console',
  laptop: 'console',
  bookcase: 'shelf',
  cabinet: 'shelf',
  plant: 'plant',
  lamp: 'lamp',
  rug: 'rug',
  board: 'designboard',
  screen: 'projector',
}
// 길을 막는 가구. 책상 위 소품, 의자, 러그는 막지 않는다.
const SOLID = new Set([
  'table',
  'bookcase',
  'cabinet',
  'plant',
  'lamp',
  'board',
  'speaker',
])
const onTop = (piece: OfficePiece) => piece.y > 0.2

/**
 * 고정 사무실(캔버스 SCR-06-rec-office v488, D36). 팀원 수와 상관없이 같은 방이다.
 * 작업 자리는 아일랜드 두 개의 12석이고, 넘는 인원은 허들 스툴과 워룸 스툴에 앉는다.
 */
export function makeTeamOffice(design?: string): Floor {
  const grid: Tile[][] = Array.from({ length: H }, () =>
    Array<Tile>(W).fill('#'),
  )
  const fill = (x0: number, z0: number, x1: number, z1: number, tile: Tile) => {
    for (let y = Math.round(ty(z0)); y < Math.round(ty(z1)); y++)
      for (let x = Math.round(tx(x0)); x < Math.round(tx(x1)); x++)
        if (grid[y]?.[x] !== undefined) grid[y][x] = tile
  }
  fill(-ROOM_W / 2, -ROOM_D / 2, ROOM_W / 2, ROOM_D / 2, 'h')
  // 바닥 재질은 구역 글자로 정한다: 작업 현황·허들은 카펫(d·m), 그 밖은 오크(D36).
  const ZONE_TILE: Record<number, Tile> = {
    1: 'b',
    2: 'r',
    3: 'm',
    4: 'd',
    5: 'c',
    6: 'k',
  }
  for (const zone of OFFICE_ZONES)
    fill(zone.x0, zone.z0, zone.x1, zone.z1, ZONE_TILE[zone.n])
  fill(
    OFFICE_LOUNGE.x0,
    OFFICE_LOUNGE.z0,
    OFFICE_LOUNGE.x1,
    OFFICE_LOUNGE.z1,
    'l',
  )
  fill(3.2, 1, 8.8, 4.4, 'u')
  for (let x = 0; x < W; x++) {
    grid[H - 3][x] = 's'
    grid[H - 2][x] = 's'
    grid[H - 1][x] = 'o'
  }

  const furniture: Furniture[] = []
  const blocked = grid.map((row) =>
    row.map((tile) => tile === '#' || tile === 'o'),
  )
  // 단단한 가구가 막는 칸: 1 m 이상인 쪽은 60% 넘게 덮는 칸, 그보다 가는 쪽은 가운데가 놓인 칸이다. 아일랜드 테이블
  // 가장자리 의자 칸은 열려 있어 앉을 수 있고, 칸반·TV장처럼 얇은 가구도 한 줄을 막는다.
  const span = (center: number, size: number) => {
    if (size < 1) return [Math.floor(center)]
    const lo = center - size / 2,
      hi = center + size / 2
    const tiles: number[] = []
    for (let t = Math.floor(lo); t < Math.ceil(hi); t++)
      if (Math.min(hi, t + 1) - Math.max(lo, t) >= 0.6) tiles.push(t)
    return tiles
  }
  const block = (piece: OfficePiece) => {
    const turned = Math.abs(Math.sin(piece.ry)) > 0.5
    for (const y of span(ty(piece.z), turned ? piece.w : piece.d))
      for (const x of span(tx(piece.x), turned ? piece.d : piece.w))
        if (blocked[y]?.[x] !== undefined) blocked[y][x] = true
  }
  const tableOf = (piece: OfficePiece) =>
    OFFICE_PIECES.filter(
      (item) => item.kind === 'table' && item.zone === piece.zone,
    ).sort(
      (a, b) =>
        Math.hypot(a.x - piece.x, a.z - piece.z) -
        Math.hypot(b.x - piece.x, b.z - piece.z),
    )[0]
  const work: Record<
    'island' | 'center' | 'huddle' | 'war' | 'library' | 'change' | 'lounge',
    Seat[]
  > = {
    island: [],
    center: [],
    huddle: [],
    war: [],
    library: [],
    change: [],
    lounge: [],
  }
  OFFICE_PIECES.forEach((piece, index) => {
    const turned = Math.abs(Math.sin(piece.ry)) > 0.5
    const w = turned ? piece.d : piece.w,
      d = turned ? piece.w : piece.d
    const seat = piece.kind === 'chair' || piece.kind === 'stool'
    const lounge = piece.model === 'loungeChairRelax'
    let facing = facingOf(piece.ry)
    if (piece.kind === 'stool') {
      const table = tableOf(piece)
      facing =
        piece.zone === 5
          ? 'down'
          : table
            ? toward(piece, table.x, table.z)
            : facing
    }
    const kit = {
      model: piece.model,
      ui: piece.ui,
      x: tx(piece.x),
      z: ty(piece.z),
      y: piece.y,
      ry: piece.ry,
      s: piece.s,
      w: piece.w,
      d: piece.d,
      h: piece.h,
      tint: piece.tint,
    }
    // 자리(의자)는 1칸, 작업 자리(모니터·노트북)는 그 자리 의자까지 덮는 칸으로 둔다(작업 화면이 주인을 찾는다).
    const workstation =
      piece.kind === 'monitor' || (piece.kind === 'laptop' && piece.zone === 0)
    const chairZ = workstation
      ? piece.z + (Math.cos(piece.ry) > 0 ? 0.9 : -0.9)
      : piece.z
    const footprint = seat
      ? { x: tx(piece.x) - 0.5, y: ty(piece.z) - 0.5, w: 1, h: 1 }
      : workstation
        ? {
            x: tx(piece.x) - 0.5,
            y: ty(Math.min(piece.z, chairZ)) - 0.5,
            w: 1,
            h: Math.abs(chairZ - piece.z) + 1,
          }
        : { x: tx(piece.x) - w / 2, y: ty(piece.z) - d / 2, w, h: d }
    const solid = SOLID.has(piece.kind) && !onTop(piece)
    furniture.push({
      kind: onTop(piece) && !workstation ? 'kit' : (KIND[piece.kind] ?? 'kit'),
      ...footprint,
      solid,
      facing: seat ? facing : undefined,
      tag: workstation
        ? `workstation/${index}`
        : lounge
          ? 'lounge'
          : `${piece.model ?? piece.ui}/${index}`,
      kit,
    })
    if (solid) block(piece)
    if (!seat) return
    const spot = seatAt(piece.x, piece.z, facing)
    if (lounge) work.lounge.push(spot)
    else if (piece.zone === 4) work.island.push(spot)
    else if (piece.zone === 0) work.center.push(spot)
    else if (piece.zone === 3) work.huddle.push(spot)
    else if (piece.zone === 5) work.war.push(spot)
    else if (piece.zone === 1) work.library.push(spot)
    else if (piece.zone === 2) work.change.push(spot)
  })
  // 의자·스툴이 놓인 칸은 열어 둔다(얇은 테이블이 의자와 같은 줄을 막지 않게).
  for (const seat of Object.values(work).flat())
    blocked[Math.floor(seat.y + 23 / 32)][Math.floor(seat.x + 0.5)] = false
  // 엘리베이터(나의 도시로): 서쪽 벽, 통로 끝. 문은 캔버스처럼 입구 표시에서 1.2 m 북쪽에 있다.
  const doorZ = OFFICE_ENTRANCE.z - 1.2
  const outdoorExit = {
    x: tx(OFFICE_ENTRANCE.x),
    y: ty(doorZ) - 0.8,
    // 벽에 붙은 문틀처럼 얇게(D38)
    w: 0.35,
    h: 1.6,
    landing: {
      x: (tx(OFFICE_ENTRANCE.x) + 1.3) * 32,
      y: (ty(doorZ) - 0.6) * 32,
    },
    label: '엘리베이터 · 나의 도시로',
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
  const bibi = seatAt(OFFICE_BIBI.x, OFFICE_BIBI.z, 'down')
  // 두 아일랜드를 번갈아, 끝 자리(x 작은 쪽 → 큰 쪽) 다음 가운데 자리 순서
  const spread = (a: Seat[], b: Seat[]) => {
    const rank = (list: Seat[], seat: Seat) => {
      const xs = [...new Set(list.map((item) => item.x))].sort((p, q) => p - q)
      const i = xs.indexOf(seat.x)
      return i === 0 ? 0 : i === xs.length - 1 ? 1 : 2
    }
    return [
      ...a.map((seat) => ({ seat, rank: rank(a, seat), side: 0 })),
      ...b.map((seat) => ({ seat, rank: rank(b, seat), side: 1 })),
    ]
      .sort((p, q) => p.rank - q.rank || p.side - q.side || p.seat.y - q.seat.y)
      .map((item) => item.seat)
  }
  return {
    id: 1,
    name: '우리 팀 사무실',
    design: officeDesignOf(design),
    short: 'OFFICE',
    W,
    H,
    grid,
    blocked,
    furniture,
    seats: {
      // 작업 자리 12석(아일랜드 둘), 넘으면 허들·워룸. 팀원은 바깥 자리부터 앉아 옆자리를 에이전트에게 남긴다(D37).
      dev: [...spread(work.island, work.center), ...work.huddle, ...work.war],
      humandesk: [...work.library, ...work.change],
      meeting: work.huddle,
      lead: [bibi],
      lobby: work.lounge,
      board: work.war,
      review: work.war,
      control: work.change,
      hr: work.change,
    },
    points: [
      {
        x: bibi.x,
        y: bibi.y,
        w: 1,
        h: 1,
        act: 'architecture',
        label: '비비에게 말 걸기',
      },
      {
        x: tx(1) - 0.5,
        y: ty(-3.8) - 1.1,
        w: 1,
        h: 2.2,
        act: 'decisions',
        label: '칸반 보드로 작업 현황 보기',
      },
    ],
    signs: [],
    decor: [],
    elevator: [],
    exit: {
      x: Math.floor(outdoorExit.landing.x / 32),
      y: Math.floor(outdoorExit.landing.y / 32),
    },
    outdoorExit,
    footprint: { left: 1, back: 1, right: W - 1, front: H - 3 },
    zoneSigns: OFFICE_ZONES.map((zone) => ({
      n: zone.n,
      name: zone.name,
      x: tx(zone.sign[0]),
      z: ty(zone.sign[1]),
    })),
  }
}
