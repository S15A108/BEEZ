import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import type { ProposalFormValues } from './proposalItems'
import { ProposeFlow } from './ProposeFlow'
import { proposeChanges } from './proposeDiff'

// 부모가 영향 분석을 1.5초마다 다시 받아 다시 그려도 줄 비교를 되풀이하지 않는지 세려고 실제 함수를 감싼다.
vi.mock('./proposeDiff', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./proposeDiff')>()
  return { ...actual, proposeChanges: vi.fn(actual.proposeChanges) }
})

const doc = (versionNo: number, versionId: string) => ({
  id: '203',
  label: '요구사항 명세',
  versionId,
  title: '요구사항 명세',
  versionNo,
})

function renderFlow(itemKinds: Record<string, string> = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  const flow = (documents: ReturnType<typeof doc>[]) => (
    <QueryClientProvider client={client}>
      <ProposeFlow
        documents={documents}
        itemKinds={itemKinds}
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
    rerenderSame: () => view.rerender(flow([doc(1, '301')])),
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

async function register(edit: (text: HTMLElement) => Promise<void>) {
  const user = userEvent.setup()
  const text = screen.getByLabelText('고친 글')
  await waitFor(() => expect(text).toHaveValue('REQ-001 이메일로 가입한다.'))
  await user.type(screen.getByLabelText('제목 *'), '가입 규칙 바꾸기')
  await user.type(
    screen.getByLabelText('바꾸려는 이유 *'),
    '규칙이 바뀌었어요.',
  )
  await edit(text)
  await user.click(screen.getByRole('button', { name: '등록하고 변경점 찾기' }))
  return user
}

// PR #135 리뷰: 승인된 REQ-001 줄을 지우고 그 자리에 REQ-002를 쓰면 REQ-001은 '고치기'가 아니라 '없애기'다.
it('지운 항목 키는 바뀐 줄의 설계 항목에 없애기로, 새 키는 새 항목으로 보인다', async () => {
  renderFlow({ 'REQ-001': '요구사항' })
  await register(async (text) => {
    await userEvent.clear(text)
    await userEvent.type(text, 'REQ-002 이메일 인증 후 가입한다.')
  })
  const items = within(
    screen.getByRole('region', { name: '바뀐 줄의 설계 항목' }),
  ).getAllByRole('listitem')
  expect(items.map((item) => item.textContent)).toEqual([
    'REQ-001요구사항없애기',
    'REQ-002새 항목',
  ])
})

// PR #135 리뷰: 빈 줄이나 끝 줄바꿈만 바뀌면 바뀐 곳이 0개인데도 다음 단계로 넘어가 빈 제안을 올릴 수 있었다.
it('빈 줄만 바꾸면 비비 검토로 넘어가지 않고 다시 고치게 한다', async () => {
  renderFlow()
  const user = await register((text) => userEvent.type(text, '{Enter}{Enter}'))
  expect(screen.getByText('바뀐 곳 0개를 찾았어요')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '비비 검토 받기' })).toBeDisabled()
  expect(screen.getByText(/빈 줄 말고는 바뀐 곳이 없어요/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '다시 고치기' }))
  expect(
    screen.getByRole('button', { name: '등록하고 변경점 찾기' }),
  ).toBeInTheDocument()
})

it('같은 내용으로 다시 그려도 줄 비교를 되풀이하지 않는다', async () => {
  const { rerenderSame } = renderFlow()
  await register((text) =>
    userEvent.type(text, '{Enter}REQ-002 환영 메일을 보낸다.'),
  )
  expect(screen.getByText('바뀐 곳 1개를 찾았어요')).toBeInTheDocument()
  vi.mocked(proposeChanges).mockClear()
  rerenderSame()
  rerenderSame()
  expect(proposeChanges).not.toHaveBeenCalled()
})
