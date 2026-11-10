import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { parseCitationFocus } from '@/lib/citationTarget'
import { BibiPanel } from './BibiPanel'

const ask = vi.hoisted(() => vi.fn())
vi.mock('@/api/services', () => ({ beeApi: { ask } }))

const openPane = vi.fn()
const originalOpenPane = useWorkspaceStore.getState().openPane
const cited = {
  origin: 'MODEL',
  model: 'model',
  text: '승인된 정책입니다. [1]',
  sources: [
    {
      document_id: '20',
      document_version_id: '21',
      version_no: 2,
      location: '초대 / L4-L6',
    },
  ],
  unresolved: [],
  answer_id: '30',
  answer_status: 'ANSWERED',
  grounded: true,
  verification: { level: 'TOKENS', claims: 1 },
  citations: [
    {
      n: 1,
      document_id: '20',
      document_version_id: '21',
      version_no: 2,
      title: '접근 정책',
      locator: { type: 'text_lines', start_line: 4, end_line: 6 },
      section_title: '초대',
      quote: '초대는 한 번만 사용할 수 있다.',
    },
  ],
  design_refs: [],
  task_refs: [],
}

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <BibiPanel projectId="1" />
    </QueryClientProvider>,
  )
}

describe('비비 답변 근거', () => {
  beforeEach(() => {
    ask.mockReset()
    openPane.mockReset()
    useWorkspaceStore.setState({ openPane })
  })
  afterEach(() => useWorkspaceStore.setState({ openPane: originalOpenPane }))

  it('번호에 연결된 원문과 정확한 문서 버전을 보여 준다', async () => {
    ask.mockResolvedValue(cited)
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '초대 정책은?')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    const reference = await screen.findByRole('button', {
      name: '인용 1번 근거 보기',
    })
    await user.click(reference)
    const panel = screen.getByRole('complementary', {
      name: '선택한 답의 근거',
    })
    expect(
      within(panel).getByText('초대는 한 번만 사용할 수 있다.'),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText(/답변의 의미는 직접 확인/),
    ).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: /문서 20 v2/ }))
    expect(openPane).toHaveBeenCalledWith(
      'documents',
      expect.stringMatching(/^citation:/),
    )
    expect(parseCitationFocus(openPane.mock.calls[0][1])).toMatchObject({
      document_id: '20',
      document_version_id: '21',
      start_line: 4,
      end_line: 6,
      quote: '초대는 한 번만 사용할 수 있다.',
    })
  })

  it('근거 부족을 알리고 이전 답변의 번호를 누르면 그 답의 근거로 돌아간다', async () => {
    ask.mockResolvedValueOnce(cited).mockResolvedValueOnce({
      origin: 'MODEL',
      text: '찾지 못했어요.',
      sources: [],
      unresolved: [],
      answer_status: 'INSUFFICIENT_EVIDENCE',
      grounded: false,
      citations: [],
      verification: { level: 'NONE', claims: 0 },
    })
    renderPanel()
    const user = userEvent.setup()
    const input = screen.getByLabelText('설계 질문')
    await user.type(input, '첫 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    await screen.findByRole('button', { name: '인용 1번 근거 보기' })
    await user.type(input, '두 번째 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByText('찾지 못했어요.')).toBeInTheDocument()
    const panel = screen.getByRole('complementary', {
      name: '선택한 답의 근거',
    })
    expect(
      within(panel).getByText('이 답변에서 확인된 근거가 없어요.'),
    ).toBeInTheDocument()
    expect(
      within(panel).queryByText('초대는 한 번만 사용할 수 있다.'),
    ).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '인용 1번 근거 보기' }))
    expect(
      within(panel).getByText('초대는 한 번만 사용할 수 있다.'),
    ).toBeInTheDocument()
  })

  it('구버전 응답은 검증 수준을 추측하지 않는다', async () => {
    ask.mockResolvedValue({
      origin: 'MODEL',
      text: '이전 응답',
      sources: [],
      unresolved: [],
    })
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(
      await screen.findByText('근거 확인 수준은 제공되지 않았어요.'),
    ).toBeInTheDocument()
  })
})
