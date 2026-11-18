import type { ReactNode } from 'react'
import { AlertCircle, Clock, LoaderCircle } from 'lucide-react'
import { isMissingApi } from '@/api/error'
import { Button } from '@/components/ui/button'

interface Props<T> {
  query: {
    data: T | undefined
    isPending: boolean
    error: Error | null
    refetch: () => unknown
  }
  children: (data: T) => ReactNode
}
export function AsyncState<T>({ query, children }: Props<T>) {
  if (query.isPending)
    return (
      <div
        className="flex min-h-36 items-center justify-center gap-3 text-sm text-muted-foreground"
        role="status"
      >
        <LoaderCircle className="size-4 animate-spin" />
        불러오는 중…
      </div>
    )
  // 서버에 아직 없는 API면 오류 대신 이 칸만 '준비 중'으로 둔다(live 모드, dev 백엔드).
  if (isMissingApi(query.error))
    return (
      <div
        role="status"
        className="flex min-h-36 flex-col items-center justify-center gap-2 rounded-xl border border-dashed p-5 text-center text-sm text-muted-foreground"
      >
        <Clock className="size-5" />이 기능은 서버에서 아직 준비 중이에요.
      </div>
    )
  if (query.error || query.data === undefined)
    return (
      <div
        role="alert"
        className="flex flex-col items-start gap-3 rounded-xl border border-destructive/20 bg-destructive/5 p-5"
      >
        <AlertCircle className="size-5 text-destructive" />
        <p>정보를 불러오지 못했어요. 연결과 접근 권한을 확인해 주세요.</p>
        <Button variant="outline" onClick={() => void query.refetch()}>
          다시 불러오기
        </Button>
      </div>
    )
  return children(query.data)
}
