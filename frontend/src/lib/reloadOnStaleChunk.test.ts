import { afterEach, expect, it, vi } from 'vitest'
import { reloadOnStaleChunk } from './reloadOnStaleChunk'

const dispatchPreloadError = () => {
  const event = new Event('vite:preloadError', { cancelable: true })
  window.dispatchEvent(event)
  return event
}

afterEach(() => sessionStorage.clear())

it('청크를 받지 못하면 새로고침하고, 10초 안에 또 실패하면 새로고침하지 않고 에러를 그대로 둔다', () => {
  const reload = vi.fn()
  const stop = reloadOnStaleChunk(reload)

  expect(dispatchPreloadError().defaultPrevented).toBe(true)
  expect(dispatchPreloadError().defaultPrevented).toBe(false)
  expect(reload).toHaveBeenCalledTimes(1)

  stop()
})
