import { useState } from 'react'
import { ShowMoreButton } from './ShowMoreButton'
import { useShowMore } from './useShowMore'
import { RefreshCw } from 'lucide-react'
import { getApiErrorMessage, getErrorCodeMessage } from '@/api/error'
import { EmptyState } from '@/components/EmptyState'
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
import {
  DEFAULT_MAX_ATTEMPTS,
  canRetryJob,
  candidateTitle,
  documentJobs,
  isActiveJob,
} from './draftJobs'
import type {
  BatchCandidatesInput,
  BatchCandidatesResponse,
  DraftCandidate,
  DraftJob,
} from './schemas'

const supportLabel = {
  DOCUMENT: '문서 근거',
  ANSWER: '답변 근거',
  DOCUMENT_AND_ANSWER: '문서·답변 근거',
  NONE: '근거 없음',
} as const

const overlapLabel = {
  EXISTING_ITEM: '이미 있는 설계 항목과 같아요',
  ADOPTED_CANDIDATE: '이미 채택한 후보와 같아요',
  DISCARDED_CANDIDATE: '이전에 폐기한 후보와 같아요',
  PENDING_CANDIDATE: '다른 문서에서 뽑은 후보와 같아요',
} as const

function JobStatus({
  job,
  label,
  isLeader,
  busy,
  onRetry,
}: {
  job: DraftJob
  label: string
  isLeader: boolean
  busy: boolean
  onRetry: (versionId: string) => void
}) {
  const parts = job.parts
  if (isActiveJob(job))
    return (
      <p role="status" className="rounded-lg border bg-muted/40 p-3 text-sm">
        <strong>{label}</strong> 후보를 만들고 있어요
        {parts && parts.total > 1
          ? ` (${parts.completed}/${parts.total}번째 부분)`
          : ''}
        . 긴 문서는 나눠서 만들어요.
      </p>
    )
  if (job.status === 'FAILED') {
    const exhausted =
      (job.attempt_no ?? 1) >= (job.max_attempts ?? DEFAULT_MAX_ATTEMPTS)
    return (
      <div
        role="alert"
        className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
      >
        <p>
          <strong>{label}</strong> 후보를 모두 만들지 못했어요.{' '}
          {getErrorCodeMessage(job.error_code) ?? ''}
        </p>
        {parts && parts.failed > 0 && (
          <p className="mt-1">
            {parts.total}개 부분 중 {parts.failed}개를 만들지 못했어요. 다시
            시도하면 못 만든 부분만 다시 만들어요.
          </p>
        )}
        {isLeader && canRetryJob(job) && (
          <Button
            className="mt-2"
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => onRetry(job.document_version_id)}
          >
            <RefreshCw />
            실패한 부분만 다시 시도
          </Button>
        )}
        {exhausted && (
          <p className="mt-2 text-foreground">
            다시 시도할 수 있는 횟수를 다 썼어요. 설계 항목을 직접 추가하거나,
            문서를 고쳐 새 버전을 승인하면 다시 만들 수 있어요.
          </p>
        )}
      </div>
    )
  }
  const count = job.candidates.length
  return (
    <p className="rounded-lg border p-3 text-sm">
      <strong>{label}</strong> 후보 {count}개를 만들었어요.
    </p>
  )
}

