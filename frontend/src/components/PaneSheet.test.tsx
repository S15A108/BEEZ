import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { FormDialog } from './FormDialog'
import { PaneSheetHost } from './PaneSheet'

function Pane({ onSubmit }: { onSubmit: (values: unknown) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  return (
    <PaneSheetHost>
      <p>작업 목록</p>
      <button type="button" onClick={() => setOpen(true)}>
        대기 보고
      </button>
      {open && (
        <FormDialog
          back="TASK-001"
          title="대기로 보고"
          fields={[
            { name: 'summary', label: '지금 상황', required: true },
            {
              name: 'color',
              label: '표시 색',
              type: 'color',
              value: '#f3c530',
            },
          ]}
          submitLabel="대기로 보고"
          onClose={() => setOpen(false)}
          onSubmit={onSubmit}
        />
      )}
    </PaneSheetHost>
  )
}

it('업무 창 안의 하위 화면은 본문을 가리고, 뒤로·취소·Esc로 본문에 돌아온다', async () => {
  const user = userEvent.setup()
  render(<Pane onSubmit={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '대기 보고' }))
  expect(screen.queryByText('작업 목록')).not.toBeVisible()
  expect(screen.getByRole('heading', { name: '대기로 보고' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: 'TASK-001' }))
  expect(screen.getByText('작업 목록')).toBeVisible()

  await user.click(screen.getByRole('button', { name: '대기 보고' }))
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(screen.getByText('작업 목록')).toBeVisible()

  await user.click(screen.getByRole('button', { name: '대기 보고' }))
  await user.keyboard('{Escape}')
  expect(screen.getByText('작업 목록')).toBeVisible()
})

it('표시 색은 색 동그라미로 고르고, 고른 색을 제출한다', async () => {
  const user = userEvent.setup()
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  render(<Pane onSubmit={onSubmit} />)
  await user.click(screen.getByRole('button', { name: '대기 보고' }))
  expect(screen.getByRole('radio', { name: '#f3c530' })).toBeChecked()
  await user.click(screen.getByRole('radio', { name: '#cfe8d8' }))
  await user.type(screen.getByLabelText(/지금 상황/), '승인을 기다려요')
  await user.click(screen.getByRole('button', { name: '대기로 보고' }))
  expect(onSubmit).toHaveBeenCalledWith({
    summary: '승인을 기다려요',
    color: '#cfe8d8',
  })
})
