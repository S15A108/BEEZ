import { useState } from 'react'
import { RefreshCw, Sparkles } from 'lucide-react'
import type { Source } from '@/api/contracts/common'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getErrorCodeMessage,
} from '@/api/error'
import { EmptyState } from '@/components/EmptyState'
import { SourceLinks } from '@/components/SourceLinks'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Tag } from '@/features/changes/Tag'
import { operationTone } from '@/features/changes/tagTones'
import { statusLabel } from '@/lib/display'
import { candidateJobs, isActiveJob, latestJobs } from './draftJobs'
import type { AdoptCandidateInput, DraftCandidate, DraftJob } from './schemas'

const supportLabel = {
  DOCUMENT: '문서 근거',
  ANSWER: '답변 근거',
  DOCUMENT_AND_ANSWER: '문서·답변 근거',
  NONE: '근거 없음',
} as const
const fieldLabel: Record<string, string> = {
  description: '설명',
  method: '메서드',
  path: '경로',
  entity: '엔티티',
  fields: '필드',
  clarification: '답변 반영 내용',
}

const labelOf = (key: string) => fieldLabel[key] ?? key
const textOf = (value: unknown) =>
  typeof value === 'string' ? value : JSON.stringify(value, null, 2)
// 전후 비교. 바뀐 필드만 이전→이후 두 칸으로 보이고, 충돌한 필드는 따로 강조한다. 모르는 필드도 숨기지 않는다.
function Compare({ candidate }: { candidate: DraftCandidate }) {
  const after = candidate.after
  const before = candidate.before ?? {}
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
  if (candidate.change === 'CREATE')
    return (
      <dl className="grid gap-2 rounded-md bg-muted/50 p-3 text-sm">
        {keys.map((key) => (
          <div key={key}>
            <dt className="text-xs font-semibold text-muted-foreground">
              {labelOf(key)}
            </dt>
            <dd className="break-words whitespace-pre-wrap">
              {textOf(after[key])}
            </dd>
          </div>
        ))}
      </dl>
    )
  const changes = new Map(
    candidate.changed_fields.map((change) => [change.field, change]),
  )
  // 충돌만 있고 값은 그대로인 필드도 보인다(AI 계약: 제안이 저장값으로 돌아온 경우).
  const changed = [
    ...new Set([...changes.keys(), ...candidate.conflicting_fields]),
  ]
  const same = keys.filter((key) => !changed.includes(key))
  const valueOf = (key: string, side: 'before' | 'after') => {
    const change = changes.get(key)
    const content = side === 'before' ? before : after
    if (change?.change === (side === 'before' ? 'ADDED' : 'REMOVED'))
      return side === 'before' ? '(없음)' : '(삭제)'
    if (change) return textOf(change[side])
    return key in content ? textOf(content[key]) : '(없음)'
  }
  return (
    <div className="grid gap-2">
      {changed.map((key) => {
        const conflict = candidate.conflicting_fields.includes(key)
        return (
          <div
            key={key}
            className={
              conflict
                ? 'rounded-md border border-amber-300 bg-amber-50 p-3'
                : 'rounded-md border p-3'
            }
          >
            <p className="mb-2 flex items-center gap-2 text-xs font-semibold text-muted-foreground">
              {labelOf(key)}
              {changes.get(key)?.change === 'ADDED' && <Tag>추가</Tag>}
              {changes.get(key)?.change === 'REMOVED' && <Tag>삭제</Tag>}
              {conflict && (
                <Tag tone={operationTone.UPDATE}>원문과 답변이 달라요</Tag>
              )}
            </p>
            <div className="grid gap-2 sm:grid-cols-2">
              <div className="rounded bg-muted/50 p-2 text-sm">
                <p className="mb-1 text-xs font-semibold text-muted-foreground">
                  이전
                </p>
                <p className="break-words whitespace-pre-wrap">
                  {valueOf(key, 'before')}
                </p>
              </div>
              <div className="rounded bg-[#e8f3ec] p-2 text-sm">
                <p className="mb-1 text-xs font-semibold text-[#00753a]">
                  이후
                </p>
                <p className="break-words whitespace-pre-wrap">
                  {valueOf(key, 'after')}
                </p>
              </div>
            </div>
          </div>
        )
      })}
      {same.length > 0 && (
        <details className="text-sm">
          <summary className="cursor-pointer text-xs text-muted-foreground">
            바뀌지 않은 내용 {same.length}개
          </summary>
          <dl className="mt-2 grid gap-2 rounded-md bg-muted/50 p-3">
            {same.map((key) => (
              <div key={key}>
                <dt className="text-xs font-semibold text-muted-foreground">
                  {labelOf(key)}
                </dt>
                <dd className="break-words whitespace-pre-wrap">
                  {textOf(after[key])}
                </dd>
              </div>
            ))}
          </dl>
        </details>
      )}
    </div>
  )
}

