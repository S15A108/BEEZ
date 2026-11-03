import { useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { PaneHeaderAction } from './PaneHeaderAction'
import { PaneHeaderSlot } from './paneHeaderSlot'
import { PaneSheet, PaneSheetHost } from './PaneSheet'

// PR #88 리뷰: 창 머리는 하위 화면이 열려도 보여서, 저장 중에 새 폼을 열면 쓰던 내용이 사라졌다.
it('하위 화면이 열려 있는 동안 창 머리 버튼을 숨기고, 닫으면 다시 보인다', async () => {
  const user = userEvent.setup()
  const slot = document.createElement('div')
  document.body.append(slot)
  function Panel() {
    const [open, setOpen] = useState(false)
    return (
      <>
        <PaneHeaderAction>
          <button type="button">작업 계획</button>
        </PaneHeaderAction>
        <button type="button" onClick={() => setOpen(true)}>
          폼 열기
        </button>
        {open && (
          <PaneSheet title="작업 계획" onClose={() => setOpen(false)}>
            <p>폼</p>
          </PaneSheet>
        )}
      </>
    )
  }
  render(
    <PaneHeaderSlot value={slot}>
      <PaneSheetHost>
        <Panel />
      </PaneSheetHost>
    </PaneHeaderSlot>,
  )
  expect(
    within(slot).getByRole('button', { name: '작업 계획' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '폼 열기' }))
  expect(within(slot).queryByRole('button', { name: '작업 계획' })).toBeNull()
  await user.click(screen.getByRole('button', { name: /뒤로/ }))
  expect(
    within(slot).getByRole('button', { name: '작업 계획' }),
  ).toBeInTheDocument()
  slot.remove()
})

// 캔버스 PNL-05-v2-tasks: '+ 작업 계획'은 업무 창 머리 줄에 둔다. 머리 자리가 없으면 본문에 그대로 그린다.
it('머리 자리가 있으면 그 자리에, 없으면 그 자리에 그대로 버튼을 그린다', () => {
  const slot = document.createElement('div')
  document.body.append(slot)
  const { unmount } = render(
    <PaneHeaderSlot value={slot}>
      <section aria-label="본문">
        <PaneHeaderAction>
          <button type="button">작업 계획</button>
        </PaneHeaderAction>
      </section>
    </PaneHeaderSlot>,
  )
  expect(slot).toContainElement(
    screen.getByRole('button', { name: '작업 계획' }),
  )
  expect(screen.getByRole('region', { name: '본문' })).toBeEmptyDOMElement()
  unmount()
  render(
    <section aria-label="본문">
      <PaneHeaderAction>
        <button type="button">작업 계획</button>
      </PaneHeaderAction>
    </section>,
  )
  expect(screen.getByRole('region', { name: '본문' })).toContainElement(
    screen.getByRole('button', { name: '작업 계획' }),
  )
  slot.remove()
})
