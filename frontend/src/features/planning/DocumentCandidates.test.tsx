import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { DocumentCandidates } from './DocumentCandidates'
import { currentVersionJobs, draftWork, publishNotice } from './draftJobs'
import type { DraftCandidate, DraftJob } from './schemas'

const candidate = (
  id: string,
  description: string,
  extra: Partial<DraftCandidate> = {},
): DraftCandidate => ({
  id,
  candidate_key: `draft-${id}`,
  change: 'CREATE',
  kind: 'REQUIREMENT',
  status: 'PENDING',
  after: { description },
  changed_fields: [],
  conflicting_fields: [],
  sources: [{ location: '1. 개요', quote: `${description} 원문` }],
  answer_refs: [],
  support: 'DOCUMENT',
  ...extra,
})

const job = (
  id: string,
  version: string,
  extra: Partial<DraftJob> = {},
): DraftJob => ({
  id,
  document_version_id: version,
  status: 'COMPLETED',
  mode: 'DOCUMENT_ONLY',
  candidates: [],
  ...extra,
})

const labelOf = (versionId: string) => `문서 ${versionId}`

const show = (jobs: DraftJob[], overrides: Record<string, unknown> = {}) => {
  const props = {
    jobs,
    labelOfVersion: labelOf,
    isLeader: true,
    busy: false,
    onRetry: vi.fn(),
    onAdopt: vi.fn(),
    onDiscard: vi.fn(),
    ...overrides,
  }
  render(<DocumentCandidates {...props} />)
  return props
}

it('문서별 상태를 따로 보인다: 만드는 중, 완료, 실패', () => {
  show([
    job('1', '11', {
      status: 'RUNNING',
      parts: { total: 9, completed: 5, failed: 0, failed_error_codes: [] },
    }),
    job('2', '12', { candidates: [candidate('21', '완료 후보')] }),
    job('3', '13', {
      status: 'FAILED',
      error_code: 'ai_output_too_long',
      attempt_no: 1,
      max_attempts: 3,
      parts: {
        total: 4,
        completed: 3,
        failed: 1,
        failed_error_codes: ['ai_output_too_long'],
      },
    }),
  ])
  expect(
    screen.getByText('문서 11', { selector: 'strong' }).closest('p'),
  ).toHaveTextContent('5/9번째 부분')
  expect(
    screen.getByText('문서 12', { selector: 'strong' }).closest('p'),
  ).toHaveTextContent('후보 1개를 만들었어요')
  const failed = screen.getByRole('alert')
  expect(failed).toHaveTextContent('문서 13')
  expect(failed).toHaveTextContent('출력 길이 상한')
  expect(failed).toHaveTextContent('4개 부분 중 1개를 만들지 못했어요')
})

it('실패 사유 세 가지가 서로 다른 안내로 나온다', () => {
  const messages = new Set<string>()
  for (const code of ['ai_timeout', 'ai_output_too_long', 'ai_unavailable']) {
    const view = render(
      <DocumentCandidates
        jobs={[job('1', '11', { status: 'FAILED', error_code: code })]}
        labelOfVersion={labelOf}
        isLeader
        busy={false}
        onRetry={vi.fn()}
        onAdopt={vi.fn()}
        onDiscard={vi.fn()}
      />,
    )
    messages.add(screen.getByRole('alert').textContent ?? '')
    view.unmount()
  }
  expect(messages.size).toBe(3)
})

it('실패한 문서만 다시 시도하고, 횟수를 다 쓰면 버튼 대신 안내가 나온다', async () => {
  const user = userEvent.setup()
  const props = show([
    job('1', '11', {
      status: 'FAILED',
      error_code: 'ai_timeout',
      attempt_no: 1,
      max_attempts: 3,
    }),
    job('2', '12', {
      status: 'FAILED',
      error_code: 'ai_timeout',
      attempt_no: 3,
      max_attempts: 3,
    }),
  ])
  const buttons = screen.getAllByRole('button', {
    name: '실패한 부분만 다시 시도',
  })
  expect(buttons).toHaveLength(1)
  await user.click(buttons[0])
  expect(props.onRetry).toHaveBeenCalledWith('11')
  expect(
    screen.getByText(/다시 시도할 수 있는 횟수를 다 썼어요/),
  ).toBeInTheDocument()
})

