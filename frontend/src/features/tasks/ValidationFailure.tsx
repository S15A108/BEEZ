import { canRetryEvidenceCollection } from '@/features/validations/evidenceCollectionFailure'

// 검증 실행 오류 코드(00-common '검증 실행 오류·상태 매핑')를 사람 말로. 모르는 코드는 보이지 않는다.
const reasons: Record<string, string> = {
  ai_evidence_insufficient: '근거가 부족해 일부 내용을 검사하지 못했어요.',
  ai_unsupported_content:
    '분석이 지원하지 않는 내용이 있어 일부를 검사하지 못했어요.',
  ai_analysis_incomplete:
    '근거가 부족하고 지원하지 않는 내용도 있어 일부를 검사하지 못했어요.',
  ai_timeout: '분석 시간이 초과됐어요.',
  ai_unavailable: '분석 서버에 연결하지 못했어요.',
  ai_invalid_output: '분석 결과의 형식이 올바르지 않았어요.',
  evidence_fetch_failed: '분석에 쓸 문서와 근거를 불러오지 못했어요.',
  validation_worker_interrupted: '분석 실행이 중간에 멈췄어요.',
  validation_lease_expired:
    '분석 실행이 제한 시간 안에 끝나지 않아 서버가 실행을 회수했어요.',
  validation_execution_failed: '서버에서 분석 결과를 저장하지 못했어요.',
}
const collectionReasons: Record<string, string> = {
  search_query_too_long:
    '검색어 길이가 한도를 넘어 근거 검색을 마치지 못했어요.',
  search_query_limit:
    '검색 요청 수가 한도를 넘어 일부 검색을 수행하지 못했어요.',
  search_results_truncated: '검색 결과가 많아 일부 근거가 빠졌어요.',
  design_candidate_limit:
    '확인할 설계 항목이 많아 일부 후보를 가져오지 못했어요.',
  design_reference_limit:
    '계획에 연결한 설계 참조가 한도를 넘었어요. 작업을 나누거나 참조 범위를 조정해 주세요.',
  related_task_limit: '관련 작업이 많아 일부 작업의 근거를 가져오지 못했어요.',
  unresolved_limit:
    '미결 질문이나 개발 맥락이 한도를 넘었어요. 담당자와 필요한 입력 범위를 정리해 주세요.',
  source_evidence_unavailable:
    '근거 문서의 원문·출처 위치·발췌 범위를 확인해야 해요.',
  evidence_fetch_failed: '근거를 가져오는 중 연결 또는 처리 오류가 발생했어요.',
  unknown_collection_failure:
    '근거 수집 실패의 상세 원인을 확인할 수 없어 담당자의 확인이 필요해요.',
}

// 검증 롤백(docs/design/11-validation-rollback.md): 실패한 실행의 결과는 작업에 반영하지 않고 작업 상태도 바꾸지 않는다.
// 롤백이 실패해도 서버가 리스 만료 뒤 실행을 회수해 같은 ERROR로 닫으므로, 화면은 이 결과 한 가지를 알린다.
export function ValidationFailure({
  errorCode,
  needsHumanReview,
  collectionFailure,
}: {
  errorCode?: string
  needsHumanReview: boolean
  collectionFailure?: {
    message: string
    retryable: boolean
    reason_codes?: string[]
  }
}) {
  const reason = errorCode && reasons[errorCode]
  const collectionRetryable =
    collectionFailure?.retryable &&
    canRetryEvidenceCollection(collectionFailure.reason_codes ?? [])
  const causes = [
    ...new Set(
      collectionFailure
        ? (collectionFailure.reason_codes?.length
            ? collectionFailure.reason_codes
            : ['unknown_collection_failure']
          ).map((code) =>
            Object.hasOwn(collectionReasons, code)
              ? collectionReasons[code]
              : collectionReasons.unknown_collection_failure,
          )
        : [],
    ),
  ]
  return (
    <div className="space-y-1">
      <p role="alert" className="text-sm text-destructive">
        {collectionFailure
          ? '근거 수집을 완료하지 못해 계획 분석을 진행하지 못했어요.'
          : needsHumanReview
            ? '근거를 확인하고 보완한 뒤 새 검증을 요청해 주세요.'
            : '분석을 완료하지 못했어요. 자동으로 통과 처리하지 않아요.'}
      </p>
      {causes.length > 0 && (
        <ul
          className="list-inside list-disc text-xs text-muted-foreground"
          aria-label="근거 수집 실패 원인"
        >
          {causes.map((cause) => (
            <li key={cause}>{cause}</li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">
        {collectionFailure
          ? collectionRetryable
            ? `${collectionFailure.message} 근거 수집을 다시 시도해 주세요.`
            : '담당자와 계획·참조 범위 또는 원문 근거를 보완한 뒤 새 검증을 요청해 주세요.'
          : `${reason ? `${reason} ` : ''}이번 실행 결과는 반영하지 않았고 작업 상태도 그대로예요.`}
      </p>
    </div>
  )
}
