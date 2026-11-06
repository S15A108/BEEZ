import { AxiosError, AxiosHeaders } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { beeApi } from '@/api/services'
import { beeStatusQuery } from './beeStatusQuery'

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const httpError = (status: number, message?: string) =>
  new AxiosError('error', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code: 'x', message } },
  })

// 비비 상태를 1분 30초 동안 지켜보면서 BEE-A04를 몇 번 불렀는지 센다.
async function callsIn90Seconds(respond: () => Promise<unknown>) {
  const status = vi
    .spyOn(beeApi, 'status')
    .mockImplementation(respond as typeof beeApi.status)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const unsubscribe = new QueryObserver(
    client,
    beeStatusQuery('101'),
  ).subscribe(() => {})
  await vi.advanceTimersByTimeAsync(90_000)
  unsubscribe()
  return status.mock.calls.length
}

it('비비 상태를 받으면 30초마다 다시 읽는다', async () => {
  expect(
    await callsIn90Seconds(() => Promise.resolve({ activity: 'IDLE' })),
  ).toBe(4)
})

it('서버에 비비 상태 API가 없으면(null) 다시 읽지 않는다', async () => {
  expect(
    await callsIn90Seconds(() =>
      Promise.reject(httpError(404, MISSING_API_MESSAGE)),
    ),
  ).toBe(1)
})

it('다시 불러도 같은 4xx 오류면 다시 읽지 않는다', async () => {
  expect(await callsIn90Seconds(() => Promise.reject(httpError(403)))).toBe(1)
})

it('서버 오류(5xx)는 잠깐일 수 있어 30초마다 계속 다시 읽는다', async () => {
  expect(await callsIn90Seconds(() => Promise.reject(httpError(503)))).toBe(4)
})

it('응답을 못 받은 네트워크 오류도 30초마다 계속 다시 읽는다', async () => {
  expect(
    await callsIn90Seconds(() =>
      Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK')),
    ),
  ).toBe(4)
})
