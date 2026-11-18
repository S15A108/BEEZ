import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { Toast } from './Toast'

afterEach(() => vi.useRealTimers())

it('토스트는 잠깐 보였다가 4초 뒤 사라진다', () => {
  vi.useFakeTimers()
  render(<Toast>초대 링크를 만들었어요.</Toast>)
  expect(screen.getByRole('status')).toHaveTextContent(
    '초대 링크를 만들었어요.',
  )
  act(() => vi.advanceTimersByTime(3999))
  expect(screen.getByRole('status')).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(1))
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
