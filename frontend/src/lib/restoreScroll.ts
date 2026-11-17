/**
 * 저장한 스크롤 위치를 본문이 그만큼 길어진 뒤 한 번 적용한다(PR #321 리뷰).
 * 문서·목록을 늦게 받으면 붙는 순간에는 로딩 안내만 있어 scrollTop이 0으로 잘린다. 본문 크기가 바뀔 때마다 다시 보고,
 * 그사이 사람이 먼저 스크롤했거나 wait가 지나면 그만둔다. 그만두는 함수를 돌려준다.
 */
export function restoreScrollWhenReady(
  element: HTMLElement,
  top: number,
  wait = 3000,
): () => void {
  const ready = () => element.scrollHeight - element.clientHeight >= top
  if (top <= 0) return () => {}
  if (ready()) {
    element.scrollTop = top
    return () => {}
  }
  const observer = new ResizeObserver(() => {
    // 기다리는 동안 사람이 스크롤했으면 그 자리를 둔다.
    if (element.scrollTop !== 0) return stop()
    if (!ready()) return
    element.scrollTop = top
    stop()
  })
  for (const child of Array.from(element.children)) observer.observe(child)
  const timer = setTimeout(stop, wait)
  function stop() {
    observer.disconnect()
    clearTimeout(timer)
  }
  return stop
}
