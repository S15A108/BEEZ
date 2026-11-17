import { Badge } from '@/components/ui/badge'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'

export function StatusPill({
  value,
  className,
}: {
  value: unknown
  /** 상태마다 색을 달리할 때 넘긴다(작업 현황, S15P31A108-416). 없으면 회색이다. */
  className?: string
}) {
  return (
    <Badge
      variant="outline"
      className={cn('border-border bg-muted/50 font-normal', className)}
    >
      {statusLabel(value) || '상태 미확인'}
    </Badge>
  )
}
