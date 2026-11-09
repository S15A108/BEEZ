# AI 판정 인수 기준 준비 (AC-02·03·07·15·16)

Jira: S15P31A108-209 · 작성 2026-11-09 · 기준 `dev` `b61e5064`

이 문서는 11/10~11/13 실환경 인수 검증을 준비한다. 자동 테스트·모델 대역·실제 AI·실제 화면은 서로 다른 증거다. 이 문서의 준비와 테스트 통과만으로 인수 기준이나 Jira 완료를 선언하지 않는다. 실제 실행 결과·검증 번호·증거와 담당자를 아래에 기록하고, 기대와 다른 결과는 결함으로 추적해 POL-15 정리 담당자에게 전달한다.

| 기준 | 이번 준비 및 기존 근거 | 실제 환경에서 확인·기록할 것 | 결과 |
| --- | --- | --- | --- |
| AC-02 | 합성 입력의 승인 API는 `userId`, 계획은 `memberId`다. AI 결과의 BLOCKING 불일치·항목/버전·문서 근거·제안 문구를 검사한다. 기존 BE 대역 검사는 [검증 브리지](../../backend/src/test/java/com/beez/backend/validation/ValidationBridgeIntegrationTest.java)의 `blockingFindingOnUnreferencedSearchCandidateStillRequiresReview`다. | 실제 AI·검색·BE에서 결과, 관련 근거와 계획 보완/변경 제안 연결을 확인한다. 자동 기준의 일치는 문구의 의미와 후속 연결을 보증하지 않는다. | 미실행 |
| AC-03 | 합성 정책 미정 입력과 근거 부족 결과를 준비한다. 기존 [정책 통합 테스트](../../backend/src/test/java/com/beez/backend/validation/PolicyGapIntegrationTest.java)의 `missingEvidenceIsExposedAsPartialAndNeverAsMissingPolicy`는 대역 결과가 임의 정책 확정이나 PASS가 되지 않는지 확인한다. | 실제 승인 설계에 필요한 정책이 없을 때의 검색·AI·BE 결과와 근거를 확인한다. 11/9 요청자 결정으로 현행 [공용 계약](../api/03-ai-internal.md)을 유지한다: 명시된 정책 미정 또는 합의된 범위 내 부재 확인은 NEEDS_REVIEW, 단지 근거가 부족한 PARTIAL은 `ERROR(ai_evidence_insufficient)`다. 두 경우를 구분해 실제 결과를 기록한다. | 미실행 |
| AC-07 | [발행 영향 테스트](../../backend/src/test/java/com/beez/backend/validation/PublicationImpactIntegrationTests.java)의 `publicationOfSearchCandidateDoesNotStaleOrHoldUnrelatedTask`, `documentOnlyPublication_changesNothing`과 [정책 테스트](../../backend/src/test/java/com/beez/backend/validation/PolicyGapIntegrationTest.java)의 `onlyCitedPoliciesInvalidateValidationAndGateOrHoldTheTask`가 완료된 검증을 다룬다. 이들은 격리 DB의 기존 자동 테스트다. | PASS 후 인용하지 않은 관련 없는 항목·문서만 발행하고 같은 검증과 작업 상태, 추가 AI 실행·사용량 유무를 확인한다. 분석 **도중** baseline이 바뀌는 경우는 현재 구현에서 결과를 폐기하므로 따로 기록한다. | 미실행 |
| AC-15 | `TasksPanel.test.tsx`의 PASS 화면 검사는 계획 통과와 코드 품질·테스트 통과 비보장 안내를 확인한다. 화면 대역 시험일 뿐 실제 코드 차이를 분석하지 않는다. | PASS 계획과 다른 실제 구현을 준비해 사전 검증 화면이 구현 검증 완료로 표시하지 않는지 확인한다. 박민용과 화면 확인을 조율한다. | 미실행 |
| AC-16 | 기존 dev에는 동일 AI 결과 캐시가 없다. [PR #247](https://github.com/S15A108/BEEZ/pull/247)은 미병합인 규칙 전용 PASS 재사용이며 검색·모델 결과 재사용은 포함하지 않는다. | 어떤 PR·설정이 배포됐는지 먼저 확인하고, 동일 입력의 권한·리비전·근거 및 변경 후 새 실행을 검증한다. 현재 모델 결과 재사용은 미구현으로 기록한다. | 미실행 |

## AC-02·03 합성 AI 입력 실행

`ai/`에서 다음 명령은 입력 두 건과 해시를 검증하지만 모델을 호출하거나 결과 파일을 만들지 않는다.

```powershell
python -m evaluations.validation.acceptance_209 --output results-209.json
```

실제 평가 시에는 격리된 합성 입력과 승인된 테스트 자격 증명을 사용해 같은 명령에 `--run-live`를 붙인다. 두 번의 유료 모델 호출이 발생하며 `VALIDATION_EVAL_ENDPOINT`, `VALIDATION_EVAL_MODEL`, `VALIDATION_EVAL_KEY_NAME` 및 해당 키 설정은 기존 [평가 안내](../../ai/evaluations/validation/README.md)를 따른다. 출력에는 케이스별 HTTP 상태, AI 응답·사용량, 로컬 왕복 `duration_ms`가 담긴다. 비용이 없으면 미확인으로 남기고 0으로 바꾸지 않는다. 테스트 데이터만 담기며 결과는 공유 전에 민감 정보가 없는지 확인한다.

이는 FastAPI에 테스트 제공자를 주입한 직접 모델 호출이다. 배포된 BE→검색→AI 경로, 최종 BE 상태, 실제 운영 성능이나 사람의 의미 판단을 대체하지 않는다. 입력 2건은 독립 평가셋 전체가 아니다. 실제 실행 뒤 출력과 각 근거를 사람이 읽고 오탐·누락·지연·비용을 기록한다.

## 실행 기록

| 실행 일시·환경 / 버전 | 기준·검증 번호 | 결과·오탐/누락 | 지연·토큰·비용 | 증거·결함 |
| --- | --- | --- | --- | --- |
| 미실행 | 미기록 | 미기록 | 미기록 | 미기록 |
