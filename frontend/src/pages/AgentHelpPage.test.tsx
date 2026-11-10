import { afterEach, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import AgentHelpPage from './AgentHelpPage'

afterEach(() => vi.restoreAllMocks())

const renderAt = (url: string) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <AgentHelpPage />
    </MemoryRouter>,
  )

// PR #230 리뷰: 라우트가 lazy라 첫 로드에서 브라우저가 해시로 스크롤할 때 절이 아직 없다.
it('주소의 해시가 가리키는 절로 스크롤한다', () => {
  const scroll = vi.fn()
  Element.prototype.scrollIntoView = scroll
  renderAt('/help/agent-connection#troubleshooting')
  expect(scroll).toHaveBeenCalledTimes(1)
  expect(scroll.mock.contexts[0]).toBe(
    screen.getByRole('region', { name: '문제 해결' }),
  )
})

// PR #230 리뷰: 연결 미확인은 진행 중 작업을 맡은 채 30분 넘게 조용한 것(AGT-A05)이고, #229로 연결 끊김이 생겼다.
it('상태 표는 연결 미확인을 진행 중 작업 기준으로 설명하고 연결 끊김을 보인다', () => {
  renderAt('/help/agent-connection')
  const check = within(
    screen.getByRole('region', { name: '4. Claude Code 켜고 연결 확인' }),
  )
  const unconfirmed = check.getByRole('row', { name: /^연결 미확인/ })
  expect(unconfirmed).toHaveTextContent('진행 중인 작업')
  expect(unconfirmed).not.toHaveTextContent('쉬는 중이면 정상')
  expect(check.getByRole('row', { name: /^연결 끊김/ })).toHaveTextContent(
    '2시간 넘게 통신이 없거나 세션이 끝났어요',
  )
})
