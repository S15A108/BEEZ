// 원형 도시(캔버스 SCR-04-v2-city, v474)의 고리 계산. three를 부르지 않아 2D 화면에서도 쓴다.
// 칸 i는 각도 i·2π/N에 있고 반지름은 N·pitch/2π라 이웃 칸 사이가 늘 pitch다. 앞(카메라 쪽) 반원만 보이고,
// 반원 끝을 넘어가는 칸은 76°부터 92°까지 줄어들며 사라진다.
export const RING_SLOTS = 12
export const RING_PITCH = 150
export const RING_RADIUS = (RING_SLOTS * RING_PITCH) / (2 * Math.PI)
const SINK_FROM = 76
const SINK_TO = 92

/** 고리를 rot칸 돌렸을 때 칸의 각도(phi), 앞에서 벗어난 각(도), 크기 비율(1 그대로 · 0 사라짐) */
export function ringSlot(slot: number, rot: number) {
  const phi = ((slot - rot) * 2 * Math.PI) / RING_SLOTS
  const away =
    (Math.abs(Math.atan2(Math.sin(phi), Math.cos(phi))) * 180) / Math.PI
  const size =
    1 - Math.min(1, Math.max(0, (away - SINK_FROM) / (SINK_TO - SINK_FROM)))
  return { phi, away, size }
}

/** 처음 화면: 가장 최근 칸을 가운데, 빈 부지를 그 오른쪽에 둔다(보드: 프로젝트 2개면 2번 칸이 가운데). */
export const initialRotation = (nextSlot: number | null) =>
  nextSlot ? nextSlot - 1 : 0
