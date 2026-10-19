import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import App from './App'

it('renders the project title', () => {
  render(<App />)
  expect(screen.getByRole('heading', { name: 'S15A108' })).toBeInTheDocument()
})
