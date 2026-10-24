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
