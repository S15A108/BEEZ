import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { changesApi, dashboardApi, planningApi } from '@/api/services'
import { useOfficeScreens } from './useOfficeScreens'

afterEach(() => vi.restoreAllMocks())

const summary = (id: string) => ({
  id,
  key: `CP-${id}`,
  title: `제안 ${id}`,
  status: 'IN_REVIEW',
})
const detail = (items: object[]) => ({ proposal: { items } }) as never

beforeEach(() => {
  vi.spyOn(dashboardApi, 'changes').mockResolvedValue({
    by_status: [],
    review_required_count: 0,
  } as never)
  vi.spyOn(dashboardApi, 'documents').mockResolvedValue({
    by_kind: [],
  } as never)
  vi.spyOn(changesApi, 'list').mockResolvedValue({
    items: [summary('71'), summary('72')],
    has_more: false,
  } as never)
  vi.spyOn(planningApi, 'items').mockResolvedValue({
    items: [{ design_item_id: '11', kind: 'REQUIREMENT' }],
    has_more: false,
  } as never)
})

function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return renderHook(
    ({ active }: { active: boolean }) => useOfficeScreens('101', 3, active),
    { wrapper, initialProps: { active: true } },
  )
}

// PR #269 리뷰 1: staleTime이 없어 업무 창을 닫을 때(active false → true)마다 승인 항목과 상세 N개를 다시 불렀다.
it('업무 창을 열었다 닫아도 받아 둔 승인 항목과 제안 상세는 다시 부르지 않는다', async () => {
  vi.spyOn(changesApi, 'get').mockResolvedValue(detail([]))
  const { result, rerender } = mount()
  await waitFor(() => expect(result.current.design).toBeDefined())
  const lists = vi.mocked(changesApi.list).mock.calls.length

  rerender({ active: false })
  rerender({ active: true })
  // 주기 재조회하는 목록은 다시 부르므로, 그 요청이 나간 뒤에 승인 항목·상세 요청 수를 본다.
  await waitFor(() =>
    expect(vi.mocked(changesApi.list).mock.calls.length).toBeGreaterThan(lists),
  )
  expect(planningApi.items).toHaveBeenCalledTimes(1)
  expect(changesApi.get).toHaveBeenCalledTimes(2)
})

// PR #269 리뷰 2: 상세 하나만 실패해도 every(Boolean)이 관계 TV 전체를 '기준 확인 전'에 묶었다.
it('제안 상세 하나가 실패하면 그 제안은 바꾸는 항목 없이 세고, 받는 중이면 기다린다', async () => {
  let fail: (error: Error) => void = () => {}
  vi.spyOn(changesApi, 'get').mockImplementation((_, id) =>
    id === '71'
      ? Promise.resolve(detail([{ operation: 'UPDATE', design_item_id: '11' }]))
      : new Promise((_, reject) => (fail = reject)),
  )
  const { result } = mount()
  await waitFor(() => expect(changesApi.get).toHaveBeenCalledTimes(2))
  // 받은 승인 항목·상세 71이 화면 값에 들어갈 틈을 준 뒤에도, 72를 받는 중이면 기다린다.
  await new Promise((resolve) => setTimeout(resolve, 50))
  expect(result.current.design).toBeUndefined()

  fail(new Error('상세를 받지 못함'))
  await waitFor(() => expect(result.current.design).toBeDefined())
  expect(result.current.design).toMatchObject({ total: 1, proposals: 2 })
  expect(result.current.design?.kinds[0]).toMatchObject({
    kind: 'REQUIREMENT',
    count: 1,
    touched: 1,
  })
})
