// 나의 도시 고리 계산(D26, 캔버스 v474 원형 도시를 앞쪽 호만 보이게). three를 부르지 않아 2D 화면에서도 쓴다.
// 칸 i는 각도 i·2π/N에 있고 반지름은 N·pitch/2π라 이웃 칸 사이가 늘 pitch다. 칸은 줄어들거나 가라앉지 않고 고리를 따라
// 돌아 화면 밖으로 나가며, 화면은 고리 앞쪽 호만 담아 부지가 가로로 한 줄 선 것처럼 보인다.
export const RING_SLOTS = 12
export const RING_PITCH = 150
export const RING_RADIUS = (RING_SLOTS * RING_PITCH) / (2 * Math.PI)
const N = RING_SLOTS

/** 고리를 rot칸 돌렸을 때 칸의 각도(phi)와 앞에서 벗어난 각(도) */
export function ringSlot(slot: number, rot: number) {
  const phi = ((slot - rot) * 2 * Math.PI) / N
  const away =
    (Math.abs(Math.atan2(Math.sin(phi), Math.cos(phi))) * 180) / Math.PI
  return { phi, away }
}

/** 처음 화면: 가장 최근 칸을 가운데, 빈 부지를 그 오른쪽에 둔다(보드: 프로젝트 2개면 2번 칸이 가운데). */
export const initialRotation = (nextSlot: number | null) =>
  nextSlot ? nextSlot - 1 : 0

/** 도시에 들어올 때: 마지막으로 연 프로젝트의 칸을 가운데에 둔다. 그 칸을 모르면(기록 없음·지운 프로젝트) 처음 화면 규칙. */
export const startRotation = (
  visitedSlot: number | null | undefined,
  nextSlot: number | null,
) => visitedSlot ?? initialRotation(nextSlot)

// 멈춤 자리는 프로젝트 칸과 빈 부지 칸(stops)이다. 꾸밈 칸에는 멈추지 않는다(사용자 결정 '꾸밈 구간 한 번에 넘기기').
// 돌린 값(rot)은 고리를 감은 연속 값이라 같은 칸도 N 차이 나는 자리가 여럿이고, 가장 가까운 것을 고른다.

/** 끌고 놓았을 때: rot에서 가장 가까운 멈춤 자리 */
export function nearestStop(rot: number, stops: readonly number[]) {
  let best = Math.round(rot)
  let gap = Infinity
  for (const stop of stops) {
    const at = stop + Math.round((rot - stop) / N) * N
    if (Math.abs(at - rot) < gap) {
      gap = Math.abs(at - rot)
      best = at
    }
  }
  return best
}

/** 휠 한 번: from에서 dir 쪽으로 다음 멈춤 자리. 빈 부지 다음은 꾸밈 칸을 건너 1번, 1번 앞은 빈 부지다. */
export function stepStop(from: number, stops: readonly number[], dir: 1 | -1) {
  if (!stops.length) return from + dir
  // 칸마다 dir 쪽으로 from을 넘는 가장 가까운 자리(stop + kN)를 구해 그중 가장 가까운 것
  return stops
    .map((stop) => {
      const turns = (from - stop) / N
      const k =
        dir > 0 ? Math.floor(turns + 1e-6) + 1 : Math.ceil(turns - 1e-6) - 1
      return stop + k * N
    })
    .reduce((a, b) => (dir > 0 ? Math.min(a, b) : Math.max(a, b)))
}
