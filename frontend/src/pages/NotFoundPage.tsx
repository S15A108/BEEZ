import { Link } from 'react-router'

// 없는 주소와 유효하지 않은 초대(만료·취소·이미 수락·삭제·잘못된 링크)가 같이 쓴다(캔버스 SCR-07-v2-not-found).
export default function NotFoundPage() {
  return (
    <main className="grid min-h-96 place-content-center gap-4 px-6 text-center">
      <h2 className="text-2xl font-bold">페이지를 찾을 수 없어요</h2>
      <Link to="/" className="text-sm underline">
        처음 화면으로 돌아가기
      </Link>
    </main>
  )
}
