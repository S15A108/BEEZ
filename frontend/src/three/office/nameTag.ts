// 사무실 머리 위 이름표(D37)의 역할·색, 글자 배치와 화면 위치 계산. 그리기는 OfficeRenderer3D가 한다.
import type { OfficeAppearance } from './OfficeRenderer3D'

/** 이름표 역할. 나·사람 팀원·에이전트를 색으로 나눈다. 비비도 AI라 에이전트 색이다. */
export type TagRole = 'me' | 'member' | 'agent'

/** 내 캐릭터 이름표 글. 다른 이름표와 달리 이름 대신 '나'라고 쓴다. */
export const MY_TAG = '나'

export function tagRole(
  appearance: OfficeAppearance | undefined,
  isMe: boolean,
): TagRole {
  if (isMe) return 'me'
  return !appearance || appearance === 'agent' || appearance === 'bibi'
    ? 'agent'
    : 'member'
}

/**
 * 역할별 알약 색. 앱 팔레트(꿀색 primary, 흰 card, 짙은 foreground)에서 골랐고 글자 대비는 모두 4.5:1 이상이다.
 * 팀원 개인 색(이니셜 원·에이전트 스카프)은 쓰지 않아 역할 색과 섞이지 않는다.
 */
export const TAG_COLORS: Record<
  TagRole,
  { fill: string; ink: string; status: string; border: string }
> = {
  me: {
    fill: '#f3c530',
    ink: '#322710',
    status: '#322710',
    border: 'rgba(50,39,16,0.3)',
  },
  member: {
    fill: '#ffffff',
    ink: '#2c291f',
    status: '#5c5647',
    border: 'rgba(44,41,31,0.14)',
  },
  agent: {
    fill: '#2c291f',
    ink: '#fcfaf4',
    status: '#d9d1bd',
    border: 'rgba(252,250,244,0.18)',
  },
}

/** 이름표 아래 말풍선(대기 이유·연결 미확인) 색 */
export const NOTE_COLORS = { fill: '#fff3c4', ink: '#6e5200' } as const

/** 이름표 크기(css px). 거리·줌과 상관없이 화면에서 이 크기로 보인다. */
export const TAG = {
  font: 15,
  noteFont: 13,
  height: 28,
  noteHeight: 24,
  padX: 12,
  gap: 4,
  /** 그림자가 잘리지 않게 둘레에 두는 여백 */
  margin: 3,
  maxWidth: 280,
} as const

/** '박팀원의 에이전트 · 작업 중'을 이름과 상태로 나눈다. 상태는 가늘고 옅게 그린다. */
export function tagParts(tag: string) {
  const at = tag.lastIndexOf(' · ')
  return at < 0
    ? { name: tag, status: '' }
    : { name: tag.slice(0, at), status: tag.slice(at) }
}

export interface TagBox {
  /** 가운데 x(css px) */
  x: number
  /** 아래 끝 y(css px, 아래로 갈수록 크다) */
  bottom: number
  width: number
  height: number
  /** 카메라에서 떨어진 거리. 가까운 이름표가 제자리에 남는다. */
  depth: number
}

/**
 * 화면에서 겹치는 이름표를 가까운 것부터 제자리에 두고, 먼 것을 겹치지 않을 때까지 위로 올린다.
 * 상자마다 올린 px를 같은 순서로 돌려준다.
 */
export function stackTags(boxes: readonly TagBox[], gap = 2) {
  const order = boxes
    .map((_, index) => index)
    .sort((a, b) => boxes[a].depth - boxes[b].depth)
  const placed: { left: number; right: number; top: number; bottom: number }[] =
    []
  const lifts = boxes.map(() => 0)
  for (const index of order) {
    const box = boxes[index]
    const left = box.x - box.width / 2,
      right = box.x + box.width / 2
    let bottom = box.bottom
    // 올릴 때마다 bottom이 줄어들고, 올린 상자와는 다시 겹치지 않아 끝난다.
    for (let moved = true; moved;) {
      moved = false
      for (const other of placed)
        if (
          left < other.right &&
          right > other.left &&
          bottom - box.height < other.bottom + gap &&
          bottom > other.top - gap
        ) {
          bottom = other.top - gap
          moved = true
        }
    }
    placed.push({ left, right, top: bottom - box.height, bottom })
    lifts[index] = box.bottom - bottom
  }
  return lifts
}
