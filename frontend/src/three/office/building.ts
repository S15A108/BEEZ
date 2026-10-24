// 외관의 층수와 분리된 단층 실내: 프로젝트에 있는 팀만 작은 공간으로 배치한다.
import { createContext, useContext } from 'react'
import type { Interior, Zone } from '../types'
import type { Floor, Furniture, FurnitureKind } from './floors'
import { makePocketOffice } from './pocketOffice'
import type { OfficePopulation } from './office-layout'

export type FloorKey =
  'lobby' | 'dev' | 'planning' | 'security' | 'human' | 'ops' | 'hr'
export const ALL_KEYS: FloorKey[] = [
  'lobby',
  'dev',
  'planning',
  'security',
  'human',
  'ops',
  'hr',
]

export const ZONE_KEY: Record<Zone, FloorKey> = {
  lobby: 'lobby',
  dev: 'dev',
  meeting: 'planning',
  board: 'planning',
  lead: 'planning',
  review: 'security',
  humandesk: 'human',
  control: 'ops',
  hr: 'hr',
}
export const FLOOR_LABEL: Record<FloorKey, string> = {
  lobby: '라운지',
  dev: '개발팀',
  planning: '기획팀',
  security: '보안·검증팀',
  human: '사람 데스크',
  ops: '관제실',
  hr: '인사팀',
}

export interface Building {
  layout: 'pocket'
  rooms: Zone[]
  keys: FloorKey[]
  design: string
  floors: Record<number, Floor & { key: FloorKey }>
  /** 실내 공간 ID (도시의 외관 층수와 별개) */
  ids: number[]
  floorOfZone: (z: Zone) => number
  hasZone: (z: Zone) => boolean
  floorOfKey: (k: FloorKey) => number | null
}

export function makeBuilding(
  keys: string[] | null | undefined,
  design = 'glass',
  interior?: Interior | null,
  developerCount?: number | OfficePopulation,
): Building {
  const order = ALL_KEYS.filter(
    (k) => k === 'lobby' || (keys ?? ALL_KEYS).includes(k),
  )
  const floor = withPocketInterior(
    makePocketOffice(order, developerCount),
    interior,
  )
  const floors: Building['floors'] = { 1: { ...floor, key: 'lobby' } }
  const floorOfKey = (k: FloorKey) => (order.includes(k) ? 1 : null)
  return {
    layout: 'pocket',
    rooms: (Object.keys(ZONE_KEY) as Zone[]).filter((zone) =>
      order.includes(ZONE_KEY[zone]),
    ),
    keys: order,
    design,
    floors,
    ids: [1],
    floorOfZone: () => 1,
    hasZone: (z) => floorOfKey(ZONE_KEY[z]) !== null,
    floorOfKey,
  }
}

/** Keep saved decoration records; reflow legacy per-floor coordinates into free shared space. */
function withPocketInterior(base: Floor, interior?: Interior | null): Floor {
  const floor = {
    ...base,
    theme: interior?.themes?.lobby ?? 'default',
    blocked: base.blocked.map((row) => [...row]),
    furniture: [...base.furniture],
  }
  for (const item of interior?.decor ?? []) {
    const spec = DECOR_SPEC[item.kind]
    if (!spec) continue
    const options = [{ x: item.x, y: item.y }]
    for (let y = 1; y < floor.H - 1; y++)
      for (let x = 1; x < floor.W - 1; x++) options.push({ x, y })
    const at = options.find(
      (p) => !placeProblem(floor, item.kind, p.x, p.y, new Set()),
    )
    if (!at) continue
    const furniture: Furniture = {
      kind: item.kind as FurnitureKind,
      ...at,
      w: spec.w,
      h: spec.h,
      solid: spec.solid,
      decorId: item.id,
      tag: 'decor',
    }
    floor.furniture.push(furniture)
    if (spec.solid)
      for (let y = at.y; y < at.y + spec.h; y++)
        for (let x = at.x; x < at.x + spec.w; x++) floor.blocked[y][x] = true
  }
  return {
    ...floor,
    staticKey: `pocket-v1|${floor.theme}|${JSON.stringify(floor.furniture)}`,
  }
}

export const BuildingContext = createContext<Building>(makeBuilding(ALL_KEYS))
export const useBuilding = () => useContext(BuildingContext)

// ───────────────────────── 꾸미기 (장식·바닥 테마) ─────────────────────────
export const DECOR_SPEC: Record<
  string,
  { label: string; icon: string; w: number; h: number; solid: boolean }
