import { Badge } from '@/components/ui/badge'
import { statusLabel } from '@/lib/display'

export function StatusPill({ value }: { value: unknown }) {
  return (
    <Badge variant="outline" className="border-border bg-muted/50 font-normal">
      {statusLabel(value) || '상태 미확인'}
    </Badge>
  )
}
