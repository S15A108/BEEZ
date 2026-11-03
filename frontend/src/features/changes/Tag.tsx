import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

// 변경 제안 화면의 칩(캔버스 PNL-04-v2 통일감 개선: 높이 22px, 12px·600, 상태마다 색). 색은 proposalItems의 operationTone.
export function Tag({
  tone,
  children,
}: {
  tone?: string
  children: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex h-[22px] shrink-0 items-center rounded-full px-2 text-xs font-semibold whitespace-nowrap',
        tone ?? 'bg-muted text-muted-foreground',
      )}
    >
      {children}
    </span>
  )
}
