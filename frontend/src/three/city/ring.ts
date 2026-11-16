// 나의 도시 고리 계산(D33, 캔버스 v474 원형 도시를 앞쪽 호만 보이게). three를 부르지 않아 2D 화면에서도 쓴다.
// 칸 i는 각도 i·2π/N에 있고 반지름은 N·pitch/2π라 이웃 칸 사이가 늘 pitch다. 칸은 줄어들거나 가라앉지 않고 고리를 따라
// 돌아 화면 밖으로 나가며, 화면은 고리 앞쪽 호만 담아 부지가 가로로 한 줄 선 것처럼 보인다.
// 칸 간격은 150에서 200으로 넓혔다(반지름 286 → 382, 사용자 요청 "건물간격 좀더 넓히고 … 원을 더 넓은 버전", 목업 A안).
export const RING_SLOTS = 12
export const RING_PITCH = 200
export const RING_RADIUS = (RING_SLOTS * RING_PITCH) / (2 * Math.PI)
const N = RING_SLOTS

/** 고리를 rot칸 돌렸을 때 칸의 각도(phi)와 앞에서 벗어난 각(도) */
export function ringSlot(slot: number, rot: number) {
  const phi = ((slot - rot) * 2 * Math.PI) / N
  const away =
    (Math.abs(Math.atan2(Math.sin(phi), Math.cos(phi))) * 180) / Math.PI
  return { phi, away }
}

/**
 * 칸 s에 놓을 차례(lots의 몇 번째, 없으면 -1). lots는 프로젝트 칸 번호 순서 다음 빈 부지다. 둘 이상이면 고리를 따라 그
 * 순서를 되풀이해 꾸밈 칸 없이 이어 놓는다(1-2-…-새 프로젝트-1-2-…, 사용자 요청 2026-11-16). 칸 s는 지금 돌린 값(rot)에서
 * 반 바퀴 안에 드는 자리 v(= s + 12k)로 보고 v번째 차례를 놓으므로, 차례가 바뀌는 칸은 늘 고리 뒤(화면 밖)다. 하나뿐이면
 * 제 칸에만 두고 나머지는 꾸밈 칸이다.
 */
export function ringOrder(s: number, rot: number, lots: readonly number[]) {
  const count = lots.length
  if (count < 2) return lots.indexOf(s)
  const v = s + N * Math.round((rot - s) / N)
  return ((v % count) + count) % count
}

/** 순환 도로 바깥 차로의 버스 자리(칸). 여섯 칸에 한 대이고, 차는 이 언저리를 비운다. */
export const BUS_SPOTS = Array.from(
  { length: Math.floor(N / 6) },
  (_, k) => 6 * k + 1.6,
)

/**
 * 순환 도로 차로 하나의 차 자리(칸). 고리를 count몫으로 고르게 나눠 몫마다 가운데 60% 안에서 흔든다. 이웃 차 사이는
 * 적어도 한 몫의 40%라 차끼리 파고들지 않는다(리뷰: 순수 무작위 각도는 같은 차로에서 겹쳤다). avoid(칸)에서 clear칸
 * 안에 드는 자리는 비운다.
 */
export function laneSpots(
  count: number,
  rnd: () => number,
  avoid: readonly number[] = [],
  clear = 0.25,
) {
  return Array.from(
    { length: count },
    (_, j) => ((j + 0.2 + rnd() * 0.6) * N) / count,
  ).filter((th) =>
    avoid.every((at) => ringSlot(th, at).away >= (clear * 360) / N),
  )
}

/** 처음 화면: 가장 최근 칸을 가운데, 빈 부지를 그 오른쪽에 둔다(보드: 프로젝트 2개면 2번 칸이 가운데). */
export const initialRotation = (nextSlot: number | null) =>
  nextSlot ? nextSlot - 1 : 0

/** 도시에 들어올 때: 마지막으로 연 프로젝트의 칸을 가운데에 둔다. 그 칸을 모르면(기록 없음·지운 프로젝트) 처음 화면 규칙. */
export const startRotation = (
  visitedSlot: number | null | undefined,
  nextSlot: number | null,
) => visitedSlot ?? initialRotation(nextSlot)

// 멈춤 자리(stops)는 프로젝트·빈 부지가 놓인 칸이다. 차례를 되풀이해 이어 놓으면(ringOrder) 모든 칸이고, 꾸밈 칸이
// 남는 경우(빈 부지 하나뿐)에는 그 칸에 멈추지 않는다(사용자 결정 '꾸밈 구간 한 번에 넘기기').
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

/** 휠 한 번: from에서 dir 쪽으로 다음 멈춤 자리. 멈춤 자리 사이에 꾸밈 칸이 있으면 건너뛴다. */
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
