// 나의 도시(D26): 부지가 곧은 거리 하나를 따라 한 줄로 늘어선 일직선 순환 큐. three를 부르지 않아 2D 화면에서도 쓴다.
// 줄은 [프로젝트(부지 번호 순)…, '+ 새 프로젝트' 빈 부지]이고, 빈 부지 다음에는 다시 첫 프로젝트가 온다.
// 칸 i는 가운데에서 d칸 떨어진 곳에 놓이고, d는 줄 길이로 감아 -K/2 ~ K/2에 둔다. 양 끝 근처 칸은 땅으로 가라앉았다가
// 반대쪽 끝에서 올라와 끊김 없이 이어지고, 한 칸이 화면에 두 번 보이지 않는다. 칸이 5개보다 적으면 보이는 폭을
// 그만큼 줄여 가운데에 모은다.
export const CITY_LOTS = 12
export const QUEUE_PITCH = 170

/** 줄의 index번째 칸이 가운데에서 몇 칸 떨어졌는지(d)와 크기 비율(1 그대로 · 0 가라앉음) */
export function queueSlot(index: number, offset: number, count: number) {
  const span = count / 2
  const d = ((((index - offset) % count) + count + span) % count) - span
  const from = Math.min(2, (count - 1) / 2)
  const to = Math.min(3, span)
  const size = 1 - Math.min(1, Math.max(0, (Math.abs(d) - from) / (to - from)))
  return { d, size }
}

/**
 * 처음 화면. 칸이 다섯 이상이면 마지막 프로젝트를 가운데, 빈 부지를 그 오른쪽에 둔다. 다섯보다 적으면 줄 전체를 순서대로
 * 가운데에 모은다(칸 수가 짝수면 칸 사이가 가운데라 반 칸 자리다).
 */
export const initialOffset = (count: number) =>
  count < 5 ? Math.max(0, (count - 1) / 2) : count - 2

/**
 * 끌기·휠이 멈출 자리. 보통은 정수 자리지만, 칸이 다섯보다 적고 짝수면 반 칸 자리에 멈춘다. 정수 자리에 멈추면 한 칸이
 * 이음매(±K/2)에 걸려 가라앉는다(프로젝트 하나면 빈 부지가 안 보인다).
 */
export function snapOffset(offset: number, count: number) {
  const shift = initialOffset(count) % 1
  return Math.round(offset - shift) + shift
}
