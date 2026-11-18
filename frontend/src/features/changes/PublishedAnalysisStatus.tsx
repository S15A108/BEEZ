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
      message:
        '발행 후 분석 상태를 확인하지 못했어요. 운영 담당자에게 문의해 주세요.',
    }
  if (analysis.status === 'PENDING') {
    const waitingLong =
      !!analysis.requested_at &&
      observedAt - Date.parse(analysis.requested_at) >= LONG_WAIT_MS
    return {
      title: waitingLong ? '오래 대기 중' : '처리 대기 중',
      message: waitingLong
        ? '처리가 평소보다 늦어지고 있어요. 계속 지연되면 운영 담당자에게 문의해 주세요.'
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
            '처리가 중단되었을 수 있어요. 복구를 기다리고 있으며 오래 걸리면 운영 담당자에게 문의해 주세요.',
        }
      : { title: '분석 중', message: '발행 후 분석을 처리하고 있어요.' }
  if (analysis.status === 'FAILED')
    return {
      title: '처리 실패',
      message:
        analysis.error_code === 'baseline_changed'
          ? '발행 이후 기준이 바뀌어 분석을 마치지 못했어요. 운영 담당자에게 문의해 주세요.'
          : '발행 후 분석을 마치지 못했어요. 운영 담당자에게 문의해 주세요.',
    }
  if (analysis.status === 'NOT_EVALUATED')
    return {
      title: '미평가',
      message: analysis.error_code
        ? (notEvaluatedReasons.get(analysis.error_code) ??
          '발행 후 영향을 평가하지 못했어요. 운영 담당자에게 문의해 주세요.')
        : '발행 후 영향을 평가하지 못했어요. 운영 담당자에게 문의해 주세요.',
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
      '처리는 끝났지만 분석 결과를 확인하지 못했어요. 운영 담당자에게 문의해 주세요.',
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
  // 발행 요약 아래 작은 상태 줄이다. 제목과 상태를 한 줄에, 발행 전 분석과의 차이 설명은 접어 둔다(S15P31A108-456).
  return (
    <section
      aria-labelledby="published-analysis-title"
      className={cn(
        'grid gap-1 rounded-lg border bg-card px-3 py-2 text-[13px] leading-5',
        analysis?.status === 'FAILED' &&
          'border-destructive/40 bg-destructive/5',
      )}
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <h4 id="published-analysis-title" className="text-sm font-semibold">
          발행 후 분석 상태
        </h4>
        <p aria-live="polite" className="min-w-0 flex-1">
          <strong>{description.title}</strong>{' '}
          <span className="text-muted-foreground">{description.message}</span>
        </p>
        <Button
          size="xs"
          variant="outline"
          disabled={refreshing}
          onClick={onRefresh}
        >
          상태 새로고침
        </Button>
      </div>
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
      {/* 발행 후 분석의 입력은 발행한 제출과 이전 기준, 분석 시점의 열린 작업·관계다(#227). 승인 전 분석(CHG-A05)과 합치지 않는다. */}
      <details className="text-xs text-muted-foreground">
        <summary className="w-fit cursor-pointer hover:text-foreground">
          발행 전 분석과 다른 점
        </summary>
        <p className="mt-1">
          발행 전 영향 분석과는 별도의 처리 상태예요. 발행 전 분석은 승인하기
          전에 제안 내용으로 본 결과이고 영향 탭에 있어요. 발행 후 분석은 발행한
          뒤 열려 있는 작업과 관계를 기준으로 다시 살펴봐요. 한쪽 결과가 다른
          쪽을 대신하지 않아요.
        </p>
      </details>
    </section>
  )
}
