const timers = new WeakMap<Element, ReturnType<typeof setTimeout>>()

/**
 * 스크롤한 칸에 data-scrolling을 붙였다가 멈추고 hide ms 뒤에 뗀다. 업무 창 스크롤바는 이 동안만 보인다(S15P31A108-455, index.css).
 * scroll 이벤트는 거품이 일지 않아 업무 창이 onScrollCapture로 받아 넘긴다.
 */
export function flashScrollbar(target: EventTarget | null, hide = 800) {
  if (!(target instanceof HTMLElement)) return
  target.dataset.scrolling = ''
  clearTimeout(timers.get(target))
  timers.set(
    target,
    setTimeout(() => delete target.dataset.scrolling, hide),
  )
}