function CandidateCard({
  candidate,
  titleOf,
  isLeader,
  busy,
  onAdopt,
  onDiscard,
  onRegenerate,
}: {
  candidate: DraftCandidate
  titleOf: (documentId: string) => string
  isLeader: boolean
  busy: boolean
  onAdopt: (input: AdoptCandidateInput) => Promise<unknown>
  onDiscard: () => Promise<unknown>
  onRegenerate: () => void
}) {
  const [confirmed, setConfirmed] = useState(false)
  const [resolved, setResolved] = useState<string[]>([])
  const [error, setError] = useState<{ code?: string; message: string }>()
  const noEvidence = candidate.support === 'NONE'
  const pending = candidate.status === 'PENDING'
  const ready =
    (!noEvidence || confirmed) &&
    candidate.conflicting_fields.every((field) => resolved.includes(field))
  const sources = candidate.sources.filter(
    (source): source is Source =>
      !!source.document_id &&
      !!source.document_version_id &&
      source.version_no !== undefined &&
      !!source.location,
  )
  const run = async (action: () => Promise<unknown>) => {
    setError(undefined)
    try {
      await action()
    } catch (cause) {
      setError({
        code: getApiErrorCode(cause),
        message: getApiErrorMessage(cause),
      })
    }
  }
  return (
    <article className="rounded-lg border p-4">
      <div className="mb-2 flex flex-wrap items-center gap-1.5">
        <Tag
          tone={
            candidate.change === 'CREATE'
              ? operationTone.CREATE
              : operationTone.UPDATE
          }
        >
          {candidate.change === 'CREATE' ? '새 항목' : '기존 항목 수정'}
        </Tag>
        <Tag>{statusLabel(candidate.kind)}</Tag>
        <Tag tone={noEvidence ? operationTone.RETIRE : undefined}>
          {supportLabel[candidate.support]}
        </Tag>
        {candidate.status === 'ADOPTED' && (
          <Tag tone={operationTone.CREATE}>채택함</Tag>
        )}
        {candidate.status === 'DISCARDED' && <Tag>폐기함</Tag>}
        <strong className="ml-1 text-sm">{candidate.candidate_key}</strong>
      </div>
      {candidate.rationale && (
        <p className="mb-3 text-sm leading-6 text-muted-foreground">
          {candidate.rationale}
        </p>
      )}
      <Compare candidate={candidate} />
      {candidate.sources.some((source) => source.quote) && (
        <ul className="mt-3 grid gap-1 text-xs text-muted-foreground">
          {candidate.sources
            .filter((source) => source.quote)
            .map((source, index) => (
              <li key={index} className="break-words">
                “{source.quote}”
              </li>
            ))}
        </ul>
      )}
      {sources.length > 0 && (
        <SourceLinks sources={sources} titleOf={titleOf} />
      )}
      {candidate.answer_refs.length > 0 && (
        <p className="mt-2 text-xs text-muted-foreground">
          답변 {candidate.answer_refs.length}개를 반영했어요.
        </p>
      )}
      {pending && isLeader && (
        <div className="mt-4 grid gap-3 border-t pt-3">
          {noEvidence && (
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-1"
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
              />
              문서와 답변에서 근거를 찾지 못한 제안이라는 점을 확인했어요.
            </label>
          )}
          {candidate.conflicting_fields.map((field) => (
            <label key={field} className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-1"
                checked={resolved.includes(field)}
                onChange={(event) =>
                  setResolved((current) =>
                    event.target.checked
                      ? [...current, field]
                      : current.filter((item) => item !== field),
                  )
                }
              />
              ‘{labelOf(field)}’은 이후 내용으로 확정해요.
            </label>
          ))}
          {error && (
            <div
              role="alert"
              className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
            >
              <p>{error.message}</p>
              {error.code === 'candidate_stale' && (
                <Button
                  className="mt-2"
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={onRegenerate}
                >
                  <RefreshCw />
                  초안 다시 만들기
                </Button>
              )}
            </div>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => void run(onDiscard)}
            >
              폐기
            </Button>
            <Button
              disabled={busy || !ready}
              onClick={() =>
                void run(() =>
                  onAdopt({
                    ...(noEvidence ? { confirm_no_evidence: true } : {}),
                    ...(candidate.conflicting_fields.length
                      ? { resolved_fields: resolved }
                      : {}),
                  }),
                )
              }
            >
              채택
            </Button>
          </div>
        </div>
      )}
    </article>
  )
}

export function DraftCandidates({
  jobs,
  labelOfVersion,
  titleOf,
  isLeader,
  busy,
  canGenerate,
  onGenerate,
  onAdopt,
  onDiscard,
}: {
  jobs: DraftJob[]
  /** 문서 버전 ID를 사람이 읽는 이름(예: 요구사항 명세서 v1)으로 바꾼다 */
  labelOfVersion: (versionId: string) => string
  /** 근거 버튼의 문서 ID를 제목으로 바꾼다 */
  titleOf: (documentId: string) => string
  isLeader: boolean
  busy: boolean
  canGenerate: boolean
  /** versionIds 가 없으면 모든 문서, 있으면 그 문서만 초안을 만든다 */
  onGenerate: (versionIds?: string[]) => void
  onAdopt: (
    candidate: DraftCandidate,
    input: AdoptCandidateInput,
  ) => Promise<unknown>
  onDiscard: (candidate: DraftCandidate) => Promise<unknown>
}) {
  const latest = latestJobs(jobs)
  const active = latest.some(isActiveJob)
  const candidates = candidateJobs(jobs).flatMap((job) => job.candidates)
  // 후보가 어느 문서에서 나왔는지. 낡은 후보를 다시 만들 때 그 문서만 다시 만든다.
  const versionOfCandidate = new Map(
    candidateJobs(jobs).flatMap((job) =>
      job.candidates.map((item) => [item.id, job.document_version_id] as const),
    ),
  )
  const regenerate = (candidate: DraftCandidate) => {
    const versionId = versionOfCandidate.get(candidate.id)
    onGenerate(versionId ? [versionId] : undefined)
  }
  const waiting = candidates.filter(
    (candidate) => candidate.status === 'PENDING',
  )
  const done = candidates.filter((candidate) => candidate.status !== 'PENDING')
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <CardTitle>초안 후보</CardTitle>
          {isLeader && (
            <Button
              size="sm"
              disabled={busy || active || !canGenerate}
              onClick={() => onGenerate()}
            >
              <Sparkles />
              답변으로 초안 만들기
            </Button>
          )}
        </div>
        <CardDescription>
          비비가 문서와 답변으로 제안한 후보예요. 팀장이 채택해야 설계 항목
          초안이 돼요.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {latest.map((job) => {
          const label = labelOfVersion(job.document_version_id)
          if (isActiveJob(job))
            return (
              <p
                key={job.id}
                role="status"
                className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
              >
                {label} 초안을 만들고 있어요. 잠시만 기다려 주세요.
              </p>
            )
          if (job.status === 'FAILED')
            return (
              <div
                key={job.id}
                role="alert"
                className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
              >
                <p>
                  {label} 초안을 만들지 못했어요.{' '}
                  {getErrorCodeMessage(job.error_code) ?? ''}
                </p>
                {isLeader && job.retryable !== false && (
                  <Button
                    className="mt-2"
                    size="sm"
                    variant="outline"
                    // 실패한 이 문서만 다시 만든다. 다른 문서가 만드는 중이어도 이 문서는 다시 요청할 수 있다.
                    disabled={busy}
                    onClick={() => onGenerate([job.document_version_id])}
                  >
                    <RefreshCw />
                    다시 만들기
                  </Button>
                )}
              </div>
            )
          return null
        })}
        {candidateJobs(jobs).some(
          (job) =>
            job.unanswered_question_keys?.length || job.unresolved?.length,
        ) && (
          <p className="text-xs text-muted-foreground">
            답하지 않은 질문과 정하지 못한 내용은 후보에 반영하지 못했어요.
          </p>
        )}
        {latest.length === 0 && (
          <EmptyState>
            ‘답변으로 초안 만들기’를 누르면 후보가 여기에 나와요.
          </EmptyState>
        )}
        {latest.length > 0 && !active && candidates.length === 0 && (
          <EmptyState>이번에는 제안할 후보가 없어요.</EmptyState>
        )}
        {waiting.map((candidate) => (
          <CandidateCard
            key={candidate.id}
            candidate={candidate}
            titleOf={titleOf}
            isLeader={isLeader}
            busy={busy}
            onAdopt={(input) => onAdopt(candidate, input)}
            onDiscard={() => onDiscard(candidate)}
            onRegenerate={() => regenerate(candidate)}
          />
        ))}
        {done.length > 0 && (
          <details>
            <summary className="cursor-pointer text-sm text-muted-foreground">
              처리한 후보 {done.length}개
            </summary>
            <div className="mt-3 space-y-3">
              {done.map((candidate) => (
                <CandidateCard
                  key={candidate.id}
                  candidate={candidate}
                  titleOf={titleOf}
                  isLeader={isLeader}
                  busy={busy}
                  onAdopt={(input) => onAdopt(candidate, input)}
                  onDiscard={() => onDiscard(candidate)}
                  onRegenerate={() => regenerate(candidate)}
                />
              ))}
            </div>
          </details>
        )}
      </CardContent>
    </Card>
  )
}
