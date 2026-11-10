import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { MAX_MENTIONS, ProposalCommentForm } from './ProposalCommentForm'

const members = Array.from({ length: MAX_MENTIONS + 2 }, (_, index) => ({
  id: `${index + 1}`,
  name: `팀원${index + 1}`,
}))

it('받을 팀원은 20명까지만 고를 수 있다', async () => {
  const user = userEvent.setup()
  render(<ProposalCommentForm members={members} onSubmit={vi.fn()} />)
  await user.click(screen.getByRole('tab', { name: '검토 요청' }))
  await user.click(screen.getByRole('button', { name: '+ 받을 팀원' }))
  for (const member of members.slice(0, MAX_MENTIONS))
    await user.click(await screen.findByRole('checkbox', { name: member.name }))
  expect(screen.getByRole('checkbox', { name: '팀원21' })).toBeDisabled()
  expect(screen.getByText('최대 20명까지 고를 수 있어요.')).toBeInTheDocument()
  await user.click(screen.getByRole('checkbox', { name: '팀원1' }))
  expect(screen.getByRole('checkbox', { name: '팀원21' })).toBeEnabled()
})

it('칩의 빼기 버튼으로 받을 팀원을 뺀다', async () => {
  const user = userEvent.setup()
  render(<ProposalCommentForm members={members} onSubmit={vi.fn()} />)
  await user.click(screen.getByRole('tab', { name: '검토 요청' }))
  await user.click(screen.getByRole('button', { name: '+ 받을 팀원' }))
  await user.click(await screen.findByRole('checkbox', { name: '팀원3' }))
  await user.keyboard('{Escape}')
  await user.type(screen.getByLabelText('내용'), '봐 주세요')
  await user.click(screen.getByRole('button', { name: '팀원3 빼기' }))
  expect(
    screen.getByText('받을 팀원을 한 명 이상 골라 주세요.'),
  ).toBeInTheDocument()
})

it('보내기에 실패하면 쓰던 내용과 고른 팀원을 그대로 둔다', async () => {
  const user = userEvent.setup()
  const onSubmit = vi.fn().mockRejectedValue(new Error('실패'))
  render(<ProposalCommentForm members={members} onSubmit={onSubmit} />)
  await user.click(screen.getByRole('tab', { name: '검토 요청' }))
  await user.click(screen.getByRole('button', { name: '+ 받을 팀원' }))
  await user.click(await screen.findByRole('checkbox', { name: '팀원2' }))
  await user.keyboard('{Escape}')
  await user.type(screen.getByLabelText('내용'), '봐 주세요')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(onSubmit).toHaveBeenCalledWith({
    kind: 'REVIEW_REQUEST',
    body: '봐 주세요',
    memberIds: ['2'],
  })
  expect(screen.getByLabelText('내용')).toHaveValue('봐 주세요')
  expect(screen.getByRole('button', { name: '팀원2 빼기' })).toBeInTheDocument()
})

it('받을 팀원 후보가 없으면 안내한다', async () => {
  const user = userEvent.setup()
  render(<ProposalCommentForm members={[]} onSubmit={vi.fn()} />)
  await user.click(screen.getByRole('tab', { name: '검토 요청' }))
  await user.click(screen.getByRole('button', { name: '+ 받을 팀원' }))
  expect(
    await screen.findByText('고를 수 있는 팀원이 없어요.'),
  ).toBeInTheDocument()
})