function CandidateRow({
  candidate,
  checked,
  confirmed,
  duplicate,
  duplicateConfirmed,
  disabled,
  onToggle,
  onConfirm,
  onConfirmDuplicate,
}: {
  candidate: DraftCandidate
  checked: boolean
  confirmed: boolean
  /** 이미 같은 설계 항목이 있을 수 있어 알고 채택한다는 확인이 필요한 후보 */
  duplicate: boolean
  duplicateConfirmed: boolean
  disabled: boolean
  onToggle: (checked: boolean) => void
  onConfirm: (confirmed: boolean) => void
  onConfirmDuplicate: (confirmed: boolean) => void
}) {
  const title = candidateTitle(candidate)
  const quote = candidate.sources.find((source) => source.quote)
  const noEvidence = candidate.support === 'NONE'
  return (
    <li
      className={`rounded-lg border p-3 ${checked ? 'border-primary bg-primary/5' : ''}`}
    >
      <label className="flex items-start gap-3 text-sm">
        <input
          type="checkbox"
          className="mt-1 size-4"
          aria-label={`${title} 선택`}
          checked={checked}
          disabled={disabled}
          onChange={(event) => onToggle(event.target.checked)}
        />
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-1.5">
            <Tag>{statusLabel(candidate.kind)}</Tag>
            <Tag tone={noEvidence ? operationTone.RETIRE : undefined}>
              {supportLabel[candidate.support]}
            </Tag>
            <strong className="break-words">{title}</strong>
          </span>
          {candidate.sources[0]?.location && (
            <span className="mt-1 block text-xs text-muted-foreground">
              {candidate.sources[0].location}
            </span>
          )}
          {quote && (
            <span className="mt-1 block text-xs break-words text-muted-foreground">
              “{quote.quote}”
            </span>
          )}
          {candidate.rationale && (
            <span className="mt-1 block text-xs break-words text-muted-foreground">
              {candidate.rationale}
            </span>
          )}
          {candidate.overlap && (
            <span className="mt-1 block text-xs text-amber-800">
              {overlapLabel[candidate.overlap.kind]}
            </span>
          )}
        </span>
      </label>
      {checked && noEvidence && (
        <label className="mt-2 ml-7 flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-1"
            checked={confirmed}
            disabled={disabled}
            onChange={(event) => onConfirm(event.target.checked)}
          />
          문서에서 근거를 찾지 못한 제안이라는 점을 확인했어요.
        </label>
      )}
      {checked && duplicate && (
        <label className="mt-2 ml-7 flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-1"
            checked={duplicateConfirmed}
            disabled={disabled}
            onChange={(event) => onConfirmDuplicate(event.target.checked)}
          />
          이미 같은 설계 항목이 있다는 점을 확인했고, 내용이 달라 따로 채택해요.
        </label>
      )}
    </li>
  )
}

