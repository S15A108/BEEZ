import type { ReactNode } from 'react'
import { Inbox } from 'lucide-react'
import { cn } from '@/lib/utils'

export function EmptyState({
  children,
  className,
}: {
  children: ReactNode
  /** 빈 칸이 차지할 높이 등. 기획 분석 탭은 목록·상세와 같은 높이로 둔다 */
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex min-h-40 flex-col items-center justify-center gap-3 rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground',
        className,
      )}
    >
      <Inbox className="size-7 opacity-50" />
      {children}
    </div>
  )
}
