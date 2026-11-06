import type { DraftJob } from './schemas'

export const isActiveJob = (job: DraftJob) =>
  job.status === 'PENDING' || job.status === 'RUNNING'

// 같은 문서 버전에서 여러 번 만들었으면 가장 최근 작업만 본다. 예전 작업의 후보는 이미 낡았을 수 있다.
export function latestJobs(jobs: DraftJob[]): DraftJob[] {
  const latest = new Map<string, DraftJob>()
  for (const job of jobs) {
    const current = latest.get(job.document_version_id)
    if (!current || BigInt(job.id) > BigInt(current.id))
      latest.set(job.document_version_id, job)
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
