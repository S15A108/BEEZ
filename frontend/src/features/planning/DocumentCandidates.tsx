import { useState } from 'react'
import { ShowMoreButton } from './ShowMoreButton'
import { useShowMore } from './useShowMore'
import { RefreshCw } from 'lucide-react'
import { getApiErrorMessage, getErrorCodeMessage } from '@/api/error'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { Tag } from '@/features/changes/Tag'
import { operationTone } from '@/features/changes/tagTones'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import {
  DEFAULT_MAX_ATTEMPTS,
  canRetryJob,
  candidateTitle,
  documentJobs,
  isActiveJob,
} from './draftJobs'
import { Notice } from './Notice'
import type {
  BatchCandidatesInput,
  BatchCandidatesResponse,
  DraftCandidate,
  DraftJob,
} from './schemas'
import { SPLIT_HEIGHT, TAB_BODY } from './splitLayout'
import { SplitView } from './SplitView'

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

// 만드는 중이거나 실패한 문서만 탭 위에 한 줄로 알린다. 다 만든 문서는 목록의 문서 머리가 개수를 보인다.
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
      <Notice tone="run">
        <p>
          <strong>{label}</strong> 후보를 만들고 있어요
          {parts && parts.total > 1
            ? ` (${parts.completed}/${parts.total}번째 부분)`
            : ''}
          . 긴 문서는 나눠서 만들어요.
        </p>
      </Notice>
    )
  const exhausted =
    (job.attempt_no ?? 1) >= (job.max_attempts ?? DEFAULT_MAX_ATTEMPTS)
  return (
    <Notice
      tone="fail"
      action={
        isLeader &&
        canRetryJob(job) && (
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => onRetry(job.document_version_id)}
          >
            <RefreshCw />
            실패한 부분만 다시 시도
          </Button>
        )
      }
    >
      <p>
        <strong>{label}</strong> 후보를 모두 만들지 못했어요.{' '}
        {getErrorCodeMessage(job.error_code) ?? ''}
        {parts && parts.failed > 0 && (
          <>
            {' '}
            {parts.total}개 부분 중 {parts.failed}개를 만들지 못했어요. 다시
            시도하면 못 만든 부분만 다시 만들어요.
          </>
        )}
      </p>
      {exhausted && (
        <p className="text-foreground">
          다시 시도할 수 있는 횟수를 다 썼어요. 설계 항목을 직접 추가하거나,
          문서를 고쳐 새 버전을 승인하면 다시 만들 수 있어요.
        </p>
      )}
    </Notice>
  )
}

