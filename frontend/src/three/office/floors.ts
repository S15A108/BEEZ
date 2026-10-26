// BEEZ 빌딩: 층별 타일맵. 1타일 = 32px.
// 1F 로비 · 2F 개발팀 · 3F 기획팀 · 4F 보안·검증팀. 층 사이 이동은 엘리베이터.
import type { Zone } from '../types'

export const T = 32
export type Dir = 'down' | 'up' | 'left' | 'right'
// # 벽, g 유리벽, h 복도, E 엘리베이터, s 보도, o 도로, 그 외 구역 바닥
export type Tile =
  | '#'
  | 'g'
  | 'h'
  | 'E'
  | 's'
  | 'o'
  | 'l'
  | 'd'
  | 'm'
  | 'b'
  | 'L'
  | 'r'
  | 'u'
  | 'c'
  | 'k'

export type FurnitureKind =
  | 'table'
  | 'chair'
  | 'desk'
  | 'plant'
  | 'tree'
  | 'sofa'
  | 'shelf'
  | 'counter'
  | 'execdesk'
  | 'rug'
  | 'cooler'
  | 'printer'
  | 'reception'
  | 'rack'
  | 'gate'
  | 'designboard'
  | 'coffee'
  | 'bench'
  | 'console'
  | 'safe'
  | 'requestdesk'
  | 'locker'
  | 'beanbag'
  | 'vending'
  | 'arcade'
  | 'aquarium'
  | 'lamp'
  | 'trophy'
  | 'projector'
  | 'glass-partition'

export interface Furniture {
  kind: FurnitureKind
  x: number
  y: number
  w: number
  h: number
  solid: boolean
  facing?: Dir
  tag?: string
  /** 사람이 꾸미기로 놓은 장식 (치울 수 있음) */
  decorId?: string
}

export interface Seat {
  x: number
  y: number
  facing: Dir
}
export interface OfficeOutdoorExit {
  x: number
  y: number
  w: number
  h: number
  landing: { x: number; y: number }
  label: string
}

export interface WallDecor {
  kind:
    | 'window'
    | 'tv'
    | 'directory'
    | 'kanban'
    | 'testwall'
    | 'logo'
    | 'picture'
    | 'clock'
    | 'videowall'
    | 'requestboard'
    | 'roster'
  x: number
  y: number
  w: number
  tag?: string
}

export interface Floor {
  id: number
  name: string
  short: string
  W: number
  H: number
  grid: Tile[][]
  furniture: Furniture[]
  blocked: boolean[][]
  seats: Partial<Record<Zone, Seat[]>>
  elevator: { x: number; y: number }[] // 엘리베이터 칸
  exit: { x: number; y: number } // 엘리베이터에서 내려 서는 칸
  decor: WallDecor[]
  signs: { label: string; x: number; y: number; color: string; zone?: Zone }[]
  points?: Interactable[]
  /** 바닥 테마 (꾸미기) */
  theme?: string
  /** 정적 레이어(바닥·러그)를 다시 그려야 하는지 판단하는 키 */
  staticKey?: string
  /** Actual indoor footprint; streets and unopened expansion space stay outdoors. */
  footprint?: { left: number; back: number; right: number; front: number }
  outdoorExit?: OfficeOutdoorExit
}

export type Act =
  | 'architecture'
  | 'tasks'
  | 'decisions'
  | 'directory'
  | 'inbox'
  | 'control'
  | 'connections'
  | 'docs'
export interface Interactable {
  x: number
  y: number
  w: number
  h: number
  act: Act
  label: string
}

export const ZONE_OF_TILE: Partial<Record<Tile, Zone>> = {
  m: 'meeting',
  b: 'board',
  l: 'lobby',
  L: 'lead',
  d: 'dev',
  r: 'review',
  u: 'humandesk',
  c: 'control',
  k: 'hr',
}
export const FLOOR_OF_ZONE: Record<Zone, number> = {
  lobby: 1,
  dev: 2,
  meeting: 3,
  board: 3,
  lead: 3,
  review: 4,
  humandesk: 5,
  control: 6,
  hr: 7,
}

