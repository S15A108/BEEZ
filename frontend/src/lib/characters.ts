import beeHead from '@/assets/characters/bee-head.png'
import bee from '@/assets/characters/bee.png'
import humanHead from '@/assets/characters/human-head.png'
import human from '@/assets/characters/human.png'
import robotHead from '@/assets/characters/robot-head.png'
import robot from '@/assets/characters/robot.png'

// 캐릭터 3종(캔버스 SCR-05-v2-account v440 이후). 키는 계정 avatar 저장값이고, 이름·표시 색은 보드 그대로다.
// portrait는 몸 전체(캐릭터 고르기), face는 머리줄·프로필 동그라미에 쓰는 얼굴 컷이다. 그림은 보드가 쓰는 3D 렌더 그대로:
// 사람은 Kenney Mini Characters(CC0) character-female-d, 로봇·벌은 같은 블록 비율로 새로 만든 모델.
export const characters = [
  {
    key: 'human',
    label: '사람',
    colorName: '파랑',
    color: '#4f86c6',
    portrait: human,
    face: humanHead,
  },
  {
    key: 'robot',
    label: '로봇',
    colorName: '청록',
    color: '#3aa6a0',
    portrait: robot,
    face: robotHead,
  },
  {
    key: 'bee',
    label: '벌',
    colorName: '노랑',
    color: '#f3c530',
    portrait: bee,
    face: beeHead,
  },
] as const

export type Character = (typeof characters)[number]

// 예전 프리셋 5종(bee-*)으로 저장된 계정은 그 표시 색으로 가장 가까운 새 캐릭터를 고른다.
const legacyColors: Record<string, string> = {
  'bee-yellow': '#f3c530',
  'bee-blue': '#4f86c6',
  'bee-green': '#3f9a5c',
  'bee-purple': '#7d64c8',
  'bee-orange': '#e07b39',
}

const hex = (value: string) =>
  [1, 3, 5].map((start) => parseInt(value.slice(start, start + 2), 16))

// 계정의 avatar가 지금 캐릭터면 그대로, 옛 값(bee-*)이면 그 색, 아니면 표시 색에 가장 가까운 캐릭터.
// 색도 없으면 id로 고르게 나눈다.
export function characterOf(
  avatar?: string,
  color?: string,
  seed = '',
): Character {
  const preset = characters.find((item) => item.key === avatar)
  if (preset) return preset
  const target = (avatar && legacyColors[avatar]) || color
  if (target && /^#[0-9a-f]{6}$/i.test(target)) {
    const [r, g, b] = hex(target)
    return characters.reduce((best, item) => {
      const distance = (candidate: Character) => {
        const [tr, tg, tb] = hex(candidate.color)
        return (r - tr) ** 2 + (g - tg) ** 2 + (b - tb) ** 2
      }
      return distance(item) < distance(best) ? item : best
    })
  }
  const sum = [...seed].reduce((total, char) => total + char.charCodeAt(0), 0)
  return characters[sum % characters.length]
}
