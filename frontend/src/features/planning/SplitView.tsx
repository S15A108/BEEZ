import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { SPLIT_HEIGHT } from './splitLayout'

// 기획 분석 탭 안의 왼쪽 목록·오른쪽 상세. 두 칸은 따로 스크롤하고, 목록 위아래에 고르기·처리 버튼 줄을 둘 수 있다.
export function SplitView({
  header,
  list,
  footer,
  detail,
}: {
  header?: ReactNode
  list: ReactNode
  footer?: ReactNode
  detail: ReactNode
}) {
  return (
    <div
      className={cn(
        'grid grid-cols-[22rem_minmax(0,1fr)] grid-rows-[minmax(0,1fr)] overflow-hidden rounded-xl border bg-card',
        SPLIT_HEIGHT,
      )}
    >
      <div className="flex min-h-0 flex-col border-r">
        {header && (
          <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2.5">
            {header}
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-y-auto">{list}</div>
        {footer && (
          <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3">
            {footer}
          </div>
        )}
      </div>
      <div className="flex min-h-0 flex-col overflow-y-auto">{detail}</div>
    </div>
  )
}
