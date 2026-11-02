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
  it('빈 칸은 조사를 맞춘 안내와 빨간 테두리를 보여 준다', async () => {
    render(
      <ActionForm
        fields={[{ name: 'email', label: '이메일', required: true }]}
        onSubmit={vi.fn()}
        submitLabel="저장"
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이메일을 입력해 주세요.',
    )
    expect(screen.getByLabelText(/이메일/)).toHaveAttribute(
      'aria-invalid',
      'true',
    )
  })
  it('추가 규칙에 맞지 않으면 그 문구를 보여 주고 보기 버튼으로 값을 드러낸다', async () => {
    const submit = vi.fn()
    render(
      <ActionForm
        fields={[
          {
            name: 'password',
            label: '비밀번호',
            type: 'password',
            check: (value) => (value.includes('!') ? undefined : '특수문자'),
          },
        ]}
        onSubmit={submit}
        submitLabel="저장"
      />,
    )
    const input = screen.getByLabelText('비밀번호', { selector: 'input' })
    await userEvent.type(input, 'plain')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('특수문자')
    expect(submit).not.toHaveBeenCalled()
    expect(input).toHaveAttribute('type', 'password')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 보기' }))
    expect(input).toHaveAttribute('type', 'text')
  })
  it('칩을 고르지 않으면 막고, 고른 칩의 값을 제출한다', async () => {
    const submit = vi.fn().mockResolvedValue(undefined)
    render(
      <ActionForm
        fields={[
          {
            name: 'reason',
            label: '기다리는 이유',
            type: 'chips',
            required: true,
            value: '',
            options: [
              { value: 'INPUT', label: '추가 입력 필요' },
              { value: 'APPROVAL', label: '승인 필요' },
            ],
          },
        ]}
        onSubmit={submit}
        submitLabel="대기로 보고"
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '대기로 보고' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '기다리는 이유를 골라 주세요.',
    )
    expect(submit).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('radio', { name: '승인 필요' }))
    await userEvent.click(screen.getByRole('button', { name: '대기로 보고' }))
    await waitFor(() =>
      expect(submit).toHaveBeenCalledWith({ reason: 'APPROVAL' }),
    )
  })

  // 프로젝트 만들기(SCR-04-v2-create-project): 제출 버튼 왼쪽 '취소'는 보내지 않고 닫는다.
  it('취소를 누르면 제출하지 않고 onCancel만 부른다', async () => {
    const submit = vi.fn()
    const cancel = vi.fn()
    render(
      <ActionForm
        fields={[{ name: 'name', label: '이름', required: true }]}
        onSubmit={submit}
        submitLabel="만들기"
        onCancel={cancel}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(cancel).toHaveBeenCalledOnce()
    expect(submit).not.toHaveBeenCalled()
  })
})
