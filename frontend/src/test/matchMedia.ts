import { vi } from 'vitest'

// 폭을 정하고 바꿀 수 있는 window.matchMedia 대역. `(min-width: Npx)` 질의만 안다.
export function mockViewport(initial: number) {
  let width = initial
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => {
    const min = Number(/min-width:\s*(\d+)px/.exec(query)?.[1] ?? 0)
    return {
      get matches() {
        return width >= min
      },
      media: query,
      addEventListener: (_: string, fn: () => void) => listeners.add(fn),
      removeEventListener: (_: string, fn: () => void) => listeners.delete(fn),
    }
  })
  return {
    resize(next: number) {
      width = next
      listeners.forEach((fn) => fn())
    },
    listenerCount: () => listeners.size,
  }
}
