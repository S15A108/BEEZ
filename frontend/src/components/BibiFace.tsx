import bibi from '@/assets/bibi.png'
import { cn } from '@/lib/utils'

// 비비 얼굴: 사용자 제공 캐릭터 시트(꿀벌 로봇)의 정면 그림을 머리 전체(더듬이 포함)와 손·'팀장' 완장이
// 조금 보이게 잘라 둔 이미지다. 둥근 칸을 그대로 채운다.
export function BibiFace({
  className,
  label,
}: {
  className?: string
  /** 그림만으로 비비를 알려야 하면 넣는다. 없으면 장식으로 숨긴다. */
  label?: string
}) {
  return (
    <span
      className={cn(
        'inline-block size-10 shrink-0 overflow-hidden rounded-full bg-[#f6e9ec]',
        className,
      )}
      aria-hidden={label ? undefined : true}
    >
      <img
        src={bibi}
        alt={label ?? ''}
        draggable={false}
        className="size-full object-cover"
      />
    </span>
  )
}
