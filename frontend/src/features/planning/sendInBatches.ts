import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import type {
  BatchCandidateResult,
  BatchCandidatesInput,
  BatchCandidatesResponse,
} from './schemas'

// 서버가 한 번에 받는 후보 수(DesignDraftAdoptionService.MAX_BATCH). 요청 스키마의 .max(50) 과 같다.
export const BATCH_LIMIT = 50

// 고른 후보가 서버 상한보다 많으면 상한만큼 나눠 차례로 보내고 결과를 하나로 합친다(S15P31A108-415).
// 첫 묶음이 실패하면 아무것도 처리되지 않은 것이라 그대로 던진다.
// 뒤 묶음이 실패하면 앞 묶음은 이미 처리됐으므로 멈추고, 보내지 못한 후보를 실패로 표시해 돌려준다.
export async function sendInBatches(
  input: BatchCandidatesInput,
  send: (part: BatchCandidatesInput) => Promise<BatchCandidatesResponse>,
): Promise<BatchCandidatesResponse> {
  const results: BatchCandidateResult[] = []
  let succeeded = 0
  let failed = 0
  for (let start = 0; start < input.items.length; start += BATCH_LIMIT) {
    try {
      const response = await send({
        items: input.items.slice(start, start + BATCH_LIMIT),
      })
      results.push(...response.results)
      succeeded += response.succeeded
      failed += response.failed
    } catch (cause) {
      if (start === 0) throw cause
      const errorCode = getApiErrorCode(cause)
      const message = getApiErrorMessage(cause)
      const unsent = input.items.slice(start)
      for (const item of unsent) {
        results.push({
          candidate_id: item.candidate_id,
          result: 'FAILED',
          ...(errorCode ? { error_code: errorCode } : {}),
          message,
        })
      }
      failed += unsent.length
      break
    }
  }
  return { results, succeeded, failed }
}