export function DocumentCandidates({
  jobs,
  labelOfVersion,
  isLeader,
  busy,
  onRetry,
  creatableVersionIds = [],
  onCreate,
  onAdopt,
  onDiscard,
}: {
  jobs: DraftJob[]
  /** 문서 버전 ID를 사람이 읽는 이름(예: 요구사항 명세서 v1)으로 바꾼다 */
  labelOfVersion: (versionId: string) => string
  isLeader: boolean
  busy: boolean
  /** 실패한 문서 하나의 실패한 부분만 다시 만든다 */
  onRetry: (versionId: string) => void
  /** 아직 문서에서 후보를 만든 적 없는 문서 버전(자동 생성이 꺼져 있거나 이 프로젝트가 자동 생성 대상이 아닐 때 팀장이 직접 만든다) */
  creatableVersionIds?: string[]
  onCreate?: (versionIds: string[]) => void
  onAdopt: (input: BatchCandidatesInput) => Promise<BatchCandidatesResponse>
  onDiscard: (input: BatchCandidatesInput) => Promise<BatchCandidatesResponse>
}) {
  const [selected, setSelected] = useState<string[]>([])
  const [confirmed, setConfirmed] = useState<string[]>([])
  const [duplicateConfirmed, setDuplicateConfirmed] = useState<string[]>([])
  // 채택하다 candidate_duplicate 로 거절당한 후보. 겹침 표시가 없어도 다음부터 확인을 받는다.
  const [duplicates, setDuplicates] = useState<string[]>([])
  const [working, setWorking] = useState(false)
  const [outcome, setOutcome] = useState<{
    kind: 'adopt' | 'discard'
    response: BatchCandidatesResponse
  }>()
  const [error, setError] = useState<string>()

  const latest = documentJobs(jobs)
  const pendingOf = (job: DraftJob) =>
    job.candidates.filter((candidate) => candidate.status === 'PENDING')
  const fresh = (job: DraftJob) =>
    pendingOf(job).filter((candidate) => !candidate.overlap)
  const overlapped = latest.flatMap((job) =>
    pendingOf(job).filter((candidate) => candidate.overlap),
  )
  const allFresh = latest.flatMap(fresh)
  // 후보가 많으면 5개씩 보인다. '전체 선택'은 화면에 보이는 후보만 고른다(안 보이는 후보를 모르고 채택하지 않게).
  const freshList = useShowMore(allFresh)
  const overlapList = useShowMore(overlapped)
  const shownIds = new Set(freshList.shown.map((candidate) => candidate.id))
  const all = [...allFresh, ...overlapped]
  const chosen = all.filter((candidate) => selected.includes(candidate.id))
  // 이미 있는 항목·이미 채택한 후보·다른 문서의 같은 후보와 같으면 알고 채택하는 확인이 필요하다.
  // 이전에 폐기한 후보와 같은 것은 설계 항목이 아니므로 확인 없이 채택한다.
  const isDuplicate = (candidate: DraftCandidate) =>
    duplicates.includes(candidate.id) ||
    (!!candidate.overlap && candidate.overlap.kind !== 'DISCARDED_CANDIDATE')
  const needsConfirm = chosen.filter(
    (candidate) =>
      (candidate.support === 'NONE' && !confirmed.includes(candidate.id)) ||
      (isDuplicate(candidate) && !duplicateConfirmed.includes(candidate.id)),
  )
  const disabled = busy || working || !isLeader
  const toggle = (id: string, on: boolean) =>
    setSelected((current) =>
      on ? [...new Set([...current, id])] : current.filter((x) => x !== id),
    )
  const allChecked =
    freshList.shown.length > 0 &&
    freshList.shown.every((candidate) => selected.includes(candidate.id))

  const run = async (kind: 'adopt' | 'discard') => {
    if (!chosen.length || working) return
    setWorking(true)
    setError(undefined)
    setOutcome(undefined)
    const input: BatchCandidatesInput = {
      items: chosen.map((candidate) => ({
        candidate_id: candidate.id,
        ...(kind === 'adopt' && candidate.support === 'NONE'
          ? { confirm_no_evidence: true }
          : {}),
        ...(kind === 'adopt' && isDuplicate(candidate)
          ? { confirm_duplicate: true }
          : {}),
      })),
    }
    try {
      const response = await (kind === 'adopt'
        ? onAdopt(input)
        : onDiscard(input))
      setOutcome({ kind, response })
      const failedIds = response.results
        .filter((result) => result.result === 'FAILED')
        .map((result) => result.candidate_id)
      // 처리된 후보는 선택에서 빼고, 실패한 후보만 선택으로 남겨 다시 시도하거나 사유를 확인하게 한다.
      setSelected((current) => current.filter((id) => failedIds.includes(id)))
      setDuplicates((current) => [
        ...new Set([
          ...current,
          ...response.results
            .filter((result) => result.error_code === 'candidate_duplicate')
            .map((result) => result.candidate_id),
        ]),
      ])
    } catch (cause) {
      setError(getApiErrorMessage(cause))
    } finally {
      setWorking(false)
    }
  }
  const failures = outcome?.response.results.filter(
    (result) => result.result === 'FAILED',
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>문서에서 뽑은 후보</CardTitle>
        <CardDescription>
          문서 4종이 승인·색인되면 비비가 문서마다 설계 항목 후보를 자동으로
          만들어요. 팀장이 고른 후보만 설계 항목 초안이 돼요.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {latest.length === 0 && (
          <EmptyState>
            문서 4종이 모두 승인되고 색인이 끝나면 후보가 여기에 나와요.
          </EmptyState>
        )}
        {isLeader && onCreate && creatableVersionIds.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-dashed p-3">
            <span className="text-sm text-muted-foreground">
              {latest.length === 0
                ? '자동으로 만들어지지 않았다면 직접 만들 수 있어요.'
                : `후보를 아직 만들지 않은 문서가 ${creatableVersionIds.length}개 있어요.`}
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={busy || working || latest.some(isActiveJob)}
              onClick={() => onCreate(creatableVersionIds)}
            >
              문서에서 후보 만들기
            </Button>
          </div>
        )}
        {latest.map((job) => (
          <JobStatus
            key={job.id}
            job={job}
            label={labelOfVersion(job.document_version_id)}
            isLeader={isLeader}
            busy={busy || working}
            onRetry={onRetry}
          />
        ))}
        {latest.length > 0 && !latest.some(isActiveJob) && all.length === 0 && (
          <EmptyState>채택하거나 폐기할 후보가 남아 있지 않아요.</EmptyState>
        )}
        {allFresh.length > 0 && (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="size-4"
                  checked={allChecked}
                  disabled={disabled}
                  onChange={(event) =>
                    setSelected((current) =>
                      event.target.checked
                        ? [
                            ...new Set([
                              ...current,
                              ...freshList.shown.map(
                                (candidate) => candidate.id,
                              ),
                            ]),
                          ]
                        : current.filter(
                            (id) =>
                              !freshList.shown.some(
                                (candidate) => candidate.id === id,
                              ),
                          ),
                    )
                  }
                />
                전체 선택
              </label>
              <span role="status" className="text-sm text-muted-foreground">
                {chosen.length}개 선택
              </span>
            </div>
            {latest.map((job) => {
              const all = fresh(job)
              const rows = all.filter((candidate) => shownIds.has(candidate.id))
              if (!rows.length) return null
              return (
                <section
                  key={job.id}
                  aria-label={labelOfVersion(job.document_version_id)}
                >
                  <h4 className="mb-2 text-xs font-semibold text-muted-foreground">
                    {labelOfVersion(job.document_version_id)} · {all.length}개
                  </h4>
                  <ul className="space-y-2">
                    {rows.map((candidate) => (
                      <CandidateRow
                        key={candidate.id}
                        candidate={candidate}
                        checked={selected.includes(candidate.id)}
                        confirmed={confirmed.includes(candidate.id)}
                        duplicate={isDuplicate(candidate)}
                        duplicateConfirmed={duplicateConfirmed.includes(
                          candidate.id,
                        )}
                        disabled={disabled}
                        onToggle={(on) => toggle(candidate.id, on)}
                        onConfirm={(on) =>
                          setConfirmed((current) =>
                            on
                              ? [...current, candidate.id]
                              : current.filter((id) => id !== candidate.id),
                          )
                        }
                        onConfirmDuplicate={(on) =>
                          setDuplicateConfirmed((current) =>
                            on
                              ? [...current, candidate.id]
                              : current.filter((id) => id !== candidate.id),
                          )
                        }
                      />
                    ))}
                  </ul>
                </section>
              )
            })}
            <ShowMoreButton
              hidden={freshList.hidden}
              label="후보"
              onClick={freshList.more}
            />
          </div>
        )}
        {overlapped.length > 0 && (
          <details className="rounded-lg border border-dashed p-3">
            <summary className="cursor-pointer text-sm text-muted-foreground">
              이미 있는 항목과 비슷한 후보 {overlapped.length}개
            </summary>
            <ul className="mt-3 space-y-2">
              {overlapList.shown.map((candidate) => (
                <CandidateRow
                  key={candidate.id}
                  candidate={candidate}
                  checked={selected.includes(candidate.id)}
                  confirmed={confirmed.includes(candidate.id)}
                  duplicate={isDuplicate(candidate)}
                  duplicateConfirmed={duplicateConfirmed.includes(candidate.id)}
                  disabled={disabled}
                  onToggle={(on) => toggle(candidate.id, on)}
                  onConfirm={(on) =>
                    setConfirmed((current) =>
                      on
                        ? [...current, candidate.id]
                        : current.filter((id) => id !== candidate.id),
                    )
                  }
                  onConfirmDuplicate={(on) =>
                    setDuplicateConfirmed((current) =>
                      on
                        ? [...current, candidate.id]
                        : current.filter((id) => id !== candidate.id),
                    )
                  }
                />
              ))}
            </ul>
            <div className="mt-3 grid">
              <ShowMoreButton
                hidden={overlapList.hidden}
                label="비슷한 후보"
                onClick={overlapList.more}
              />
            </div>
          </details>
        )}
        {isLeader && all.length > 0 && (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-3">
            {needsConfirm.length > 0 && (
              <span className="mr-auto text-xs text-muted-foreground">
                근거 없는 후보는 확인 체크를 해야 채택할 수 있어요.
              </span>
            )}
            <Button
              variant="outline"
              disabled={disabled || chosen.length === 0}
              onClick={() => void run('discard')}
            >
              폐기
            </Button>
            <Button
              disabled={
                disabled || chosen.length === 0 || needsConfirm.length > 0
              }
              onClick={() => void run('adopt')}
            >
              선택한 항목 채택
            </Button>
          </div>
        )}
        {error && (
          <p
            role="alert"
            className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
          >
            {error}
          </p>
        )}
        {outcome && (
          <div role="status" className="rounded-lg bg-primary/5 p-3 text-sm">
            <p>
              {outcome.kind === 'adopt'
                ? `${outcome.response.succeeded}개를 설계 항목 초안으로 채택했어요.`
                : `${outcome.response.succeeded}개를 폐기했어요.`}
              {outcome.response.failed > 0 &&
                ` ${outcome.response.failed}개는 처리하지 못했어요.`}
            </p>
            {failures && failures.length > 0 && (
              <ul className="mt-2 list-disc space-y-1 pl-5 text-destructive">
                {failures.map((result) => {
                  const candidate = all.find(
                    (item) => item.id === result.candidate_id,
                  )
                  return (
                    <li key={result.candidate_id}>
                      {candidate
                        ? candidateTitle(candidate)
                        : `후보 ${result.candidate_id}`}
                      {': '}
                      {getErrorCodeMessage(result.error_code) ??
                        result.message ??
                        '처리하지 못했어요.'}
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