> = {
  plant: { label: '난 화분', icon: '🪴', w: 1, h: 1, solid: true },
  tree: { label: '실내 나무', icon: '🌳', w: 1, h: 1, solid: true },
  sofa: { label: '소파', icon: '🛋️', w: 2, h: 1, solid: true },
  beanbag: { label: '빈백', icon: '🫘', w: 1, h: 1, solid: false },
  rug: { label: '러그', icon: '🟫', w: 3, h: 2, solid: false },
  shelf: { label: '책장', icon: '📚', w: 2, h: 1, solid: true },
  bench: { label: '벤치', icon: '🪑', w: 2, h: 1, solid: true },
  cooler: { label: '정수기', icon: '🚰', w: 1, h: 1, solid: true },
  coffee: { label: '커피머신', icon: '☕', w: 1, h: 1, solid: true },
  vending: { label: '자판기', icon: '🥤', w: 1, h: 1, solid: true },
  arcade: { label: '오락기', icon: '🕹️', w: 1, h: 1, solid: true },
  aquarium: { label: '수족관', icon: '🐠', w: 2, h: 1, solid: true },
  lamp: { label: '스탠드 조명', icon: '💡', w: 1, h: 1, solid: true },
  trophy: { label: '트로피 진열장', icon: '🏆', w: 1, h: 1, solid: true },
}
export const THEME_LABEL: Record<string, string> = {
  default: '기본',
  wood: '원목 마루',
  carpet: '네이비 카펫',
  marble: '대리석',
  mint: '민트 타일',
  night: '다크 라운지',
}

const PLACEABLE = new Set(['l', 'd', 'm', 'b', 'L', 'r', 'u', 'c', 'k'])
/** 장식을 놓을 수 있는지: 바닥 위 · 다른 가구와 안 겹침 · 좌석·엘리베이터 앞 아님 · 사람이 서 있지 않음 · 길을 끊지 않음. 안 되면 이유를 돌려준다 */
export function placeProblem(
  f: Floor,
  kind: string,
  x: number,
  y: number,
  occupied: Set<string>,
): string | null {
  const s = DECOR_SPEC[kind]
  if (!s) return '알 수 없는 장식'
  if (x < 1 || y < 1 || x + s.w > f.W - 1 || y + s.h > f.H - 1) return '바닥 밖'
  const seats = new Set(
    Object.values(f.seats).flatMap((list) =>
      (list ?? []).map((p) => p.x + ',' + p.y),
    ),
  )
  const cells: [number, number][] = []
  for (let j = y; j < y + s.h; j++)
    for (let i = x; i < x + s.w; i++) cells.push([i, j])
  for (const [i, j] of cells) {
    if (!PLACEABLE.has(f.grid[j][i])) return '통로나 벽에는 놓을 수 없어요'
    if (f.blocked[j][i]) return '다른 가구가 있어요'
    if (seats.has(i + ',' + j)) return '직원 자리예요'
    if (i === f.exit.x && j === f.exit.y) return '출입구는 비워 둬야 해요'
    if (
      f.outdoorExit &&
      i + 1 > f.outdoorExit.x - 0.2 &&
      i < f.outdoorExit.x + f.outdoorExit.w + 0.2 &&
      j >= Math.floor(f.outdoorExit.landing.y / 32) - 1 &&
      j <= Math.floor(f.outdoorExit.landing.y / 32)
    )
      return '엘리베이터 앞은 비워 둬야 해요'
    if (f.elevator.length && j <= 4 && i >= 12 && i <= 17)
      return '엘리베이터 앞은 비워 둬야 해요'
    if (!f.elevator.length && j >= 16 && i >= 12 && i <= 17)
      return '출입구 앞은 비워 둬야 해요'
    if (occupied.has(i + ',' + j)) return '사람이 서 있어요'
  }
  if (
    f.furniture.some(
      (fu) =>
        x < fu.x + fu.w && fu.x < x + s.w && y < fu.y + fu.h && fu.y < y + s.h,
    )
  )
    return '다른 가구와 겹쳐요'
  if (!s.solid) return null
  // 길을 끊는지: 엘리베이터 앞에서 갈 수 있던 칸이 놓은 뒤에도 모두 갈 수 있어야 한다
  const reach = (blk: boolean[][]) => {
    const seen = new Set<string>()
    const q: [number, number][] = [[f.exit.x, f.exit.y]]
    seen.add(f.exit.x + ',' + f.exit.y)
    while (q.length) {
      const [cx, cy] = q.pop()!
      for (const [dx, dy] of [
        [1, 0],
        [-1, 0],
        [0, 1],
        [0, -1],
      ]) {
        const nx = cx + dx,
          ny = cy + dy,
          k = nx + ',' + ny
        if (
          nx < 0 ||
          ny < 0 ||
          nx >= f.W ||
          ny >= f.H ||
          blk[ny][nx] ||
          seen.has(k)
        )
          continue
        seen.add(k)
        q.push([nx, ny])
      }
    }
    return seen
  }
  const before = reach(f.blocked)
  const after = f.blocked.map((row) => row.slice())
  for (const [i, j] of cells) after[j][i] = true
  const now = reach(after)
  for (const k of before)
    if (!now.has(k) && !cells.some(([i, j]) => i + ',' + j === k))
      return '길이 막혀요'
  return null
}