const s = (x: number, y: number, facing: Dir): Seat => ({ x, y, facing })

function build(
  id: number,
  name: string,
  short: string,
  W: number,
  H: number,
  paint: (
    fill: (x: number, y: number, w: number, h: number, t: Tile) => void,
  ) => void,
  furniture: Furniture[],
  seats: Floor['seats'],
  decor: WallDecor[],
  signs: Floor['signs'],
): Floor {
  const grid: Tile[][] = Array.from({ length: H }, () =>
    Array<Tile>(W).fill('#'),
  )
  const fill = (x: number, y: number, w: number, h: number, t: Tile) => {
    for (let j = y; j < y + h; j++)
      for (let i = x; i < x + w; i++)
        if (j >= 0 && i >= 0 && j < H && i < W) grid[j][i] = t
  }
  paint(fill)
  // 엘리베이터는 모든 층 같은 위치 (윗벽 가운데)
  fill(14, 1, 2, 1, 'E')
  const blocked = grid.map((row) =>
    row.map((t) => t === '#' || t === 'g' || t === 'o'),
  )
  for (const f of furniture)
    if (f.solid)
      for (let j = f.y; j < f.y + f.h; j++)
        for (let i = f.x; i < f.x + f.w; i++) blocked[j][i] = true
  return {
    id,
    name,
    short,
    W,
    H,
    grid,
    furniture,
    blocked,
    seats,
    elevator: [
      { x: 14, y: 1 },
      { x: 15, y: 1 },
    ],
    exit: { x: 14, y: 3 },
    decor,
    signs,
  }
}

const plant = (x: number, y: number): Furniture => ({
  kind: 'plant',
  x,
  y,
  w: 1,
  h: 1,
  solid: true,
})
const desk = (x: number, y: number, tag?: string): Furniture => ({
  kind: 'desk',
  x,
  y,
  w: 3,
  h: 1,
  solid: true,
  tag,
})

// ───────────────────────── 1F 로비 ─────────────────────────
const F1 = build(
  1,
  '로비',
  '1F',
  30,
  22,
  (fill) => {
    fill(1, 1, 28, 16, 'l')
    fill(12, 17, 6, 1, 'l') // 정문
    fill(0, 18, 30, 2, 's') // 보도
    fill(0, 20, 30, 2, 'o') // 도로
  },
  [
    { kind: 'rug', x: 10, y: 9, w: 10, h: 6, solid: false },
    { kind: 'reception', x: 4, y: 6, w: 6, h: 2, solid: true },
    { kind: 'sofa', x: 20, y: 6, w: 3, h: 1, solid: true },
    { kind: 'sofa', x: 24, y: 6, w: 3, h: 1, solid: true },
    {
      kind: 'table',
      x: 21,
      y: 8,
      w: 5,
      h: 2,
      solid: true,
      tag: 'coffee-table',
    },
    ...[20, 23, 26].map((x) => ({
      kind: 'chair' as const,
      x,
      y: 11,
      w: 1,
      h: 1,
      solid: false,
      facing: 'up' as Dir,
    })),
    { kind: 'gate', x: 12, y: 4, w: 1, h: 1, solid: true },
    { kind: 'gate', x: 17, y: 4, w: 1, h: 1, solid: true },
    plant(1, 1),
    plant(28, 1),
    plant(1, 16),
    plant(28, 16),
    plant(11, 16),
    plant(18, 16),
    { kind: 'cooler', x: 27, y: 10, w: 1, h: 1, solid: true },
    ...[2, 7, 22, 27].map((x) => ({
      kind: 'tree' as const,
      x,
      y: 18,
      w: 1,
      h: 1,
      solid: true,
    })),
    { kind: 'bench', x: 4, y: 18, w: 2, h: 1, solid: true },
    { kind: 'bench', x: 24, y: 18, w: 2, h: 1, solid: true },
  ],
  {
    lobby: [
      s(21, 6, 'down'),
      s(25, 6, 'down'),
      s(23, 11, 'up'),
      s(20, 11, 'up'),
      s(26, 11, 'up'),
      s(7, 8, 'up'),
    ],
  },
  [
    { kind: 'directory', x: 4, y: 0, w: 6 },
    { kind: 'logo', x: 18, y: 0, w: 6 },
    { kind: 'window', x: 2, y: 0, w: 1 },
    { kind: 'window', x: 26, y: 0, w: 1 },
  ],
  [
    { label: '안내 데스크', x: 7, y: 5, color: '#475569', zone: 'lobby' },
    { label: '대기 라운지', x: 23, y: 5, color: '#475569', zone: 'lobby' },
  ],
)

