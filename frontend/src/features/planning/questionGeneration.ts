// 보완 질문 생성(PLN-A01 question_generation)은 문서 버전마다 따로 돌고 끝난다. 상태는 문서별로 따로 본다.
export type GenerationStatus =
  'WAITING' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export interface DocumentGeneration {
  status: GenerationStatus
  errorCode?: string
  retryable?: boolean
}
interface AnalysisLike {
  status: string
  question_generation?: {
    status: string
    error_code?: string
    retryable?: boolean
  }
}

// 서버 워커는 분석이 끝난 문서를 한 번에 하나씩 순서대로 처리하고, 처리를 시작할 때 실행 행을 만든다. 그래서 앞 문서의
// AI 호출이 길면 뒤 문서는 한참 동안 응답에 question_generation 이 없다. 이 상태는 시간으로 끊지 않고 문서가 서버의
// 대상(isQuestionTarget)인 동안 WAITING 으로 계속 따라간다.
export const ACTIVE_POLL_MS = 1500
// 서버 워커가 대기 중인 문서를 찾는 주기(beez.planning.poll-millis, 기본 3초)에 맞춘다.
export const WAITING_POLL_MS = 3000

// 서버(PlanningQuestionRepository.nextRunnable)는 기준 설계가 없고 문서 버전이 DRAFT·APPROVED 일 때만 질문을 만든다.
// 기준이 발행됐거나 다른 상태의 버전은 실행 행이 영영 생기지 않으므로 기다리지 않는다.
export const isQuestionTarget = (
  version: { status: string },
  hasBaseline: boolean,
) =>
  !hasBaseline && (version.status === 'DRAFT' || version.status === 'APPROVED')

export function generationOf(
  analysis: AnalysisLike | undefined,
  target: boolean,
): DocumentGeneration | undefined {
  if (analysis?.status !== 'COMPLETED') return undefined
  const run = analysis.question_generation
  if (run)
    return {
      status: run.status as GenerationStatus,
      errorCode: run.error_code,
      retryable: run.retryable,
    }
  return target ? { status: 'WAITING' } : undefined
}

export const isGenerating = (generation: DocumentGeneration | undefined) =>
  generation?.status === 'WAITING' ||
  generation?.status === 'PENDING' ||
  generation?.status === 'RUNNING'

// 분석이 도는 중이거나 질문 생성이 대기·진행 중이면 분석 상태를 계속 다시 읽는다. 끝났거나 실패한 문서는 멈춘다.
export function pollIntervalOf(
  analysis: AnalysisLike | undefined,
  target: boolean,
): number | false {
  if (analysis?.status === 'PENDING' || analysis?.status === 'RUNNING')
    return ACTIVE_POLL_MS
  const generation = generationOf(analysis, target)
  if (generation?.status === 'WAITING') return WAITING_POLL_MS
  return isGenerating(generation) ? ACTIVE_POLL_MS : false
}
