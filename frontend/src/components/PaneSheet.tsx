import { use, useCallback, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ChevronLeft } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { PaneSheetBody, PaneSheetContext } from './paneSheetContext'

/**
 * 업무 창·설정 창의 본문. 안에서 하위 화면(PaneSheet)이 열리면 본문을 가리고
 * 그 자리를 창 안 전체 화면으로 쓴다(캔버스 수정안 '← 뒤로' 머리줄).
 */
export function PaneSheetHost({
  children,
  onOpenChange,
  className,
}: {
  children: ReactNode
  /** 하위 화면이 열려 있는지 알려 준다(Esc를 창 닫기 대신 하위 화면 닫기로 쓰기 위해). */
  onOpenChange?: (open: boolean) => void
  className?: string
}) {
  const [host, setHost] = useState<HTMLElement | null>(null)
  const [open, setOpen] = useState(0)
  const track = useCallback(
    (on: boolean) => setOpen((count) => count + (on ? 1 : -1)),
    [],
  )
  useEffect(() => onOpenChange?.(open > 0), [open, onOpenChange])
  return (
    <PaneSheetContext value={{ host, track }}>
      <div className={className}>
        {/* 부모가 세로 flex면 본문과 하위 화면이 남은 높이를 채운다. */}
        <div hidden={open > 0} className="flex-1">
          {children}
        </div>
        <div ref={setHost} className="flex-1 empty:hidden" />
      </div>
    </PaneSheetContext>
  )
}

/**
 * 하위 화면. 업무 창 안이면 본문 자리를 덮는 '‹ 뒤로' 화면으로, 그 밖(단위 테스트 등)에서는
 * 기존처럼 작은 창으로 연다.
 */
export function PaneSheet({
  back = '뒤로',
  title,
  description,
  busy = false,
  onClose,
  className,
  children,
}: {
  /** 머리줄 '‹' 옆에 둘 돌아갈 곳 이름 */
  back?: string
  title: ReactNode
  description?: ReactNode
  /** 저장 중이면 닫지 않는다 */
  busy?: boolean
  onClose: () => void
  /** 작은 창으로 열 때 너비 */
  className?: string
  children: ReactNode
}) {
  const context = use(PaneSheetContext)
  const host = context?.host
  const track = context?.track
  useEffect(() => {
    if (!host || !track) return
    track(true)
    return () => track(false)
  }, [host, track])
  useEffect(() => {
    if (!host) return
    // Esc는 창 전체가 아니라 이 하위 화면만 닫는다.
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.stopPropagation()
      event.preventDefault()
      if (!busy) onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [host, busy, onClose])
  if (!host)
    return (
      <Dialog
        open
        onOpenChange={(open) => {
          if (!open && !busy) onClose()
        }}
      >
        <DialogContent
          className={cn('max-h-[90dvh] overflow-y-auto sm:max-w-xl', className)}
          onEscapeKeyDown={(event) => {
            if (busy) event.preventDefault()
          }}
          onInteractOutside={(event) => {
            if (busy) event.preventDefault()
          }}
        >
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>
              {description ?? '입력한 내용을 확인한 뒤 저장해 주세요.'}
            </DialogDescription>
          </DialogHeader>
          {children}
        </DialogContent>
      </Dialog>
    )
  return createPortal(
    <section
      aria-label={typeof title === 'string' ? title : undefined}
      className="flex min-h-full flex-col gap-3.5 rounded-2xl border bg-card px-6 py-5 shadow-xs"
    >
      <button
        type="button"
        disabled={busy}
        onClick={onClose}
        className="inline-flex items-center gap-1 self-start text-[13px] font-semibold text-[#57534e] hover:text-foreground"
      >
        <ChevronLeft className="size-4" />
        {back}
      </button>
      <div>
        <h3 className="text-lg font-bold">{title}</h3>
        {description && (
          <p className="mt-0.5 text-[13px] text-muted-foreground">
            {description}
          </p>
        )}
      </div>
      {/* 폼은 남은 높이를 채워 아래 버튼 줄(SheetSubmit)을 맨 아래에 둔다. 격자 폼은 줄이 늘어나지 않게 위로 붙인다. */}
      <div className="flex flex-1 flex-col gap-4 [&>form]:flex-1 [&>form.grid]:content-start">
        <PaneSheetBody value={{ onClose }}>{children}</PaneSheetBody>
      </div>
    </section>,
    host,
  )
}

/**
 * 폼 제출 버튼. 창 안 하위 화면이면 아래 고정 줄에 '취소'와 함께 두고(캔버스 수정안),
 * 작은 창에서는 버튼 하나만 둔다.
 */
export function SheetSubmit({
  children,
  disabled,
  destructive,
  className,
}: {
  children: ReactNode
  disabled?: boolean
  destructive?: boolean
  /** 작은 창일 때 버튼 자리 */
  className?: string
}) {
  const sheet = use(PaneSheetBody)
  const submit = (
    <Button
      type="submit"
      variant={destructive ? 'destructive' : 'default'}
      disabled={disabled}
      className={sheet ? undefined : className}
    >
      {children}
    </Button>
  )
  if (!sheet) return submit
  return (
    <div className="sticky -bottom-5 z-10 col-span-full -mx-6 mt-auto -mb-5 flex items-center justify-end gap-2 rounded-b-2xl border-t bg-card px-6 pt-3.5 pb-5">
      <Button
        type="button"
        variant="outline"
        disabled={disabled}
        onClick={sheet.onClose}
      >
        취소
      </Button>
      {submit}
    </div>
  )
}
