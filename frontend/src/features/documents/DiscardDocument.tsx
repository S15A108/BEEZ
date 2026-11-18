import { useState } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

// 비비 검토가 계속 실패하는 등 이 문서 등록을 포기한다(DOC-A25). 올린 사람이나 팀장이 승인된 적 없는 문서만 취소할 수 있고,
// 이력은 남고 같은 종류의 문서를 새로 올릴 수 있다.
export function DiscardDocument({
  busy,
  onConfirm,
}: {
  busy: boolean
  onConfirm: () => Promise<unknown>
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        variant="outline"
        className="justify-self-start"
        disabled={busy}
        onClick={() => setOpen(true)}
      >
        등록 취소
      </Button>
      {open && (
        <Dialog open onOpenChange={(next) => !next && !busy && setOpen(false)}>
          <DialogContent className="sm:max-w-[420px]">
            <DialogHeader>
              <DialogTitle>이 문서 등록을 취소할까요?</DialogTitle>
              <DialogDescription>
                올린 내용과 비비 검토는 이력에만 남고 목록에서 사라져요. 같은
                종류의 문서를 새로 올릴 수 있어요. 이미 승인된 적이 있는 문서는
                취소할 수 없어요.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => setOpen(false)}
              >
                돌아가기
              </Button>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => void onConfirm().finally(() => setOpen(false))}
              >
                등록 취소하기
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </>
  )
}
