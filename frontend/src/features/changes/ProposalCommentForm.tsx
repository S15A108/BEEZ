import { useState } from 'react'
import { Check, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'

// 변경 제안 의견 입력(캔버스 PNL-04-v2-comments). 윗줄은 '의견 / 검토 요청' 탭과 받을 팀원 칩,
// 아랫줄은 내용 입력칸과 보내기 버튼이다. 받을 팀원은 CHG-A12 mentioned_member_ids(최대 20명)로 보낸다.
export const MAX_MENTIONS = 20
const MAX_BODY = 2000

type Kind = 'COMMENT' | 'REVIEW_REQUEST'
export interface CommentSubmit {
  kind: Kind
  body: string
  memberIds: string[]
}
interface Props {
  /** 받을 팀원 후보(본인 제외) */
  members: { id: string; name: string }[]
  onSubmit: (value: CommentSubmit) => Promise<unknown>
}

const kinds = [
  ['COMMENT', '의견'],
  ['REVIEW_REQUEST', '검토 요청'],
] as const

export function ProposalCommentForm({ members, onSubmit }: Props) {
  const [kind, setKind] = useState<Kind>('COMMENT')
  const [body, setBody] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const review = kind === 'REVIEW_REQUEST'
  const full = picked.length >= MAX_MENTIONS
  const nameOf = (id: string) =>
    members.find((member) => member.id === id)?.name ?? '팀원'
  const needTarget = review && picked.length === 0
  const canSend = !!body.trim() && !needTarget && !busy
  const toggle = (id: string) =>
    setPicked((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : current.length < MAX_MENTIONS
          ? [...current, id]
          : current,
    )
  const send = async () => {
    if (!canSend) return
    setBusy(true)
    try {
      await onSubmit({
        kind,
        body: body.trim(),
        memberIds: review ? picked : [],
      })
      setBody('')
      setPicked([])
    } catch {
      // 실패 문구는 부모가 보인다. 쓰던 내용과 고른 팀원은 그대로 둔다.
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid gap-2 rounded-[14px] bg-[#fbfaf6] px-3.5 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div
          role="tablist"
          aria-label="남길 종류"
          className="flex gap-0.5 rounded-[10px] bg-[#e6e9ee] p-[3px]"
        >
          {kinds.map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={kind === value}
              onClick={() => setKind(value)}
              className={cn(
                'h-[30px] rounded-lg px-2.5 text-[13px] whitespace-nowrap',
                kind === value
                  ? 'bg-white font-bold shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
                  : 'font-medium text-[#57534e]',
              )}
            >
              {label}
            </button>
          ))}
        </div>
        <span className="flex-1" />
        {review && (
          <>
            <ul
              aria-label="받을 팀원"
              className="flex flex-wrap items-center gap-1.5"
            >
              {picked.map((id) => (
                <li key={id}>
                  <span className="inline-flex h-[22px] items-center gap-1 rounded-full bg-[#f0eee8] pr-1 pl-2 text-xs font-semibold text-[#57534e]">
                    {nameOf(id)}
                    <button
                      type="button"
                      aria-label={`${nameOf(id)} 빼기`}
                      className="grid size-4 place-items-center rounded-full hover:bg-black/10"
                      onClick={() => toggle(id)}
                    >
                      <X className="size-3" />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
            <Popover open={open} onOpenChange={setOpen}>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="text-[13px] font-semibold whitespace-nowrap text-[#8a6a00] hover:underline"
                >
                  + 받을 팀원
                </button>
              </PopoverTrigger>
              <PopoverContent align="end" className="w-60 p-1.5">
                {members.length ? (
                  <ul aria-label="팀원 고르기" className="grid gap-0.5">
                    {members.map((member) => {
                      const on = picked.includes(member.id)
                      return (
                        <li key={member.id}>
                          <label
                            className={cn(
                              'flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-muted',
                              !on && full && 'cursor-not-allowed opacity-50',
                            )}
                          >
                            <input
                              type="checkbox"
                              className="peer sr-only"
                              checked={on}
                              disabled={!on && full}
                              onChange={() => toggle(member.id)}
                            />
                            <span
                              aria-hidden="true"
                              className={cn(
                                'grid size-4 place-items-center rounded border peer-focus-visible:ring-2 peer-focus-visible:ring-ring',
                                on
                                  ? 'border-[#f3c530] bg-[#f3c530] text-[#322710]'
                                  : 'border-[#d6d3cb] bg-white',
                              )}
                            >
                              {on && <Check className="size-3" />}
                            </span>
                            {member.name}
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                ) : (
                  <p className="px-2 py-1.5 text-sm text-muted-foreground">
                    고를 수 있는 팀원이 없어요.
                  </p>
                )}
                {full && (
                  <p className="px-2 pt-1.5 text-xs text-muted-foreground">
                    최대 {MAX_MENTIONS}명까지 고를 수 있어요.
                  </p>
                )}
              </PopoverContent>
            </Popover>
          </>
        )}
      </div>
      <div className="flex items-start gap-1.5">
        <Textarea
          aria-label="내용"
          rows={1}
          maxLength={MAX_BODY}
          value={body}
          onChange={(event) => setBody(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
              event.preventDefault()
              void send()
            }
          }}
          placeholder={
            review ? '검토를 부탁하는 내용을 적어 주세요' : '의견을 적어 주세요'
          }
          className="max-h-36 min-h-9 resize-none rounded-[10px] border-[#e5e5e5] px-3 py-[7px] text-sm leading-5"
        />
        <Button
          className="h-9 px-3.5"
          disabled={!canSend}
          onClick={() => void send()}
        >
          보내기
        </Button>
      </div>
      {needTarget && !!body.trim() && (
        <p aria-live="polite" className="text-xs leading-[18px] text-[#b42318]">
          받을 팀원을 한 명 이상 골라 주세요.
        </p>
      )}
      {review && picked.length > 0 && (
        <p
          aria-live="polite"
          className="text-xs leading-[18px] text-muted-foreground"
        >
          {picked.length}명에게 검토를 요청해요.
        </p>
      )}
      <span className="text-xs leading-[18px] text-muted-foreground">
        검토 요청은 고른 사람에게만 알림이 가요.
      </span>
    </div>
  )
}
