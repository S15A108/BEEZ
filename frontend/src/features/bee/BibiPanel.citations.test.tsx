import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { parseCitationFocus } from '@/lib/citationTarget'
import { BibiPanel } from './BibiPanel'
import { askResponseSchema } from './schemas'

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
      location: '접근 정책 / L4-L6',
    },
  ],
  unresolved: [],
  answer_id: '30',
  grounded: true,
  mode: 'APPROVED',
  baseline_id: '10',
  document_version_ids: ['21'],
  verification: { level: 'TOKENS', claims: 1 },
  citations: [
    {
      n: 1,
      document_id: '20',
      document_version_id: '21',
      version_no: 2,
      title: '접근 정책',
      locator: { type: 'text_lines', start_line: 4, end_line: 6 },
      quote: '초대는 한 번만 사용할 수 있다.',
    },
  ],
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
    expect(within(panel).queryByText('참고 문서')).not.toBeInTheDocument()
    expect(
      within(panel).getAllByRole('button', { name: /문서 20 v2/ }),
    ).toHaveLength(1)
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
      answer_id: '31',
      mode: 'APPROVED',
      baseline_id: '10',
      document_version_ids: ['21'],
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

  it('인용과 같은 문서의 다른 출처 위치는 참고 문서에 남긴다', async () => {
    ask.mockResolvedValue({
      ...cited,
      sources: [
        ...cited.sources,
        { ...cited.sources[0], location: '승인 설계 / API-01' },
      ],
      notices: ['확인한 근거의 범위 안에서만 답했어요.'],
    })
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(
      await screen.findByText('확인한 근거의 범위 안에서만 답했어요.'),
    ).toBeInTheDocument()
    const panel = screen.getByRole('complementary')
    expect(within(panel).getByText('참고 문서')).toBeInTheDocument()
    expect(
      within(panel).getByRole('button', { name: /승인 설계 \/ API-01/ }),
    ).toBeInTheDocument()
    expect(
      within(panel).getAllByRole('button', { name: /문서 20 v2/ }),
    ).toHaveLength(2)
  })

  it('인용과 사용량이 없는 템플릿 답을 검증된 모델 답으로 표시하지 않는다', async () => {
    ask.mockResolvedValue(
      askResponseSchema.parse({
        origin: 'TEMPLATE',
        text: '문서의 원문을 직접 확인해 주세요.',
        sources: cited.sources,
        unresolved: [],
        answer_id: '32',
        grounded: false,
        citations: [],
        mode: 'APPROVED',
        baseline_id: '10',
        document_version_ids: ['21'],
      }),
    )
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(
      await screen.findByText('문서의 원문을 직접 확인해 주세요.'),
    ).toBeInTheDocument()
    // 모델 답을 쓰지 못한 응답에는 꼬리표가 없다(오너 결정 11-11). 줄 글자가 '찾은 문서 1개'뿐이다.
    expect(screen.getByText('찾은 문서 1개')).toBeInTheDocument()
    expect(screen.queryByText('템플릿 응답')).not.toBeInTheDocument()
    expect(
      screen.getByText('이 답변에서 확인된 근거가 없어요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('참고 문서')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /인용 \d+번 근거 보기/ }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('모델 생성')).not.toBeInTheDocument()
  })

  // S15P31A108-395: 참고 문서의 위치는 서버의 L4-L6 표기 대신 인용과 같은 4~6행 표기로 보인다.
  it('참고 문서의 줄 범위를 행 표기로 보인다', async () => {
    ask.mockResolvedValue({ ...cited, citations: [], grounded: false })
    renderPanel()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('설계 질문'), '질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    const panel = await screen.findByRole('complementary')
    expect(
      await within(panel).findByRole('button', { name: /접근 정책 . 4~6행/ }),
    ).toBeInTheDocument()
    expect(within(panel).queryByText(/L4-L6/)).not.toBeInTheDocument()
  })
})
