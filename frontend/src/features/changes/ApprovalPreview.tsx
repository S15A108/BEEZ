import type { ReactNode } from 'react'
import type { ApprovalPreviewResponse } from './schemas'
import { Tag } from './Tag'
import { operationTone, statusTone } from './tagTones'

interface Props {
  preview: ApprovalPreviewResponse
  /** 제안이 바꾸는 문서 수. 미리 보기에는 변경 문서 수가 없어 제안에서 받는다. */
  changedDocuments: number
  nameOfMember: (id?: string) => string
}

type Analysis = ApprovalPreviewResponse['unreviewed']['ai_analysis']

// 영향 분석 상태를 한 문장으로. 승인 게이트(S15P31A108-241)가 믿는 상태는 '완료 · 최신 · 재분석 아님' 하나뿐이다.
function analysisText(analysis: Analysis) {
  if (!analysis) return '영향 분석 상태를 확인하지 못했어요.'
  if (analysis.reanalysis_in_progress)
    return '다시 분석하는 중이에요. 끝난 뒤에 승인할 수 있어요.'
  if (['PENDING', 'RUNNING'].includes(analysis.status))
    return '영향 분석이 진행 중이에요. 끝난 뒤에 승인할 수 있어요.'
  if (
    analysis.status === 'NOT_EVALUATED' &&
    analysis.error_code === 'analysis_not_started'
  )
    return '아직 영향 분석을 하지 않았어요.'
  if (analysis.status === 'NOT_EVALUATED') return '영향을 평가하지 못했어요.'
  if (analysis.status === 'FAILED') return '영향 분석에 실패했어요.'
  if (analysis.status === 'INSUFFICIENT_EVIDENCE')
    return '근거가 부족해서 영향을 확정하지 못했어요.'
  if (analysis.status === 'STALE' || !analysis.is_current)
    return '제안이 바뀌어 이전 분석이 오래됐어요.'
  return '영향 분석이 끝났어요.'
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="grid gap-1.5">
      <p className="text-xs text-muted-foreground">{title}</p>
      {children}
    </section>
  )
}

const rowClass = 'flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2'

// 승인 확인 화면의 '승인하면 같이 바뀌는 것'(CHG-A14). 읽기 전용 계산이라 아무것도 저장하지 않는다.
export function ApprovalPreview({
  preview,
  changedDocuments,
  nameOfMember,
}: Props) {
  const created = preview.baseline_items.filter(
    (item) => item.operation === 'CREATE',
  ).length
  const updated = preview.baseline_items.filter(
    (item) => item.operation === 'UPDATE',
  ).length
  const summary = [
    created ? `설계 항목 ${created}개 추가` : '',
    updated ? `${created ? '' : '설계 항목 '}${updated}개 수정` : '',
    changedDocuments ? `문서 ${changedDocuments}개 수정` : '',
  ].filter(Boolean)
  const { unreviewed } = preview
  const analysis = unreviewed.ai_analysis
  // 승인 게이트가 믿는 분석은 '완료 · 최신 · 재분석 아님' 하나뿐이다. 아니면 후보 수가 0이어도 확인한 것이 아니다.
  const analysisTrusted =
    !!analysis &&
    analysis.status === 'COMPLETED' &&
    analysis.is_current &&
    !analysis.reanalysis_in_progress
  // 사람이 더 봐야 할 것이 있으면 경고색, 없으면 차분하게.
  const attention =
    !analysisTrusted ||
    unreviewed.ai_candidates === null ||
    unreviewed.ai_candidates > 0 ||
    unreviewed.open_review_requests > 0 ||
    unreviewed.requires_override_reason
  return (
    <div
      className="grid gap-3"
      role="group"
      aria-label="승인하면 같이 바뀌는 것"
    >
      <p className="rounded-lg bg-muted/50 p-3 text-sm leading-6">
        기준 v{preview.next_baseline_version_no}에{' '}
        {summary.length ? `${summary.join(', ')}이 들어가요. ` : ''}
        나머지 항목 {preview.unchanged.items}개와 문서{' '}
        {preview.unchanged.documents}개는 그대로예요.
      </p>
      <Section
        title={`다시 검증이 필요해지는 검증 ${preview.validations_to_stale.length}개`}
      >
        {preview.validations_to_stale.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            다시 검증이 필요해지는 검증이 없어요.
          </p>
        ) : (
          <ul className="grid gap-1.5 text-sm">
            {preview.validations_to_stale.map((entry) => (
              <li key={entry.validation_id} className={rowClass}>
                <Tag tone={operationTone.UPDATE}>다시 검증 필요</Tag>
                <span className="min-w-0 break-words">{entry.task_title}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section
        title={`대기 · 재검증이 되는 작업 ${preview.tasks_to_wait.length}개`}
      >
        {preview.tasks_to_wait.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            대기 · 재검증이 되는 진행 중 작업이 없어요.
          </p>
        ) : (
          <ul className="grid gap-1.5 text-sm">
            {preview.tasks_to_wait.map((entry) => (
              <li key={entry.task_id} className={rowClass}>
                <Tag tone={statusTone.IN_REVIEW}>대기 · 재검증</Tag>
                <span className="min-w-0 break-words">{entry.title}</span>
                <span className="text-xs text-muted-foreground">
                  담당 {nameOfMember(entry.assignee_member_id)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <section
        aria-label="확인하지 못한 것"
        className={
          attention
            ? 'grid gap-1.5 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm leading-6'
            : 'grid gap-1.5 rounded-lg bg-muted/50 p-3 text-sm leading-6'
        }
      >
        <p className="font-semibold">
          {attention
            ? '아직 확인하지 못한 것이 있어요'
            : '확인하지 못한 것이 없어요'}
        </p>
        <ul className="grid gap-1">
          <li>
            AI 영향 후보:{' '}
            {unreviewed.ai_candidates === null
              ? '아직 평가하지 못했어요'
              : `${unreviewed.ai_candidates}개`}
          </li>
          <li>답하지 않은 검토 요청: {unreviewed.open_review_requests}개</li>
          <li>{analysisText(analysis)}</li>
        </ul>
      </section>
    </div>
  )
}
