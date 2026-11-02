import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it } from 'vitest'
import { TourGuide } from './TourGuide'
import type { TourMode } from './TourGuide'

function Harness() {
  const [mode, setMode] = useState<TourMode>(null)
  return (
    <>
      {['office', 'card', 'hotbar', 'todo', 'top', 'bibi'].map((target) => (
        <div key={target} data-tour={target} />
      ))}
      <TourGuide accountId="11" mode={mode} onChange={setMode} />
    </>
  )
}

beforeEach(() => localStorage.clear())

it('? 키로 화면 안내를 열고 처음부터 둘러보기 6단계를 마치면 봤다고 남긴다', async () => {
  const user = userEvent.setup()
  render(<Harness />)
  await user.keyboard('?')
  expect(screen.getByRole('dialog', { name: '화면 안내' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '처음부터 둘러보기' }))
  expect(
    screen.getByText('문서 초안 4종이 승인되면 프로젝트가 시작돼요'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '1분 둘러보기' }))
  expect(screen.getByText('화면 둘러보기 · 1 / 6')).toBeInTheDocument()
  for (let step = 1; step < 6; step++)
    await user.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('dialog', { name: '비비' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '시작하기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(localStorage.getItem('beez.tour.11')).toBe('seen')
})

it('입력 칸에서 ?를 치면 안내를 열지 않고 Esc로 닫는다', async () => {
  const user = userEvent.setup()
  render(
    <>
      <input aria-label="검색" />
      <Harness />
    </>,
  )
  await user.type(screen.getByLabelText('검색'), '?')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(document.body)
  await user.keyboard('?')
  expect(screen.getByRole('dialog', { name: '화면 안내' })).toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

// PR #72 전수 리뷰 UI-1: 환영 창에서 Tab을 눌러도 포커스가 창 밖(뒤 화면)으로 나가지 않는다.
it('환영 창은 Tab 포커스를 창 안에 가둔다', async () => {
  const user = userEvent.setup()
  render(
    <>
      <button type="button">뒤 화면 버튼</button>
      <Harness />
    </>,
  )
  await user.keyboard('?')
  await user.click(screen.getByRole('button', { name: '처음부터 둘러보기' }))
  const welcome = screen.getByRole('dialog', {
    name: 'BEEZ 사무실에 오신 걸 환영해요',
  })
  for (let press = 0; press < 4; press++) {
    await user.tab()
    expect(welcome).toContainElement(document.activeElement as HTMLElement)
  }
})
