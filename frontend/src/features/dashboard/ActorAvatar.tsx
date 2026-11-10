import { cn } from '@/lib/utils'

// 사람은 이름 첫 글자, 에이전트는 AI로 구분한다(캔버스 PNL-09-v2-timeline).
export function ActorAvatar({
  label,
  agent = false,
}: {
  label: string
  agent?: boolean
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid size-6.5 shrink-0 place-items-center rounded-full text-[10px] font-bold',
        agent ? 'bg-[#e9e4ff] text-[#4b3aa8]' : 'bg-muted text-foreground',
      )}
    >
      {label}
    </span>
  )
}
