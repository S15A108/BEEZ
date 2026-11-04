import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authApi } from '@/api/services'
import { resetMockApi, setupMockApi } from '@/mocks/setup'
import { EmailVerification } from './EmailVerification'
import type { SendEmailVerificationResponse } from './schemas'

let restore: () => void
beforeEach(() => {
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => {
  restore()
  vi.restoreAllMocks()
})

const email = () => screen.getByRole('textbox', { name: '이메일' })

// PR #117 리뷰: busy 검사 뒤 검증(trigger)을 기다리는 동안 두 번째 클릭이 들어와 요청이 두 번 나갔다.
// userEvent는 클릭 사이에 기다려 검증이 먼저 끝나므로, 검증 전에 두 클릭이 들어오게 fireEvent로 연달아 누른다.
const clickTwice = (name: string) => {
  const button = screen.getByRole('button', { name })
  fireEvent.click(button)
  fireEvent.click(button)
}
it('코드 받기와 확인을 빠르게 두 번 눌러도 요청은 한 번만 나간다', async () => {
  const user = userEvent.setup()
  const send = vi.spyOn(authApi, 'sendEmailVerification')
  const confirm = vi.spyOn(authApi, 'confirmEmailVerification')
  const onVerified = vi.fn()
  render(<EmailVerification needed={false} onVerified={onVerified} />)

  await user.type(email(), 'twice@beez.local')
  clickTwice('인증 코드 받기')
  const code = (await screen.findByText(/예시 코드: \d{6}/)).textContent!.slice(
    -6,
  )
  expect(send).toHaveBeenCalledTimes(1)
  expect(screen.queryByText(/너무 자주/)).not.toBeInTheDocument()

  await user.type(screen.getByLabelText('인증 코드'), code)
  clickTwice('확인')
  expect(await screen.findByText('인증됨')).toBeInTheDocument()
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(onVerified).toHaveBeenCalledTimes(1)
})

it('이메일 형식이 틀려 보내지 못하면 잠금을 풀어 고친 뒤 다시 보낼 수 있다', async () => {
  const user = userEvent.setup()
  const send = vi.spyOn(authApi, 'sendEmailVerification')
  render(<EmailVerification needed={false} onVerified={vi.fn()} />)

  await user.type(email(), 'not-an-email')
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  expect(
    await screen.findByText('이메일 형식을 확인해 주세요.'),
  ).toBeInTheDocument()
  await user.clear(email())
  await user.type(email(), 'fixed@beez.local')
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  expect(await screen.findByText(/예시 코드: \d{6}/)).toBeInTheDocument()
  expect(send).toHaveBeenCalledTimes(1)
})

// PR #117 리뷰: 보내는 중에 이메일을 A에서 B로 고치면 A로 받은 인증으로 가입됐다.
it('코드를 보내는 동안 이메일 칸을 잠가 보낸 이메일과 칸의 이메일이 같다', async () => {
  const user = userEvent.setup()
  let reply: (value: SendEmailVerificationResponse) => void = () => {}
  vi.spyOn(authApi, 'sendEmailVerification').mockImplementationOnce(
    () => new Promise((resolve) => (reply = resolve)),
  )
  render(<EmailVerification needed={false} onVerified={vi.fn()} />)

  await user.type(email(), 'a@beez.local')
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  expect(email()).toHaveAttribute('readonly')
  await user.type(email(), 'b')
  expect(email()).toHaveValue('a@beez.local')

  reply({
    verification_id: 'k3Jq9xV2mT7pLr0sYwE5bN8cHd1fGa4uZiQoXeRtMvA',
    expires_at: new Date(Date.now() + 5 * 60_000).toISOString(),
    resend_available_at: new Date(Date.now() + 60_000).toISOString(),
  })
  expect(await screen.findByText(/남은 시간/)).toBeInTheDocument()
  expect(email()).not.toHaveAttribute('readonly')
  expect(email()).toHaveValue('a@beez.local')
})
