import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, expect, it } from 'vitest'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '@/components/ui/dialog'
import { Hotbar } from '@/features/workspace/Hotbar'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { BibiPanel } from './BibiPanel'

beforeEach(() => useWorkspaceStore.setState({ pane: null, focusId: null }))

// 업무 창(WorkspacePage)처럼 단축 바 가운데 비비 버튼이 여는 Radix 창 안에 비비 대화를 띄운다.
const client = new QueryClient()
function Workspace() {
  const pane = useWorkspaceStore((state) => state.pane)
  return (
    <QueryClientProvider client={client}>
      <Hotbar locked={[]} badges={{}} />
      <Dialog open={pane === 'chat'}>
        <DialogContent>
          <DialogTitle>비비와 대화</DialogTitle>
          <DialogDescription>예시 프로젝트</DialogDescription>
          <BibiPanel projectId="101" />
        </DialogContent>
      </Dialog>
    </QueryClientProvider>
  )
}

// S15P31A108-364: 창이 첫 포커스 요소인 '승인된 문서' 칩에 포커스를 줘서 열자마자 친 질문이 들어가지 않았다.
it('단축 바로 비비 창을 열면 질문 입력칸에 포커스가 가서 바로 입력된다', async () => {
  render(<Workspace />)
  await userEvent.click(screen.getByRole('button', { name: '비비와 대화' }))
  expect(screen.getByLabelText('설계 질문')).toHaveFocus()
  await userEvent.keyboard('로그인 실패는 어떻게 하나요?')
  expect(screen.getByLabelText('설계 질문')).toHaveValue(
    '로그인 실패는 어떻게 하나요?',
  )
})
