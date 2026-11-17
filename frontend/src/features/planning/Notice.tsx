import type { ReactNode } from 'react'
import { CircleAlert, Clock3, LoaderCircle } from 'lucide-react'
import { cn } from '@/lib/utils'

const tones = {
  fail: 'bg-destructive/5 text-destructive',
  run: 'border border-amber-300 bg-amber-50 text-amber-900',
  wait: 'bg-muted text-foreground/80',
} as const

// 탭 위쪽의 한 줄 안내(실패·만드는 중·기다림). 문서마다 한 줄이고 다시 시도 같은 버튼은 줄 끝에 둔다.
export function Notice({
  tone,
  children,
  action,
}: {
  tone: keyof typeof tones
  children: ReactNode
  action?: ReactNode
}) {
  const Icon =
    tone === 'fail' ? CircleAlert : tone === 'run' ? LoaderCircle : Clock3
  return (
    <div
      role={tone === 'fail' ? 'alert' : 'status'}
      className={cn(
        'flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg px-3.5 py-2 text-sm',
        tones[tone],
      )}
    >
      <Icon
        aria-hidden
        className={cn(
          'size-4 shrink-0',
          tone === 'run' && 'animate-spin motion-reduce:animate-none',
        )}
      />
      <div className="min-w-0 flex-1 leading-6">{children}</div>
      {action}
    </div>
  )
}
