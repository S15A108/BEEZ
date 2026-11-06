import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { changesApi } from '@/api/services/changes'
import { ScopedDraft } from './ScopedDraft'

vi.mock('@/api/services/changes', () => ({
  changesApi: { scopedBlocks: vi.fn(), scopedDraft: vi.fn() },
}))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(changesApi.scopedBlocks).mockResolvedValue({
    document_id: '1',
    base_document_version_id: '2',
    text_hash: 'abc',
    parser_profile: 'plain-v1',
    parser_version: '1',
    blocks: [
      {
        block_id: 'block-1',
        block_type: 'paragraph',
        locator: { type: 'text_lines', start_line: 1, end_line: 1 },
        text: '기존 문장\n',
      },
    ],
  })
  vi.mocked(changesApi.scopedDraft).mockResolvedValue({
    document_id: '1',
    base_document_version_id: '2',
    after_content: '새 문장\n',
    changes: [
      {
        block_id: 'block-1',
        before: '기존 문장\n',
        after: '새 문장\n',
        reason: '합의',
        evidence: [],
      },
    ],
    unresolved: [],
    generation: { origin: 'MODEL', model: 'test', usage: [] },
  })
})

it('선택한 블록만 요청하고 전후를 확인한 뒤 적용한다', async () => {
  const user = userEvent.setup()
  const apply = vi.fn()
  render(
    <ScopedDraft
      projectId="10"
      documentId="1"
      versionId="2"
      currentContent="기존 문장\n"
      onApply={apply}
    />,
  )
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  await user.click(await screen.findByRole('checkbox', { name: /기존 문장/ }))
  await user.type(screen.getByLabelText('수정 요청'), '팀장으로 변경')
  await user.click(screen.getByText('직접 입력한 참고 내용 (선택)'))
  await user.type(
    screen.getByLabelText('직접 입력한 합의 내용'),
    '팀장만 삭제\n권한 기록',
  )
  await user.type(
    screen.getByLabelText('직접 입력한 관련 변경 제안'),
    '권한 개선',
  )
  await user.click(
    screen.getByRole('button', { name: '선택 범위 초안 만들기' }),
  )
  expect(await screen.findByText('새 문장')).toBeInTheDocument()
  expect(apply).not.toHaveBeenCalled()
  expect(changesApi.scopedDraft).toHaveBeenCalledWith(
    '10',
    expect.objectContaining({
      document_id: '1',
      base_document_version_id: '2',
      selected_block_ids: ['block-1'],
      agreements: ['팀장만 삭제', '권한 기록'],
      related_proposals: ['권한 개선'],
    }),
  )
  await user.click(screen.getByRole('button', { name: '초안 적용' }))
  expect(apply).toHaveBeenCalledWith('새 문장\n')
})

it('결과를 만든 뒤 고친 글이 바뀌면 초안으로 덮어쓰지 않는다', async () => {
  const user = userEvent.setup()
  const apply = vi.fn()
  const props = {
    projectId: '10',
    documentId: '1',
    versionId: '2',
    currentContent: '기존 문장\n',
    onApply: apply,
  }
  const view = render(<ScopedDraft {...props} />)
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  await user.click(await screen.findByRole('checkbox', { name: /기존 문장/ }))
  await user.type(screen.getByLabelText('수정 요청'), '수정')
  await user.click(
    screen.getByRole('button', { name: '선택 범위 초안 만들기' }),
  )
  await screen.findByText('새 문장')
  view.rerender(<ScopedDraft {...props} currentContent="직접 수정" />)
  expect(screen.getByRole('button', { name: '초안 적용' })).toBeDisabled()
  expect(apply).not.toHaveBeenCalled()
})

it('문서 버전이 바뀌면 늦게 도착한 이전 초안을 표시하거나 적용하지 않는다', async () => {
  const user = userEvent.setup()
  let resolve!: (
    value: Awaited<ReturnType<typeof changesApi.scopedDraft>>,
  ) => void
  const response = await changesApi.scopedDraft('10', {
    document_id: '1',
    base_document_version_id: '2',
    selected_block_ids: ['block-1'],
    instruction: '수정',
  })
  vi.mocked(changesApi.scopedDraft).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const apply = vi.fn()
  const props = {
    projectId: '10',
    documentId: '1',
    versionId: '2',
    currentContent: '기존 문장\n',
    onApply: apply,
  }
  const view = render(<ScopedDraft {...props} />)
  await user.click(screen.getByRole('button', { name: '수정 범위 불러오기' }))
  await user.click(await screen.findByRole('checkbox', { name: /기존 문장/ }))
  await user.type(screen.getByLabelText('수정 요청'), '수정')
  await user.click(
    screen.getByRole('button', { name: '선택 범위 초안 만들기' }),
  )
  view.rerender(<ScopedDraft {...props} versionId="3" />)
  await act(async () => {
    resolve(response)
    await Promise.resolve()
  })
  expect(screen.queryByText('수정 전후 확인')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '초안 적용' }),
  ).not.toBeInTheDocument()
  expect(apply).not.toHaveBeenCalled()
})
