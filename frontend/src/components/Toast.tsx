import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Check, X } from 'lucide-react'
import { cn } from '@/lib/utils'

// 잠깐 떴다가 사라지는 알림. 새 내용을 보이려면 key를 바꿔 다시 그린다.
// 기본은 오른쪽 위 4초. tone을 주면 화면 가운데 위의 흰 카드(초록 체크·빨간 X)로 6초(PNL-02 수정안 10-29:
// 프로젝트 시작·문서 등록·팀장 승인/반려). 움직임 줄이기 설정이면 미끄러지지 않는다.
export function Toast({
  children,
  tone,
}: {
  children: ReactNode
  /** dark: 워크스페이스 단계 알림(위 가운데 어두운 띠, 노란 체크, 캔버스 SCR-06-v2-workspace-docs-ready) */
  tone?: 'success' | 'danger' | 'dark'
}) {
  const [shown, setShown] = useState(true)
  useEffect(() => {
    const timer = setTimeout(() => setShown(false), tone ? 6000 : 4000)
    return () => clearTimeout(timer)
  }, [tone])
  if (!shown) return null
  if (tone === 'dark')
    return (
      <div
        role="status"
        className="fixed inset-x-0 top-[68px] z-50 mx-auto flex w-max max-w-[calc(100vw-2rem)] items-center gap-2.5 rounded-[14px] bg-foreground px-4 py-2.5 text-white shadow-lg motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-4"
      >
        <span
          aria-hidden="true"
          className="grid size-[22px] shrink-0 place-items-center rounded-full bg-primary text-primary-foreground"
        >
          <Check className="size-3.5" strokeWidth={3} />
        </span>
        <div className="grid min-w-0 gap-px">{children}</div>
      </div>
    )
  if (!tone)
    return (
      <p
        role="status"
        className="fixed top-4 right-4 z-50 rounded-xl border bg-card px-4 py-3 text-sm shadow-lg"
      >
        {children}
      </p>
    )
  return (
    <div
      role="status"
      className="fixed inset-x-0 top-6 z-50 mx-auto flex w-90 max-w-[calc(100vw-2rem)] items-start gap-3 rounded-xl border bg-card px-4 py-3.5 text-sm break-keep shadow-xl motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-4"
    >
      <span
        aria-hidden="true"
        className={cn(
          'grid size-6 shrink-0 place-items-center rounded-full text-white',
          tone === 'success' ? 'bg-green-700' : 'bg-destructive',
        )}
      >
        {tone === 'success' ? (
          <Check className="size-3.5" strokeWidth={3} />
        ) : (
          <X className="size-3.5" strokeWidth={3} />
        )}
      </span>
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  )
}
