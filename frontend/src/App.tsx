import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Hexagon, LogOut, Settings } from 'lucide-react'
import { authApi } from '@/api/services'
import { useSession } from '@/features/auth/useSession'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { CharacterAvatar } from '@/components/CharacterAvatar'
import { characterOf } from '@/lib/characters'

export default function App() {
  const { pathname } = useLocation()
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const logout = useMutation({
    mutationFn: () => authApi.logout(),
    onSuccess: () => {
      queryClient.clear()
      navigate('/login')
    },
  })
  // 워크스페이스는 3D 게임 UI라 위쪽 헤더를 두지 않는다.
  if (pathname.startsWith('/projects/'))
    return (
      <div className="min-h-dvh bg-background text-foreground">
        <Outlet />
      </div>
    )
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
        {session.data ? (
          // 이름을 누르면 회원 설정·로그아웃 메뉴가 열린다(화면 검수 2026-11-16, S15P31A108-446).
          <nav aria-label="계정">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  className="h-10 gap-2 rounded-full pr-2.5 pl-1.5"
                >
                  <CharacterAvatar
                    character={characterOf(session.data.avatar)}
                    className="size-7 ring-2 ring-primary"
                  />
                  {session.data.name}
                  <ChevronDown className="text-muted-foreground" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {/* 회원 설정 안(비밀번호 변경·탈퇴 포함)에서는 회원 설정을 빼고 로그아웃만(캔버스 SCR-05-v2) */}
                {!pathname.startsWith('/account') && (
                  <DropdownMenuItem asChild>
                    <Link to="/account">
                      <Settings />
                      회원 설정
                    </Link>
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem
                  disabled={logout.isPending}
                  onSelect={() => logout.mutate()}
                >
                  <LogOut />
                  로그아웃
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </nav>
        ) : ['/', '/login', '/register'].includes(pathname) ? null : (
          // 로그인 화면 밖(초대·없는 주소)에서는 머리줄 오른쪽에 로그인 링크(캔버스 SCR-03·SCR-07)
          <Link to="/login" className="text-sm font-medium">
            로그인
          </Link>
        )}
      </header>
      {/* 임시 비밀번호(ACC-A07)로 로그인했으면 비밀번호 변경으로 안내만 하고 강제로 옮기지 않는다(Q04). */}
      {session.data?.must_change_password &&
        pathname !== '/account/password' && (
          <p
            role="status"
            className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b bg-primary/10 px-5 py-2.5 text-sm"
          >
            임시 비밀번호로 로그인했어요. 회원 설정에서 새 비밀번호로 바꿔
            주세요.
            <Link
              to="/account/password"
              className="font-semibold underline underline-offset-3"
            >
              비밀번호 변경
            </Link>
          </p>
        )}
      <Outlet />
    </div>
  )
}
