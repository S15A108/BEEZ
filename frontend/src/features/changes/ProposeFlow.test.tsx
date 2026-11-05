import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import type { ProposalFormValues } from './proposalItems'
import { ProposeFlow } from './ProposeFlow'

const doc = (versionNo: number, versionId: string) => ({
  id: '203',
  label: '요구사항 명세',
  versionId,
  title: '요구사항 명세',
  versionNo,
})

function renderFlow() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  const flow = (documents: ReturnType<typeof doc>[]) => (
    <QueryClientProvider client={client}>
      <ProposeFlow
        documents={documents}
        itemKinds={{}}
        tasks={[]}
        fetchDocumentContent={async () => ({
          content: 'REQ-001 이메일로 가입한다.',
          versionId: '301',
        })}
        defaultValues={
          {
            title: '',
            reason: '',
            task_id: '',
            document_id: '203',
            after_content: '',
          } as ProposalFormValues
        }
        onCancel={vi.fn()}
        onSubmit={onSubmit}
      />
    </QueryClientProvider>
  )
  const view = render(flow([doc(1, '301')]))
  return {
    onSubmit,
    approveNewVersion: () => view.rerender(flow([doc(2, '302')])),
  }
}

// PR #135 리뷰(오서현): v1과 비교해 ②에 들어간 뒤 승인본이 v2로 바뀌면, 화면은 'v2와 비교'라고 하는데 실제 비교·제출은
// v1이었다(서버는 baseline_conflict로 거절). 비교한 버전을 고정해 보이고, 올리기 전에 승인본이 바뀌었으면 다시 비교하게 한다.
it('단계 중간에 승인본이 바뀌면 비교한 버전을 그대로 보이고 올리기 전에 다시 비교하라고 막는다', async () => {
  const user = userEvent.setup()
  const { onSubmit, approveNewVersion } = renderFlow()
  await waitFor(() =>
    expect(
      (screen.getByLabelText('고친 글') as HTMLTextAreaElement).value,
    ).toContain('REQ-001'),
  )
  await user.type(screen.getByLabelText('제목 *'), '가입 안내 추가')
  await user.type(screen.getByLabelText('바꾸려는 이유 *'), '안내가 필요해요.')
  await user.type(
    screen.getByLabelText('고친 글'),
    '\nREQ-002 가입하면 환영 메일을 보낸다.',
  )
  await user.click(screen.getByRole('button', { name: '등록하고 변경점 찾기' }))
  expect(screen.getAllByText(/기준 v1/).length).toBeGreaterThan(0)

  approveNewVersion()

  expect(screen.getAllByText(/기준 v1/).length).toBeGreaterThan(0)
  expect(screen.queryByText(/기준 v2/)).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent(
    '승인본이 v2로 바뀌었어요',
  )
  await user.click(screen.getByRole('button', { name: '비비 검토 받기' }))
  await user.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('button', { name: '팀장에게 올리기' })).toBeDisabled()
  expect(onSubmit).not.toHaveBeenCalled()

  await user.click(
    screen.getByRole('button', { name: '새 승인본과 다시 비교' }),
  )
  expect(screen.getByLabelText('제목 *')).toHaveValue('가입 안내 추가')
})
