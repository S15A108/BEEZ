import { useEffect, useRef } from 'react'
import { isMissingApi } from '@/api/error'
import { presenceApi } from '@/api/services/presence'
import { PRESENCE_REPORT_MS } from '@/lib/pollIntervals'
import type { PresenceReport } from './schemas'

/**
 * 워크스페이스를 연 동안 내 위치를 주기적으로 보고한다(MON-A06). 탭이 가려져 있으면 보내지 않아
 * 탭을 닫거나 오래 가려 두면 서버 TTL(45초) 뒤 오프라인이 된다. 다시 보이면 바로 보낸다.
 * 위치·메뉴가 바뀌면 바로 보낸다. 실패(429 포함)는 다음 주기에 다시 보내면 되므로 화면에 알리지 않는다.
 * 서버에 없는 API(404·405·501)면 더 보내지 않는다.
 */
export function usePresenceReporter(
  projectId: string,
  accountId: string | undefined,
  report: PresenceReport,
) {
  const latest = useRef(report)
  const send = useRef<() => void>(() => undefined)
  const key = JSON.stringify(report)

  useEffect(() => {
    latest.current = report
    send.current()
    // report 의 내용(key)이 바뀔 때만 다시 보낸다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  useEffect(() => {
    if (!accountId) return
    let stopped = false
    const post = () => {
      if (stopped || document.visibilityState === 'hidden') return
      presenceApi.report(projectId, latest.current).catch((error: unknown) => {
        if (isMissingApi(error)) stopped = true
      })
    }
    send.current = post
    post()
    const timer = window.setInterval(post, PRESENCE_REPORT_MS)
    document.addEventListener('visibilitychange', post)
    return () => {
      stopped = true
      send.current = () => undefined
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', post)
    }
  }, [projectId, accountId])
}
