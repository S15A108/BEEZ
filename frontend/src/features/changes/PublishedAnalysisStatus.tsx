import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import type { PublishedAnalysis } from './schemas'

// 요청부터 자동 재시도까지 포함한 대기 시간이다. 장애 여부나 운영 알림과는 다르다.
const LONG_WAIT_MS = 10 * 60 * 1000

const notEvaluatedReasons = new Map([
  [
    'published_before_intake',
    '이전 발행의 분석 기록이 없어요. 영향이 없다는 뜻은 아니에요.',
  ],
  [
    'document_change_unavailable',
    '문서 변경은 아직 자동 분석을 지원하지 않아요. 팀에서 직접 확인해 주세요.',
  ],
  [
    'change_schema_version_unavailable',
    '변경 내용을 분석할 정보가 부족해요. 팀에 문의해 주세요.',
  ],
  [
    'change_content_unavailable',
    '변경 내용을 분석할 정보가 부족해요. 팀에 문의해 주세요.',
  ],
  [
    'analysis_candidates_unavailable',
    '분석에 필요한 설계 항목이나 관련 작업을 찾지 못했어요. 팀에서 확인해 주세요.',
  ],
])

function describe(analysis: PublishedAnalysis | undefined, observedAt: number) {
  if (!analysis || analysis.status === 'UNKNOWN')
    return {
      title: '상태 확인 필요',
      message: '발행 후 분석 상태를 확인하지 못했어요. 팀에 문의해 주세요.',
    }
  if (analysis.status === 'PENDING') {
    const waitingLong =
      !!analysis.requested_at &&
      observedAt - Date.parse(analysis.requested_at) >= LONG_WAIT_MS
    return {
      title: waitingLong ? '오래 대기 중' : '처리 대기 중',
      message: waitingLong
        ? '처리까지 시간이 걸리고 있어요. 환경 설정에 따라 대기할 수 있으며, 계속 지연되면 팀에 문의해 주세요.'
        : analysis.guidance === 'RETRY_SCHEDULED'
          ? '자동 재시도를 기다리고 있어요.'
          : '발행 후 분석이 시작되기를 기다리고 있어요.',
    }
  }
  if (analysis.status === 'RUNNING')
    return analysis.lease_expired || analysis.guidance === 'RECOVERY_PENDING'
      ? {
          title: '복구 확인 중',
          message:
            '처리가 중단되었을 수 있어요. 복구를 기다리고 있으며 오래 걸리면 팀에 문의해 주세요.',
        }
      : { title: '분석 중', message: '발행 후 분석을 처리하고 있어요.' }
  if (analysis.status === 'FAILED')
    return {
      title: '처리 실패',
      message:
        analysis.error_code === 'baseline_changed'
          ? '발행 이후 기준이 바뀌어 분석을 마치지 못했어요. 팀에 문의해 주세요.'
          : '발행 후 분석을 마치지 못했어요. 팀에 문의해 주세요.',
    }
  if (analysis.status === 'NOT_EVALUATED')
    return {
      title: '미평가',
      message: analysis.error_code
        ? (notEvaluatedReasons.get(analysis.error_code) ??
          '발행 후 영향을 평가하지 못했어요. 팀에 문의해 주세요.')
        : '발행 후 영향을 평가하지 못했어요. 팀에 문의해 주세요.',
    }
  if (analysis.evaluation_status === 'COMPLETED')
    return {
      title: '분석 완료',
      message: '발행 후 분석을 마쳤어요. 완료가 영향이 없다는 뜻은 아니에요.',
    }
  if (analysis.evaluation_status === 'INSUFFICIENT_EVIDENCE')
    return {
      title: '근거 확인 필요',
      message: '분석은 끝났지만 근거가 부족해 영향을 확정하지 못했어요.',
    }
  return {
    title: '결과 확인 필요',
    message:
      '처리는 끝났지만 분석 결과를 확인하지 못했어요. 팀에 문의해 주세요.',
  }
}

export function PublishedAnalysisStatus({
  analysis,
  observedAt,
  refreshing,
  onRefresh,
}: {
  analysis?: PublishedAnalysis
  observedAt: number
  refreshing: boolean
  onRefresh: () => void
}) {
  const description = describe(analysis, observedAt)
  return (
    <section
      aria-labelledby="published-analysis-title"
      className={cn(
        'grid gap-2 rounded-xl border bg-card p-4 text-sm',
        analysis?.status === 'FAILED' &&
          'border-destructive/40 bg-destructive/5',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 id="published-analysis-title" className="font-semibold">
          발행 후 분석 상태
        </h4>
        <Button
          size="sm"
          variant="outline"
          disabled={refreshing}
          onClick={onRefresh}
        >
          상태 새로고침
        </Button>
      </div>
      <div aria-live="polite" className="grid gap-1">
        <strong>{description.title}</strong>
        <p>{description.message}</p>
        {analysis?.guidance === 'RETRY_SCHEDULED' &&
          description.title === '오래 대기 중' && (
            <p className="text-muted-foreground">
              자동 재시도를 기다리고 있어요.
            </p>
          )}
        {analysis?.current_baseline === false && (
          <p className="text-muted-foreground">
            이 발행 이후 새로운 기준이 발행됐어요.
          </p>
        )}
      </div>
      {analysis &&
        (analysis.requested_at ||
          analysis.finished_at ||
          analysis.next_attempt_at ||
          analysis.attempt !== undefined) && (
          <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
            {analysis.requested_at && (
              <div>
                <dt className="inline">접수 </dt>
                <dd className="inline">
                  <time dateTime={analysis.requested_at}>
                    {formatDate(analysis.requested_at)}
                  </time>
                </dd>
              </div>
            )}
            {analysis.finished_at && (
              <div>
                <dt className="inline">처리 종료 </dt>
                <dd className="inline">
                  <time dateTime={analysis.finished_at}>
                    {formatDate(analysis.finished_at)}
                  </time>
                </dd>
              </div>
            )}
            {analysis.next_attempt_at && (
              <div>
                <dt className="inline">다음 재시도 예정 </dt>
                <dd className="inline">
                  <time dateTime={analysis.next_attempt_at}>
                    {formatDate(analysis.next_attempt_at)}
                  </time>
                </dd>
              </div>
            )}
            {analysis.attempt !== undefined && (
              <div>
                <dt className="inline">처리 시도 </dt>
                <dd className="inline">{analysis.attempt}회</dd>
              </div>
            )}
          </dl>
        )}
      <p className="text-xs text-muted-foreground">
        발행 전 영향 분석과는 별도의 처리 상태예요.
      </p>
    </section>
  )
}
