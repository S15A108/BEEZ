import type { Floor } from './floors'

export function makeDreamFloor(base: Floor, phase: number): Floor {
  const bounds = base.footprint ?? {
    left: 1,
    back: 1,
    right: base.W - 1,
    front: 17,
  }
  const visible = (x: number, y: number) =>
    phase >= 4
      ? x >= bounds.left &&
        x < bounds.right &&
        y >= bounds.back &&
        y < bounds.front
      : phase >= 3
        ? x >= 1 && x <= 20 && y >= 1 && y <= 16
        : phase >= 2
          ? x >= 1 && x <= 10 && y >= 1 && y <= 16
          : phase >= 1 && x >= 1 && x <= 8 && y >= 1 && y <= 6
  const grid = base.grid.map((row, y) =>
    row.map((tile, x) =>
      visible(x, y)
        ? phase < 4 && (tile === '#' || tile === 'g')
          ? 'h'
          : tile
        : '#',
    ),
  )
  return {
    ...base,
    staticKey: `dream-${phase}`,
    grid,
    decor: [],
    signs: [],
    outdoorExit: phase >= 4 ? base.outdoorExit : undefined,
    furniture: base.furniture.filter(
      (f) => visible(f.x, f.y) && visible(f.x + f.w - 1, f.y + f.h - 1),
    ),
  }
}

// 워크스페이스 사무실의 단계별 바닥. 시작 전(3)에도 사무실 바닥 전체와 엘리베이터를 보여 준다. 엘리베이터 문은
// outdoorExit로 그려서, 시작 전에 이를 비우면 문까지 사라지고 엘리베이터 앞에서 시작하지도 못했다(S15P31A108-305).
// 바깥 산책으로 나가는 동작만 OfficeScene이 시작 뒤(4)에 연다. 1인칭으로 엘리베이터를 가리킬 때 보이는 안내도 그에 맞춘다.
export function officeFloorAt(base: Floor, phase: number): Floor {
  if (phase !== 3) return makeDreamFloor(base, phase)
  const floor = makeDreamFloor(base, 4)
  return {
    ...floor,
    staticKey: 'dream-3',
    outdoorExit: floor.outdoorExit && {
      ...floor.outdoorExit,
      label: '엘리베이터 · 프로젝트를 시작하면 산책할 수 있어요',
    },
  }
}
