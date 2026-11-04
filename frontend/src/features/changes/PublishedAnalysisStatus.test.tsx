import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PublishedAnalysisStatus } from './PublishedAnalysisStatus'
import { publishedAnalysisSchema } from './schemas'
import type { PublishedAnalysis } from './schemas'

const requested = '2026-11-04T00:00:00Z'
const observed = Date.parse('2026-11-04T00:11:00Z')
const renderStatus = (analysis?: PublishedAnalysis, onRefresh = vi.fn()) =>
  render(
    <PublishedAnalysisStatus
      analysis={analysis}
      observedAt={observed}
      refreshing={false}
      onRefresh={onRefresh}
    />,
  )

describe('발행 후 분석 상태', () => {
  it('오래 대기한 자동 재시도는 대기와 재시도 시각을 보여 주되 장애로 단정하지 않는다', () => {
    renderStatus({
      status: 'PENDING',
      guidance: 'RETRY_SCHEDULED',
      requested_at: requested,
      next_attempt_at: '2026-11-04T00:12:00Z',
      attempt: 2,
    })
    expect(screen.getByText('오래 대기 중')).toBeInTheDocument()
    expect(
      screen.getByText(/환경 설정에 따라 대기할 수 있으며/),
    ).toBeInTheDocument()
    expect(
      screen.getByText('자동 재시도를 기다리고 있어요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('다음 재시도 예정')).toBeInTheDocument()
    expect(screen.getByText('2회')).toBeInTheDocument()
    expect(screen.queryByText('처리 실패')).not.toBeInTheDocument()
  })

  it('실행 lease가 만료되면 복구 중임을 확정하지 않고 확인이 필요함을 표시한다', () => {
    renderStatus({
      status: 'RUNNING',
      lease_expired: true,
      guidance: 'RECOVERY_PENDING',
    })
    expect(screen.getByText('복구 확인 중')).toBeInTheDocument()
    expect(screen.getByText(/처리가 중단되었을 수 있어요/)).toBeInTheDocument()
    expect(screen.queryByText('분석 완료')).not.toBeInTheDocument()
  })

  it.each([
    [
      {
        status: 'FAILED',
        guidance: 'CONTACT_OPERATOR',
        error_code: 'model_unavailable',
      },
      '처리 실패',
    ],
    [{ status: 'NOT_EVALUATED', guidance: 'REVIEW_REASON' }, '미평가'],
    [{ status: 'UNKNOWN', guidance: 'CHECK_RESULT' }, '상태 확인 필요'],
    [
      {
        status: 'COMPLETED',
        evaluation_status: 'INSUFFICIENT_EVIDENCE',
        guidance: 'REVIEW_EVIDENCE',
      },
      '근거 확인 필요',
    ],
    [{ status: 'COMPLETED', guidance: 'CHECK_RESULT' }, '결과 확인 필요'],
  ] satisfies Array<[PublishedAnalysis, string]>)(
    '실패·미평가·미확인 결과를 분석 완료로 표시하지 않는다: %s',
    (analysis, title) => {
      renderStatus(analysis)
      expect(screen.getByText(title)).toBeInTheDocument()
      expect(screen.queryByText('분석 완료')).not.toBeInTheDocument()
      expect(screen.queryByText('model_unavailable')).not.toBeInTheDocument()
    },
  )

  it('평가가 완료된 경우에만 완료를 표시하고 최신 기준 여부와 새로고침을 제공한다', async () => {
    const refresh = vi.fn()
    renderStatus(
      {
        status: 'COMPLETED',
        evaluation_status: 'COMPLETED',
        guidance: 'NONE',
        current_baseline: false,
      },
      refresh,
    )
    expect(screen.getByText('분석 완료')).toBeInTheDocument()
    expect(
      screen.getByText(/완료가 영향이 없다는 뜻은 아니에요/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/이 발행 이후 새로운 기준이 발행됐어요/),
    ).toBeInTheDocument()
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '상태 새로고침' }))
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('상태가 없는 이전 서버 응답도 정상으로 추정하지 않는다', () => {
    renderStatus()
    expect(screen.getByText('상태 확인 필요')).toBeInTheDocument()
  })

  it('확인된 미평가 사유는 이해할 수 있는 안내로 표시한다', () => {
    renderStatus({
      status: 'NOT_EVALUATED',
      guidance: 'REVIEW_REASON',
      error_code: 'document_change_unavailable',
    })
    expect(
      screen.getByText(/문서 변경은 아직 자동 분석을 지원하지 않아요/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('document_change_unavailable'),
    ).not.toBeInTheDocument()
  })

  it('서버 상태와 부가 필드를 보존하고 잘못된 상태를 거절한다', () => {
    const value = {
      status: 'PENDING',
      guidance: 'RETRY_SCHEDULED',
      requested_at: requested,
      next_attempt_at: '2026-11-04T00:12:00Z',
      attempt: 2,
      current_baseline: true,
    }
    expect(publishedAnalysisSchema.parse(value)).toEqual(value)
    expect(
      publishedAnalysisSchema.safeParse({ ...value, status: 'SUCCESS' })
        .success,
    ).toBe(false)
    expect(
      publishedAnalysisSchema.safeParse({ ...value, attempt: -1 }).success,
    ).toBe(false)
  })
})
