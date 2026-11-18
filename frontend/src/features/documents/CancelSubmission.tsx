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

// 팀장이 결정하기 전에 올린 사람이 요청을 거둔다(DOC-A24). 거두면 고칠 수 있고, 고친 뒤에는 비비 검토부터 다시 받는다.
export function CancelSubmission({
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
        검토 요청 취소
      </Button>
      {open && (
        <Dialog open onOpenChange={(next) => !next && !busy && setOpen(false)}>
          <DialogContent className="sm:max-w-[420px]">
            <DialogHeader>
              <DialogTitle>팀장 검토 요청을 취소할까요?</DialogTitle>
              <DialogDescription>
                취소하면 비비가 문서를 다시 검토해요. 검토가 끝나면 문서를 다시
                고치거나 팀장에게 새로 요청할 수 있어요. 이번 요청은 이력에
                &lsquo;취소됨&rsquo;으로 남아요.
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
                disabled={busy}
                onClick={() => void onConfirm().finally(() => setOpen(false))}
              >
                요청 취소하기
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </>
  )
}
