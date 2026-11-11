import type { DraftCandidate, DraftJob } from './schemas'

export const DEFAULT_MAX_ATTEMPTS = 3

export const isActiveJob = (job: DraftJob) =>
  job.status === 'PENDING' || job.status === 'RUNNING'

// 같은 문서 버전에서 여러 번 만들었으면 가장 최근 작업만 본다. 예전 작업의 후보는 이미 낡았을 수 있다.
// 문서에서 뽑는 작업(DOCUMENT_ONLY)과 답변으로 보완하는 작업(WITH_ANSWERS)은 서로 다른 작업이라 따로 본다.
export function latestJobs(jobs: DraftJob[]): DraftJob[] {
  const latest = new Map<string, DraftJob>()
  for (const job of jobs) {
    const key = `${job.document_version_id}:${job.mode}`
    const current = latest.get(key)
    if (!current || BigInt(job.id) > BigInt(current.id)) latest.set(key, job)
  }
  return [...latest.values()].sort((a, b) =>
    BigInt(a.id) < BigInt(b.id) ? -1 : 1,
  )
}

export const hasActiveJob = (jobs: DraftJob[]) =>
  latestJobs(jobs).some(isActiveJob)

// 후보는 문서 버전마다 가장 최근에 완료된 작업의 것만 본다. 더 새 작업이 실패했거나 도는 중이어도
// 서버는 기존 후보를 바꾸지 않으므로(PLN-A05) 그 사이에는 이전 후보를 계속 보인다.
export const candidateJobs = (jobs: DraftJob[]) =>
  latestJobs(jobs.filter((job) => job.status === 'COMPLETED'))

// 문서 단독 작업은 부분마다 후보를 저장하므로 일부 부분이 실패해도 만들어진 후보를 보인다.
export const documentJobs = (jobs: DraftJob[]) =>
  latestJobs(jobs.filter((job) => job.mode === 'DOCUMENT_ONLY'))

export const answerJobs = (jobs: DraftJob[]) =>
  jobs.filter((job) => job.mode !== 'DOCUMENT_ONLY')

// 기준 v1 을 발행하기 전에 알릴 것: 아직 채택하지 않은 후보와 후보를 다 만들지 못한 문서.
// 발행은 막지 않는다. 팀장이 알고 발행하게 한다(S15P31A108-389).
export function publishNotice(jobs: DraftJob[]): string {
  const relevant = [...documentJobs(jobs), ...candidateJobs(answerJobs(jobs))]
  const pending = relevant
    .flatMap((job) => job.candidates)
    .filter((candidate) => candidate.status === 'PENDING').length
  const failedDocuments = documentJobs(jobs).filter(
    (job) => job.status === 'FAILED',
  ).length
  const notes = []
  if (pending > 0)
    notes.push(
      `아직 채택하지 않은 후보 ${pending}개는 기준에 들어가지 않아요. 필요하면 창을 닫고 후보를 채택해 주세요.`,
    )
  if (failedDocuments > 0)
    notes.push(
      `후보를 모두 만들지 못한 문서가 ${failedDocuments}개 있어요. 빠진 설계 항목은 발행 뒤 변경 제안으로 추가해야 해요.`,
    )
  return notes.length ? ` ${notes.join(' ')}` : ''
}

// 시작 전 화면(공용 문서함·시작 가이드)이 '후보 단계가 끝났는지'를 알려 주는 요약.
// 설계 초안이 하나라도 있어도 채택하지 않은 후보·만드는 중·실패한 문서가 남아 있으면 기획 분석 창으로 돌아갈 길을 둔다.
export function draftWork(jobs: DraftJob[]) {
  const documents = documentJobs(jobs)
  const pending = [...documents, ...candidateJobs(answerJobs(jobs))]
    .flatMap((job) => job.candidates)
    .filter((candidate) => candidate.status === 'PENDING').length
  const running = latestJobs(jobs).filter(isActiveJob).length
  const failed = documents.filter((job) => job.status === 'FAILED').length
  const parts = [
    pending > 0 && `채택하지 않은 후보 ${pending}개`,
    running > 0 && `만드는 중 ${running}건`,
    failed > 0 && `만들지 못한 문서 ${failed}개`,
  ].filter(Boolean)
  return {
    pending,
    running,
    failed,
    open: pending + running + failed > 0,
    summary: parts.join(' · '),
    label:
      pending > 0
        ? `후보 ${pending}개 검토하기`
        : running > 0
          ? '후보 만드는 중 보기'
          : '만들지 못한 문서 보기',
  }
}

/** 후보를 한눈에 알아볼 이름: API 는 메서드+경로, 데이터는 엔티티, 나머지는 설명 */
export function candidateTitle(candidate: DraftCandidate) {
  const after = candidate.after
  const text = (value: unknown) => (typeof value === 'string' ? value : '')
  if (candidate.kind === 'API_CONTRACT' && text(after.path))
    return `${text(after.method)} ${text(after.path)}`.trim()
  if (candidate.kind === 'DATA_CONTRACT' && text(after.entity))
    return text(after.entity)
  return text(after.description) || candidate.candidate_key
}

/** 이 작업이 아직 시도할 수 있는지. 서버가 알려 준 상한을 따르고, 모르면 기본값을 쓴다. */
export function canRetryJob(job: DraftJob) {
  return (
    job.status === 'FAILED' &&
    job.retryable !== false &&
    (job.attempt_no ?? 1) < (job.max_attempts ?? DEFAULT_MAX_ATTEMPTS)
  )
}
