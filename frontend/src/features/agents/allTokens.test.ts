import { afterEach, expect, it, vi } from 'vitest'
import { agentsApi } from '@/api/services'
import { allTokens } from './allTokens'

afterEach(() => vi.restoreAllMocks())

const token = (id: string, revoked = false) => ({
  id,
  project_id: '101',
  created_at: '2026-10-29T00:00:00Z',
  ...(revoked ? { revoked_at: '2026-10-29T01:00:00Z' } : {}),
})

it('has_more가 false가 될 때까지 다음 cursor로 이어 받는다', async () => {
  const myTokens = vi
    .spyOn(agentsApi, 'myTokens')
    .mockResolvedValueOnce({
      items: [token('9', true), token('8', true)],
      has_more: true,
      next_cursor: 'after-8',
    })
    .mockResolvedValueOnce({ items: [token('3')], has_more: false })
  expect((await allTokens('101')).map((item) => item.id)).toEqual([
    '9',
    '8',
    '3',
  ])
  expect(myTokens).toHaveBeenNthCalledWith(1, {
    project_id: '101',
    limit: 100,
    cursor: undefined,
  })
  expect(myTokens).toHaveBeenNthCalledWith(2, {
    project_id: '101',
    limit: 100,
    cursor: 'after-8',
  })
})

it('프로젝트를 주지 않으면 project_id 없이 내 모든 토큰을 받는다', async () => {
  const myTokens = vi
    .spyOn(agentsApi, 'myTokens')
    .mockResolvedValue({ items: [token('4')], has_more: false })
  expect(await allTokens()).toHaveLength(1)
  expect(myTokens).toHaveBeenCalledWith({ limit: 100, cursor: undefined })
})
