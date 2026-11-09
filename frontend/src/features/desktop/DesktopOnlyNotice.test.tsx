import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { DesktopOnlyNotice } from './DesktopOnlyNotice'

afterEach(() => vi.unstubAllGlobals())

it('PC 이용 안내 문구를 보여준다', () => {
  render(<DesktopOnlyNotice />)
  expect(
    screen.getByRole('heading', { name: 'PC에서 이용해 주세요' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText(
      '화면이 좁아 이용할 수 없어요. PC 브라우저에서 열어 주세요.',
    ),
  ).toBeInTheDocument()
})

it('현재 주소를 복사하고 결과를 알린다', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  render(<DesktopOnlyNotice />)
  await userEvent.click(screen.getByRole('button', { name: '현재 주소 복사' }))
  expect(writeText).toHaveBeenCalledWith(window.location.href)
  expect(await screen.findByText('주소를 복사했어요.')).toBeInTheDocument()
})

it('복사가 거부되면 실패를 알린다', async () => {
  const writeText = vi.fn().mockRejectedValue(new Error('denied'))
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  render(<DesktopOnlyNotice />)
  await userEvent.click(screen.getByRole('button', { name: '현재 주소 복사' }))
  expect(await screen.findByText(/복사하지 못했어요/)).toBeInTheDocument()
})
