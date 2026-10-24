import { Link } from 'react-router'

export default function NotFoundPage() {
  return (
    <main className="grid min-h-96 place-content-center gap-4 text-center">
      <h2 className="text-2xl font-bold">페이지를 찾을 수 없어요</h2>
      <Link to="/" className="text-sm underline">
        나의 도시로 돌아가기
      </Link>
    </main>
  )
}
