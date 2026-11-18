import { useSyncExternalStore } from 'react'

// 이 폭(px) 이상이어야 앱 화면을 보여준다. 미만이면 PC 이용 안내(D40).
export const DESKTOP_MIN_WIDTH = 1024
const QUERY = `(min-width: ${DESKTOP_MIN_WIDTH}px)`

function subscribe(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const media = window.matchMedia(QUERY)
  media.addEventListener('change', onChange)
  return () => media.removeEventListener('change', onChange)
}

// matchMedia가 없는 환경(jsdom 등)에서는 데스크톱으로 본다.
function getSnapshot() {
  return typeof window.matchMedia !== 'function'
    ? true
    : window.matchMedia(QUERY).matches
}

/** 뷰포트가 데스크톱 폭인지. 창 크기가 바뀌면 새로고침 없이 갱신된다. */
export function useIsDesktop() {
  return useSyncExternalStore(subscribe, getSnapshot, () => true)
}
