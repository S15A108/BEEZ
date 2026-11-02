import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, it, vi } from 'vitest'
import { ProposalForm } from './ProposalForm'

type Loaded = { content: string; versionId: string }

// 문서함 '고치기'로 연 문서 수정 제안 폼. 목록은 승인 버전 2031을 알려 주고, 그 원문이 캐시에 남아 있다.
function renderForm(fetchContent: () => Promise<Loaded>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['documents', '203', 'approved-content', '2031'], {
    content: '# 요구사항\n옛 원문',
    versionId: '2031',
  })
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[{ id: '203', label: '요구사항 명세서', versionId: '2031' }]}
        fetchDocumentContent={fetchContent}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  return { onSubmit, text: () => screen.getByLabelText('고친 글') }
}

// PR #72 리뷰: 옛 원문 캐시로 먼저 채워져도 새 승인본을 받으면, 손대지 않은 글은 새 원문으로 바뀐다.
it('손대지 않은 고친 글은 새 승인본 원문을 받으면 새 원문으로 바뀐다', async () => {
  const { text } = renderForm(() =>
    Promise.resolve({ content: '# 요구사항\n새 원문', versionId: '2032' }),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n새 원문'))
  expect(screen.queryByText(/새 버전으로 바뀌었어요/)).not.toBeInTheDocument()
})

it('손댄 고친 글은 두고 새 버전 안내를 보이며, 새 원문을 확인하기 전에는 올리지 않는다', async () => {
  const user = userEvent.setup()
  let resolve: (value: Loaded) => void = () => undefined
  const { onSubmit, text } = renderForm(
    () => new Promise<Loaded>((done) => (resolve = done)),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n옛 원문'))
  await user.type(text(), '\n고친 줄')
  resolve({ content: '# 요구사항\n새 원문', versionId: '2032' })
  expect(
    await screen.findByText(/기준 문서가 새 버전으로 바뀌었어요/),
  ).toBeInTheDocument()
  expect(text()).toHaveValue('# 요구사항\n옛 원문\n고친 줄')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  expect(
    await screen.findByText(
      '기준 문서가 새 버전으로 바뀌었어요. 새 원문을 확인한 뒤 올려 주세요.',
    ),
  ).toBeInTheDocument()
  expect(onSubmit).not.toHaveBeenCalled()
  await user.click(
    screen.getByRole('button', { name: '새 원문으로 다시 쓰기' }),
  )
  expect(text()).toHaveValue('# 요구사항\n새 원문')
})

// 추가 점검 2: 기준 원문(버전)을 받지 못했으면 고친 문서를 빼고 보내지 않고 막는다.
it('기준 원문을 받지 못하면 고친 글을 써도 올리지 않는다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmit = vi.fn()
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[{ id: '203', label: '요구사항 명세서' }]}
        fetchDocumentContent={() => Promise.reject(new Error('network'))}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  await screen.findByText(/기준 원문을 불러오지 못했어요/)
  await user.type(screen.getByLabelText('고친 글'), '새로 쓴 글')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  expect(
    await screen.findByText('기준 원문을 받은 뒤에 올릴 수 있어요.'),
  ).toBeInTheDocument()
  expect(onSubmit).not.toHaveBeenCalled()
})

// 추가 점검 3: 기준이 바뀌어 반려된 제안을 다시 내면, 승인본이 바뀐 문서는 옛 글 대신 새 기준 원문에서 다시 시작한다.
it('기준이 바뀐 제안을 다시 낼 때 경고를 보이고 새 기준 원문에서 시작하며, 승인본이 바뀐 문서는 보내지 않는다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="고쳐서 다시 올리기"
        submitLabel="다시 올리기"
        tasks={[]}
        documents={[
          { id: '201', label: '기획안', versionId: '2012' },
          { id: '203', label: '요구사항 명세서', versionId: '2031' },
          { id: '205', label: 'ERD', versionId: '2052' },
        ]}
        fetchDocumentContent={() =>
          Promise.resolve({ content: '# 기획안\n새 기준', versionId: '2012' })
        }
        sourceDocuments={[
          {
            id: '201',
            title: '기획안',
            afterContent: '# 기획안\n옛 기준에서 쓴 글',
            stale: true,
          },
          {
            id: '203',
            title: '요구사항 명세서',
            afterContent: '요구사항 글',
            stale: false,
          },
          { id: '205', title: 'ERD', afterContent: 'ERD 글', stale: true },
        ]}
        baseChangedTo={2}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '201',
          after_content: '',
        }}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  expect(screen.getByText('기준이 v2(으)로 바뀌었어요')).toBeInTheDocument()
  await waitFor(() =>
    expect(screen.getByLabelText('고친 글')).toHaveValue('# 기획안\n새 기준'),
  )
  expect(
    screen.getByText('반려된 제안의 글 보기 (비교용)').closest('details'),
  ).toHaveTextContent('옛 기준에서 쓴 글')
  expect(screen.getByText(/함께 올라가는 문서 변경 1개/)).toHaveTextContent(
    '요구사항 명세서',
  )
  expect(screen.getByText(/승인본이 바뀐 문서 1개/)).toHaveTextContent('ERD')
})

// PR #72 재전수 ROUND2-DOC-01: 두 문서를 바꾼 제안에서 고친 문서를 기준 원문으로 되돌리면 그 문서를 뺐다고 알린다.
it('고친 글을 기준 원문으로 되돌려 올리면 그 문서를 뺀 문서로 알린다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="고쳐서 다시 올리기"
        submitLabel="다시 올리기"
        tasks={[]}
        documents={[
          { id: '203', label: '요구사항 명세서', versionId: '2031' },
          { id: '204', label: 'API 명세서', versionId: '2041' },
        ]}
        sourceDocuments={[
          {
            id: '203',
            title: '요구사항 명세서',
            afterContent: '# 요구사항\n옛 수정안',
            stale: false,
          },
          {
            id: '204',
            title: 'API 명세서',
            afterContent: '# API\n수정안',
            stale: false,
          },
        ]}
        fetchDocumentContent={() =>
          Promise.resolve({ content: '# 요구사항\n원문', versionId: '2031' })
        }
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '# 요구사항\n원문',
        }}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  await screen.findByText(/함께 올라가는 문서 변경 1개/)
  await waitFor(() =>
    expect(screen.getByLabelText('고친 글')).toHaveValue('# 요구사항\n원문'),
  )
  await user.click(screen.getByRole('button', { name: '다시 올리기' }))
  await waitFor(() => expect(onSubmit).toHaveBeenCalled())
  expect(onSubmit.mock.calls[0][0]).toMatchObject({
    document_id: '',
    removed_document_id: '203',
  })
})
