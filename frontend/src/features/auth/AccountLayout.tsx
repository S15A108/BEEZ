import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { useSession } from '@/features/auth/useSession'
import { Button } from '@/components/ui/button'
import { characterOf } from '@/lib/characters'
import type { Character } from '@/lib/characters'
import { formatDay } from '@/lib/formatDate'

// 회원 설정과 비밀번호 변경이 같이 쓰는 틀(캔버스 SCR-05-v2-account·password):
// 제목 줄, 왼쪽 프로필 요약(캐릭터 · 이름 · 이메일 · 표시 색 · 가입일), 오른쪽 내용.
// character를 넘기면 저장 전에 고른 캐릭터를 미리 보여 준다.
export function AccountLayout({
  character,
  children,
}: {
  character?: Character
  children: ReactNode
}) {
  const session = useSession()
  const navigate = useNavigate()
  const picked = character ?? characterOf(session.data?.avatar)
  return (
    <main className="mx-auto grid max-w-[1120px] gap-5 px-6 pt-8 pb-10">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold">회원 설정</h2>
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ArrowLeft />
          이전 페이지로
        </Button>
      </div>
      <div className="grid items-start gap-6 md:grid-cols-[280px_minmax(0,1fr)]">
        <aside aria-label="내 프로필">
          <section className="grid justify-items-center gap-3 rounded-[14px] bg-card p-6 text-center ring-1 ring-foreground/10">
            <span
              className="block size-42 overflow-hidden rounded-full"
              style={{
                backgroundColor: `${picked.color}33`,
                boxShadow: `inset 0 0 0 3px ${picked.color}8c`,
              }}
            >
              <img
                src={picked.face}
                alt={`내 캐릭터: ${picked.label}`}
                className="size-full object-cover"
              />
            </span>
            <div className="grid gap-0.5">
              <strong className="text-lg font-bold">
                {session.data?.name}
              </strong>
              <span className="text-[13px] text-muted-foreground">
                {session.data?.email}
              </span>
            </div>
            <span className="inline-flex h-6.5 items-center gap-1.5 rounded-full bg-muted px-2.5 text-[12.5px] font-semibold">
              <span
                aria-hidden="true"
                className="size-2.25 rounded-full"
                style={{ backgroundColor: picked.color }}
              />
              {picked.label} · 표시 색 {picked.colorName}
            </span>
            {session.data && (
              <span className="text-xs text-muted-foreground">
                가입일 {formatDay(session.data.created_at)}
              </span>
            )}
          </section>
        </aside>
        <div className="grid min-w-0 gap-5">{children}</div>
      </div>
    </main>
  )
}
