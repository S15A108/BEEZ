import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, it, vi } from 'vitest'
import { ProposalForm } from './ProposalForm'
import { changesApi } from '@/api/services/changes'

type Loaded = { content: string; versionId: string }

it('선택 초안은 확인 후 고친 글에만 적용하고 직접 쓴 글은 덮어쓰지 않는다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  vi.spyOn(changesApi, 'scopedBlocks').mockResolvedValue({
    document_id: '203',
    base_document_version_id: '2031',
    text_hash: 'hash',
    parser_profile: 'plain-v1',
    parser_version: '1',
    blocks: [
      {
        block_id: 'block',
        block_type: 'paragraph',
        locator: { type: 'text_lines', start_line: 1, end_line: 1 },
        text: '기준 원문',
      },
    ],
  })
  vi.spyOn(changesApi, 'scopedDraft').mockResolvedValue({
    document_id: '203',
    base_document_version_id: '2031',
    after_content: '새 초안',
    changes: [
      {
        block_id: 'block',
        before: '기준 원문',
        after: '새 초안',
        reason: '변경',
        evidence: [],
      },
    ],
    unresolved: [],
    generation: { origin: 'MODEL', model: 'test', usage: [] },
  })
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        projectId="101"
        heading="제안"
        submitLabel="올리기"
        tasks={[]}
        documents={[{ id: '203', label: '문서', versionId: '2031' }]}
        fetchDocumentContent={() =>
          Promise.resolve({ content: '기준 원문', versionId: '2031' })
        }
        defaultValues={{
          title: '',
          reason: '',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  await screen.findByDisplayValue('기준 원문')
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  await user.click(await screen.findByRole('checkbox', { name: /기준 원문/ }))
  await user.type(screen.getByLabelText('수정 요청'), '변경해줘')
  await user.click(
    screen.getByRole('button', { name: '선택 범위 초안 만들기' }),
  )
  await screen.findByText('새 초안')
  expect(screen.getByLabelText('고친 글')).toHaveValue('기준 원문')
  await user.click(screen.getByRole('button', { name: '초안 적용' }))
  expect(screen.getByLabelText('고친 글')).toHaveValue('새 초안')
  expect(screen.getByText(/직접 고친 글은 유지돼요/)).toBeInTheDocument()
  vi.restoreAllMocks()
})

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

// PR #72 3차 조사 THIRD-DOC-01: 두 문서를 바꾼 제안을 다시 낼 때 고칠 문서를 두 번째로 바꾸면 그 문서의 원래 수정안을 채우고,
// 원래 제안에 없던 문서를 고를 때만 기준 원문을 채운다.
it('다시 내는 제안에서 고칠 문서를 바꾸면 원래 제안의 그 문서 수정안을 채운다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  const originals: Record<string, Loaded> = {
    '203': { content: '# 요구사항\n원문', versionId: '2031' },
    '204': { content: '# API\n원문', versionId: '2041' },
    '205': { content: '# ERD\n원문', versionId: '2051' },
  }
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="새 제안으로 다시 올리기"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[
          { id: '203', label: '요구사항 명세서', versionId: '2031' },
          { id: '204', label: 'API 명세서', versionId: '2041' },
          { id: '205', label: 'ERD', versionId: '2051' },
        ]}
        sourceDocuments={[
          {
            id: '203',
            title: '요구사항 명세서',
            afterContent: '# 요구사항\n수정안',
            stale: false,
          },
          {
            id: '204',
            title: 'API 명세서',
            afterContent: '# API\n수정안',
            stale: false,
          },
        ]}
        fetchDocumentContent={(id) => Promise.resolve(originals[id])}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '# 요구사항\n수정안',
        }}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  const text = () => screen.getByLabelText('고친 글')
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '204')
  await waitFor(() => expect(text()).toHaveValue('# API\n수정안'))
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '205')
  await waitFor(() => expect(text()).toHaveValue('# ERD\n원문'))
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '204')
  await waitFor(() => expect(text()).toHaveValue('# API\n수정안'))
  await user.type(text(), '\n보완한 줄')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  await waitFor(() => expect(onSubmit).toHaveBeenCalled())
  expect(onSubmit.mock.calls[0][0]).toMatchObject({
    document_id: '204',
    after_content: '# API\n수정안\n보완한 줄',
    base_version_id: '2041',
  })
})

