import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { MemoryRouter } from 'react-router'
import App from './App'

it('기존 앱 진입점에서 BEEZ 브랜드와 목데이터 안내를 보여준다', () => {
  render(
    <MemoryRouter>
      <App />
    </MemoryRouter>,
  )
  expect(screen.getByRole('heading', { name: 'BEEZ' })).toBeInTheDocument()
  expect(
    screen.getByText('목데이터로 미리 보기 · 실제 서버 호출 없음'),
  ).toBeInTheDocument()
})
