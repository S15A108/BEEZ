import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { DraftCandidates } from './DraftCandidates'
import type { DraftCandidate, DraftJob } from './schemas'

const job = (id: string, extra: Partial<DraftJob>): DraftJob => ({
  id,
  document_version_id: '11',
  status: 'COMPLETED',
  mode: 'DOCUMENT_ONLY',
  candidates: [],
  ...extra,
})

const show = (jobs: DraftJob[]) => {
  const onGenerate = vi.fn()
  render(
    <DraftCandidates
      jobs={jobs}
      labelOfVersion={(id) => `문서 ${id}`}
      titleOf={() => '문서'}
      isLeader
      busy={false}
      canGenerate
      onGenerate={onGenerate}
      onAdopt={vi.fn()}
      onDiscard={vi.fn()}
    />,
  )
  return onGenerate
}

// 한 문서 버전에는 동시에 하나의 작업만 돈다(서버 409 draft_in_progress). 문서에서 후보를 만드는 중에 눌러 충돌하지 않게 한다.
it('문서에서 후보를 만드는 중에는 답변으로 보완 버튼을 막고 이유를 알린다', () => {
  show([job('1', { status: 'RUNNING' })])
  expect(
    screen.getByRole('button', { name: '답변으로 보완 초안 만들기' }),
  ).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent(
    '문서에서 후보를 만드는 중이라',
  )
})

it('만드는 작업이 없으면 버튼이 켜져 있고 안내도 없다', () => {
  show([job('1', {})])
  expect(
    screen.getByRole('button', { name: '답변으로 보완 초안 만들기' }),
  ).toBeEnabled()
  expect(screen.queryByRole('status')).toBeNull()
})

// PR #303 재검토 6: 다른 후보를 보고 돌아와도 근거 확인 체크가 그대로 남는다.
it('다른 후보를 보고 돌아와도 근거 확인 체크가 그대로 남는다', async () => {
  const user = userEvent.setup()
  const candidate = (
    id: string,
    key: string,
    support: DraftCandidate['support'],
  ): DraftCandidate => ({
    id,
    candidate_key: key,
    change: 'CREATE',
    kind: 'POLICY',
    status: 'PENDING',
    after: { description: `${key} 설명` },
    changed_fields: [],
    conflicting_fields: [],
    sources: [],
    answer_refs: [],
    support,
  })
  show([
    job('1', {
      mode: 'WITH_ANSWERS',
      candidates: [
        candidate('21', 'policy-a', 'NONE'),
        candidate('22', 'policy-b', 'DOCUMENT'),
      ],
    }),
  ])
  const row = (key: string) =>
    screen.getByRole('button', { name: (name) => name.startsWith(key) })
  await user.click(row('policy-a'))
  const confirm = screen.getByRole('checkbox', {
    name: /근거를 찾지 못한 제안이라는 점을 확인했어요/,
  })
  await user.click(confirm)
  expect(screen.getByRole('button', { name: '채택' })).toBeEnabled()
  await user.click(row('policy-b'))
  await user.click(row('policy-a'))
  expect(
    screen.getByRole('checkbox', {
      name: /근거를 찾지 못한 제안이라는 점을 확인했어요/,
    }),
  ).toBeChecked()
  expect(screen.getByRole('button', { name: '채택' })).toBeEnabled()
})
