import { Link, Outlet } from 'react-router'
import { Hexagon } from 'lucide-react'
import { apiMode } from '@/lib/apiMode'

export default function App() {
  return (
    <div className="min-h-dvh bg-background text-foreground">
      <header className="relative z-20 flex h-16 items-center justify-between border-b bg-card px-5 sm:px-8">
        <Link
          to="/"
          className="flex items-center gap-2.5"
          aria-label="BEEZ 나의 도시"
        >
          <span className="grid size-9 place-items-center rounded-xl bg-primary">
            <Hexagon
              className="size-6 text-primary-foreground"
              strokeWidth={2.5}
            />
          </span>
          <h1 className="text-xl font-extrabold tracking-wide">BEEZ</h1>
        </Link>
        <p className="text-xs text-muted-foreground">
          {apiMode === 'mock'
            ? '목데이터로 미리 보기 · 실제 서버 호출 없음'
            : '프로젝트의 설계와 맥락을 함께'}
        </p>
      </header>
      <Outlet />
    </div>
  )
}