// 캔버스 PNL-04-v2-propose-doc '파일 올리기': .md 파일 한 개를 브라우저에서 읽어 고친 글로 넣는다(BOM·CRLF는 정규화).
it('파일 올리기로 .md 파일을 고르면 내용을 고친 글에 넣고, 다른 파일은 받지 않는다', async () => {
  const user = userEvent.setup({ applyAccept: false })
  const { text } = renderForm(() =>
    Promise.resolve({ content: '# 요구사항\n옛 원문', versionId: '2031' }),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n옛 원문'))
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  await user.upload(
    screen.getByLabelText('고친 문서 파일'),
    new File(['\uFEFF# 요구사항\r\n고친 원문'], 'spec.png'),
  )
  expect(
    await screen.findByText(/\.md·\.markdown·\.txt 파일 한 개를 올려 주세요/),
  ).toBeInTheDocument()
  await user.upload(
    screen.getByLabelText('고친 문서 파일'),
    new File(['\uFEFF# 요구사항\r\n고친 원문'], 'spec.md'),
  )
  expect(await screen.findByRole('status')).toHaveTextContent(
    'spec.md 내용을 고친 글에 넣었어요.',
  )
  expect(screen.getByRole('tab', { name: '고친 글 쓰기' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(text()).toHaveValue('# 요구사항\n고친 원문')
})

// PR #135 리뷰: file.text()는 UTF-8이 아닌 바이트를 U+FFFD로 조용히 바꿔 CP949 .txt의 한글이 깨진 채 들어갔다.
// 읽다 실패한 파일도 처리하지 않은 거절(unhandled rejection)이 되지 않고 안내로 끝나야 한다.
it('UTF-8이 아니거나 읽지 못한 파일은 고친 글에 넣지 않고 UTF-8로 저장한 파일을 올리라고 알린다', async () => {
  const user = userEvent.setup()
  const { text } = renderForm(() =>
    Promise.resolve({ content: '# 요구사항\n옛 원문', versionId: '2031' }),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n옛 원문'))
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  // CP949로 저장한 '# 한글'
  await user.upload(
    screen.getByLabelText('고친 문서 파일'),
    new File([new Uint8Array([0x23, 0x20, 0xc7, 0xd1, 0xb1, 0xdb])], 'a.txt'),
  )
  expect(
    await screen.findByText(/UTF-8로 저장한 파일만 올릴 수 있어요/),
  ).toBeInTheDocument()
  const unreadable = new File(['# 요구사항'], 'b.md')
  const fail = () =>
    Promise.reject(new DOMException('읽기 실패', 'NotReadableError'))
  Object.assign(unreadable, { text: fail, arrayBuffer: fail })
  await user.upload(screen.getByLabelText('고친 문서 파일'), unreadable)
  expect(
    await screen.findByText(/UTF-8로 저장한 파일만 올릴 수 있어요/),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: '고친 글 쓰기' }))
  expect(text()).toHaveValue('# 요구사항\n옛 원문')
})

// 서버(DocumentInputValidator)는 정규화한 본문이 100,000자(코드 포인트)를 넘거나 줄바꿈·탭 말고 제어 문자가 있으면
// 거절한다. 300KB 영문 .md가 ①을 통과하고 마지막 단계에서야 413 file_too_large로 막히지 않게 파일을 넣을 때 같은 규칙으로 막는다.
it('100,000자를 넘거나 제어 문자가 든 파일은 넣지 않고, 이모지 100,000자는 코드 포인트로 세어 받는다', async () => {
  const user = userEvent.setup()
  const { text } = renderForm(() =>
    Promise.resolve({ content: '# 요구사항\n옛 원문', versionId: '2031' }),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n옛 원문'))
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  const file = () => screen.getByLabelText('고친 문서 파일')
  await user.upload(file(), new File(['a'.repeat(100_001)], 'long.md'))
  expect(
    await screen.findByText('고친 글은 100,000자까지 쓸 수 있어요.'),
  ).toBeInTheDocument()
  await user.upload(file(), new File(['# 제목\u0007종소리'], 'bell.md'))
  expect(
    await screen.findByText('글에 쓸 수 없는 제어 문자가 들어 있어요.'),
  ).toBeInTheDocument()
  // 줄 끝 CRLF는 정규화한 뒤에 센다.
  const emoji = `\t${'😀'.repeat(99_998)}\r\n`
  await user.upload(file(), new File([emoji], 'emoji.md'))
  expect(await screen.findByRole('status')).toHaveTextContent(
    'emoji.md 내용을 고친 글에 넣었어요.',
  )
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('직접 쓴 고친 글에 제어 문자가 있으면 올리지 않는다', async () => {
  const user = userEvent.setup()
  const { onSubmit, text } = renderForm(() =>
    Promise.resolve({ content: '# 요구사항\n옛 원문', versionId: '2031' }),
  )
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n옛 원문'))
  await user.click(text())
  await user.paste('\u000B세로 탭')
  await user.click(screen.getByRole('button', { name: '제안 올리기' }))
  expect(
    await screen.findByText('글에 쓸 수 없는 제어 문자가 들어 있어요.'),
  ).toBeInTheDocument()
  expect(onSubmit).not.toHaveBeenCalled()
})

// PR #135 리뷰(오서현): A 문서에서 고른 파일을 읽는 동안 B 문서로 바꾸면, 늦게 끝난 A 파일 내용이 B의 고친 글에 들어가
// B의 문서 ID·기준 버전에 A의 본문이 붙어 올라갔다. 읽기를 시작한 문서와 요청이 그대로일 때만 고친 글에 넣는다.
it('파일을 읽는 동안 고칠 문서를 바꾸면 늦게 끝난 파일 내용을 바꾼 문서의 고친 글에 넣지 않는다', async () => {
  const user = userEvent.setup()
  const originals: Record<string, Loaded> = {
    '203': { content: '# 요구사항\n원문', versionId: '2031' },
    '204': { content: '# API\n원문', versionId: '2041' },
  }
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[
          { id: '203', label: '요구사항 명세서', versionId: '2031' },
          { id: '204', label: 'API 명세서', versionId: '2041' },
        ]}
        fetchDocumentContent={(id) => Promise.resolve(originals[id])}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  const text = () => screen.getByLabelText('고친 글')
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n원문'))
  let finish: (buffer: ArrayBuffer) => void = () => undefined
  const slow = new File(['# 요구사항\nA 파일'], 'a.md')
  Object.assign(slow, {
    arrayBuffer: () => new Promise<ArrayBuffer>((done) => (finish = done)),
  })
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  await user.upload(screen.getByLabelText('고친 문서 파일'), slow)
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '204')
  await user.click(screen.getByRole('tab', { name: '고친 글 쓰기' }))
  await waitFor(() => expect(text()).toHaveValue('# API\n원문'))
  finish(new TextEncoder().encode('# 요구사항\nA 파일').buffer as ArrayBuffer)
  await new Promise((done) => setTimeout(done, 0))
  expect(text()).toHaveValue('# API\n원문')
  expect(
    screen.queryByText('a.md 내용을 고친 글에 넣었어요.'),
  ).not.toBeInTheDocument()
})

// 파일 읽기를 멈춰 둔 채로 폼을 그린다. 읽기가 끝나는 때를 시험이 정한다.
function renderSlowFile(fetchOriginal: () => Promise<Loaded>) {
  let finish: () => void = () => undefined
  const slow = new File(['# 요구사항\n파일 내용'], 'a.md')
  Object.assign(slow, {
    arrayBuffer: () =>
      new Promise<ArrayBuffer>((done) => {
        finish = () =>
          done(
            new TextEncoder().encode('# 요구사항\n파일 내용')
              .buffer as ArrayBuffer,
          )
      }),
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[{ id: '203', label: '요구사항 명세서', versionId: '2031' }]}
        fetchDocumentContent={fetchOriginal}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  return { slow, finish: () => finish() }
}

// PR #135 리뷰(오서현): 파일을 읽는 동안 '고친 글 쓰기'로 돌아가 직접 고친 글이, 늦게 끝난 파일 내용으로 확인 없이 덮어써졌다.
it('파일을 읽는 동안 글을 직접 고치면 늦게 끝난 파일 내용으로 덮어쓰지 않고 그대로 뒀다고 알린다', async () => {
  const user = userEvent.setup()
  const { slow, finish } = renderSlowFile(() =>
    Promise.resolve({ content: '# 요구사항\n원문', versionId: '2031' }),
  )
  const text = () => screen.getByLabelText('고친 글')
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n원문'))
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  await user.upload(screen.getByLabelText('고친 문서 파일'), slow)
  await user.click(screen.getByRole('tab', { name: '고친 글 쓰기' }))
  await user.type(text(), ' 직접 고침')
  finish()
  expect(await screen.findByRole('status')).toHaveTextContent(
    '파일을 읽는 동안 글을 고쳐서 a.md 내용을 넣지 않았어요.',
  )
  expect(text()).toHaveValue('# 요구사항\n원문 직접 고침')
})

it('파일을 읽는 동안 탭만 옮기고 글을 고치지 않으면 파일 내용을 넣는다', async () => {
  const user = userEvent.setup()
  const { slow, finish } = renderSlowFile(() =>
    Promise.resolve({ content: '# 요구사항\n원문', versionId: '2031' }),
  )
  const text = () => screen.getByLabelText('고친 글')
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n원문'))
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  await user.upload(screen.getByLabelText('고친 문서 파일'), slow)
  await user.click(screen.getByRole('tab', { name: '고친 글 쓰기' }))
  finish()
  expect(await screen.findByRole('status')).toHaveTextContent(
    'a.md 내용을 고친 글에 넣었어요.',
  )
  expect(text()).toHaveValue('# 요구사항\n파일 내용')
})

// 기준 원문이 빈 글을 채운 것은 직접 고친 글이 아니므로, 고른 파일을 그대로 넣는다.
it('파일을 읽는 동안 기준 원문이 도착해도 파일 내용을 넣는다', async () => {
  const user = userEvent.setup()
  let arrive: (loaded: Loaded) => void = () => undefined
  const original = vi.fn(() => new Promise<Loaded>((done) => (arrive = done)))
  const { slow, finish } = renderSlowFile(original)
  await waitFor(() => expect(original).toHaveBeenCalled())
  await user.click(screen.getByRole('tab', { name: '파일 올리기' }))
  await user.upload(screen.getByLabelText('고친 문서 파일'), slow)
  arrive({ content: '# 요구사항\n원문', versionId: '2031' })
  await waitFor(() =>
    expect(screen.queryByText('기준 원문을 불러오는 중…')).toBeNull(),
  )
  finish()
  expect(
    await screen.findByText('a.md 내용을 고친 글에 넣었어요.'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('고친 글')).toHaveValue('# 요구사항\n파일 내용')
})

// S15P31A108-203: 비비에게 초안 받기(CHG-A07). 기준 원문의 버전과 고른 작업을 담아 요청하고, 손대지 않은 글만 초안으로 채운다.
function renderDraftForm(requestDraft: () => Promise<unknown>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['documents', '203', 'approved-content', '2031'], {
    content: '# 요구사항\n기준 원문',
    versionId: '2031',
  })
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[
          {
            id: '203',
            label: '요구사항 명세서 · 기준 v1',
            title: '요구사항 명세서',
            versionId: '2031',
          },
        ]}
        fetchDocumentContent={() =>
          Promise.resolve({
            content: '# 요구사항\n기준 원문',
            versionId: '2031',
          })
        }
        requestDraft={requestDraft as never}
        defaultValues={{
          title: '빈 목록 안내',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  return { text: () => screen.getByLabelText('고친 글') }
}

const draftResponse = {
  document_id: '203',
  base_document_version_id: '2031',
  after_content: '# 요구사항\n비비 초안',
  generation: { origin: 'MODEL', text: '초안', sources: [], unresolved: [] },
}

it('초안 받기는 기준 원문 버전을 담아 요청하고 손대지 않은 글을 초안으로 채운다', async () => {
  const user = userEvent.setup()
  const requestDraft = vi.fn().mockResolvedValue(draftResponse)
  const { text } = renderDraftForm(requestDraft)
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n기준 원문'))
  await user.click(
    await screen.findByRole('button', { name: '비비에게 초안 받기' }),
  )
  await user.type(
    screen.getByLabelText('어떻게 고칠지 알려 주세요'),
    '빈 화면 안내를 추가해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '초안 만들기' }))
  await waitFor(() => expect(text()).toHaveValue('# 요구사항\n비비 초안'))
  expect(requestDraft).toHaveBeenCalledWith({
    document_id: '203',
    base_document_version_id: '2031',
    instruction: '빈 화면 안내를 추가해 주세요.',
  })
})

it('초안 요청 입력은 제안 폼을 제출하지 않는다', async () => {
  const user = userEvent.setup()
  const requestDraft = vi.fn().mockResolvedValue(draftResponse)
  renderDraftForm(requestDraft)
  await user.click(
    await screen.findByRole('button', { name: '비비에게 초안 받기' }),
  )
  await user.type(
    screen.getByLabelText('어떻게 고칠지 알려 주세요'),
    '줄바꿈{enter}도 쓸 수 있어요',
  )
  expect(
    screen.queryByText('기준 문서와 달라진 내용이 없어요.'),
  ).not.toBeInTheDocument()
  expect(requestDraft).not.toHaveBeenCalled()
})

// 코드 리뷰(높음): 문서 A 로 초안을 요청하고 기다리는 동안 고칠 문서를 B 로 바꾸면 A 의 초안이 B 의 고친 글에 들어갔다.
function renderTwoDocumentForm(requestDraft: () => Promise<unknown>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const contents: Record<string, { content: string; versionId: string }> = {
    '203': { content: '# A 문서\n기준 원문', versionId: '2031' },
    '204': { content: '# B 문서\n기준 원문', versionId: '2041' },
  }
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[
          {
            id: '203',
            label: 'A 문서 · 기준 v1',
            title: 'A 문서',
            versionId: '2031',
          },
          {
            id: '204',
            label: 'B 문서 · 기준 v1',
            title: 'B 문서',
            versionId: '2041',
          },
        ]}
        fetchDocumentContent={(id) => Promise.resolve(contents[id])}
        requestDraft={requestDraft as never}
        defaultValues={{
          title: '안내 추가',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  return { text: () => screen.getByLabelText('고친 글') }
}

async function askForDraft(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    await screen.findByRole('button', { name: '비비에게 초안 받기' }),
  )
  await user.type(
    screen.getByLabelText('어떻게 고칠지 알려 주세요'),
    '안내를 추가해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '초안 만들기' }))
}

it('초안을 기다리는 동안 고칠 문서를 바꾸면 이전 문서의 초안을 새 문서에 넣지 않는다', async () => {
  const user = userEvent.setup()
  let resolve: (value: unknown) => void = () => undefined
  const { text } = renderTwoDocumentForm(
    () => new Promise((done) => (resolve = done)),
  )
  await waitFor(() => expect(text()).toHaveValue('# A 문서\n기준 원문'))
  await askForDraft(user)
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '204')
  await waitFor(() => expect(text()).toHaveValue('# B 문서\n기준 원문'))
  // 문서가 바뀌면 열려 있던 초안 패널과 받은 결과도 사라진다.
  expect(
    screen.queryByLabelText('어떻게 고칠지 알려 주세요'),
  ).not.toBeInTheDocument()
  resolve({
    document_id: '203',
    base_document_version_id: '2031',
    after_content: '# A 문서\nA 의 초안',
    generation: { origin: 'MODEL', text: '초안', sources: [], unresolved: [] },
  })
  await new Promise((done) => setTimeout(done, 50))
  expect(text()).toHaveValue('# B 문서\n기준 원문')
})

it('다른 문서로 바꾸면 열려 있던 초안 패널을 닫고 새 문서로 다시 받게 한다', async () => {
  const user = userEvent.setup()
  const { text } = renderTwoDocumentForm(() => Promise.resolve(draftResponse))
  await waitFor(() => expect(text()).toHaveValue('# A 문서\n기준 원문'))
  await user.click(
    await screen.findByRole('button', { name: '비비에게 초안 받기' }),
  )
  expect(screen.getByLabelText('어떻게 고칠지 알려 주세요')).toBeInTheDocument()
  await user.selectOptions(screen.getByLabelText('고칠 문서'), '204')
  await waitFor(() => expect(text()).toHaveValue('# B 문서\n기준 원문'))
  expect(
    screen.queryByLabelText('어떻게 고칠지 알려 주세요'),
  ).not.toBeInTheDocument()
  expect(
    await screen.findByRole('button', { name: '비비에게 초안 받기' }),
  ).toBeEnabled()
})

it('손댄 글이 있으면 초안이 와도 덮어쓰지 않고 바꾸기를 눌러야 바뀐다', async () => {
  const user = userEvent.setup()
  const { text } = renderTwoDocumentForm(() =>
    Promise.resolve({
      document_id: '203',
      base_document_version_id: '2031',
      after_content: '# A 문서\n비비 초안',
      generation: {
        origin: 'MODEL',
        text: '초안',
        sources: [],
        unresolved: [],
      },
    }),
  )
  await waitFor(() => expect(text()).toHaveValue('# A 문서\n기준 원문'))
  await user.type(text(), '\n내가 쓴 줄')
  await askForDraft(user)
  expect(
    await screen.findByText(/지금 쓰고 있는 글이 있어서 바꾸지 않았어요/),
  ).toBeInTheDocument()
  expect(text()).toHaveValue('# A 문서\n기준 원문\n내가 쓴 줄')
  await user.click(screen.getByRole('button', { name: '초안으로 바꾸기' }))
  expect(text()).toHaveValue('# A 문서\n비비 초안')
})

// 코드 리뷰(낮음): 초안이 409 version_conflict 로 실패해도 문서·원문을 다시 읽지 않아 같은 409 가 반복됐다.
it('초안이 기준 변경 409 로 실패하면 기준 원문과 문서 목록을 다시 읽는다', async () => {
  const user = userEvent.setup()
  const fetchContent = vi.fn().mockResolvedValue({
    content: '# A 문서\n기준 원문',
    versionId: '2031',
  })
  const onDraftConflict = vi.fn()
  const conflictError = Object.assign(new Error('conflict'), {
    isAxiosError: true,
    response: {
      status: 409,
      data: { error: { code: 'version_conflict', message: 'x' } },
    },
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ProposalForm
        heading="문서 수정 제안"
        submitLabel="제안 올리기"
        tasks={[]}
        documents={[
          {
            id: '203',
            label: 'A 문서 · 기준 v1',
            title: 'A 문서',
            versionId: '2031',
          },
        ]}
        fetchDocumentContent={fetchContent}
        requestDraft={() => Promise.reject(conflictError)}
        onDraftConflict={onDraftConflict}
        defaultValues={{
          title: '안내 추가',
          reason: '안내가 필요해요.',
          task_id: '',
          document_id: '203',
          after_content: '',
        }}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
        onCancel={vi.fn()}
      />
    </QueryClientProvider>,
  )
  await waitFor(() =>
    expect(screen.getByLabelText('고친 글')).toHaveValue('# A 문서\n기준 원문'),
  )
  expect(fetchContent).toHaveBeenCalledTimes(1)
  await askForDraft(user)
  await waitFor(() => expect(onDraftConflict).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(fetchContent).toHaveBeenCalledTimes(2))
})
