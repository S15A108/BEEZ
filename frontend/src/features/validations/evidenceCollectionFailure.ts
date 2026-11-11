import type { Validation } from '@/api/contracts/common'

const legacyMessages = new Set([
  '검색 또는 원문 근거 범위가 불완전합니다',
  '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
])

/** 과거 ai_evidence_insufficient에는 실제 내용의 근거 부족도 있어 코드만으로 재분류하지 않는다. */
export function evidenceCollectionFailure(validation: Validation) {
  if (validation.status !== 'ERROR') return undefined
  if (validation.execution_failure?.type === 'EVIDENCE_COLLECTION_FAILED')
    return validation.execution_failure
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
      reason_codes: [],
      retryable: true,
      message: '검증에 필요한 문서와 검색 근거를 모두 가져오지 못했어요.',
    }
  return undefined
}
