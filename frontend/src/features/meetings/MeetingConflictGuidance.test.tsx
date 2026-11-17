import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import type { MeetingConflictGuidance as Guidance } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { MeetingConflictGuidance } from './MeetingConflictGuidance'

const guidance: Guidance = {
  validation_id: '700',
  tasks: [
    { task_id: '502', title: '초대 화면 정보 바꾸기' },
    { task_id: '501', title: '프로젝트 목록 화면 구현' },
  ],
  conflicts: [
    {
      message: '두 작업이 만료된 초대 안내를 다르게 보여 줘요.',
      design_item: {
        id: '305',
        version_id: '315',
        key: 'REQ-005',
        title: '초대받은 사용자는 링크로 프로젝트에 참여한다',
        document_id: '203',
        document_version_id: '213',
      },
      differences: [],
    },
  ],
}
const keys: Record<string, string> = { '501': 'TASK-001', '502': 'TASK-002' }

function renderAgenda(
  props: Partial<Parameters<typeof MeetingConflictGuidance>[0]> = {},
) {
  render(
    <MeetingConflictGuidance
      summary="만료된 초대를 어떻게 안내할지 정해 주세요"
      taskIds={['501', '502']}
      guidance={guidance}
      closed={false}
      taskKeyOf={(id) => keys[id]}
      documentTitleOf={() => '요구사항 명세서'}
      {...props}
    />,
  )
  return screen.getByRole('region', { name: '안건' })
}

// 사용자 요청 11-17(S15P31A108-457): 안내는 처음부터 펼쳐 두고, '정할 것'은 알약, 접기·펼치기는 상자 버튼이다.
it('처음부터 펼쳐져 정할 질문·걸린 작업·요구사항 번호와 비비의 충돌 안내 전문이 보인다', () => {
  const agenda = renderAgenda()
  expect(within(agenda).getByText('정할 것')).toHaveClass('rounded-full')
  expect(agenda).toHaveTextContent('만료된 초대를 어떻게 안내할지 정해 주세요')
  expect(
    within(agenda).getByRole('button', { name: 'TASK-001' }),
  ).toBeInTheDocument()
  expect(
    within(agenda).getByRole('button', { name: 'TASK-002' }),
  ).toBeInTheDocument()
  expect(agenda).toHaveTextContent('REQ-005')
  expect(within(agenda).getByRole('button', { name: '접기' })).toHaveAttribute(
    'aria-expanded',
    'true',
  )
  expect(
    screen.getByText('두 작업이 만료된 초대 안내를 다르게 보여 줘요.'),
  ).toBeInTheDocument()
  expect(screen.getByText('회의에서 정할 내용')).toBeInTheDocument()
})

it('접기를 누르면 정할 것 한 줄만 남고 펼치기로 다시 펼친다', async () => {
  const user = userEvent.setup()
  renderAgenda()
  await user.click(screen.getByRole('button', { name: '접기' }))
  expect(
    screen.queryByRole('region', { name: '비비의 충돌 안내' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '펼치기' }))
  const detail = screen.getByRole('region', { name: '비비의 충돌 안내' })
  expect(detail).toHaveTextContent(
    '두 작업이 만료된 초대 안내를 다르게 보여 줘요.',
  )
  expect(detail).toHaveTextContent('회의에서 정할 내용')
})

it('충돌 안내가 없으면 비비가 연 첫 메시지 전문을 보인다', () => {
  renderAgenda({
    guidance: undefined,
    brief: '작업이 부딪혀서 회의를 열었어요.',
  })
  expect(
    screen.getByRole('region', { name: '비비의 충돌 안내' }),
  ).toHaveTextContent('작업이 부딪혀서 회의를 열었어요.')
})

it('걸린 작업 번호를 누르면 그 작업을 연다', async () => {
  const user = userEvent.setup()
  renderAgenda()
  await user.click(screen.getByRole('button', { name: 'TASK-002' }))
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: 'tasks',
    focusId: '502',
  })
})

it('끝난 회의에 펼쳐 볼 안내가 없으면 펼치기를 두지 않는다', () => {
  const agenda = renderAgenda({ guidance: undefined, closed: true })
  expect(
    within(agenda).queryByRole('button', { name: /펼치기|접기/ }),
  ).not.toBeInTheDocument()
  expect(
    within(agenda).queryByRole('region', { name: '비비의 충돌 안내' }),
  ).not.toBeInTheDocument()
})
