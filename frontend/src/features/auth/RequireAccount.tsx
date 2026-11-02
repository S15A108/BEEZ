import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useSession } from './useSession'
import { getApiErrorStatus } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'

export function RequireAccount({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  const queryClient = useQueryClient()
  // 로그인이 만료돼 /me가 401이면 남아 있는 계정 캐시를 믿지 않고 로그인으로 보낸다. 캐시도 지워 다시 들어와도
  // 옛 계정으로 보호 화면을 열지 않는다(PR #72 재전수 SECOND-AMP-03). 다른 API의 401은 main.tsx가 /me를 다시 묻는다.
  const expired = getApiErrorStatus(session.error) === 401
  useEffect(() => {
    if (expired) queryClient.removeQueries({ queryKey: ['account'] })
  }, [expired, queryClient])
  if (session.isPending)
    return (
      <p role="status" className="p-12 text-center text-muted-foreground">
        로그인 상태를 확인하고 있어요…
      </p>
    )
  if (session.error && !expired)
    return <AsyncState query={session}>{() => children}</AsyncState>
  if (expired || !session.data)
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search }}
      />
    )
  return children
}
