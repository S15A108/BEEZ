/** 4방향 A* 경로 탐색. 도착 칸은 막혀 있어도 허용(소파·의자 위 등). */
export function findPath(
  blocked: boolean[][],
  sx: number,
  sy: number,
  gx: number,
  gy: number,
): [number, number][] {
  if (sx === gx && sy === gy) return []
  const H = blocked.length
  const W = blocked[0].length
  const key = (x: number, y: number) => y * W + x
  const open: { x: number; y: number; f: number }[] = [{ x: sx, y: sy, f: 0 }]
  const g = new Map<number, number>([[key(sx, sy), 0]])
  const prev = new Map<number, number>()
  const h = (x: number, y: number) => Math.abs(x - gx) + Math.abs(y - gy)
  while (open.length) {
    open.sort((a, b) => a.f - b.f)
    const cur = open.shift()!
    if (cur.x === gx && cur.y === gy) {
      const out: [number, number][] = []
      let k = key(gx, gy)
      while (k !== key(sx, sy)) {
        out.push([k % W, Math.floor(k / W)])
        k = prev.get(k)!
      }
      return out.reverse()
    }
    for (const [dx, dy] of [
      [1, 0],
      [-1, 0],
      [0, 1],
      [0, -1],
    ]) {
      const nx = cur.x + dx
      const ny = cur.y + dy
      if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
      if (blocked[ny][nx] && !(nx === gx && ny === gy)) continue
      const ng = g.get(key(cur.x, cur.y))! + 1
      if (ng < (g.get(key(nx, ny)) ?? Infinity)) {
        g.set(key(nx, ny), ng)
        prev.set(key(nx, ny), key(cur.x, cur.y))
        open.push({ x: nx, y: ny, f: ng + h(nx, ny) })
      }
    }
  }
  return [[gx, gy]]
}
