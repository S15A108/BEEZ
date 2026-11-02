import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it } from 'vitest'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { Hotbar } from './Hotbar'

beforeEach(() => useWorkspaceStore.setState({ pane: null, focusId: null }))

it('숫자 키로 단축 바 칸을 열고 잠긴 칸은 열지 않는다', async () => {
  render(<Hotbar locked={['changes']} badges={{}} />)
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBeNull()
  await userEvent.keyboard('4')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
})

it('잠긴 칸을 눌러도 창이 열리지 않고 이유를 알려 준다', async () => {
  render(<Hotbar locked={['relations']} badges={{ changes: 2 }} />)
  const locked = screen.getByRole('button', { name: '설계 관계' })
  expect(locked).toHaveAttribute('title', '문서 4종을 올리면 열려요')
  await userEvent.click(locked)
  expect(useWorkspaceStore.getState().pane).toBeNull()
  await userEvent.click(screen.getByRole('button', { name: '비비와 대화' }))
  expect(useWorkspaceStore.getState().pane).toBe('chat')
})

it('잠긴 칸의 이유는 부르는 쪽이 정한다', () => {
  render(
    <Hotbar
      locked={['tasks']}
      badges={{}}
      lockedReason="기준 v1이 생기면 열려요"
    />,
  )
  expect(screen.getByRole('button', { name: '작업 현황' })).toHaveAttribute(
    'title',
    '기준 v1이 생기면 열려요',
  )
})

it('회의실에도 변경 제안과 같은 숫자 배지를 붙인다', () => {
  render(<Hotbar locked={[]} badges={{ changes: 2, meetings: 1 }} />)
  const meetings = screen.getByRole('button', { name: '회의실' })
  expect(meetings).toContainElement(screen.getByLabelText('새 항목 1개'))
  expect(screen.getByLabelText('새 항목 1개')).toHaveTextContent('1')
  expect(screen.getByRole('button', { name: '변경 제안' })).toContainElement(
    screen.getByLabelText('새 항목 2개'),
  )
})

it('배지가 0이면 숫자를 붙이지 않는다', () => {
  render(<Hotbar locked={[]} badges={{ changes: 0, meetings: 0 }} />)
  expect(screen.queryByLabelText(/새 항목/)).not.toBeInTheDocument()
})

it('업무 창이 열려 있으면 그 창의 번호 키로 닫고, 다른 번호로는 열지 않는다', async () => {
  useWorkspaceStore.setState({ pane: 'tasks' })
  render(<Hotbar locked={[]} badges={{}} />)
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  await userEvent.keyboard('4')
  expect(useWorkspaceStore.getState().pane).toBeNull()
})

// D안(캔버스 v437~438): 칸을 하나씩 떼어 두고 비비를 가운데(3번과 4번 사이)에 둔다.
it('단축 바는 비비를 가운데에 두고 칸마다 이름을 보인다', () => {
  render(<Hotbar locked={[]} badges={{}} />)
  expect(
    screen
      .getAllByRole('button')
      .map((button) => button.getAttribute('aria-label')),
  ).toEqual([
    '공용 문서함',
    '변경 제안',
    '회의실',
    '비비와 대화',
    '작업 현황',
    '설계 관계',
    '대시보드',
  ])
  expect(screen.getByText('작업 현황')).toBeVisible()
})

// PR #72 전수 리뷰 UI-1: 환영 창·둘러보기가 열린 동안에는 숫자 키가 뒤 화면 창을 열지 않는다.
it('가이드가 열린 동안에는 숫자 키로 창을 열지 않는다', async () => {
  render(<Hotbar locked={[]} badges={{}} shortcutsOff />)
  await userEvent.keyboard('4')
  expect(useWorkspaceStore.getState().pane).toBeNull()
})
