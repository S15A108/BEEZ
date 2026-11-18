import { beforeEach, afterEach, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { webcrypto } from 'node:crypto'
import { authApi, meetingsApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { SummaryCard } from './SummaryCard'

let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

// 서버가 원문과 맞지 않아 버린 변경이나 입력 한도로 뺀 기록은 unresolved 에만 남는다. 팀장이 알고 끝내도록 보인다.
it('반영하지 못한 내용(unresolved)이 있으면 정리 카드에 알린다', async () => {
  const entries = await meetingsApi.entries('101', '901', {})
  const summary = await meetingsApi.summarize('101', '901', {
    through_entry_id: entries.items.at(-1)!.id,
  })
  const props = {
    documentTitleOf: () => '문서',
    nameOfMember: () => '팀원',
  }
  const { rerender } = render(<SummaryCard summary={summary} {...props} />)
  expect(
    screen.queryByRole('region', { name: '반영하지 못한 내용' }),
  ).not.toBeInTheDocument()
  rerender(
    <SummaryCard
      summary={{
        ...summary,
        generation: {
          ...summary.generation,
          unresolved: ['앞쪽 기록 3건은 입력 한도를 넘어 제외했어요.'],
        },
      }}
      {...props}
    />,
  )
  expect(
    screen.getByRole('region', { name: '반영하지 못한 내용' }),
  ).toHaveTextContent('앞쪽 기록 3건은 입력 한도를 넘어 제외했어요.')
})
