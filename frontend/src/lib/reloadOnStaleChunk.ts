const STORAGE_KEY = 'beez:stale-chunk-reload-at'
const MIN_INTERVAL_MS = 10_000

/**
 * 새 배포로 예전 해시의 청크가 사라지면, 배포 전에 열어 둔 탭은 다른 화면으로 갈 때 청크를 받지 못한다.
 * 그때 새로고침해 새 index.html을 받는다. 청크가 정말 없을 때 무한 새로고침이 되지 않게 10초에 한 번만 한다.
 */
export function reloadOnStaleChunk(reload = () => window.location.reload()) {
  const onPreloadError = (event: Event) => {
    const now = Date.now()
    const last = Number(sessionStorage.getItem(STORAGE_KEY))
    if (now - last < MIN_INTERVAL_MS) return
    sessionStorage.setItem(STORAGE_KEY, String(now))
    event.preventDefault()
    reload()
  }
  window.addEventListener('vite:preloadError', onPreloadError)
  return () => window.removeEventListener('vite:preloadError', onPreloadError)
}
