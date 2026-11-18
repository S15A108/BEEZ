import { act, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { mockViewport } from '@/test/matchMedia'
import { useIsDesktop } from './useIsDesktop'

afterEach(() => vi.unstubAllGlobals())

it.each([
  [390, false],
  [1023, false],
  [1024, true],
  [1920, true],
])('폭 %ipx 에서 데스크톱은 %s', (width, expected) => {
  mockViewport(width)
  expect(renderHook(() => useIsDesktop()).result.current).toBe(expected)
})

it('matchMedia가 없으면 데스크톱으로 본다', () => {
  vi.stubGlobal('matchMedia', undefined)
  expect(renderHook(() => useIsDesktop()).result.current).toBe(true)
})

it('폭이 바뀌면 새로고침 없이 값이 바뀌고, 언마운트하면 구독을 푼다', () => {
  const viewport = mockViewport(800)
  const { result, unmount } = renderHook(() => useIsDesktop())
  expect(result.current).toBe(false)
  act(() => viewport.resize(1280))
  expect(result.current).toBe(true)
  act(() => viewport.resize(1000))
  expect(result.current).toBe(false)
  unmount()
  expect(viewport.listenerCount()).toBe(0)
})
