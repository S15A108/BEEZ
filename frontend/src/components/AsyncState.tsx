import type { ReactNode } from 'react'
import { AlertCircle, LoaderCircle } from 'lucide-react'
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
