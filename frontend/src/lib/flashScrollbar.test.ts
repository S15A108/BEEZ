import { afterEach, expect, it, vi } from 'vitest'
import { flashScrollbar } from './flashScrollbar'

afterEach(() => {
  vi.useRealTimers()
})

it('스크롤하는 동안 표시를 붙이고, 멈추고 잠시 뒤에 뗀다', () => {
  vi.useFakeTimers()
  const list = document.createElement('div')
  flashScrollbar(list)
  expect(list).toHaveAttribute('data-scrolling')
  vi.advanceTimersByTime(500)
  // 계속 스크롤하면 떼는 시각을 미룬다.
  flashScrollbar(list)
  vi.advanceTimersByTime(500)
  expect(list).toHaveAttribute('data-scrolling')
  vi.advanceTimersByTime(300)
  expect(list).not.toHaveAttribute('data-scrolling')
})
