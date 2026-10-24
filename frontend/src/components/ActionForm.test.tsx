import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ActionForm } from './ActionForm'

describe('공용 입력 폼', () => {
  it('이메일 형식이 잘못되면 요청을 보내지 않고 입력 오류를 보여준다', async () => {
    const submit = vi.fn()
    render(
      <ActionForm
        fields={[
          { name: 'email', label: '이메일', type: 'email', required: true },
        ]}
        onSubmit={submit}
        submitLabel="저장"
      />,
    )
    await userEvent.type(screen.getByLabelText(/이메일/), 'invalid')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이메일 형식을 확인해 주세요.',
    )
    expect(submit).not.toHaveBeenCalled()
  })
  it('새 비밀번호와 확인 값이 다르면 제출하지 않는다', async () => {
    const submit = vi.fn()
    render(
      <ActionForm
        fields={[
          {
            name: 'password',
            label: '새 비밀번호',
            type: 'password',
            required: true,
          },
          {
            name: 'confirm',
            label: '비밀번호 확인',
            type: 'password',
            matches: 'password',
          },
        ]}
        onSubmit={submit}
        submitLabel="변경"
      />,
    )
    await userEvent.type(screen.getByLabelText(/새 비밀번호/), 'first-password')
    await userEvent.type(
      screen.getByLabelText(/비밀번호 확인/),
      'different-password',
    )
    await userEvent.click(screen.getByRole('button', { name: '변경' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('서로 달라요')
    expect(submit).not.toHaveBeenCalled()
  })
  it('실패한 요청 뒤에도 작성한 내용을 보존하고 다시 제출할 수 있다', async () => {
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(undefined)
    render(
      <ActionForm
        fields={[{ name: 'summary', label: '요약', required: true }]}
        onSubmit={submit}
        submitLabel="저장"
      />,
    )
    await userEvent.type(screen.getByLabelText(/요약/), '보존할 공유 요약')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByLabelText(/요약/)).toHaveValue('보존할 공유 요약')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
  })
})
