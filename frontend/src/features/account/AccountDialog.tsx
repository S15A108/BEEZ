import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useIsMutating } from '@tanstack/react-query'
import { ChevronLeft, CircleCheckBig, XIcon } from 'lucide-react'
import { useSession } from '@/features/auth/useSession'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { AccountPassword } from './AccountPassword'
import { AccountProfile } from './AccountProfile'
import { AccountWithdraw } from './AccountWithdraw'
import { ACCOUNT_MUTATION_KEY, useAccountDialog } from './useAccountDialog'
import type { AccountView } from './useAccountDialog'

const titles: Record<AccountView, string> = {
  profile: '회원 설정',
  password: '비밀번호 변경',
  withdraw: '회원 탈퇴',
}

// 회원 설정 창(D48). 나의 도시든 사무실이든 지금 화면 위에 큰 창으로 뜬다. 상태는 주소의 ?account=에 있다.
// X와 Esc로 닫고, 하위 화면(비밀번호 변경·회원 탈퇴)에서는 Esc가 먼저 회원 설정으로 돌아간다(PaneSheet와 같은 규칙).
export function AccountDialog() {
  const dialog = useAccountDialog()
  const session = useSession()
  const open = dialog.view !== null && !!session.data
  return (
    <Dialog open={open}>
      {open && dialog.view && (
        <AccountDialogBody {...dialog} view={dialog.view} />
      )}
    </Dialog>
  )
}

function AccountDialogBody({
  view,
  show,
  back,
  close,
}: Omit<ReturnType<typeof useAccountDialog>, 'view'> & { view: AccountView }) {
  // 이 창의 요청(저장·토큰 폐기·비밀번호 변경·탈퇴) 중에는 닫지도, 하위 화면을 떠나지도 않는다.
  // 앱의 다른 요청은 세지 않는다(PR #316 리뷰).
  const busy = useIsMutating({ mutationKey: ACCOUNT_MUTATION_KEY }) > 0
  const navigate = useNavigate()
  const dirty = useRef(false)
  // 고치던 프로필을 버릴지 묻는 중이면 그 뒤에 할 일: 창 닫기(true) 또는 창 안 링크의 주소로 옮기기.
  const [confirmClose, setConfirmClose] = useState<boolean | string>(false)
  const [toast, setToast] = useState('')
  const sub = view !== 'profile'
  // 하위 화면으로 가거나 돌아오면 누른 버튼이 가려지므로 포커스를 창 제목으로 옮긴다.
  const title = useRef<HTMLHeadingElement>(null)
  useEffect(() => title.current?.focus(), [view])
  const requestClose = () => {
    if (busy) return
    // 프로필을 고치던 중이면 한 번 묻는다(나의 도시 만들기 창과 같은 방식, S15P31A108-371).
    if (dirty.current) setConfirmClose(true)
    else close()
  }
  const goBack = () => {
    if (!busy) back()
  }
  return (
    <DialogContent
      showCloseButton={false}
      className="grid h-[min(880px,90dvh)] grid-rows-[auto_minmax(0,1fr)] gap-0 overflow-hidden bg-background p-0 sm:max-w-[min(960px,94vw)]"
      onEscapeKeyDown={(event) => {
        event.preventDefault()
        if (sub) goBack()
        else requestClose()
      }}
      // 바깥을 눌러서는 닫지 않는다. X와 Esc로만 닫는다.
      onInteractOutside={(event) => event.preventDefault()}
    >
      <DialogHeader className="gap-1 px-8 pt-7 pb-4">
        {sub && (
          <button
            type="button"
            disabled={busy}
            onClick={goBack}
            className="inline-flex items-center gap-1 self-start text-sub font-semibold text-[#57534e] hover:text-foreground"
          >
            <ChevronLeft className="size-4" />
            회원 설정
          </button>
        )}
        <DialogTitle
          ref={title}
          tabIndex={-1}
          className="text-lg font-semibold outline-none"
        >
          {titles[view]}
        </DialogTitle>
        <DialogDescription className="sr-only">
          프로필과 캐릭터, 비밀번호, 에이전트 토큰, 회원 탈퇴를 관리해요.
        </DialogDescription>
      </DialogHeader>
      <Button
        variant="ghost"
        size="icon-sm"
        className="absolute top-2 right-2"
        disabled={busy}
        onClick={requestClose}
      >
        <XIcon />
        <span className="sr-only">닫기</span>
      </Button>
      <div
        className="overflow-y-auto px-8 pt-1 pb-8"
        // 창 안 링크(토큰의 프로젝트, 탈퇴의 '팀장 위임'·'프로젝트 삭제')로 떠날 때도 X·Esc와 같다(PR #316 리뷰).
        // 요청 중이면 떠나지 않고, 고치던 프로필이면 묻는다.
        onClickCapture={(event) => {
          const link = (event.target as HTMLElement).closest('a[href]')
          if (!link || !(busy || dirty.current)) return
          event.preventDefault()
          if (busy) return
          setConfirmClose(link.getAttribute('href') ?? true)
        }}
      >
        <div className="mx-auto grid max-w-[720px] gap-5">
          {toast && !sub && (
            <p
              role="status"
              className="flex items-center gap-2.5 rounded-xl bg-foreground px-4 py-3 text-sm text-white"
            >
              <CircleCheckBig
                aria-hidden="true"
                className="size-[18px] shrink-0 text-[#7bd88f]"
              />
              {toast}
            </p>
          )}
          {/* 하위 화면에 가 있는 동안에도 첫 화면을 내리지 않고 가려 두어 고치던 프로필이 남는다. */}
          <div hidden={sub} className="grid gap-5">
            <AccountProfile
              onShow={(next) => {
                setToast('')
                show(next)
              }}
              onDirtyChange={(value) => {
                dirty.current = value
              }}
            />
          </div>
          {view === 'password' && (
            <AccountPassword
              onCancel={goBack}
              onDone={(message) => {
                setToast(message)
                back()
              }}
            />
          )}
          {view === 'withdraw' && <AccountWithdraw onCancel={goBack} />}
        </div>
      </div>
      {confirmClose && (
        <Dialog open onOpenChange={(next) => !next && setConfirmClose(false)}>
          <DialogContent className="sm:max-w-[420px]">
            <DialogHeader>
              <DialogTitle>고치던 프로필을 닫을까요?</DialogTitle>
              <DialogDescription>
                바꾼 이름과 캐릭터는 저장되지 않아요.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setConfirmClose(false)}>
                계속 고치기
              </Button>
              <Button
                variant="destructive"
                onClick={() => {
                  setConfirmClose(false)
                  if (typeof confirmClose === 'string') navigate(confirmClose)
                  else close()
                }}
              >
                버리고 닫기
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </DialogContent>
  )
}