// ───────────────────────── 2F 개발팀 ─────────────────────────
const F2 = build(
  2,
  '개발팀',
  '2F',
  30,
  18,
  (fill) => {
    fill(1, 1, 28, 16, 'd')
    fill(12, 1, 6, 3, 'h')
  },
  [
    ...[
      [3, 6],
      [8, 6],
      [19, 6],
      [24, 6],
      [3, 11],
      [8, 11],
      [19, 11],
      [24, 11],
    ].map(([x, y]) => desk(x, y)),
    { kind: 'printer', x: 1, y: 15, w: 1, h: 1, solid: true },
    { kind: 'coffee', x: 28, y: 15, w: 1, h: 1, solid: true },
    { kind: 'shelf', x: 1, y: 1, w: 3, h: 1, solid: true },
    plant(28, 1),
    plant(11, 1),
    plant(18, 1),
    plant(14, 16),
    { kind: 'rug', x: 12, y: 13, w: 6, h: 3, solid: false },
    { kind: 'bench', x: 13, y: 14, w: 4, h: 1, solid: true },
  ],
  {
    dev: [
      s(4, 7, 'up'),
      s(9, 7, 'up'),
      s(20, 7, 'up'),
      s(25, 7, 'up'),
      s(4, 12, 'up'),
      s(9, 12, 'up'),
      s(20, 12, 'up'),
      s(25, 12, 'up'),
    ],
  },
  [
    { kind: 'kanban', x: 4, y: 0, w: 7 },
    { kind: 'window', x: 20, y: 0, w: 1 },
    { kind: 'window', x: 23, y: 0, w: 1 },
    { kind: 'window', x: 26, y: 0, w: 1 },
    { kind: 'clock', x: 18, y: 0, w: 1 },
  ],
  [{ label: '개발팀 · 작업 보드', x: 7, y: 2, color: '#15803d', zone: 'dev' }],
)

