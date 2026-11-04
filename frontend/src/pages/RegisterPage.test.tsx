/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { authApi } from '@/api/services'
import { resetMockApi, setupMockApi } from '@/mocks/setup'
import RegisterPage from './RegisterPage'

// 인증 코드 보내기·확인(ACC-A11·A12)과 가입은 목 어댑터로 실제 경로를 탄다.
let restore: () => void
beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  vi.spyOn(authApi, 'register')
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

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
  expect(await screen.findByText(/남은 시간 0[45]:\d\d/)).toBeInTheDocument()
  const code = (await screen.findByText(/예시 코드: \d{6}/)).textContent!.slice(
    -6,
  )

  await user.type(
    screen.getByLabelText('인증 코드'),
    code === '000000' ? '111111' : '000000',
  )
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
    verified_token: expect.stringMatching(/^evt_/),
  })
})

it('가입한 이메일은 코드를 보내지 않고, 인증이 만료됐다는 가입 오류 뒤에는 인증을 다시 하게 한다', async () => {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/register']}>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
      </Routes>
    </MemoryRouter>,
  )
  const email = () => screen.getByRole('textbox', { name: '이메일' })
  await user.type(screen.getByRole('textbox', { name: '이름' }), '새 팀원')
  await user.type(email(), 'demo@beez.local')
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  expect(
    await screen.findByText('이미 가입된 이메일이에요. 로그인해 주세요.'),
  ).toBeInTheDocument()

  await user.clear(email())
  await user.type(email(), 'expired@beez.local')
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  const code = (await screen.findByText(/예시 코드: \d{6}/)).textContent!.slice(
    -6,
  )
  await user.type(screen.getByLabelText('인증 코드'), code)
  await user.click(screen.getByRole('button', { name: '확인' }))
  expect(await screen.findByText('인증됨')).toBeInTheDocument()

  vi.mocked(authApi.register).mockRejectedValueOnce(
    new AxiosError('expired', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 400,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'email_not_verified' } },
    }),
  )
  await user.type(screen.getByLabelText(/^비밀번호( \*)?$/), 'beez-2026!')
  await user.type(screen.getByLabelText(/^비밀번호 확인/), 'beez-2026!')
  await user.click(screen.getByRole('button', { name: '가입하기' }))
  expect(
    await screen.findByText(
      '이메일 인증이 끝났거나 만료됐어요. 이메일을 다시 인증해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(screen.queryByText('인증됨')).not.toBeInTheDocument()
  expect(email()).toHaveValue('')
})
