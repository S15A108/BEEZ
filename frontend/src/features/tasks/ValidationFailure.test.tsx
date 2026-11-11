import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
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
        reason_codes: [
          'search_results_truncated',
          'source_evidence_unavailable',
          'unknown_code',
        ],
      }}
    />,
  )
  expect(
    screen.getByRole('list', { name: '근거 수집 실패 원인' }),
  ).toHaveTextContent('검색 결과가 많아 일부 근거가 빠졌어요.')
  expect(screen.getByRole('list')).toHaveTextContent(
    '문서 원문에서 확인할 근거를 가져오지 못했어요.',
  )
  expect(screen.getByText(/근거 수집을 다시 시도해 주세요/)).toBeInTheDocument()
  expect(screen.queryByText(/보완한 뒤/)).not.toBeInTheDocument()
  expect(screen.queryByText(/unknown_code/)).not.toBeInTheDocument()
})