// ───────────────────────── 3F 기획팀 ─────────────────────────
const F3 = build(
  3,
  '기획팀',
  '3F',
  30,
  18,
  (fill) => {
    fill(1, 1, 12, 8, 'm') // 회의실
    fill(13, 1, 1, 9, 'g') // 회의실 유리벽
    fill(1, 9, 12, 1, 'g')
    fill(6, 9, 2, 1, 'm') // 회의실 문
    fill(14, 1, 2, 10, 'h') // 엘리베이터 홀
    fill(16, 1, 1, 9, '#')
    fill(17, 1, 12, 8, 'L') // 팀장실
    fill(17, 9, 12, 1, '#')
    fill(21, 9, 2, 1, 'L') // 팀장실 문
    fill(1, 10, 28, 2, 'h') // 복도
    fill(1, 12, 28, 5, 'b') // 설계 보드 구역
  },
  [
    { kind: 'rug', x: 2, y: 2, w: 10, h: 6, solid: false },
    { kind: 'table', x: 3, y: 3, w: 7, h: 3, solid: true },
    ...[4, 6, 8].map((x) => ({
      kind: 'chair' as const,
      x,
      y: 2,
      w: 1,
      h: 1,
      solid: false,
      facing: 'down' as Dir,
    })),
    ...[4, 6, 8].map((x) => ({
      kind: 'chair' as const,
      x,
      y: 6,
      w: 1,
      h: 1,
      solid: false,
      facing: 'up' as Dir,
    })),
    plant(1, 1),
    plant(12, 1),
    { kind: 'rug', x: 19, y: 2, w: 8, h: 5, solid: false },
    { kind: 'shelf', x: 17, y: 1, w: 3, h: 1, solid: true },
    { kind: 'execdesk', x: 21, y: 3, w: 4, h: 1, solid: true },
    { kind: 'chair', x: 21, y: 5, w: 1, h: 1, solid: false, facing: 'up' },
    { kind: 'chair', x: 24, y: 5, w: 1, h: 1, solid: false, facing: 'up' },
    plant(28, 1),
    plant(28, 8),
    { kind: 'designboard', x: 8, y: 12, w: 8, h: 1, solid: true },
    desk(3, 13, 'planner'),
    desk(20, 13),
    desk(24, 13),
    plant(1, 16),
    plant(28, 16),
  ],
  {
    meeting: [
      s(4, 2, 'down'),
      s(8, 6, 'up'),
      s(8, 2, 'down'),
      s(4, 6, 'up'),
      s(6, 2, 'down'),
      s(6, 6, 'up'),
      s(2, 4, 'right'),
      s(10, 4, 'left'),
    ],
    lead: [
      s(23, 2, 'down'),
      s(21, 5, 'up'),
      s(24, 5, 'up'),
      s(22, 6, 'up'),
      s(25, 6, 'up'),
    ],
    board: [s(11, 14, 'up'), s(4, 14, 'up'), s(13, 14, 'up'), s(21, 14, 'up')],
  },
  [
    { kind: 'tv', x: 5, y: 0, w: 3 },
    { kind: 'window', x: 2, y: 0, w: 1 },
    { kind: 'window', x: 10, y: 0, w: 1 },
    { kind: 'window', x: 19, y: 0, w: 1 },
    { kind: 'picture', x: 25, y: 0, w: 2 },
  ],
  [
    { label: '회의실', x: 7, y: 10, color: '#1d4ed8', zone: 'meeting' },
    { label: '팀장실', x: 22, y: 10, color: '#c2410c', zone: 'lead' },
    { label: '설계 보드', x: 12, y: 16, color: '#a16207', zone: 'board' },
  ],
)

// ───────────────────────── 4F 보안·검증팀 ─────────────────────────
const F4 = build(
  4,
  '보안·검증팀',
  '4F',
  30,
  18,
  (fill) => {
    fill(1, 1, 28, 16, 'r')
    fill(12, 1, 6, 4, 'h')
  },
  [
    { kind: 'gate', x: 12, y: 4, w: 1, h: 1, solid: true },
    { kind: 'gate', x: 17, y: 4, w: 1, h: 1, solid: true },
    { kind: 'counter', x: 9, y: 9, w: 12, h: 1, solid: true },
    ...[2, 4].map((y) => ({
      kind: 'rack' as const,
      x: 25,
      y,
      w: 3,
      h: 1,
      solid: true,
    })),
    ...[2, 4].map((y) => ({
      kind: 'rack' as const,
      x: 2,
      y,
      w: 3,
      h: 1,
      solid: true,
    })),
    desk(3, 13, 'lab'),
    desk(23, 13, 'lab'),
    { kind: 'shelf', x: 1, y: 7, w: 2, h: 1, solid: true },
    plant(28, 16),
    plant(1, 16),
    { kind: 'sofa', x: 13, y: 15, w: 4, h: 1, solid: true },
  ],
  {
    review: [
      s(15, 8, 'down'),
      s(12, 11, 'up'),
      s(18, 11, 'up'),
      s(15, 11, 'up'),
      s(10, 12, 'up'),
      s(20, 12, 'up'),
    ],
  },
  [
    { kind: 'testwall', x: 5, y: 0, w: 6 },
    { kind: 'window', x: 20, y: 0, w: 1 },
    { kind: 'window', x: 23, y: 0, w: 1 },
  ],
  [
    { label: '검토대', x: 15, y: 6, color: '#6d28d9', zone: 'review' },
    { label: '테스트 랩', x: 4, y: 11, color: '#6d28d9', zone: 'review' },
  ],
)

