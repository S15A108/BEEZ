import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { beeApi } from '@/api/services'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { BibiPanel } from './BibiPanel'

// PR #218: 업무 창은 하나만 뜬다. 근거를 눌러 문서함으로 가면 BibiPanel이 내려가도 대화는 남아야 한다.
const answer = {
  origin: 'TEMPLATE' as const,
  text: '기획안의 초안 v2를 확인해 주세요.',
  sources: [
    {
      document_id: '201',
      document_version_id: '215',
      version_no: 2,
      location: '기획안',
    },
  ],
  unresolved: [],
}

function Workspace({ projectId }: { projectId: string }) {
  const pane = useWorkspaceStore((state) => state.pane)
  return pane === 'chat' ? (
    <BibiPanel projectId={projectId} />
  ) : (
    <p>{pane} 창</p>
  )
}

function renderWorkspace() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const tree = (projectId: string) => (
    <QueryClientProvider client={client}>
      <Workspace projectId={projectId} />
    </QueryClientProvider>
  )
  const view = render(tree('101'))
  return {
    switchProject: (projectId: string) => view.rerender(tree(projectId)),
  }
}

async function send(question: string) {
  await userEvent.type(screen.getByLabelText('설계 질문'), question)
  await userEvent.click(screen.getByRole('button', { name: '보내기' }))
}

const log = () => screen.getByRole('log', { name: '비비 설계 질의응답' })
const backToChat = () =>
  act(() => useWorkspaceStore.getState().openPane('chat'))

beforeEach(() => {
  useWorkspaceStore.setState({ pane: 'chat', focusId: null })
})
afterEach(() => {
  vi.restoreAllMocks()
})

it('근거를 눌러 문서함에 갔다가 비비 창으로 돌아와도 대화가 남아 있다', async () => {
  vi.spyOn(beeApi, 'ask').mockResolvedValue(answer)
  renderWorkspace()
  await send('초대 만료 안내는 정해졌나요?')
  expect(await within(log()).findByText(answer.text)).toBeInTheDocument()

  await userEvent.click(
    within(screen.getByRole('complementary')).getByRole('button', {
      name: /문서 201 v2/,
    }),
  )
  expect(screen.getByText('documents 창')).toBeInTheDocument()

  backToChat()
  expect(
    within(log()).getByText('초대 만료 안내는 정해졌나요?'),
  ).toBeInTheDocument()
  expect(within(log()).getByText(answer.text)).toBeInTheDocument()
})

it('대화는 프로젝트별로 나뉜다', async () => {
  vi.spyOn(beeApi, 'ask').mockResolvedValue(answer)
  const { switchProject } = renderWorkspace()
  await send('초대 만료 안내는 정해졌나요?')
  expect(await within(log()).findByText(answer.text)).toBeInTheDocument()

  switchProject('102')
  expect(
    within(log()).queryByText('초대 만료 안내는 정해졌나요?'),
  ).not.toBeInTheDocument()

  switchProject('101')
  expect(
    within(log()).getByText('초대 만료 안내는 정해졌나요?'),
  ).toBeInTheDocument()
})

it('창이 내려간 뒤에 도착한 답도 대화에 붙는다', async () => {
  let resolve!: (value: typeof answer) => void
  vi.spyOn(beeApi, 'ask').mockReturnValue(
    new Promise((done) => (resolve = done)),
  )
  renderWorkspace()
  await send('로그인 실패는 어떻게 처리하나요?')
  expect(screen.getByRole('status')).toHaveTextContent('찾는 중')

  act(() => useWorkspaceStore.getState().openPane('documents'))
  await act(async () => resolve(answer))

  backToChat()
  expect(
    within(log()).getByText('로그인 실패는 어떻게 처리하나요?'),
  ).toBeInTheDocument()
  expect(within(log()).getByText(answer.text)).toBeInTheDocument()
})
