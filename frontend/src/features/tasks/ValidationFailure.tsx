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

// 검증 롤백(docs/design/11-validation-rollback.md): 실패한 실행의 결과는 작업에 반영하지 않고 작업 상태도 바꾸지 않는다.
// 롤백이 실패해도 서버가 리스 만료 뒤 실행을 회수해 같은 ERROR로 닫으므로, 화면은 이 결과 한 가지를 알린다.
export function ValidationFailure({
  errorCode,
  needsHumanReview,
}: {
  errorCode?: string
  needsHumanReview: boolean
}) {
  const reason = errorCode && reasons[errorCode]
  return (
    <div className="space-y-1">
      <p role="alert" className="text-sm text-destructive">
        {needsHumanReview
          ? '근거를 확인하고 보완한 뒤 새 검증을 요청해 주세요.'
          : '분석을 완료하지 못했어요. 자동으로 통과 처리하지 않아요.'}
      </p>
      <p className="text-xs text-muted-foreground">
        {reason ? `${reason} ` : ''}이번 실행 결과는 반영하지 않았고 작업 상태도
        그대로예요.
      </p>
    </div>
  )
}
