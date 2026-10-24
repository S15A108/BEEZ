import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useSession } from './useSession'
import { getApiErrorStatus } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'

export function RequireAccount({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  if (session.isPending)
    return (
      <p role="status" className="p-12 text-center text-muted-foreground">
        로그인 상태를 확인하고 있어요…
      </p>
    )
  if (session.error && getApiErrorStatus(session.error) !== 401)
    return <AsyncState query={session}>{() => children}</AsyncState>
  if (!session.data)
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search }}
      />
    )
  return children
}
