import type { ReactNode } from 'react'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { statusTone } from './tagTones'

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
export function StatusTag({ value }: { value: string }) {
  return <Tag tone={statusTone[value]}>{statusLabel(value)}</Tag>
}