F1.points = [
  {
    x: 4,
    y: 6,
    w: 6,
    h: 2,
    act: 'architecture',
    label: '안내 로봇에게 말 걸기',
  },
  { x: 4, y: 1, w: 6, h: 1, act: 'directory', label: '층별 안내' },
]
F2.points = [{ x: 4, y: 1, w: 7, h: 1, act: 'tasks', label: '작업 보드 보기' }]
F3.points = [
  { x: 8, y: 12, w: 8, h: 1, act: 'decisions', label: '설계 보드 보기' },
  { x: 17, y: 1, w: 3, h: 1, act: 'docs', label: '📚 공용 문서함 열기' },
]
F4.points = [
  { x: 2, y: 2, w: 3, h: 3, act: 'docs', label: 'ERD 문서 보기' },
  { x: 25, y: 2, w: 3, h: 3, act: 'docs', label: 'ERD 문서 보기' },
  { x: 5, y: 1, w: 6, h: 1, act: 'tasks', label: '작업 검증 보기' },
]

// ───────────────────────── 5F 사람 작업층 ─────────────────────────
const F5 = build(
  5,
  '사람 작업층',
  '5F',
  30,
  18,
  (fill) => {
    fill(1, 1, 28, 16, 'u')
    fill(12, 1, 6, 3, 'h')
  },
  [
    { kind: 'rug', x: 9, y: 4, w: 12, h: 3, solid: false },
    { kind: 'requestdesk', x: 9, y: 8, w: 12, h: 1, solid: true },
    desk(3, 12, 'human'),
    desk(8, 12, 'human'),
    desk(19, 12, 'human'),
    desk(24, 12, 'human'),
    { kind: 'safe', x: 26, y: 2, w: 2, h: 2, solid: true },
    { kind: 'shelf', x: 1, y: 1, w: 3, h: 1, solid: true },
    { kind: 'sofa', x: 13, y: 15, w: 4, h: 1, solid: true },
    { kind: 'coffee', x: 28, y: 15, w: 1, h: 1, solid: true },
    plant(11, 1),
    plant(18, 1),
    plant(1, 16),
    plant(28, 8),
  ],
  {
    humandesk: [
      s(11, 7, 'down'),
      s(18, 7, 'down'),
      s(14, 7, 'down'),
      s(16, 7, 'down'),
      s(12, 6, 'down'),
      s(17, 6, 'down'),
    ],
  },
  [
    { kind: 'requestboard', x: 4, y: 0, w: 7 },
    { kind: 'window', x: 20, y: 0, w: 1 },
    { kind: 'window', x: 23, y: 0, w: 1 },
  ],
  [
    {
      label: '사람 작업 요청함',
      x: 14,
      y: 9,
      color: '#be123c',
      zone: 'humandesk',
    },
    {
      label: '비밀 저장소 (값은 여기에만)',
      x: 26,
      y: 4,
      color: '#334155',
      zone: 'humandesk',
    },
  ],
)

// ───────────────────────── 6F 관제실 ─────────────────────────
const F6 = build(
  6,
  '대시보드',
  '6F',
  30,
  18,
  (fill) => {
    fill(1, 1, 28, 16, 'c')
    fill(12, 1, 6, 3, 'h')
  },
  [
    { kind: 'console', x: 4, y: 7, w: 8, h: 1, solid: true },
    { kind: 'console', x: 18, y: 7, w: 8, h: 1, solid: true },
    { kind: 'console', x: 4, y: 11, w: 8, h: 1, solid: true },
    { kind: 'console', x: 18, y: 11, w: 8, h: 1, solid: true },
    ...[2, 6, 22, 26].map((x) => ({
      kind: 'rack' as const,
      x,
      y: 15,
      w: 3,
      h: 1,
      solid: true,
    })),
    plant(11, 1),
    plant(18, 1),
  ],
  {
    control: [
      s(7, 8, 'up'),
      s(21, 8, 'up'),
      s(10, 8, 'up'),
      s(24, 8, 'up'),
      s(7, 12, 'up'),
      s(21, 12, 'up'),
    ],
  },
  [
    { kind: 'videowall', x: 1, y: 0, w: 11, tag: 'left' },
    { kind: 'videowall', x: 18, y: 0, w: 11, tag: 'right' },
  ],
  [
    {
      label: '관제 화면 · 에이전트/작업',
      x: 6,
      y: 2,
      color: '#0e7490',
      zone: 'control',
    },
    {
      label: '관제 화면 · 테스트/모델/이벤트',
      x: 23,
      y: 2,
      color: '#0e7490',
      zone: 'control',
    },
  ],
)

