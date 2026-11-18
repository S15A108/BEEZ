import { Link } from 'react-router'
import { BibiFace } from '@/components/BibiFace'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/auth/useSession'

// 없는 주소와 유효하지 않은 초대(만료·취소·이미 수락·삭제·잘못된 링크)가 같이 쓴다(캔버스 SCR-07-v2-not-found).
// 없는·볼 수 없는 프로젝트 주소는 문구만 바꿔 쓴다.
// 메일의 초대 링크는 보통 로그아웃 상태로 열리고, 그때 나의 도시(/)는 로그인으로 보내므로 버튼도 로그인으로 잇는다(PR #316 리뷰).
export default function NotFoundPage({
  title = '페이지를 찾을 수 없어요',
  back,
}: {
  title?: string
  back?: string
}) {
  const session = useSession()
  const signedOut = !session.isPending && !session.data
  return (
    <main className="grid min-h-96 place-content-center justify-items-center gap-5 px-6 py-16 text-center">
      <BibiFace className="size-28" />
      <h2 className="text-2xl font-bold">{title}</h2>
      <Button asChild className="h-11 rounded-xl px-6 font-bold">
        {signedOut ? (
          <Link to="/login">로그인하러 가기</Link>
        ) : (
          <Link to="/">{back ?? '나의 도시로'}</Link>
        )}
      </Button>
    </main>
  )
}
