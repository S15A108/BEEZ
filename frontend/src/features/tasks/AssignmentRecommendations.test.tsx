import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { beeApi, tasksApi } from '@/api/services'
import { AssignmentRecommendations } from './AssignmentRecommendations'

afterEach(() => vi.restoreAllMocks())

it('후보와 대안의 이유를 표시하고 담당자 변경은 호출하지 않는다', async () => {
  const suggest = vi.spyOn(beeApi, 'suggestAssignments').mockResolvedValue({
    suggestions: [
      {
        task_id: '501',
        member_id: '21',
        reason: '백엔드 담당 영역을 참고했어요.',
        alternatives: [
          { member_id: '22', reason: '현재 업무량을 참고했어요.' },
        ],
      },
    ],
    generation: {
      origin: 'MODEL',
      model: 'test',
      text: '제안',
      sources: [],
      unresolved: ['선행 작업을 확인해 주세요.'],
    },
  })
  const assign = vi.spyOn(tasksApi, 'assign')
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <AssignmentRecommendations
        projectId="101"
        taskId="501"
        members={[
          {
            id: '21',
            account_id: '11',
            name: '상은',
            role: 'MEMBER',
            agent_connected: false,
          },
          {
            id: '22',
            account_id: '12',
            name: '팀원',
            role: 'MEMBER',
            agent_connected: false,
          },
        ]}
      />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: '후보 추천받기' }))
  expect(await screen.findByText('우선 후보 · 상은')).toBeInTheDocument()
  expect(screen.getByText('백엔드 담당 영역을 참고했어요.')).toBeInTheDocument()
  expect(screen.getByText('대안 · 팀원')).toBeInTheDocument()
  expect(screen.getByText('현재 업무량을 참고했어요.')).toBeInTheDocument()
  expect(screen.getByText('선행 작업을 확인해 주세요.')).toBeInTheDocument()
  await waitFor(() =>
    expect(suggest).toHaveBeenCalledWith('101', { task_ids: ['501'] }),
  )
  expect(assign).not.toHaveBeenCalled()
})
