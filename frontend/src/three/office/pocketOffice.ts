import type { Zone } from '../types'
import type { Floor, Furniture, Seat, Tile } from './floors'
import { makeTeamOffice, type OfficePopulation } from './office-layout'

/** 본관 개발석 6개를 넘으면 오른쪽 별관이 2석(한 줄)씩 열린다. 별관 최대 석수. */
export const ANNEX_MAX = 10
export const ANNEX_X = 30

/** A shared, compact interior; the city's building height remains project metadata. */
export function makePocketOffice(
  keys: readonly string[],
  developerCount?: number | OfficePopulation,
): Floor {
  if (typeof developerCount === 'object')
    return makeTeamOffice(keys, developerCount)
  const count =
    developerCount === undefined
      ? 6
      : Number.isFinite(developerCount)
        ? Math.max(0, Math.ceil(developerCount))
        : 0
  // 개발자가 늘면 사무실 오른쪽 바깥 잔디가 별관 바닥으로 바뀌고, 줄면 다시 잔디로 돌아간다
  const annex =
    keys.includes('dev') && developerCount !== undefined
      ? Math.min(ANNEX_MAX, Math.max(0, count - 6))
      : 0
  const W = annex ? ANNEX_X + 11 : 30,
    H = 22
  const grid: Tile[][] = Array.from({ length: H }, () =>
    Array<Tile>(W).fill('#'),
  )
  const fill = (x: number, y: number, w: number, h: number, tile: Tile) => {
    for (let j = y; j < y + h; j++)
      for (let i = x; i < x + w; i++) grid[j][i] = tile
  }
  fill(1, 1, 28, 16, 'h')
  fill(12, 17, 6, 1, 'l')
  fill(0, 18, W, 2, 's')
  fill(0, 20, W, 2, 'o')
  const furniture: Furniture[] = []
  const seats: Floor['seats'] = {}
  const signs: Floor['signs'] = []
  const points: NonNullable<Floor['points']> = []
  const seat = (x: number, y: number, facing: Seat['facing'] = 'up'): Seat => ({
    x,
    y,
    facing,
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
  const chair = (
    x: number,
    y: number,
    facing: Seat['facing'] = 'up',
  ): Furniture => ({ kind: 'chair', x, y, w: 1, h: 1, solid: false, facing })
  const sign = (label: string, zone: Zone, x: number, y: number) =>
    signs.push({ label, zone, x, y, color: '#5d5950' })

  // Welcome nook and a shared entrance, in the same position as existing arrivals.
  fill(1, 11, 8, 6, 'l')
  furniture.push(
    { kind: 'reception', x: 3, y: 12, w: 3, h: 1, solid: true },
    { kind: 'sofa', x: 2, y: 15, w: 3, h: 1, solid: true },
    {
      kind: 'table',
      x: 6,
      y: 15,
      w: 2,
      h: 1,
      solid: true,
      tag: 'coffee-table',
    },
    chair(4, 14),
    chair(7, 13),
  )
  seats.lobby = [seat(4, 14), seat(7, 13), seat(3, 15, 'down')]
  sign('라운지 · 안내', 'lobby', 5, 16.5)
  points.push({
    x: 3,
    y: 12,
    w: 3,
    h: 1,
    act: 'architecture',
    label: '비비에게 말 걸기',
  })

  if (keys.includes('planning')) {
    fill(1, 1, 8, 6, 'm')
    fill(1, 8, 8, 3, 'b')
    fill(22, 1, 7, 6, 'L')
    fill(9, 1, 1, 5, '#')
    fill(21, 1, 1, 5, '#')
    fill(9, 8, 1, 2, '#')
    furniture.push(
      { kind: 'rug', x: 2, y: 2, w: 6, h: 4, solid: false },
      { kind: 'table', x: 3, y: 3, w: 4, h: 2, solid: true },
      chair(3, 2, 'down'),
      chair(5, 2, 'down'),
      chair(3, 5),
      chair(5, 5),
      chair(2, 3, 'right'),
      chair(7, 3, 'left'),
      { kind: 'execdesk', x: 23, y: 3, w: 4, h: 1, solid: true },
      chair(25, 2, 'down'),
      chair(24, 5),
      chair(27, 5),
      { kind: 'shelf', x: 22, y: 1, w: 2, h: 1, solid: true },
      { kind: 'designboard', x: 2, y: 8, w: 6, h: 1, solid: true },
    )
    seats.meeting = [
      seat(3, 2, 'down'),
      seat(5, 5),
      seat(5, 2, 'down'),
      seat(3, 5),
      seat(2, 3, 'right'),
      seat(7, 3, 'left'),
    ]
    seats.lead = [seat(25, 2, 'down'), seat(24, 5), seat(27, 5)]
    seats.board = [seat(4, 9), seat(6, 10), seat(2, 10)]
    sign('회의실', 'meeting', 5, 1.5)
    sign('팀장실', 'lead', 25, 1.5)
    sign('설계 · 문서', 'board', 5, 10.5)
    points.push(
      { x: 2, y: 8, w: 6, h: 1, act: 'decisions', label: '설계 보드 보기' },
      { x: 22, y: 1, w: 2, h: 1, act: 'docs', label: '공용 문서함 열기' },
    )
  }
  if (keys.includes('dev')) {
    fill(11, 1, 10, 10, 'd')
    // Keep the legacy six-seat order unless live headcount is explicitly supplied.
    // A growing team fills the front row first, then opens the two rows behind it.
    const capacity = Math.min(6, Math.max(2, Math.ceil(count / 2) * 2))
    const positions =
      developerCount === undefined
        ? [
            [11, 2],
            [16, 2],
            [11, 5],
            [16, 5],
            [11, 8],
            [16, 8],
          ]
        : [
            [11, 8],
            [16, 8],
            [11, 5],
            [16, 5],
            [11, 2],
            [16, 2],
          ].slice(0, capacity)
    furniture.push(...positions.map(([x, y]) => desk(x, y)), {
      kind: 'printer',
      x: 19,
      y: 9,
      w: 1,
      h: 1,
      solid: true,
    })
    seats.dev = positions.map(([x, y]) => seat(x + 1, y + 1))
    sign(
      developerCount === undefined ? '개발팀' : `개발팀 · ${capacity}석`,
      'dev',
      15,
      1,
    )
    points.push({
      x: 11,
      y: 1,
      w: 8,
      h: 1,
      act: 'tasks',
      label: '작업 보드 보기',
    })
    if (annex) {
      // 별관: 아직 열리지 않은 칸은 바깥 잔디('o'), 열린 줄은 개발팀 바닥('d')
      const rows = Math.ceil(annex / 2)
      fill(ANNEX_X, 1, 10, 16, 'o')
      fill(ANNEX_X, 1, 10, rows * 3, 'd')
      fill(ANNEX_X - 1, 1, 1, rows * 3, 'd') // 본관과 이어지는 통로
      const annexDesks = Array.from({ length: rows }, (_, r) => [
        [ANNEX_X + 1, 1 + r * 3],
        [ANNEX_X + 6, 1 + r * 3],
      ])
        .flat()
        .slice(0, annex)
      furniture.push(...annexDesks.map(([x, y]) => desk(x, y, 'annex')))
      seats.dev = [
        ...(seats.dev ?? []),
        ...annexDesks.map(([x, y]) => seat(x + 1, y + 1)),
      ]
      sign(
        `개발팀 별관 · ${annex}석${count > 6 + ANNEX_MAX ? ' (만석 · 공유)' : ''}`,
        'dev',
        ANNEX_X + 5,
        1,
      )
    }
  }
  if (keys.includes('security')) {
    fill(22, 8, 7, 3, 'r')
    fill(21, 8, 1, 2, '#')
    furniture.push(
      { kind: 'console', x: 23, y: 8, w: 4, h: 1, solid: true },
      chair(24, 9),
      chair(26, 9),
      { kind: 'safe', x: 28, y: 8, w: 1, h: 1, solid: true },
    )
    seats.review = [seat(24, 9), seat(26, 9), seat(23, 10)]
    sign('리뷰 · 테스트', 'review', 25, 10.5)
    points.push(
      { x: 23, y: 8, w: 4, h: 2, act: 'tasks', label: '작업 검증 보기' },
      { x: 28, y: 8, w: 1, h: 1, act: 'docs', label: 'ERD 문서 보기' },
    )
  }
  if (keys.includes('human')) {
    fill(10, 13, 4, 4, 'u')
    furniture.push(desk(10, 13, 'human'))
    seats.humandesk = [seat(11, 14), seat(12, 15)]
    sign('사람 데스크', 'humandesk', 11, 16.5)
    points.push({
      x: 10,
      y: 13,
      w: 3,
      h: 2,
      act: 'inbox',
      label: '알림함 열기',
    })
  }
  if (keys.includes('hr')) {
    fill(17, 13, 4, 4, 'k')
    furniture.push(desk(18, 13, 'hr'))
    seats.hr = [seat(19, 14), seat(18, 15)]
    sign('에이전트 연결', 'hr', 19, 16.5)
    points.push({
      x: 18,
      y: 13,
      w: 3,
      h: 2,
      act: 'connections',
      label: '에이전트 연결 열기',
    })
  }
  if (keys.includes('ops')) {
    fill(22, 12, 7, 5, 'c')
    fill(21, 13, 1, 3, '#')
    furniture.push(
      { kind: 'console', x: 23, y: 12, w: 4, h: 1, solid: true },
      chair(24, 13),
      chair(26, 13),
      { kind: 'rack', x: 28, y: 14, w: 1, h: 2, solid: true },
    )
    seats.control = [seat(24, 13), seat(26, 13), seat(23, 15)]
    sign('대시보드', 'control', 25, 16.5)
    points.push({
      x: 23,
      y: 12,
      w: 4,
      h: 3,
      act: 'control',
      label: '대시보드 열기',
    })
  }
  for (const [x, y] of [
    [1, 1],
    [28, 11],
    [18, 16],
  ])
    furniture.push({ kind: 'plant', x, y, w: 1, h: 1, solid: true })
  furniture.push({ kind: 'cooler', x: 1, y: 12, w: 1, h: 1, solid: true })
  // A single cutaway office: welcome at the back left, shared meeting space in
  // the middle, developers at the front, and support rooms along the right.
  // Move the floor, furniture, seats and interaction targets together.
  const rooms = [
    { x: 1, y: 11, w: 8, h: 6, nx: 1, ny: 1 },
    { x: 1, y: 1, w: 8, h: 6, nx: 11, ny: 7 },
    { x: 1, y: 8, w: 8, h: 3, nx: 21, ny: 1 },
    { x: 22, y: 1, w: 7, h: 6, nx: 12, ny: 1 },
    { x: 11, y: 1, w: 10, h: 10, nx: 1, ny: 7 },
    { x: 22, y: 8, w: 7, h: 3, nx: 22, ny: 13 },
    { x: 10, y: 13, w: 4, h: 4, nx: 12, ny: 13 },
    { x: 17, y: 13, w: 4, h: 4, nx: 22, ny: 9 },
    { x: 22, y: 12, w: 7, h: 5, nx: 21, ny: 4 },
  ]
  const original = grid.map((row) => [...row])
  fill(1, 1, 28, 16, 'h')
  for (const room of rooms)
    for (let y = 0; y < room.h; y++)
      for (let x = 0; x < room.w; x++)
        grid[room.ny + y][room.nx + x] = original[room.y + y][room.x + x]
  const move = (item: { x: number; y: number }) => {
    const room = rooms.find(
      (r) =>
        item.x >= r.x &&
        item.x < r.x + r.w &&
        item.y >= r.y &&
        item.y < r.y + r.h,
    )
    if (room) {
      item.x += room.nx - room.x
      item.y += room.ny - room.y
    }
  }
  furniture.forEach(move)
  signs.forEach(move)
  points.forEach(move)
  Object.values(seats).forEach((list) => list?.forEach(move))
  // Keep the original public entrance and the annex connection clear.
  if (annex) fill(29, 1, 1, Math.ceil(annex / 2) * 3, 'h')
  const blocked = grid.map((row) =>
    row.map((tile) => tile === '#' || tile === 'g' || tile === 'o'),
  )
  for (const f of furniture)
    if (f.solid)
      for (let y = f.y; y < f.y + f.h; y++)
        for (let x = f.x; x < f.x + f.w; x++) blocked[y][x] = true
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
    signs,
    points,
    elevator: [],
    exit: { x: 14, y: 14 },
    decor: [
      { kind: 'logo', x: 1, y: 0, w: 3 },
      { kind: 'window', x: 8, y: 0, w: 1 },
      ...(keys.includes('dev')
        ? [{ kind: 'kanban' as const, x: 11, y: 0, w: 8 }]
        : []),
      { kind: 'window', x: 26, y: 0, w: 1 },
    ],
  }
}