it('고른 후보만 채택 요청에 담고, 일부가 실패해도 건별 사유를 보여 준다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi.fn().mockResolvedValue({
    succeeded: 1,
    failed: 1,
    results: [
      { candidate_id: '21', result: 'ADOPTED', design_item_version_id: '901' },
      { candidate_id: '22', result: 'FAILED', error_code: 'candidate_stale' },
    ],
  })
  show(
    [
      job('2', '12', {
        candidates: [
          candidate('21', '첫째'),
          candidate('22', '둘째'),
          candidate('23', '셋째'),
        ],
      }),
    ],
    { onAdopt },
  )
  await user.click(screen.getByRole('checkbox', { name: '첫째 선택' }))
  await user.click(screen.getByRole('checkbox', { name: '둘째 선택' }))
  expect(screen.getByText('2개 선택')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(onAdopt).toHaveBeenCalledWith({
    items: [{ candidate_id: '21' }, { candidate_id: '22' }],
  })
  expect(
    await screen.findByText(/1개를 설계 항목 초안으로 채택했어요/),
  ).toBeInTheDocument()
  expect(screen.getByText(/1개는 처리하지 못했어요/)).toBeInTheDocument()
  expect(screen.getByText(/둘째: /)).toHaveTextContent('후보를 만든 뒤')
  // 실패한 후보만 선택으로 남는다.
  expect(screen.getByRole('checkbox', { name: '둘째 선택' })).toBeChecked()
  expect(screen.getByRole('checkbox', { name: '첫째 선택' })).not.toBeChecked()
})

it('아무것도 고르지 않으면 채택·폐기를 누를 수 없다', () => {
  show([job('2', '12', { candidates: [candidate('21', '첫째')] })])
  expect(
    screen.getByRole('button', { name: '선택한 항목 채택' }),
  ).toBeDisabled()
  expect(screen.getByRole('button', { name: '폐기' })).toBeDisabled()
})

it('근거 없는 후보는 확인 체크를 해야 채택할 수 있고 채택 요청에 확인이 담긴다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi.fn().mockResolvedValue({
    succeeded: 1,
    failed: 0,
    results: [{ candidate_id: '21', result: 'ADOPTED' }],
  })
  show(
    [
      job('2', '12', {
        candidates: [
          candidate('21', '근거 없는 후보', { support: 'NONE', sources: [] }),
        ],
      }),
    ],
    { onAdopt },
  )
  await user.click(
    screen.getByRole('checkbox', { name: '근거 없는 후보 선택' }),
  )
  const adopt = screen.getByRole('button', { name: '선택한 항목 채택' })
  expect(adopt).toBeDisabled()
  await user.click(
    screen.getByRole('checkbox', { name: /근거를 찾지 못한 제안/ }),
  )
  expect(adopt).toBeEnabled()
  await user.click(adopt)
  await waitFor(() =>
    expect(onAdopt).toHaveBeenCalledWith({
      items: [{ candidate_id: '21', confirm_no_evidence: true }],
    }),
  )
})

it('이미 있는 항목과 같은 후보는 접힌 영역에 따로 두고 전체 선택에 들어가지 않는다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi.fn().mockResolvedValue({
    succeeded: 1,
    failed: 0,
    results: [{ candidate_id: '21', result: 'ADOPTED' }],
  })
  show(
    [
      job('2', '12', {
        candidates: [
          candidate('21', '새 후보'),
          candidate('22', '겹치는 후보', {
            overlap: { kind: 'EXISTING_ITEM', ref_id: '7' },
          }),
        ],
      }),
    ],
    { onAdopt },
  )
  expect(screen.getByText(/비슷한 후보 1개/)).toBeInTheDocument()
  await user.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(onAdopt).toHaveBeenCalledWith({ items: [{ candidate_id: '21' }] })
})

