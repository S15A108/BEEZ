import { renderHook } from '@testing-library/react'
import { AxiosError } from 'axios'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { presenceApi } from '@/api/services/presence'
import { PRESENCE_REPORT_MS } from '@/lib/pollIntervals'
import { usePresenceReporter } from './usePresenceReporter'

vi.mock('@/api/services/presence', () => ({
  presenceApi: { report: vi.fn() },
}))

const report = vi.mocked(presenceApi.report)
const visibility = vi.spyOn(document, 'visibilityState', 'get')

beforeEach(() => {
  vi.useFakeTimers()
  report.mockReset().mockResolvedValue(undefined)
  visibility.mockReturnValue('visible')
})
afterEach(() => {
  vi.useRealTimers()
})

it('열자마자 한 번 보내고 15초마다 현재 값을 다시 보낸다', () => {
  renderHook(() => usePresenceReporter('101', '7', { zone: 'dev' }))
  expect(report).toHaveBeenCalledTimes(1)
  expect(report).toHaveBeenLastCalledWith('101', { zone: 'dev' })

  vi.advanceTimersByTime(PRESENCE_REPORT_MS)
  expect(report).toHaveBeenCalledTimes(2)
  vi.advanceTimersByTime(PRESENCE_REPORT_MS * 2)
  expect(report).toHaveBeenCalledTimes(4)
})

it('위치나 메뉴가 바뀌면 주기를 기다리지 않고 바로 보낸다', () => {
  const { rerender } = renderHook(
    ({ panel }) => usePresenceReporter('101', '7', { zone: 'dev', panel }),
    { initialProps: { panel: undefined as 'tasks' | undefined } },
  )
  expect(report).toHaveBeenCalledTimes(1)
  rerender({ panel: 'tasks' })
  expect(report).toHaveBeenCalledTimes(2)
  expect(report).toHaveBeenLastCalledWith('101', {
    zone: 'dev',
    panel: 'tasks',
  })
  // 같은 값으로 다시 그려도 보내지 않는다
  rerender({ panel: 'tasks' })
  expect(report).toHaveBeenCalledTimes(2)
})

it('탭이 가려져 있으면 보내지 않고 다시 보이면 바로 보낸다', () => {
  visibility.mockReturnValue('hidden')
  renderHook(() => usePresenceReporter('101', '7', {}))
  vi.advanceTimersByTime(PRESENCE_REPORT_MS * 3)
  expect(report).not.toHaveBeenCalled()

  visibility.mockReturnValue('visible')
  document.dispatchEvent(new Event('visibilitychange'))
  expect(report).toHaveBeenCalledTimes(1)
})

it('로그인 정보가 없으면 보내지 않고 닫으면 멈춘다', () => {
  renderHook(() => usePresenceReporter('101', undefined, {}))
  expect(report).not.toHaveBeenCalled()

  const { unmount } = renderHook(() => usePresenceReporter('101', '7', {}))
  expect(report).toHaveBeenCalledTimes(1)
  unmount()
  vi.advanceTimersByTime(PRESENCE_REPORT_MS * 3)
  expect(report).toHaveBeenCalledTimes(1)
})

it('보내기에 실패해도 다음 주기에 다시 보낸다', async () => {
  report.mockRejectedValueOnce(new Error('network'))
  renderHook(() => usePresenceReporter('101', '7', {}))
  await vi.advanceTimersByTimeAsync(PRESENCE_REPORT_MS)
  expect(report).toHaveBeenCalledTimes(2)
})

it('서버에 없는 API(501)면 더 보내지 않는다', async () => {
  const missing = new AxiosError('x', '501', undefined, undefined, {
    status: 501,
    data: {},
    statusText: '',
    headers: {},
    config: { headers: {} as never },
  })
  report.mockRejectedValueOnce(missing)
  renderHook(() => usePresenceReporter('101', '7', {}))
  await vi.advanceTimersByTimeAsync(PRESENCE_REPORT_MS * 3)
  expect(report).toHaveBeenCalledTimes(1)
})
