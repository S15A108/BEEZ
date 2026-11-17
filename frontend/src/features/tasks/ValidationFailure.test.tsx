import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { ValidationFailure } from './ValidationFailure'

it('서버가 멈춘 실행을 회수한 오류면 이유와 함께 결과를 반영하지 않았다고 알린다', () => {
  render(
    <ValidationFailure
      errorCode="validation_lease_expired"
      needsHumanReview={false}
    />,
  )
  expect(screen.getByRole('alert')).toHaveTextContent(
    '분석을 완료하지 못했어요. 자동으로 통과 처리하지 않아요.',
  )
  expect(
    screen.getByText(
      '분석 실행이 제한 시간 안에 끝나지 않아 서버가 실행을 회수했어요. 이번 실행 결과는 반영하지 않았고 작업 상태도 그대로예요.',
    ),
  ).toBeInTheDocument()
})

it('사람 검토가 필요한 오류면 이유와 보완 안내를 보인다', () => {
  render(
    <ValidationFailure errorCode="ai_evidence_insufficient" needsHumanReview />,
  )
  expect(screen.getByRole('alert')).toHaveTextContent(
    '근거를 확인하고 보완한 뒤 새 검증을 요청해 주세요.',
  )
  expect(
    screen.getByText(/근거가 부족해 일부 내용을 검사하지 못했어요\./),
  ).toBeInTheDocument()
})

it('모르는 오류 코드나 코드가 없으면 코드를 보이지 않고 결과만 알린다', () => {
  const { rerender } = render(
    <ValidationFailure errorCode="brand_new_code" needsHumanReview={false} />,
  )
  const outcome = '이번 실행 결과는 반영하지 않았고 작업 상태도 그대로예요.'
  expect(screen.getByText(outcome)).toBeInTheDocument()
  expect(screen.queryByText(/brand_new_code/)).not.toBeInTheDocument()
  rerender(<ValidationFailure needsHumanReview={false} />)
  expect(screen.getByText(outcome)).toBeInTheDocument()
})

it('근거 수집 실패는 구체적인 원인과 재시도를 안내하고 계획 보완을 요구하지 않는다', () => {
  render(
    <ValidationFailure
      errorCode="evidence_collection_failed"
      needsHumanReview={false}
      collectionFailure={{
        message: '근거 수집을 완료하지 못했어요.',
        retryable: true,
        reason_codes: ['evidence_fetch_failed'],
      }}
    />,
  )
  expect(
    screen.getByRole('list', { name: '근거 수집 실패 원인' }),
  ).toHaveTextContent('근거를 가져오는 중 연결 또는 처리 오류가 발생했어요.')
  expect(screen.getByText(/근거 수집을 다시 시도해 주세요/)).toBeInTheDocument()
  expect(screen.queryByText(/보완한 뒤/)).not.toBeInTheDocument()
})

it('입력 한계가 섞이면 과거 재시도 표시 대신 원인과 수동 검토 경로를 안내한다', () => {
  render(
    <ValidationFailure
      needsHumanReview
      collectionFailure={{
        message: '근거 수집을 완료하지 못했어요.',
        retryable: true,
        reason_codes: [
          'search_results_truncated',
          'source_evidence_unavailable',
          'unknown_code',
        ],
      }}
    />,
  )
  expect(screen.getByRole('list')).toHaveTextContent(
    '검색 결과가 많아 일부 근거가 빠졌어요.',
  )
  expect(screen.getByRole('list')).toHaveTextContent(
    '근거 문서의 원문·출처 위치·발췌 범위를 확인해야 해요.',
  )
  expect(screen.getByText(/담당자와 계획·참조 범위/)).toHaveTextContent(
    '원문 근거를 보완한 뒤 새 검증을 요청해 주세요.',
  )
  expect(screen.getByRole('list')).toHaveTextContent(
    '근거 수집 실패의 상세 원인을 확인할 수 없어 담당자의 확인이 필요해요.',
  )
  expect(screen.queryByText(/근거 수집을 다시 시도해 주세요/)).toBeNull()
  expect(screen.queryByText(/잠시 후 다시 확인/)).toBeNull()
  expect(screen.queryByText(/unknown_code/)).toBeNull()
})

// S15P31A108-422: 원인 항목이 없으면 80개 항목을 REST로 뒤져야 했다. 팀장만 고칠 수 있다.
it('출처 때문에 막힌 설계 항목을 보이고 팀장이면 출처 위치 지정을 연다', async () => {
  const item = {
    design_item_id: '884',
    design_item_version_id: '888',
    key: 'REQ-015',
  }
  const onFixSource = vi.fn()
  const failure = {
    message: '근거를 확인하지 못했어요.',
    retryable: false,
    reason_codes: ['source_evidence_unavailable'],
    design_items: [item],
  }
  const { rerender } = render(
    <ValidationFailure
      needsHumanReview
      collectionFailure={failure}
      onFixSource={onFixSource}
    />,
  )
  await userEvent.click(
    screen.getByRole('button', { name: 'REQ-015 출처 위치 지정' }),
  )
  expect(onFixSource).toHaveBeenCalledWith(item)

  rerender(<ValidationFailure needsHumanReview collectionFailure={failure} />)
  expect(
    screen.getByRole('list', { name: '출처를 확인할 설계 항목' }),
  ).toHaveTextContent('REQ-015팀장이 출처 위치를 지정해야 해요.')
  expect(screen.queryByRole('button')).toBeNull()
})

it('문서가 바뀌어 출처 확인이 필요한 사유를 사람 말로 안내한다', () => {
  render(
    <ValidationFailure
      needsHumanReview
      collectionFailure={{
        message: '출처 위치를 확인하지 못했어요.',
        retryable: false,
        reason_codes: ['source_confirmation_required'],
      }}
    />,
  )
  expect(screen.getByRole('list')).toHaveTextContent(
    '문서가 바뀌어 설계 항목이 인용한 원문 위치를 확인해야 해요.',
  )
  expect(screen.queryByText(/source_confirmation_required/)).toBeNull()
  expect(screen.queryByText(/근거 수집을 다시 시도해 주세요/)).toBeNull()
})
