import type { OfficeOutdoorExit } from './floors'
import type { Point } from './motion'
export const nearOfficeExit = (
  point: Point,
  exit: OfficeOutdoorExit | undefined,
) =>
  !!exit && Math.hypot(point.x - exit.landing.x, point.y - exit.landing.y) < 64
