import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { DraftCandidates } from './DraftCandidates'
import type { DraftJob } from './schemas'

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
