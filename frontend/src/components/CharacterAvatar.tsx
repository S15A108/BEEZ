import type { Character } from '@/lib/characters'
import { cn } from '@/lib/utils'

// 머리줄의 내 캐릭터 동그라미(캔버스 SCR-05-v2-account). 이름이 옆에 있어 그림은 장식이다.
export function CharacterAvatar({
  character,
  className,
}: {
  character: Character
  className?: string
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'block size-7 shrink-0 overflow-hidden rounded-full',
        className,
      )}
      style={{ backgroundColor: `${character.color}38` }}
    >
      <img
        src={character.face}
        alt=""
        draggable={false}
        className="block size-full object-cover"
      />
    </span>
  )
}