function CandidateRow({
  candidate,
  checked,
  focused,
  confirmed,
  duplicate,
  duplicateConfirmed,
  disabled,
  onToggle,
  onFocus,
  onConfirm,
  onConfirmDuplicate,
}: {
  candidate: DraftCandidate
  checked: boolean
  /** 오른쪽 상세에 펼친 후보 */
  focused: boolean
  confirmed: boolean
  /** 이미 같은 설계 항목이 있을 수 있어 알고 채택한다는 확인이 필요한 후보 */
  duplicate: boolean
  duplicateConfirmed: boolean
  disabled: boolean
  onToggle: (checked: boolean) => void
  onFocus: () => void
  onConfirm: (confirmed: boolean) => void
  onConfirmDuplicate: (confirmed: boolean) => void
}) {
  const title = candidateTitle(candidate)
  const noEvidence = candidate.support === 'NONE'
  return (
    <li
      className={cn(
        'border-b px-4 py-3 text-sm',
        focused ? 'bg-primary/15' : checked && 'bg-primary/5',
      )}
    >
      <div className="flex items-start gap-3">
        <input
          type="checkbox"
          className="mt-0.5 size-4 shrink-0 accent-primary"
          aria-label={`${title} 선택`}
          checked={checked}
          disabled={disabled}
          onChange={(event) => onToggle(event.target.checked)}
        />
        <button
          type="button"
          aria-current={focused || undefined}
          onClick={onFocus}
          className="min-w-0 flex-1 text-left focus-visible:underline focus-visible:outline-none"
        >
          <span className="line-clamp-2 leading-[1.5] font-medium break-words">
            {title}
          </span>
          <span className="mt-0.5 block text-xs text-muted-foreground">
            {statusLabel(candidate.kind)}
            {noEvidence && (
              <span className="text-destructive">, 근거 없음</span>
            )}
          </span>
          {candidate.overlap && (
            <span className="mt-0.5 block text-xs text-amber-800">
              {overlapLabel[candidate.overlap.kind]}
            </span>
          )}
        </button>
      </div>
      {checked && noEvidence && (
        <label className="mt-2 ml-7 flex items-start gap-2">
          <input
            type="checkbox"
            className="mt-1 accent-primary"
            checked={confirmed}
            disabled={disabled}
            onChange={(event) => onConfirm(event.target.checked)}
          />
          문서에서 근거를 찾지 못한 제안이라는 점을 확인했어요.
        </label>
      )}
      {checked && duplicate && (
        <label className="mt-2 ml-7 flex items-start gap-2">
          <input
            type="checkbox"
            className="mt-1 accent-primary"
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

// 오른쪽 상세: 후보 이름, 비비가 뽑은 이유, 문서에서 찾은 문장과 위치
function CandidateDetail({
  candidate,
  label,
}: {
  candidate: DraftCandidate
  label: string
}) {
  const title = candidateTitle(candidate)
  const description =
    typeof candidate.after.description === 'string'
      ? candidate.after.description
      : ''
  const quoted = candidate.sources.filter((source) => source.quote)
  const location = candidate.sources.find((source) => source.location)?.location
  return (
    <div className="px-8 pt-5 pb-6">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="mr-1 text-xs font-semibold text-muted-foreground">
          {label}
        </span>
        <Tag>{statusLabel(candidate.kind)}</Tag>
        <Tag
          tone={candidate.support === 'NONE' ? operationTone.RETIRE : undefined}
        >
          {supportLabel[candidate.support]}
        </Tag>
      </div>
      <h3 className="mt-3 text-xl leading-8 font-semibold tracking-tight break-words">
        {title}
      </h3>
      {description && description !== title && (
        <p className="mt-2 text-[15px] leading-7 break-words whitespace-pre-wrap">
          {description}
        </p>
      )}
      {candidate.rationale && (
        <p className="mt-2 text-[15px] leading-7 break-words text-muted-foreground">
          {candidate.rationale}
        </p>
      )}
      {candidate.overlap && (
        <p className="mt-4 rounded-lg bg-amber-50 px-4 py-2.5 text-sm text-amber-900">
          {overlapLabel[candidate.overlap.kind]}
        </p>
      )}
      {quoted.length > 0 ? (
        <figure className="mt-6 rounded-lg bg-muted/60 px-5 py-4">
          <figcaption className="mb-2 text-xs font-semibold text-muted-foreground">
            문서에서 찾은 문장
          </figcaption>
          {quoted.map((source, index) => (
            <blockquote
              key={index}
              className="text-[15px] leading-7 break-words"
            >
              “{source.quote}”
            </blockquote>
          ))}
          {location && (
            <p className="mt-2 text-xs text-muted-foreground">{location}</p>
          )}
        </figure>
      ) : (
        candidate.support === 'NONE' && (
          <p className="mt-6 rounded-lg bg-destructive/5 px-4 py-3 text-sm leading-6 text-destructive">
            문서에서 근거를 찾지 못한 제안이에요. 채택하려면 목록에서 고른 뒤
            확인 체크를 해 주세요.
          </p>
        )
      )}
    </div>
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
  const [focusedId, setFocusedId] = useState<string>()
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
  // 숨은 후보까지 한 번에 고르려면 '후보 N개 모두 선택'을 누른다. 근거 없는 후보는 건별 확인이 필요해 여기에 넣지 않는다.
  const freshList = useShowMore(allFresh)
  const overlapList = useShowMore(overlapped)
  const shownIds = new Set(freshList.shown.map((candidate) => candidate.id))
  const all = [...allFresh, ...overlapped]
  const chosen = all.filter((candidate) => selected.includes(candidate.id))
  const focused =
    all.find((candidate) => candidate.id === focusedId) ??
    freshList.shown[0] ??
    overlapped[0]
  const versionOf = (candidate: DraftCandidate) =>
    latest.find((job) => job.candidates.includes(candidate))
      ?.document_version_id
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
  const selectable = allFresh.filter(
    (candidate) => candidate.support !== 'NONE',
  )
  const unsupported = allFresh.length - selectable.length

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
  const creatable = isLeader && !!onCreate && creatableVersionIds.length > 0
  const createButton = (
    <Button
      size="sm"
      variant="outline"
      disabled={busy || working || latest.some(isActiveJob)}
      onClick={() => onCreate?.(creatableVersionIds)}
    >
      문서에서 후보 만들기
    </Button>
  )
  const row = (candidate: DraftCandidate) => (
    <CandidateRow
      key={candidate.id}
      candidate={candidate}
      checked={selected.includes(candidate.id)}
      focused={candidate.id === focused?.id}
      confirmed={confirmed.includes(candidate.id)}
      duplicate={isDuplicate(candidate)}
      duplicateConfirmed={duplicateConfirmed.includes(candidate.id)}
      disabled={disabled}
      onToggle={(on) => toggle(candidate.id, on)}
      onFocus={() => setFocusedId(candidate.id)}
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
  )

  return (
    <div className={TAB_BODY}>
      {latest
        .filter((job) => isActiveJob(job) || job.status === 'FAILED')
        .map((job) => (
          <JobStatus
            key={job.id}
            job={job}
            label={labelOfVersion(job.document_version_id)}
            isLeader={isLeader}
            busy={busy || working}
            onRetry={onRetry}
          />
        ))}
      {creatable && latest.length > 0 && (
        <Notice tone="wait" action={createButton}>
          후보를 아직 만들지 않은 문서가 {creatableVersionIds.length}개 있어요.
        </Notice>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 px-3.5 py-2 text-sm text-destructive"
        >
          {error}
        </p>
      )}
      {outcome && (
        <div
          role="status"
          className="rounded-lg bg-primary/10 px-3.5 py-2 text-sm"
        >
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
      {latest.length === 0 ? (
        <EmptyState className={SPLIT_HEIGHT}>
          <p>문서 4종이 모두 승인되고 색인이 끝나면 후보가 여기에 나와요.</p>
          {creatable && (
            <>
              <p className="text-xs">
                자동으로 만들어지지 않았다면 직접 만들 수 있어요.
              </p>
              {createButton}
            </>
          )}
        </EmptyState>
      ) : all.length === 0 ? (
        <EmptyState className={SPLIT_HEIGHT}>
          {latest.some(isActiveJob)
            ? '만드는 중인 후보는 끝나면 여기에 나와요.'
            : '채택하거나 폐기할 후보가 남아 있지 않아요.'}
        </EmptyState>
      ) : (
        <SplitView
          header={
            <>
              <label className="flex items-center gap-2 text-[13px]">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={allChecked}
                  disabled={disabled || allFresh.length === 0}
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
                        : current.filter((id) => !shownIds.has(id)),
                    )
                  }
                />
                전체 선택
              </label>
              {freshList.hidden > 0 && selectable.length > 0 && (
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  disabled={disabled}
                  onClick={() =>
                    setSelected((current) => [
                      ...new Set([
                        ...current,
                        ...selectable.map((candidate) => candidate.id),
                      ]),
                    ])
                  }
                >
                  후보 {selectable.length}개 모두 선택
                </Button>
              )}
              <span
                role="status"
                className="ml-auto text-[13px] text-muted-foreground"
              >
                {chosen.length}개 선택
              </span>
            </>
          }
          list={
            <>
              {freshList.hidden > 0 && unsupported > 0 && (
                <p className="border-b bg-muted/40 px-4 py-2 text-xs text-muted-foreground">
                  근거 없는 후보 {unsupported}개는 모두 선택에 들어가지 않아요.
                  내용을 확인하고 직접 골라 주세요.
                </p>
              )}
              {latest.map((job) => {
                const label = labelOfVersion(job.document_version_id)
                const rows = fresh(job).filter((candidate) =>
                  shownIds.has(candidate.id),
                )
                if (!rows.length) return null
                return (
                  <section key={job.id} aria-label={label}>
                    <h4 className="sticky top-0 z-10 border-b bg-muted px-4 py-1.5 text-xs font-semibold text-muted-foreground">
                      {label} · {fresh(job).length}개
                    </h4>
                    <ul>{rows.map(row)}</ul>
                  </section>
                )
              })}
              {freshList.hidden > 0 && (
                <div className="grid p-3">
                  <ShowMoreButton
                    hidden={freshList.hidden}
                    label="후보"
                    onClick={freshList.more}
                  />
                </div>
              )}
              {overlapped.length > 0 && (
                <details className="border-b">
                  <summary className="cursor-pointer px-4 py-2.5 text-sm text-muted-foreground">
                    이미 있는 항목과 비슷한 후보 {overlapped.length}개
                  </summary>
                  <ul className="border-t">{overlapList.shown.map(row)}</ul>
                  {overlapList.hidden > 0 && (
                    <div className="grid p-3">
                      <ShowMoreButton
                        hidden={overlapList.hidden}
                        label="비슷한 후보"
                        onClick={overlapList.more}
                      />
                    </div>
                  )}
                </details>
              )}
            </>
          }
          footer={
            isLeader && (
              <>
                {needsConfirm.length > 0 && (
                  <p className="w-full text-xs text-muted-foreground">
                    근거 없는 후보는 확인 체크를 해야 채택할 수 있어요.
                  </p>
                )}
                <Button
                  variant="outline"
                  className="ml-auto"
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
              </>
            )
          }
          detail={
            focused && (
              <CandidateDetail
                candidate={focused}
                label={labelOfVersion(versionOf(focused) ?? '')}
              />
            )
          }
        />
      )}
    </div>
  )
}
