import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
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
        reason: '백엔드 담당 도메인을 참고했어요.',
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
  expect(screen.getByText('모델 추천')).toBeInTheDocument()
  expect(screen.getByText('제안')).toBeInTheDocument()
  expect(
    screen.getByText('백엔드 담당 도메인을 참고했어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText('대안 · 팀원')).toBeInTheDocument()
  expect(screen.getByText('현재 업무량을 참고했어요.')).toBeInTheDocument()
  expect(screen.getByText('선행 작업을 확인해 주세요.')).toBeInTheDocument()
  await waitFor(() =>
    expect(suggest).toHaveBeenCalledWith('101', { task_ids: ['501'] }),
  )
  expect(assign).not.toHaveBeenCalled()
})

it('템플릿 추천의 출처와 확인이 필요한 사항을 표시한다', async () => {
  vi.spyOn(beeApi, 'suggestAssignments').mockResolvedValue({
    suggestions: [
      {
        task_id: '501',
        member_id: '21',
        reason: '기본 규칙을 참고했어요.',
        alternatives: [],
      },
    ],
    generation: {
      origin: 'TEMPLATE',
      text: '기본 규칙으로 만든 추천입니다.',
      sources: [],
      unresolved: ['담당 도메인을 확인해 주세요.'],
    },
  })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AssignmentRecommendations projectId="101" taskId="501" members={[]} />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: '후보 추천받기' }))
  expect(await screen.findByText('템플릿 추천')).toBeInTheDocument()
  expect(screen.getByText('기본 규칙으로 만든 추천입니다.')).toBeInTheDocument()
  expect(screen.getByText('담당 도메인을 확인해 주세요.')).toBeInTheDocument()
  expect(screen.getByText('우선 후보 · 팀원 확인 필요')).toBeInTheDocument()
})

it('이 작업의 후보가 없으면 빈 결과와 확인할 사항을 표시한다', async () => {
  vi.spyOn(beeApi, 'suggestAssignments').mockResolvedValue({
    suggestions: [],
    generation: {
      origin: 'MODEL',
      text: '추천 결과가 없습니다.',
      sources: [],
      unresolved: ['작업을 다시 확인해 주세요.'],
    },
  })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AssignmentRecommendations projectId="101" taskId="501" members={[]} />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: '후보 추천받기' }))
  expect(
    await screen.findByText(
      '이 작업에 대한 담당 후보를 받지 못했어요. 다시 추천해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('작업을 다시 확인해 주세요.')).toBeInTheDocument()
  expect(screen.queryByLabelText('담당 후보 추천 결과')).not.toBeInTheDocument()
})

const apiError = (status: number, code: string) =>
  new AxiosError('failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: 'failed',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: 'x' } },
  })

it('서버에서 꺼 둔 기능이면 사용할 수 없다고 알리고 다시 누르지 못하게 한다', async () => {
  const suggest = vi
    .spyOn(beeApi, 'suggestAssignments')
    .mockRejectedValue(apiError(503, 'assignment_suggestion_disabled'))
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <AssignmentRecommendations projectId="101" taskId="501" members={[]} />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: '후보 추천받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '담당 후보 추천은 아직 사용할 수 없어요.',
  )
  expect(screen.getByRole('alert')).not.toHaveTextContent('다시')
  const button = screen.getByRole('button', { name: '후보 추천받기' })
  expect(button).toBeDisabled()
  fireEvent.click(button)
  expect(suggest).toHaveBeenCalledTimes(1)
})

it('일시 장애면 다시 시도하라고 알리고 버튼을 잠그지 않는다', async () => {
  vi.spyOn(beeApi, 'suggestAssignments').mockRejectedValue(
    apiError(502, 'ai_unavailable'),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <AssignmentRecommendations projectId="101" taskId="501" members={[]} />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: '후보 추천받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도')
  expect(screen.getByRole('button', { name: '후보 추천받기' })).toBeEnabled()
})
