import type { ReactNode } from 'react'
import { Inbox } from 'lucide-react'

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      <Inbox className="size-7 opacity-50" />
      {children}
    </div>
  )
}