F5.points = [
  { x: 9, y: 8, w: 12, h: 1, act: 'inbox', label: '알림함 열기' },
  { x: 26, y: 2, w: 2, h: 2, act: 'inbox', label: '설계 변경 알림 보기' },
  { x: 4, y: 1, w: 7, h: 1, act: 'inbox', label: '알림함 보기' },
]
F6.points = [
  { x: 1, y: 1, w: 11, h: 1, act: 'control', label: '대시보드 열기' },
  { x: 18, y: 1, w: 11, h: 1, act: 'control', label: '대시보드 열기' },
  { x: 4, y: 7, w: 8, h: 5, act: 'control', label: '대시보드 열기' },
  { x: 18, y: 7, w: 8, h: 5, act: 'control', label: '대시보드 열기' },
]

// ───────────────────────── 7F 인사팀 ─────────────────────────
const F7 = build(
  7,
  '에이전트 연결',
  '7F',
  30,
  18,
  (fill) => {
    fill(1, 1, 28, 16, 'k')
    fill(12, 1, 6, 3, 'h')
    fill(20, 9, 1, 8, 'g') // 면접실 유리벽
    fill(20, 12, 1, 2, 'k') // 면접실 문
  },
  [
    { kind: 'reception', x: 4, y: 6, w: 7, h: 2, solid: true, tag: 'hr' },
    { kind: 'rug', x: 22, y: 10, w: 6, h: 6, solid: false },
    { kind: 'table', x: 23, y: 12, w: 4, h: 2, solid: true },
    { kind: 'locker', x: 25, y: 2, w: 4, h: 1, solid: true },
    { kind: 'shelf', x: 1, y: 1, w: 3, h: 1, solid: true },
    desk(4, 12, 'hr'),
    desk(10, 12, 'hr'),
    { kind: 'sofa', x: 14, y: 15, w: 4, h: 1, solid: true },
    plant(11, 1),
    plant(18, 1),
    plant(1, 16),
    plant(19, 8),
  ],
  {
    hr: [
      s(7, 5, 'down'),
      s(5, 13, 'up'),
      s(11, 13, 'up'),
      s(24, 11, 'down'),
      s(25, 14, 'up'),
      s(9, 5, 'down'),
    ],
  },
  [
    { kind: 'roster', x: 4, y: 0, w: 7 },
    { kind: 'window', x: 20, y: 0, w: 1 },
    { kind: 'window', x: 22, y: 0, w: 1 },
  ],
  [
    { label: '에이전트 연결 데스크', x: 7, y: 9, color: '#db2777', zone: 'hr' },
    { label: '면접실', x: 24, y: 9, color: '#9d174d', zone: 'hr' },
  ],
)
F7.points = [
  { x: 4, y: 6, w: 7, h: 2, act: 'connections', label: '에이전트 연결' },
  {
    x: 4,
    y: 1,
    w: 7,
    h: 1,
    act: 'connections',
    label: '에이전트 연결 상태 보기',
  },
]

export const FLOORS: Record<number, Floor> = {
  1: F1,
  2: F2,
  3: F3,
  4: F4,
  5: F5,
  6: F6,
  7: F7,
}
export const FLOOR_IDS = [7, 6, 5, 4, 3, 2, 1]

export function zoneAt(f: Floor, tx: number, ty: number): Zone | null {
  if (tx < 0 || ty < 0 || tx >= f.W || ty >= f.H) return null
  return ZONE_OF_TILE[f.grid[ty][tx]] ?? null
}

export const SPAWN_OUTSIDE = { floor: 1, x: 14, y: 19 }
export const LOBBY_ENTRY = { floor: 1, x: 14, y: 14 }
