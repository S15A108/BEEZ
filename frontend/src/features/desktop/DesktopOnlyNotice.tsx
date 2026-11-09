import { useState } from 'react'
import { Hexagon } from 'lucide-react'
import { Button } from '@/components/ui/button'

// 좁은 화면(모바일)에서 앱 대신 보이는 안내. 모바일 레이아웃은 지원하지 않는다(D40).
export function DesktopOnlyNotice() {
  const [copy, setCopy] = useState<'idle' | 'done' | 'failed'>('idle')
  async function copyAddress() {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setCopy('done')
    } catch {
      setCopy('failed')
    }
  }
  return (
    <main className="grid min-h-dvh place-content-center justify-items-center gap-6 bg-background px-6 text-center text-foreground">
      <span className="grid size-12 place-items-center rounded-2xl bg-primary">
        <Hexagon
          className="size-7 text-primary-foreground"
          strokeWidth={2.5}
          aria-hidden="true"
        />
      </span>
      <div className="grid max-w-sm gap-3">
        <h1 className="text-2xl font-bold">PC에서 이용해 주세요</h1>
        <p className="text-sm break-keep text-muted-foreground">
          BEEZ는 팀이 승인한 설계와 다른 구현이 쌓이지 않게 돌려보내 주는
          서비스예요.
        </p>
        <p>
          화면이 좁아 이용할 수 없어요.
          <br /> PC 브라우저에서 열어 주세요.
        </p>
      </div>
      <Button type="button" className="min-h-11" onClick={copyAddress}>
        현재 주소 복사
      </Button>
      <p role="status" className="min-h-5 text-sm text-muted-foreground">
        {copy === 'done' && '주소를 복사했어요.'}
        {copy === 'failed' &&
          '복사하지 못했어요. 주소창에서 직접 복사해 주세요.'}
      </p>
    </main>
  )
}
