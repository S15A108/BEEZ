import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'

// 회원 설정·비밀번호 변경·회원 탈퇴가 같이 쓰는 틀: 최대 720px 한 열, 제목 줄 오른쪽에 돌아가는 길 하나.
// 비밀번호 변경과 탈퇴(toAccount)는 회원 설정으로, 회원 설정은 이전 페이지로 돌아간다.
export function AccountLayout({
  title,
  toAccount = false,
  children,
}: {
  title: string
  toAccount?: boolean
  children: ReactNode
}) {
  const navigate = useNavigate()
  return (
    <main className="mx-auto grid max-w-[720px] gap-5 px-6 pt-8 pb-10">
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-2xl font-bold">{title}</h2>
        {toAccount ? (
          <Button variant="outline" asChild>
            <Link to="/account">
              <ArrowLeft />
              회원 설정
            </Link>
          </Button>
        ) : (
          <Button variant="outline" onClick={() => navigate(-1)}>
            <ArrowLeft />
            이전 페이지로
          </Button>
        )}
      </div>
      {children}
    </main>
  )
}
