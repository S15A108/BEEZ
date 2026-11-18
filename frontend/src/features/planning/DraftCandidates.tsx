import { useState } from 'react'
import { ShowMoreButton } from './ShowMoreButton'
import { useShowMore } from './useShowMore'
import { RefreshCw, Sparkles, TriangleAlert } from 'lucide-react'
import type { Source } from '@/api/contracts/common'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getErrorCodeMessage,
} from '@/api/error'
import { EmptyState } from '@/components/EmptyState'
import { SourceLinks } from '@/components/SourceLinks'
import { Button } from '@/components/ui/button'
import { Tag } from '@/features/changes/Tag'
import { operationTone } from '@/features/changes/tagTones'
import { statusLabel } from '@/lib/display'
import {
  answerJobs,
  candidateJobs,
  hasActiveJob,
  isActiveJob,
  latestJobs,
} from './draftJobs'
import { Notice } from './Notice'
import type { AdoptCandidateInput, DraftCandidate, DraftJob } from './schemas'
import {
  DETAIL_FOOTER,
  SPLIT_HEIGHT,
  TAB_BODY,
  listRowClass,
} from './splitLayout'
import { SplitView } from './SplitView'

const supportLabel = {
  DOCUMENT: '문서 근거',
  ANSWER: '답변 근거',
  DOCUMENT_AND_ANSWER: '문서·답변 근거',
  NONE: '근거 없음',
} as const
const changeLabel = { CREATE: '새 항목', UPDATE: '기존 항목 수정' } as const
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
      <dl className="grid gap-3 rounded-lg bg-muted/60 px-4 py-3 text-[15px] leading-7">
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
    <div className="grid gap-3">
      {changed.map((key) => {
        const conflict = candidate.conflicting_fields.includes(key)
        return (
          <div
            key={key}
            className={
              conflict
                ? 'rounded-lg border border-amber-300 bg-amber-50 p-3'
                : 'rounded-lg border p-3'
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
              <div className="rounded-md bg-muted/60 px-3 py-2.5">
                <p className="mb-1 text-xs font-semibold text-muted-foreground">
                  이전
                </p>
                <p className="text-sm leading-6 break-words whitespace-pre-wrap text-foreground/75">
                  {valueOf(key, 'before')}
                </p>
              </div>
              <div className="rounded-md bg-[#e8f3ec] px-3 py-2.5">
                <p className="mb-1 text-xs font-semibold text-[#00753a]">
                  이후
                </p>
                <p className="text-sm leading-6 break-words whitespace-pre-wrap">
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
          <dl className="mt-2 grid gap-2 rounded-lg bg-muted/60 p-3">
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

// 오른쪽 상세: 후보 하나의 전후 비교·근거와 채택·폐기
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
    <article
      aria-label={candidate.candidate_key}
      className="flex flex-1 flex-col"
    >
      <div className="px-8 pt-5 pb-6">
        <div className="flex flex-wrap items-center gap-1.5">
          <Tag
            tone={
              candidate.change === 'CREATE'
                ? operationTone.CREATE
                : operationTone.UPDATE
            }
          >
            {changeLabel[candidate.change]}
          </Tag>
          <Tag>{statusLabel(candidate.kind)}</Tag>
          <Tag tone={noEvidence ? operationTone.RETIRE : undefined}>
            {supportLabel[candidate.support]}
          </Tag>
          {candidate.status === 'ADOPTED' && (
            <Tag tone={operationTone.CREATE}>채택함</Tag>
          )}
          {candidate.status === 'DISCARDED' && <Tag>폐기함</Tag>}
          {candidate.answer_refs.length > 0 && (
            <span className="ml-auto text-xs text-muted-foreground">
              답변 {candidate.answer_refs.length}개를 반영했어요.
            </span>
          )}
        </div>
        <h3 className="mt-3 text-xl leading-8 font-semibold tracking-tight break-words">
          {candidate.candidate_key}
        </h3>
        {candidate.rationale && (
          <p className="mt-2 text-[15px] leading-7 text-muted-foreground">
            {candidate.rationale}
          </p>
        )}
        <div className="mt-5">
          <Compare candidate={candidate} />
        </div>
        {candidate.sources.some((source) => source.quote) && (
          <ul className="mt-4 grid gap-1 text-[13px] leading-6 text-muted-foreground">
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
        {pending && isLeader && (
          <div className="mt-5 grid gap-3">
            {noEvidence && (
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-1 accent-primary"
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
                  className="mt-1 accent-primary"
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
          </div>
        )}
      </div>
      {pending && isLeader && (
        <div className={DETAIL_FOOTER}>
          <Button
            variant="outline"
            className="ml-auto"
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
      )}
    </article>
  )
}

function CandidateRow({
  candidate,
  active,
  onClick,
}: {
  candidate: DraftCandidate
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-current={active || undefined}
      onClick={onClick}
      className={listRowClass(active)}
    >
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">
          {candidate.candidate_key}
        </span>
        <span className="mt-0.5 block text-xs text-muted-foreground">
          <span
            className={
              candidate.change === 'CREATE'
                ? 'text-[#00753a]'
                : 'text-[#6e5200]'
            }
          >
            {candidate.change === 'CREATE' ? '새 항목' : '수정'}
          </span>
          , {statusLabel(candidate.kind)}
          {candidate.support === 'NONE' && (
            <span className="text-destructive">, 근거 없음</span>
          )}
        </span>
      </span>
      {candidate.conflicting_fields.length > 0 && (
        <TriangleAlert
          role="img"
          aria-label="원문과 답변이 달라요"
          className="mt-0.5 size-4 shrink-0 text-amber-600"
        />
      )}
    </button>
  )
}

export function DraftCandidates({
  jobs,
  labelOfVersion,
  titleOf,
  isLeader,
  busy,
  canGenerate,
  answerSummary,
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
  /** 지금 만들면 반영할 답변 수 안내(예: 답한 질문 3개를 반영해요) */
  answerSummary?: string
  /** versionIds 가 없으면 모든 문서, 있으면 그 문서만 초안을 만든다 */
  onGenerate: (versionIds?: string[]) => void
  onAdopt: (
    candidate: DraftCandidate,
    input: AdoptCandidateInput,
  ) => Promise<unknown>
  onDiscard: (candidate: DraftCandidate) => Promise<unknown>
}) {
  const [focusedId, setFocusedId] = useState<string>()
  // 문서에서 자동으로 뽑은 후보는 DocumentCandidates 가 보여 준다. 여기는 질문·답변으로 보완한 후보만 다룬다.
  const answered = answerJobs(jobs)
  const latest = latestJobs(answered)
  const active = latest.some(isActiveJob)
  // 한 문서 버전에는 동시에 하나의 생성 작업만 돈다(서버 409 draft_in_progress). 문서에서 후보를 만드는 중에는 이 단계도 기다린다.
  const waitingForDocuments = !active && hasActiveJob(jobs)
  const candidates = candidateJobs(answered).flatMap((job) => job.candidates)
  // 후보가 어느 문서에서 나왔는지. 낡은 후보를 다시 만들 때 그 문서만 다시 만든다.
  const versionOfCandidate = new Map(
    candidateJobs(answered).flatMap((job) =>
      job.candidates.map((item) => [item.id, job.document_version_id] as const),
    ),
  )
  const regenerate = (candidate: DraftCandidate) => {
    if (waitingForDocuments) return
    const versionId = versionOfCandidate.get(candidate.id)
    onGenerate(versionId ? [versionId] : undefined)
  }
  const waiting = candidates.filter(
    (candidate) => candidate.status === 'PENDING',
  )
  const done = candidates.filter((candidate) => candidate.status !== 'PENDING')
  const waitingList = useShowMore(waiting)
  const doneList = useShowMore(done)
  const focused =
    candidates.find((candidate) => candidate.id === focusedId) ??
    waiting[0] ??
    done[0]
  const generateButton = isLeader && (
    <Button
      disabled={busy || active || waitingForDocuments || !canGenerate}
      onClick={() => onGenerate()}
    >
      <Sparkles />
      답변으로 보완 초안 만들기
    </Button>
  )
  const row = (candidate: DraftCandidate) => (
    <li key={candidate.id}>
      <CandidateRow
        candidate={candidate}
        active={candidate.id === focused?.id}
        onClick={() => setFocusedId(candidate.id)}
      />
    </li>
  )
  return (
    <div className={TAB_BODY}>
      {latest.map((job) => {
        const label = labelOfVersion(job.document_version_id)
        if (isActiveJob(job))
          return (
            <Notice key={job.id} tone="run">
              {label} 초안을 만들고 있어요. 잠시만 기다려 주세요.
            </Notice>
          )
        if (job.status === 'FAILED')
          return (
            <Notice
              key={job.id}
              tone="fail"
              action={
                isLeader &&
                job.retryable !== false && (
                  <Button
                    size="sm"
                    variant="outline"
                    // 실패한 이 문서만 다시 만든다. 다른 문서가 만드는 중이어도 이 문서는 다시 요청할 수 있다.
                    disabled={busy}
                    onClick={() => onGenerate([job.document_version_id])}
                  >
                    <RefreshCw />
                    다시 만들기
                  </Button>
                )
              }
            >
              {label} 초안을 만들지 못했어요.{' '}
              {getErrorCodeMessage(job.error_code) ?? ''}
            </Notice>
          )
        return null
      })}
      {waitingForDocuments && (
        <Notice tone="wait">
          문서에서 후보를 만드는 중이라 끝난 뒤에 누를 수 있어요.
        </Notice>
      )}
      {candidateJobs(answered).some(
        (job) => job.unanswered_question_keys?.length || job.unresolved?.length,
      ) && (
        <p className="text-xs text-muted-foreground">
          답하지 않은 질문과 정하지 못한 내용은 후보에 반영하지 못했어요.
        </p>
      )}
      {candidates.length === 0 ? (
        <EmptyState className={SPLIT_HEIGHT}>
          <p>
            {latest.length === 0
              ? '‘답변으로 보완 초안 만들기’를 누르면 후보가 여기에 나와요.'
              : active
                ? '만드는 중인 후보는 끝나면 여기에 나와요.'
                : '이번에는 제안할 후보가 없어요.'}
          </p>
          {answerSummary && <p className="text-xs">{answerSummary}</p>}
          {generateButton}
        </EmptyState>
      ) : (
        <SplitView
          header={
            generateButton && (
              <div className="grid w-full gap-1.5 py-0.5">
                {generateButton}
                {answerSummary && (
                  <p className="text-center text-xs text-muted-foreground">
                    {answerSummary}
                  </p>
                )}
              </div>
            )
          }
          list={
            <>
              <ul>{waitingList.shown.map(row)}</ul>
              {waitingList.hidden > 0 && (
                <div className="grid p-3">
                  <ShowMoreButton
                    hidden={waitingList.hidden}
                    label="후보"
                    onClick={waitingList.more}
                  />
                </div>
              )}
              {done.length > 0 && (
                <details>
                  <summary className="cursor-pointer border-b px-4 py-2.5 text-sm text-muted-foreground">
                    처리한 후보 {done.length}개
                  </summary>
                  <ul>{doneList.shown.map(row)}</ul>
                  {doneList.hidden > 0 && (
                    <div className="grid p-3">
                      <ShowMoreButton
                        hidden={doneList.hidden}
                        label="처리한 후보"
                        onClick={doneList.more}
                      />
                    </div>
                  )}
                </details>
              )}
            </>
          }
          // 후보마다 상세를 띄워 두고 고른 후보만 보인다. 다른 후보를 보고 돌아와도 근거 확인·확정한 필드·
          // 낡은 후보 안내(초안 다시 만들기)가 그대로 남는다(PR #303 재검토).
          detail={candidates.map((candidate) => (
            <div
              key={candidate.id}
              hidden={candidate.id !== focused?.id}
              className="flex flex-1 flex-col"
            >
              <CandidateCard
                candidate={candidate}
                titleOf={titleOf}
                isLeader={isLeader}
                busy={busy}
                onAdopt={(input) => onAdopt(candidate, input)}
                onDiscard={() => onDiscard(candidate)}
                onRegenerate={() => regenerate(candidate)}
              />
            </div>
          ))}
        />
      )}
    </div>
  )
}
