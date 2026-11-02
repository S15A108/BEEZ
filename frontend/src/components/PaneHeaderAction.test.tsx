import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { PaneHeaderAction } from './PaneHeaderAction'
import { PaneHeaderSlot } from './paneHeaderSlot'

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
