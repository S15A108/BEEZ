import { expect, it, vi } from 'vitest'
import { BATCH_LIMIT, sendInBatches } from './sendInBatches'
import type { BatchCandidatesInput, BatchCandidatesResponse } from './schemas'

const input = (count: number): BatchCandidatesInput => ({
  items: Array.from({ length: count }, (_, index) => ({
    candidate_id: String(index + 1),
  })),
})

const adoptAll = (part: BatchCandidatesInput): BatchCandidatesResponse => ({
  results: part.items.map((item) => ({
    candidate_id: item.candidate_id,
    result: 'ADOPTED',
  })),
  succeeded: part.items.length,
  failed: 0,
})

it('상한 이하면 한 번만 보내고 응답을 그대로 돌려준다', async () => {
  const send = vi.fn((part: BatchCandidatesInput) =>
    Promise.resolve(adoptAll(part)),
  )
  const response = await sendInBatches(input(BATCH_LIMIT), send)
  expect(send).toHaveBeenCalledTimes(1)
  expect(response.succeeded).toBe(BATCH_LIMIT)
  expect(response.failed).toBe(0)
})

it('상한을 넘으면 상한만큼 나눠 차례로 보내고 결과를 합친다', async () => {
  const send = vi.fn((part: BatchCandidatesInput) =>
    Promise.resolve(adoptAll(part)),
  )
  const response = await sendInBatches(input(85), send)
  expect(send.mock.calls.map(([part]) => part.items.length)).toEqual([50, 35])
  expect(send.mock.calls[1][0].items[0].candidate_id).toBe('51')
  expect(response.succeeded).toBe(85)
  expect(response.failed).toBe(0)
  expect(response.results).toHaveLength(85)
})

it('묶음 안의 건별 실패는 그대로 합쳐진다', async () => {
  const send = vi.fn((part: BatchCandidatesInput) =>
    Promise.resolve<BatchCandidatesResponse>({
      results: part.items.map((item) =>
        item.candidate_id === '60'
          ? {
              candidate_id: '60',
              result: 'FAILED',
              error_code: 'candidate_stale',
            }
          : { candidate_id: item.candidate_id, result: 'ADOPTED' },
      ),
      succeeded: part.items.length - (part.items.length === 10 ? 1 : 0),
      failed: part.items.length === 10 ? 1 : 0,
    }),
  )
  const response = await sendInBatches(input(60), send)
  expect(response.succeeded).toBe(59)
  expect(response.failed).toBe(1)
  expect(
    response.results.filter((result) => result.result === 'FAILED'),
  ).toEqual([
    { candidate_id: '60', result: 'FAILED', error_code: 'candidate_stale' },
  ])
})

it('첫 묶음이 실패하면 처리된 것이 없으므로 오류를 그대로 던진다', async () => {
  const send = vi.fn(() => Promise.reject(new Error('down')))
  await expect(sendInBatches(input(60), send)).rejects.toThrow('down')
  expect(send).toHaveBeenCalledTimes(1)
})

it('뒤 묶음이 실패하면 멈추고 보내지 못한 후보를 실패로 돌려준다', async () => {
  const send = vi
    .fn<(part: BatchCandidatesInput) => Promise<BatchCandidatesResponse>>()
    .mockImplementationOnce((part) => Promise.resolve(adoptAll(part)))
    .mockRejectedValueOnce(new Error('down'))
  const response = await sendInBatches(input(120), send)
  // 51~120번은 보내지 못했다. 세 번째 묶음은 시도하지 않는다.
  expect(send).toHaveBeenCalledTimes(2)
  expect(response.succeeded).toBe(50)
  expect(response.failed).toBe(70)
  const unsent = response.results.filter((result) => result.result === 'FAILED')
  expect(unsent).toHaveLength(70)
  expect(unsent[0].candidate_id).toBe('51')
  expect(unsent[0].message).toBeTruthy()
})
