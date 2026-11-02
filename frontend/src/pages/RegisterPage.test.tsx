import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { authApi } from '@/api/services'
import RegisterPage from './RegisterPage'

vi.mock('@/api/services', () => ({ authApi: { register: vi.fn() } }))

it('이메일 인증 코드를 확인해야 가입되고, 틀린 코드는 남은 시도를 알려 준다', async () => {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/register']}>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<p>로그인 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )

  await user.type(screen.getByRole('textbox', { name: '이름' }), '새 팀원')
  await user.type(
    screen.getByRole('textbox', { name: '이메일' }),
    'verify-new@beez.local',
  )
  await user.type(screen.getByLabelText(/^비밀번호( \*)?$/), 'beez-2026!')
  await user.type(screen.getByLabelText(/^비밀번호 확인/), 'beez-2026!')
  await user.click(screen.getByRole('button', { name: '가입하기' }))
  expect(
    await screen.findByText('이메일 인증을 먼저 해 주세요.'),
  ).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  expect(screen.getByText(/남은 시간 0[45]:\d\d/)).toBeInTheDocument()
  const code = screen.getByText(/예시 코드: \d{6}/).textContent!.slice(-6)

  await user.type(screen.getByLabelText('인증 코드'), '000000')
  await user.click(screen.getByRole('button', { name: '확인' }))
  expect(
    await screen.findByText('코드가 맞지 않아요 (남은 시도 4번)'),
  ).toBeInTheDocument()

  await user.clear(screen.getByLabelText('인증 코드'))
  await user.type(screen.getByLabelText('인증 코드'), code)
  await user.click(screen.getByRole('button', { name: '확인' }))
  expect(await screen.findByText('인증됨')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '가입하기' }))
  expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
  expect(authApi.register).toHaveBeenCalledWith({
    name: '새 팀원',
    email: 'verify-new@beez.local',
    password: 'beez-2026!',
  })
})
