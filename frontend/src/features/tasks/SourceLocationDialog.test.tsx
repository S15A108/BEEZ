import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { documentsApi, planningApi } from '@/api/services'
import { SourceLocationDialog } from './SourceLocationDialog'

const target = {
  design_item_id: '884',
  design_item_version_id: '888',
  key: 'REQ-015',
}
const source = {
  document_id: '97',
  document_version_id: '104',
  version_no: 1,
  location: '',
}
const timestamp = '2026-11-13T03:00:00Z'

beforeEach(() => {
  vi.spyOn(documentsApi, 'get').mockResolvedValue({
    document: {
      id: '97',
      project_id: '30',
      kind: 'REQUIREMENTS',
      title: '요구사항',
      created_at: timestamp,
    },
    version: {
      id: '104',
      document_id: '97',
      version_no: 1,
      status: 'APPROVED',
      file_name: 'req.md',
      media_type: 'text/markdown',
      content_hash: 'sha256:x',
      content:
        '# 요구사항\n방장은 준비 취소를 거부한다.\n카운트다운은 3초다.\n',
      created_at: timestamp,
    },
  } as Awaited<ReturnType<typeof documentsApi.get>>)
})
afterEach(() => vi.restoreAllMocks())

function renderDialog(
  onConfirm = vi.fn().mockResolvedValue({}),
  known: typeof source | null = source,
) {
  const onClose = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SourceLocationDialog
        projectId="30"
        target={target}
        source={known ?? undefined}
        onClose={onClose}
        onConfirm={onConfirm}
      />
    </QueryClientProvider>,
  )
  return { onConfirm, onClose }
}

it('원문 줄을 번호와 함께 보이고 고른 줄 범위를 숫자로 보낸 뒤 닫는다', async () => {
  const { onConfirm, onClose } = renderDialog()
  expect(
    await screen.findByText('방장은 준비 취소를 거부한다.'),
  ).toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('시작 줄'), '2')
  await userEvent.type(screen.getByLabelText('끝 줄'), '3')
  await userEvent.click(screen.getByRole('button', { name: '이 줄로 지정' }))
  expect(onConfirm).toHaveBeenCalledWith({
    expected_document_version_id: '104',
    start_line: 2,
    end_line: 3,
  })
  expect(onClose).toHaveBeenCalled()
})

it('문서 밖이거나 거꾸로 된 범위는 보내지 않고 이유를 보인다', async () => {
  const { onConfirm } = renderDialog()
  await screen.findByText('방장은 준비 취소를 거부한다.')
  await userEvent.type(screen.getByLabelText('시작 줄'), '2')
  await userEvent.type(screen.getByLabelText('끝 줄'), '9')
  await userEvent.click(screen.getByRole('button', { name: '이 줄로 지정' }))
  expect(await screen.findByText('문서는 3줄까지 있어요.')).toBeInTheDocument()
  await userEvent.clear(screen.getByLabelText('끝 줄'))
  await userEvent.type(screen.getByLabelText('끝 줄'), '1')
  await userEvent.click(screen.getByRole('button', { name: '이 줄로 지정' }))
  expect(
    await screen.findByText('끝 줄은 시작 줄보다 앞설 수 없어요.'),
  ).toBeInTheDocument()
  expect(onConfirm).not.toHaveBeenCalled()
})

it('인용 없음으로 확정하고, 서버가 거절하면 창을 닫지 않고 이유를 보인다', async () => {
  const { onConfirm, onClose } = renderDialog(
    vi.fn().mockRejectedValue(new Error('거절')),
  )
  await screen.findByText('방장은 준비 취소를 거부한다.')
  await userEvent.click(
    screen.getByRole('button', { name: '인용 없음으로 확정' }),
  )
  expect(onConfirm).toHaveBeenCalledWith({
    expected_document_version_id: '104',
    no_quote: true,
  })
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(onClose).not.toHaveBeenCalled()
})

// 리뷰 반영: 작업 패널은 승인 항목 첫 100개만 읽는다. 뒤쪽 항목이면 창이 '출처 없음'으로 막혀 화면에서 고칠 수 없었다.
it('화면이 출처를 모르면 승인 항목을 마지막 페이지까지 읽어 찾는다', async () => {
  const approved = (id: string, withSource: boolean) => ({
    id: `${id}0`,
    design_item_id: id,
    key: `REQ-${id}`,
    kind: 'REQUIREMENT' as const,
    version_no: 1,
    status: 'APPROVED' as const,
    schema_version: 1,
    content: { description: '항목' },
    ...(withSource ? { source } : {}),
  })
  const items = vi
    .spyOn(planningApi, 'items')
    .mockResolvedValueOnce({
      items: [approved('1', true)],
      has_more: true,
      next_cursor: 'next',
    })
    .mockResolvedValueOnce({ items: [approved('884', true)], has_more: false })
  renderDialog(undefined, null)
  expect(
    await screen.findByText('방장은 준비 취소를 거부한다.'),
  ).toBeInTheDocument()
  expect(items).toHaveBeenLastCalledWith('30', {
    status: 'APPROVED',
    cursor: 'next',
    limit: 100,
  })
})

// PR #299 리뷰: 창을 연 사이 문서가 새로 발행되면 서버가 거절한다. 옛 원문에서 고른 줄을 그대로 다시 보내지 않게 입력을 비운다.
it('문서가 바뀌어 거절되면 이유를 보이고 고른 줄을 비운다', async () => {
  const changed = new AxiosError(
    'conflict',
    'ERR_BAD_REQUEST',
    undefined,
    undefined,
    {
      data: { error: { code: 'source_document_changed', message: '바뀜' } },
      status: 409,
      statusText: 'Conflict',
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
    },
  )
  const { onClose } = renderDialog(vi.fn().mockRejectedValue(changed))
  await screen.findByText('방장은 준비 취소를 거부한다.')
  await userEvent.type(screen.getByLabelText('시작 줄'), '2')
  await userEvent.type(screen.getByLabelText('끝 줄'), '3')
  await userEvent.click(screen.getByRole('button', { name: '이 줄로 지정' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '출처 문서가 새 버전으로 바뀌었어요.',
  )
  expect(screen.getByLabelText('시작 줄')).toHaveValue('')
  expect(screen.getByLabelText('끝 줄')).toHaveValue('')
  expect(onClose).not.toHaveBeenCalled()
})
