import { fireEvent, render, screen } from '@testing-library/react'
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

// S15P31A108-371 4번(사용자 결정 11-10): 화면 안내가 '숫자 키로 메뉴 열기'인데 창이 열려 있으면 다른 번호가 먹지 않았다.
it('업무 창이 열려 있으면 다른 번호 키로 그 창으로 바꾸고, 열린 창의 번호 키로 닫는다', async () => {
  useWorkspaceStore.setState({ pane: 'tasks' })
  render(<Hotbar locked={[]} badges={{}} />)
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBe('changes')
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBeNull()
})

it('업무 창 안의 버튼에 포커스가 있어도 숫자 키로 다른 창으로 바꾸고, 잠긴 칸으로는 바꾸지 않는다', async () => {
  useWorkspaceStore.setState({ pane: 'tasks' })
  render(
    <>
      <Hotbar locked={['meetings']} badges={{}} />
      <button type="button">창 안 버튼</button>
    </>,
  )
  screen.getByRole('button', { name: '창 안 버튼' }).focus()
  await userEvent.keyboard('3')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBe('changes')
})

it('업무 창의 입력칸에 치는 숫자는 글자로 들어가고 창을 바꾸지 않는다', async () => {
  useWorkspaceStore.setState({ pane: 'tasks' })
  render(
    <>
      <Hotbar locked={[]} badges={{}} />
      <input aria-label="작업 이름" />
      <div aria-label="메모" contentEditable="plaintext-only" />
    </>,
  )
  await userEvent.click(screen.getByLabelText('작업 이름'))
  await userEvent.keyboard('2')
  expect(screen.getByLabelText('작업 이름')).toHaveValue('2')
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
  fireEvent.keyDown(screen.getByLabelText('메모'), { key: '2' })
  expect(useWorkspaceStore.getState().pane).toBe('tasks')
})

// 설정 창은 단축 바를 덮는다(단축 바가 위로 올라오지 않는다). 눌러서 못 옮기는 만큼 숫자 키로도 옮기지 않는다.
it('설정 창이 열려 있으면 숫자 키로 바꾸지 않는다', async () => {
  useWorkspaceStore.setState({ pane: 'settings' })
  render(<Hotbar locked={[]} badges={{}} />)
  await userEvent.keyboard('2')
  expect(useWorkspaceStore.getState().pane).toBe('settings')
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

// S15P31A108-285: 열린 창이 입력칸에 포커스를 주면 keydown 뒤의 글자 입력이 그 칸으로 들어갔다.
it('숫자 키로 창을 열어도 그 숫자가 창의 입력칸에 들어가지 않는다', async () => {
  function PaneInput() {
    const pane = useWorkspaceStore((state) => state.pane)
    return pane ? <input aria-label="검색어" autoFocus /> : null
  }
  render(
    <>
      <Hotbar locked={[]} badges={{}} />
      <PaneInput />
    </>,
  )
  await userEvent.keyboard('1')
  expect(useWorkspaceStore.getState().pane).toBe('documents')
  expect(screen.getByLabelText('검색어')).toHaveFocus()
  expect(screen.getByLabelText('검색어')).toHaveValue('')
})

// 사용자 요청(11-02): '여기부터 시작'으로 강조한 칸이 열려 있어도 흰 글자가 아니라 검정 글자로 보인다.
it('강조한 칸이 열려 있어도 글자와 번호를 검정으로 보인다', () => {
  useWorkspaceStore.setState({ pane: 'documents' })
  render(<Hotbar locked={[]} badges={{}} highlight="documents" />)
  const button = screen.getByRole('button', { name: '공용 문서함' })
  expect(button).toHaveAttribute('aria-current', 'page')
  expect(button).toHaveClass('text-foreground')
  expect(button).not.toHaveClass('text-white')
  expect(screen.getByText('1')).not.toHaveClass('text-white/85')
  expect(screen.getByText('여기부터 시작')).toBeInTheDocument()
})