it('팀장이 아니면 선택은 보여도 채택·폐기 버튼은 없다', () => {
  show([job('2', '12', { candidates: [candidate('21', '첫째')] })], {
    isLeader: false,
  })
  expect(screen.queryByRole('button', { name: '선택한 항목 채택' })).toBeNull()
})

it('문서에서 뽑은 작업이 없으면 자동 생성 조건을 안내한다', () => {
  show([])
  expect(screen.getByText(/승인되고 색인이 끝나면/)).toBeInTheDocument()
})

it('발행 안내는 미채택 후보 수와 후보를 다 못 만든 문서를 알린다', () => {
  const jobs = [
    job('2', '12', {
      candidates: [
        candidate('21', 'a'),
        candidate('22', 'b', { status: 'ADOPTED' }),
      ],
    }),
    job('3', '13', { status: 'FAILED', error_code: 'ai_timeout' }),
    job('4', '14', {
      mode: 'WITH_ANSWERS',
      candidates: [candidate('41', 'c')],
    }),
  ]
  const notice = publishNotice(jobs)
  expect(notice).toContain('아직 채택하지 않은 후보 2개')
  expect(notice).toContain('후보를 모두 만들지 못한 문서가 1개')
  expect(publishNotice([])).toBe('')
})

it('시작 전 요약은 채택하지 않은 후보·만드는 중·실패한 문서를 따로 센다', () => {
  expect(draftWork([]).open).toBe(false)
  const work = draftWork([
    job('2', '12', {
      candidates: [
        candidate('21', 'a'),
        candidate('22', 'b', { status: 'ADOPTED' }),
      ],
    }),
    job('3', '13', { status: 'FAILED', error_code: 'ai_timeout' }),
    job('4', '14', { status: 'RUNNING' }),
  ])
  expect(work).toMatchObject({ pending: 1, running: 1, failed: 1, open: true })
  expect(work.label).toBe('후보 1개 검토하기')
  expect(work.summary).toBe(
    '채택하지 않은 후보 1개 · 만드는 중 1건 · 만들지 못한 문서 1개',
  )
  // 후보를 모두 처리했으면 더 보여 줄 것이 없다.
  expect(
    draftWork([
      job('2', '12', {
        candidates: [candidate('22', 'b', { status: 'ADOPTED' })],
      }),
    ]).open,
  ).toBe(false)
})

it('자동으로 만들어지지 않은 문서는 팀장이 직접 후보를 만들 수 있다', async () => {
  const user = userEvent.setup()
  const onCreate = vi.fn()
  show([], { creatableVersionIds: ['11', '12'], onCreate })
  expect(
    screen.getByText('자동으로 만들어지지 않았다면 직접 만들 수 있어요.'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '문서에서 후보 만들기' }))
  expect(onCreate).toHaveBeenCalledWith(['11', '12'])
})

it('팀원에게는 직접 만들기 버튼이 없고, 만드는 중에는 막는다', () => {
  show([], { creatableVersionIds: ['11'], onCreate: vi.fn(), isLeader: false })
  expect(
    screen.queryByRole('button', { name: '문서에서 후보 만들기' }),
  ).toBeNull()
})

it('만드는 작업이 도는 동안에는 직접 만들기를 막는다', () => {
  show([job('1', '11', { status: 'RUNNING' })], {
    creatableVersionIds: ['12'],
    onCreate: vi.fn(),
  })
  expect(
    screen.getByRole('button', { name: '문서에서 후보 만들기' }),
  ).toBeDisabled()
})

