import { AxiosError } from 'axios'
import { afterEach, expect, it, vi } from 'vitest'
import { planningApi } from '@/api/services'
import { readAllDrafts } from './useProjectStart'

afterEach(() => vi.restoreAllMocks())

const draft = (id: string) => ({ id }) as never

// PR #72 전수 리뷰 AMP-05: 초안이 100개를 넘어도 모든 페이지를 같은 draft_snapshot_hash로 읽어 시작에 보낸다.
it('설계 초안을 다음 페이지까지 모두 읽는다', async () => {
  const items = vi
    .spyOn(planningApi, 'items')
    .mockResolvedValueOnce({
      items: [draft('1'), draft('2')],
      has_more: true,
      next_cursor: 'c2',
      draft_snapshot_hash: 'h1',
    })
    .mockResolvedValueOnce({
      items: [draft('3')],
      has_more: false,
      draft_snapshot_hash: 'h1',
    })
  const result = await readAllDrafts('10')
  expect(result.items.map((item) => item.id)).toEqual(['1', '2', '3'])
  expect(result.draft_snapshot_hash).toBe('h1')
  expect(items).toHaveBeenLastCalledWith('10', {
    status: 'DRAFT',
    limit: 100,
    cursor: 'c2',
  })
})

it('읽는 사이 초안 해시가 바뀌면 처음부터 다시 읽는다', async () => {
  vi.spyOn(planningApi, 'items')
    .mockResolvedValueOnce({
      items: [draft('1')],
      has_more: true,
      next_cursor: 'c2',
      draft_snapshot_hash: 'old',
    })
    .mockResolvedValueOnce({
      items: [draft('2')],
      has_more: false,
      draft_snapshot_hash: 'new',
    })
    .mockResolvedValueOnce({
      items: [draft('1'), draft('2')],
      has_more: false,
      draft_snapshot_hash: 'new',
    })
  const result = await readAllDrafts('10')
  expect(result.items.map((item) => item.id)).toEqual(['1', '2'])
  expect(result.draft_snapshot_hash).toBe('new')
})

// PR #72 mock/live 전수 대조 DOC-LIVE-4: 뒤 페이지가 409 draft_changed면 처음부터 다시 읽고, 세 번에서 멈춘다.
it('뒤 페이지가 409 draft_changed면 처음부터 다시 읽는다', async () => {
  const changed = new AxiosError('conflict', '409', undefined, undefined, {
    status: 409,
    data: { error: { code: 'draft_changed', message: '바뀜' } },
  } as never)
  vi.spyOn(planningApi, 'items')
    .mockResolvedValueOnce({
      items: [draft('1')],
      has_more: true,
      next_cursor: 'c2',
      draft_snapshot_hash: 'old',
    })
    .mockRejectedValueOnce(changed)
    .mockResolvedValueOnce({
      items: [draft('1'), draft('2')],
      has_more: false,
      draft_snapshot_hash: 'new',
    })
  const result = await readAllDrafts('10')
  expect(result.items.map((item) => item.id)).toEqual(['1', '2'])
})

it('초안이 계속 바뀌면 세 번 다시 읽은 뒤 멈추고 알린다', async () => {
  const changed = new AxiosError('conflict', '409', undefined, undefined, {
    status: 409,
    data: { error: { code: 'draft_changed', message: '바뀜' } },
  } as never)
  const items = vi
    .spyOn(planningApi, 'items')
    .mockImplementation(async (_projectId, query) => {
      if (query?.cursor) throw changed
      return {
        items: [draft('1')],
        has_more: true,
        next_cursor: 'c2',
        draft_snapshot_hash: 'h',
      }
    })
  await expect(readAllDrafts('10')).rejects.toThrow('설계 초안이 바뀌는 중')
  expect(items).toHaveBeenCalledTimes(6)
})
