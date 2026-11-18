import type { Validation } from '@/api/contracts/common'

const legacyMessages = new Set([
  '검색 또는 원문 근거 범위가 불완전합니다',
  '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
])

const transientReasons = new Set(['evidence_fetch_failed'])

/** 입력 한계나 원인 미상은 같은 입력을 재실행해도 해결되지 않는다. 과거 retryable 값도 재확인한다. */
export function canRetryEvidenceCollection(reasonCodes: string[]) {
  return (
    reasonCodes.length > 0 &&
    reasonCodes.every((code) => transientReasons.has(code))
  )
}

/** 과거 ai_evidence_insufficient에는 실제 내용의 근거 부족도 있어 코드만으로 재분류하지 않는다. */
export function evidenceCollectionFailure(validation: Validation) {
  if (validation.status !== 'ERROR') return undefined
  const errors = validation.findings.filter(
    (finding) => finding.kind === 'ERROR',
  )
  const retryFindings =
    errors.length > 0 &&
    errors.every((finding) => finding.resolution === 'RETRY')
  if (validation.execution_failure?.type === 'EVIDENCE_COLLECTION_FAILED') {
    const failure = validation.execution_failure
    return {
      ...failure,
      retryable:
        failure.retryable &&
        canRetryEvidenceCollection(failure.reason_codes) &&
        retryFindings,
    }
  }
  if (
    validation.error_code === 'evidence_collection_failed' ||
    validation.error_code === 'evidence_fetch_failed' ||
    (validation.error_code === 'ai_evidence_insufficient' &&
      validation.findings.length > 0 &&
      validation.findings.every(
        (finding) =>
          finding.kind === 'ERROR' && legacyMessages.has(finding.message),
      ))
  )
    return {
      type: 'EVIDENCE_COLLECTION_FAILED' as const,
      reason_codes:
        validation.error_code === 'evidence_fetch_failed'
          ? ['evidence_fetch_failed']
          : ['unknown_collection_failure'],
      retryable:
        validation.error_code === 'evidence_fetch_failed' && retryFindings,
      message: '검증에 필요한 문서와 검색 근거를 모두 가져오지 못했어요.',
    }
  return undefined
}
