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

// 분석이 COMPLETED 가 된 직후 워커가 실행 행을 만들기 전에는 응답에 question_generation 이 없다. 그 사이를
// WAITING 으로 보고 기다린다. 기준 발행 뒤처럼 서버가 질문을 만들지 않는 문서도 응답이 같으므로 기다리는 시간은 제한한다.
export const START_WAIT_MS = 15_000

export function generationOf(
  analysis: AnalysisLike | undefined,
  graceOver: boolean,
): DocumentGeneration | undefined {
  if (analysis?.status !== 'COMPLETED') return undefined
  const run = analysis.question_generation
  if (run)
    return {
      status: run.status as GenerationStatus,
      errorCode: run.error_code,
      retryable: run.retryable,
    }
  return graceOver ? undefined : { status: 'WAITING' }
}

export const isGenerating = (generation: DocumentGeneration | undefined) =>
  generation?.status === 'WAITING' ||
  generation?.status === 'PENDING' ||
  generation?.status === 'RUNNING'

// 분석 자체가 도는 중이거나 질문 생성이 도는 중이면 분석 상태를 계속 다시 읽는다.
export const shouldPollAnalysis = (
  analysis: AnalysisLike | undefined,
  graceOver: boolean,
) =>
  analysis?.status === 'PENDING' ||
  analysis?.status === 'RUNNING' ||
  isGenerating(generationOf(analysis, graceOver))