it('다른 문서에서 뽑은 후보와 같은 후보는 그렇게 표시하고 전체 선택에서 뺀다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi
    .fn()
    .mockResolvedValue({ succeeded: 1, failed: 0, results: [] })
  show(
    [
      job('2', '12', {
        candidates: [
          candidate('21', '새 후보'),
          candidate('22', '겹치는 후보', {
            overlap: { kind: 'PENDING_CANDIDATE', ref_id: '21' },
          }),
        ],
      }),
    ],
    { onAdopt },
  )
  expect(screen.getByText(/비슷한 후보 1개/)).toBeInTheDocument()
  await user.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(onAdopt).toHaveBeenCalledWith({ items: [{ candidate_id: '21' }] })
})

it('이미 같은 항목이 있는 후보는 알고 채택한다는 확인을 해야 채택되고 요청에 확인이 담긴다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi
    .fn()
    .mockResolvedValue({ succeeded: 1, failed: 0, results: [] })
  show(
    [
      job('2', '12', {
        candidates: [
          candidate('22', '겹치는 후보', {
            overlap: { kind: 'EXISTING_ITEM', ref_id: '7' },
          }),
        ],
      }),
    ],
    { onAdopt },
  )
  await user.click(screen.getByText(/비슷한 후보 1개/))
  await user.click(screen.getByRole('checkbox', { name: '겹치는 후보 선택' }))
  const adopt = screen.getByRole('button', { name: '선택한 항목 채택' })
  expect(adopt).toBeDisabled()
  await user.click(
    screen.getByRole('checkbox', { name: /이미 같은 설계 항목이 있다는 점/ }),
  )
  expect(adopt).toBeEnabled()
  await user.click(adopt)
  expect(onAdopt).toHaveBeenCalledWith({
    items: [{ candidate_id: '22', confirm_duplicate: true }],
  })
})

it('이전에 폐기한 후보와 같은 후보는 확인 없이 채택할 수 있다', async () => {
  const user = userEvent.setup()
  show([
    job('2', '12', {
      candidates: [
        candidate('22', '폐기했던 후보', {
          overlap: { kind: 'DISCARDED_CANDIDATE', ref_id: '5' },
        }),
      ],
    }),
  ])
  await user.click(screen.getByText(/비슷한 후보 1개/))
  await user.click(screen.getByRole('checkbox', { name: '폐기했던 후보 선택' }))
  expect(screen.getByRole('button', { name: '선택한 항목 채택' })).toBeEnabled()
})

it('표시가 없던 후보가 candidate_duplicate 로 거절되면 다음부터 확인을 받는다', async () => {
  const user = userEvent.setup()
  const onAdopt = vi.fn().mockResolvedValue({
    succeeded: 0,
    failed: 1,
    results: [
      {
        candidate_id: '21',
        result: 'FAILED',
        error_code: 'candidate_duplicate',
      },
    ],
  })
  show([job('2', '12', { candidates: [candidate('21', '새 후보')] })], {
    onAdopt,
  })
  await user.click(screen.getByRole('checkbox', { name: '새 후보 선택' }))
  await user.click(screen.getByRole('button', { name: '선택한 항목 채택' }))
  expect(
    await screen.findByRole('checkbox', {
      name: /이미 같은 설계 항목이 있다는 점/,
    }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '선택한 항목 채택' }),
  ).toBeDisabled()
})

it('더 새 버전이 승인되어 대체된 문서 버전의 작업은 보이지 않는다', () => {
  const jobs = [
    job('1', '11', { candidates: [candidate('21', '옛 후보')] }),
    job('2', '12', { candidates: [candidate('22', '새 후보')] }),
  ]
  expect(currentVersionJobs(jobs, ['12']).map((item) => item.id)).toEqual(['2'])
  // 대체된 버전의 미채택 후보는 발행 안내와 시작 전 안내에도 세지 않는다.
  expect(draftWork(currentVersionJobs(jobs, ['12'])).pending).toBe(1)
})
