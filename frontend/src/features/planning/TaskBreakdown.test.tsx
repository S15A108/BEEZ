import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import { afterEach, expect, it, vi } from 'vitest'
import { tasksApi } from '@/api/services'
import type { BreakdownJob } from '@/features/tasks/schemas'
import { TaskBreakdown } from './TaskBreakdown'

const running = { id: '41', baseline_id: '10', status: 'RUNNING' } as const
const completed = {
  ...running,
  status: 'COMPLETED',
  tasks: [{ id: '51', key: 'T-1', title: '재고 API 구현' }],
} satisfies BreakdownJob

function renderBreakdown() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const refresh = vi.spyOn(client, 'invalidateQueries')
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={client}>
      <TaskBreakdown projectId="101" />
    </QueryClientProvider>,
  )
  return { user, client, refresh }
}

afterEach(() => vi.restoreAllMocks())

it('화면을 다시 열면 서버의 실행을 이어 보고, 완료 시 목록과 알림을 한 번 갱신한다', async () => {
  let job: BreakdownJob = running
  vi.spyOn(tasksApi, 'breakdownStatus').mockImplementation(async () => job)
  const start = vi
    .spyOn(tasksApi, 'generateBreakdown')
    .mockResolvedValue(running)
  const { client, refresh } = renderBreakdown()

  expect(await screen.findByText('작업을 만드는 중…')).toBeInTheDocument()
  expect(start).not.toHaveBeenCalled()
  job = completed

  expect(await screen.findByText(/작업 1건을 준비했어요/)).toBeInTheDocument()
  await waitFor(() => {
    expect(refresh).toHaveBeenCalledWith({
      queryKey: ['projects', '101', 'tasks'],
    })
    expect(refresh).toHaveBeenCalledWith({ queryKey: ['notifications'] })
  })
  await act(() =>
    client.refetchQueries({ queryKey: ['projects', '101', 'task-breakdown'] }),
  )
  expect(
    refresh.mock.calls.filter(
      ([filter]) => filter?.queryKey?.[0] === 'notifications',
    ),
  ).toHaveLength(1)
})

it('서버가 재시도할 수 있는 실패를 반환하면 사용자가 다시 접수한다', async () => {
  let job: BreakdownJob = {
    ...running,
    status: 'FAILED',
    error_code: 'ai_timeout',
    retryable: true,
  }
  vi.spyOn(tasksApi, 'breakdownStatus').mockImplementation(async () => job)
  const start = vi
    .spyOn(tasksApi, 'generateBreakdown')
    .mockImplementation(async () => {
      job = { ...running, id: '42' }
      return job
    })
  const { user } = renderBreakdown()

  expect(
    await screen.findByText(/분석 시간이 초과되었습니다/),
  ).toBeInTheDocument()
  expect(start).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '작업 생성 다시 시도' }))
  expect(await screen.findByText('작업을 만드는 중…')).toBeInTheDocument()
  expect(start).toHaveBeenCalledOnce()
})

it('접수 응답을 잃으면 생성 요청을 재전송하기 전에 상태를 조회한다', async () => {
  const offline = new AxiosError('Network Error')
  const status = vi
    .spyOn(tasksApi, 'breakdownStatus')
    .mockResolvedValueOnce(null)
    .mockRejectedValueOnce(offline)
    .mockResolvedValue(running)
  const start = vi
    .spyOn(tasksApi, 'generateBreakdown')
    .mockRejectedValue(offline)
  const { user } = renderBreakdown()

  await user.click(
    await screen.findByRole('button', { name: '작업 만들고 배정하기' }),
  )
  const refresh = await screen.findByRole('button', {
    name: '작업 상태 다시 불러오기',
  })
  expect(status).toHaveBeenCalledTimes(2)
  expect(
    screen.queryByRole('button', { name: '작업 만들고 배정하기' }),
  ).not.toBeInTheDocument()
  await user.click(refresh)

  expect(await screen.findByText('작업을 만드는 중…')).toBeInTheDocument()
  expect(start).toHaveBeenCalledOnce()
})
