import { expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { ApprovalPreview } from './ApprovalPreview'
import { approvalPreviewResponseSchema } from './schemas'
import type { ApprovalPreviewResponse } from './schemas'

// CHG-A14 응답(명세 예시): CREATE 는 design_item_id 가 없고, 유지 항목은 proposal_item_index 가 없다.
const payload = {
  next_baseline_version_no: 2,
  baseline_items: [
    {
      design_item_id: '701',
      title: 'REQ-001 프로젝트 목록 빈 화면 안내',
      version_no: 2,
      operation: 'UPDATE',
      proposal_item_index: 0,
    },
    {
      title: '새 안내 문구',
      version_no: 1,
      operation: 'CREATE',
      proposal_item_index: 1,
    },
    {
      design_item_id: '702',
      title: 'REQ-002',
      version_no: 1,
      operation: 'UNCHANGED',
    },
  ],
  validations_to_stale: [
    { validation_id: '601', task_id: '502', task_title: '프로젝트 목록 화면' },
  ],
  tasks_to_wait: [
    { task_id: '503', title: '초대 문구 작업', assignee_member_id: '23' },
  ],
  unchanged: { items: 1, documents: 4 },
  unreviewed: {
    ai_candidates: 0,
    open_review_requests: 1,
    ai_analysis: {
      status: 'COMPLETED',
      is_current: true,
      reanalysis_in_progress: false,
    },
    requires_override_reason: false,
  },
}

function parse(patch: Partial<ApprovalPreviewResponse['unreviewed']> = {}) {
  return approvalPreviewResponseSchema.parse({
    ...payload,
    unreviewed: { ...payload.unreviewed, ...patch },
  })
}

function renderPreview(preview: ApprovalPreviewResponse, documents = 1) {
  render(
    <ApprovalPreview
      preview={preview}
      changedDocuments={documents}
      nameOfMember={(id) => (id === '23' ? '최팀원' : '담당자 확인 필요')}
    />,
  )
}

it('새 기준에서 바뀌는 항목과 그대로인 항목·문서 수를 한 문장으로 보여 준다', () => {
  renderPreview(parse())
  expect(
    screen.getByText(
      /기준 v2에 설계 항목 1개 추가, 1개 수정, 문서 1개 수정이 들어가요\. 나머지 항목 1개와 문서 4개는 그대로예요\./,
    ),
  ).toBeInTheDocument()
})

it('다시 검증이 필요해지는 검증과 대기가 되는 작업을 담당자와 함께 보여 준다', () => {
  renderPreview(parse())
  const stale = screen.getByRole('region', {
    name: '다시 검증이 필요해지는 검증 1개',
  })
  expect(within(stale).getByText('프로젝트 목록 화면')).toBeInTheDocument()
  expect(within(stale).getByText('다시 검증 필요')).toBeInTheDocument()
  const waiting = screen.getByRole('region', {
    name: '대기 · 재검증이 되는 작업 1개',
  })
  expect(within(waiting).getByText('초대 문구 작업')).toBeInTheDocument()
  expect(within(waiting).getByText('담당 최팀원')).toBeInTheDocument()
})

it('영향받는 검증과 작업이 없으면 없다고 말한다', () => {
  renderPreview({
    ...parse(),
    validations_to_stale: [],
    tasks_to_wait: [],
  })
  expect(
    screen.getByText('다시 검증이 필요해지는 검증이 없어요.'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('대기 · 재검증이 되는 진행 중 작업이 없어요.'),
  ).toBeInTheDocument()
})

it('영향 분석이 믿을 수 없어 후보가 null 이면 0개가 아니라 평가하지 못했다고 보인다', () => {
  renderPreview(
    parse({
      ai_candidates: null,
      ai_analysis: {
        status: 'FAILED',
        is_current: false,
        error_code: 'analysis_failed',
        reanalysis_in_progress: false,
      },
      requires_override_reason: true,
    }),
  )
  const box = screen.getByRole('region', { name: '확인하지 못한 것' })
  expect(box).toHaveTextContent('AI 영향 후보: 아직 평가하지 못했어요')
  expect(box).not.toHaveTextContent('AI 영향 후보: 0개')
  expect(box).toHaveTextContent('영향 분석에 실패했어요.')
  expect(box).toHaveTextContent('아직 확인하지 못한 것이 있어요')
})

it('답하지 않은 검토 요청이 있으면 개수를 알리고 경고색으로 보인다', () => {
  renderPreview(parse({ open_review_requests: 2 }))
  const box = screen.getByRole('region', { name: '확인하지 못한 것' })
  expect(box).toHaveTextContent('답하지 않은 검토 요청: 2개')
  expect(box).toHaveTextContent('아직 확인하지 못한 것이 있어요')
})

it('확인하지 못한 것이 없으면 차분하게 보인다', () => {
  renderPreview(parse({ open_review_requests: 0 }))
  const box = screen.getByRole('region', { name: '확인하지 못한 것' })
  expect(box).toHaveTextContent('확인하지 못한 것이 없어요')
  expect(box).not.toHaveTextContent('아직 확인하지 못한 것이 있어요')
  expect(box).toHaveTextContent('영향 분석이 끝났어요.')
})

it.each([
  [
    { status: 'COMPLETED', is_current: true, reanalysis_in_progress: true },
    '다시 분석하는 중이에요',
  ],
  [
    { status: 'RUNNING', is_current: false, reanalysis_in_progress: false },
    '영향 분석이 진행 중이에요',
  ],
  [
    {
      status: 'NOT_EVALUATED',
      is_current: false,
      error_code: 'analysis_not_started',
      reanalysis_in_progress: false,
    },
    '아직 영향 분석을 하지 않았어요.',
  ],
  [
    {
      status: 'NOT_EVALUATED',
      is_current: false,
      reanalysis_in_progress: false,
    },
    '영향을 평가하지 못했어요.',
  ],
  [
    {
      status: 'INSUFFICIENT_EVIDENCE',
      is_current: true,
      reanalysis_in_progress: false,
    },
    '근거가 부족해서 영향을 확정하지 못했어요.',
  ],
  [
    { status: 'STALE', is_current: false, reanalysis_in_progress: false },
    '제안이 바뀌어 이전 분석이 오래됐어요.',
  ],
])('영향 분석 상태 %j 는 "%s" 로 알린다', (analysis, text) => {
  // 서버는 완료 · 최신 · 재분석 아님일 때만 후보 수를 주므로 믿을 수 없는 상태에서는 늘 null 이다.
  renderPreview(parse({ ai_candidates: null, ai_analysis: analysis as never }))
  expect(
    screen.getByRole('region', { name: '확인하지 못한 것' }),
  ).toHaveTextContent(text)
})

it('분석 상태를 알 수 없으면 확인하지 못했다고 알린다', () => {
  renderPreview(parse({ ai_analysis: null }))
  expect(
    screen.getByRole('region', { name: '확인하지 못한 것' }),
  ).toHaveTextContent('영향 분석 상태를 확인하지 못했어요.')
})

it('서버가 후보 키를 빼면 영향 없음으로 오해하지 않도록 응답을 거절한다', () => {
  const { ai_candidates: _omitted, ...rest } = payload.unreviewed
  void _omitted
  expect(
    approvalPreviewResponseSchema.safeParse({ ...payload, unreviewed: rest })
      .success,
  ).toBe(false)
  expect(
    approvalPreviewResponseSchema.safeParse({
      ...payload,
      unreviewed: { ...payload.unreviewed, ai_candidates: null },
    }).success,
  ).toBe(true)
})

// 실제 화면 확인에서 찾은 문제: 재분석 중에 '확인하지 못한 것이 없어요'라고 하면 안 된다.
// 서버는 재분석 중이면 이전 결과가 보여도 후보 수를 null 로 보낸다(ImpactApprovalGate).
it('재분석 중이면 후보를 평가하지 못했다고 하고 확인할 것이 남았다고 말한다', () => {
  renderPreview(
    parse({
      ai_candidates: null,
      open_review_requests: 0,
      ai_analysis: {
        status: 'COMPLETED',
        is_current: true,
        reanalysis_in_progress: true,
      },
    }),
  )
  const box = screen.getByRole('region', { name: '확인하지 못한 것' })
  expect(box).toHaveTextContent('아직 확인하지 못한 것이 있어요')
  expect(box).toHaveTextContent('AI 영향 후보: 아직 평가하지 못했어요')
  expect(box).not.toHaveTextContent('확인하지 못한 것이 없어요')
})

it('담당자가 없는 작업은 담당자 확인 필요가 아니라 미배정으로 보인다', () => {
  renderPreview({
    ...parse(),
    tasks_to_wait: [{ task_id: '504', title: '담당 없는 작업' }],
  })
  const waiting = screen.getByRole('region', {
    name: '대기 · 재검증이 되는 작업 1개',
  })
  expect(within(waiting).getByText('담당 미배정')).toBeInTheDocument()
  expect(
    within(waiting).queryByText(/담당자 확인 필요/),
  ).not.toBeInTheDocument()
})

it('항목만 수정하는 제안은 무엇이 수정되는지 밝혀 적는다', () => {
  renderPreview(
    {
      ...parse(),
      baseline_items: [
        {
          design_item_id: '701',
          title: 'REQ-001',
          version_no: 2,
          operation: 'UPDATE',
          proposal_item_index: 0,
        },
      ],
    },
    0,
  )
  expect(
    screen.getByText(/기준 v2에 설계 항목 1개 수정이 들어가요\./),
  ).toBeInTheDocument()
})
