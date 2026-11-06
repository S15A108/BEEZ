import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { RelationGraph } from './RelationGraph'
import type { RelationsResponse } from './schemas'

const data: RelationsResponse = {
  nodes: [
    { id: '501', type: 'TASK', label: 'TASK-001 목록 화면' },
    { id: '301', type: 'DESIGN_ITEM', label: 'REQ-001' },
    { id: '12', type: 'VALIDATION', label: '계획 검증 PASS' },
    { id: '71', type: 'CHANGE_PROPOSAL', label: '빈 화면 안내' },
  ],
  edges: [
    {
      from_id: '501',
      from_type: 'TASK',
      to_id: '301',
      to_type: 'DESIGN_ITEM',
      relation: 'IMPLEMENTS',
      confirmed: true,
    },
    {
      from_id: '501',
      from_type: 'TASK',
      to_id: '12',
      to_type: 'VALIDATION',
      relation: 'VALIDATED_BY',
      confirmed: true,
    },
    {
      from_id: '71',
      from_type: 'CHANGE_PROPOSAL',
      to_id: '301',
      to_type: 'DESIGN_ITEM',
      relation: 'CHANGES',
      confirmed: false,
    },
  ],
}

function graph(props: RelationsResponse = data, onOpen = vi.fn()) {
  render(<RelationGraph data={props} onOpen={onOpen} />)
  return { svg: screen.getByRole('img', { name: /관계 그래프/ }), onOpen }
}

it('선 위에 관계 이름을 쓰고 후보는 후보로 쓴다', () => {
  const { svg } = graph()
  expect(within(svg).getByText('구현')).toBeInTheDocument()
  // 열 제목, 상자 안 종류, 선 위 이름
  expect(within(svg).getAllByText('검증')).toHaveLength(3)
  expect(within(svg).getByText('후보')).toBeInTheDocument()
})

it('확인된 관계는 실선, 후보는 점선으로 그린다', () => {
  const { svg } = graph()
  const line = (title: RegExp) =>
    within(svg)
      .getByText(title, { selector: 'title' })
      .parentElement?.querySelector('path')
  expect(line(/REQ-001: 구현/)).not.toHaveAttribute('stroke-dasharray')
  expect(line(/REQ-001: 후보/)).toHaveAttribute('stroke-dasharray')
})

it('상자마다 테두리 색과 함께 종류를 글로 쓰고 종류가 다르면 색도 다르다', () => {
  const { svg } = graph()
  const box = (label: string) =>
    within(svg).getByText(label).closest('g')?.querySelector('rect')
  const colors = data.nodes.map((node) =>
    box(node.label)?.getAttribute('stroke'),
  )
  expect(new Set(colors).size).toBe(4)
  for (const name of ['작업', '설계 항목', '변경 제안']) {
    // 열 제목과 상자 안 이름
    expect(within(svg).getAllByText(name)).toHaveLength(2)
  }
})

it('접근성 이름으로 색 없이도 종류와 관계를 읽을 수 있다', () => {
  const { svg } = graph()
  expect(svg).toHaveAccessibleName(
    expect.stringContaining(
      '작업 TASK-001 목록 화면 → 설계 항목 REQ-001: 구현',
    ),
  )
  expect(svg).toHaveAccessibleName(
    expect.stringContaining(
      '변경 제안 빈 화면 안내 → 설계 항목 REQ-001: 후보, 확정 아님',
    ),
  )
})

it('검증을 포함해 상자를 누르면 그 종류와 번호로 화면을 연다', async () => {
  const user = userEvent.setup()
  const { svg, onOpen } = graph()
  await user.click(within(svg).getByText('계획 검증 PASS'))
  expect(onOpen).toHaveBeenLastCalledWith('VALIDATION', '12')
  await user.click(within(svg).getByText('REQ-001'))
  expect(onOpen).toHaveBeenLastCalledWith('DESIGN_ITEM', '301')
})

it('키보드로도 전체 목록의 버튼에서 같은 화면을 연다', async () => {
  const user = userEvent.setup()
  const { onOpen } = graph()
  await user.click(screen.getByText('전체 항목과 관계 읽기'))
  await user.click(screen.getByRole('button', { name: '계획 검증 PASS' }))
  expect(onOpen).toHaveBeenCalledWith('VALIDATION', '12')
})

it('모르는 종류와 관계가 와도 기타 칸에 그리고 누를 수 없게 둔다', async () => {
  const user = userEvent.setup()
  const { svg, onOpen } = graph({
    nodes: [
      { id: '501', type: 'TASK', label: 'TASK-001 목록 화면' },
      { id: '9', type: 'NEW_KIND', label: '새 종류 항목' },
    ],
    edges: [
      {
        from_id: '501',
        from_type: 'TASK',
        to_id: '9',
        to_type: 'NEW_KIND',
        relation: 'NEW_RELATION',
        confirmed: true,
      },
    ],
  })
  expect(within(svg).getAllByText('기타')).toHaveLength(2)
  expect(within(svg).getByText('NEW_REL…')).toBeInTheDocument()
  await user.click(within(svg).getByText('새 종류 항목'))
  expect(onOpen).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: '새 종류 항목' })).toBeNull()
})

it('연결된 노드가 그래프 밖에 있어도 깨지지 않고 그릴 수 있는 관계만 그린다', () => {
  const { svg } = graph({
    nodes: [{ id: '501', type: 'TASK', label: 'TASK-001 목록 화면' }],
    edges: [
      {
        from_id: '501',
        from_type: 'TASK',
        to_id: '404',
        to_type: 'DESIGN_ITEM',
        relation: 'IMPLEMENTS',
        confirmed: true,
      },
    ],
  })
  expect(within(svg).queryByText('구현')).toBeNull()
  expect(svg).toHaveAccessibleName(expect.stringContaining('이어진 관계 없음'))
})
