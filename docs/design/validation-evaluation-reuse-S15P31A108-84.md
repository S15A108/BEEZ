# 검증 평가와 결과 재사용 정책 조사 (S15P31A108-84)

기준: #247이 병합된 `dev` 커밋 `0c19c371`의 백엔드 구현과 계약 문서. 현재 구현된 규칙 전용 PASS 재사용과 검색·모델 결과에 관한 향후 협의 제안을 구분한다.

## 현재 동작: 서로 다른 네 가지

| 구분 | 현재 동작과 범위 | 근거 |
| --- | --- | --- |
| 동일 입력의 새 검증 결과 재사용 (AC-16) | **규칙 전용 PASS만 구현** (#247). 현재 프로젝트 권한·담당자와 규칙을 다시 확인하고, 정책 분석이 꺼진 AGENT 요청에서 현재 규칙 결과가 finding·추가 검사 없는 PASS일 때 작업의 최신 검증이 같은 현재 리비전·기준·입력 해시·규칙 버전의 정상 완료된 규칙 전용 PASS라면 이를 돌려준다. 이 접수 응답만 `cache_hit=true`이며 새 검증 실행·사용량을 만들지 않는다. DELTA·정책 분석 및 검색·모델 결과 재사용은 대상이 아니다. AI 분석이 필요한 새 요청은 동일 입력 시도 제한을 적용한다. | `backend/src/main/java/com/beez/backend/validation/service/ValidationAcceptService.java`, `backend/src/main/java/com/beez/backend/validation/repository/ValidationRepository.java`, `docs/design/12-validation-resilience.md`의 결과 재사용·시도 제한, `docs/api/01-backend-rest.md`의 VAL-A01 |
| REST 검증 재시도 중복 | `expected_attempt`가 현재 시도보다 1 작으면 새 실행을 열지 않고 **현재 검증 상태**를 202로 돌려준다. 더 오래된 번호는 `revision_conflict`다. 실제 재시도는 재시도 가능한 ERROR에 새 attempt/run/job을 열고 기존 이력과 사용량을 보존한다. 매 요청에 권한을 확인한다. | `backend/src/main/java/com/beez/backend/validation/service/ValidationRetryService.java`, `docs/api/01-backend-rest.md` VAL-A04, `docs/design/12-validation-resilience.md` |
| MCP 요청 멱등 재생 | `validate_task_plan`, `retry_validation`의 `idempotency_key`는 계정·프로젝트·동작·키 범위에서 24시간 저장된다. 같은 입력은 최초 응답을 재생하고 다른 입력은 409다. 재생 전 현재 프로젝트 구성원과 작업 담당자 권한을 확인한다. REST VAL-A01과 VAL-A04는 `Idempotency-Key`를 처리하지 않는다. | `backend/src/main/java/com/beez/backend/mcp/service/ValidationMcpService.java`, `McpCommandStore.java`, `backend/src/main/java/com/beez/backend/validation/api/ValidationController.java`, `docs/api/01-backend-rest.md` VAL-A01·VAL-A04 |
| 동일 내용 계획 리비전의 결과 승계 | 같은 작업의 계획 변경 시 입력이 같고 필요한 컨텍스트 조건을 만족하면 완료된 계획 검증 결과를 새 리비전에 잇는다. 완료된 PASS·NEEDS_INPUT·NEEDS_REVIEW가 대상이고 DELTA·ERROR·STALE은 대상이 아니다. 검색 기반 검증과 정책 분석은 제목·접근 방식 등 추가 조건이 있다. 실행 중인 결과는 나중에 반영할 때 별도로 확인한다. 원본 출처를 남기며 새 모델 실행을 복사하는 동작이 아니다. | `backend/src/main/java/com/beez/backend/task/service/TaskPlanService.java`, `backend/src/main/java/com/beez/backend/task/service/ValidationStatusLookup.java`, `backend/src/main/java/com/beez/backend/validation/service/AnalysisResultApplier.java` |

입력 해시 일치만으로 재사용하지 않는다. `ValidationAcceptService.inputHash`는 트리거, 계획 입력 해시, 기준 버전, 완료 변경분, 정렬된 설계 참조와 관련 열린 작업의 계획 입력, 규칙 버전 등을 스냅샷으로 계산한다. 정책 모드에서는 제목과 접근 방식도 포함한다. 횟수 제한은 작업·해시와 별도로 컨텍스트 패킷을 고려한다. 검색 결과와 런타임의 모든 의존성을 해시가 담는다고 확인된 것은 아니므로 해시 일치만으로 안전한 결과 재사용을 보장할 수 없다. 실제 모델에 도달한 횟수에 제한이 있으며 가짜 분석기와 모델 호출 전 실패 등은 제외한다. 세부 집계는 VAL-A01 및 `ValidationRepository.countedModelAttempts`를 따른다.

## 변경과 무효화: 확인한 경계

- 현재 작업의 계획 입력이나 컨텍스트, 관련 열린 작업의 구성·계획이 바뀌면 큐 대기 중 또는 분석 결과 반영 시 확인해 결과를 버리고 STALE 처리할 수 있다. 검색 기반 입력 변경은 `INPUT_CHANGED`, 계획과 관련 계획 변경은 각각 `PLAN_CHANGED`, `RELATED_PLAN_CHANGED` 등으로 처리한다. 반영은 프로젝트 잠금 아래 재확인한다. 근거: `backend/src/main/java/com/beez/backend/validation/service/AnalysisResultApplier.java`, `docs/api/01-backend-rest.md` VAL-A01.
- 설계 발행 시 변경된 설계 항목의 이전 버전을 참조한 검증과 관련 정책 출처를 무효화하고 영향을 받은 진행 작업을 재검증 대기 상태로 바꾸는 경로가 있다. 문서만 바뀌고 변경 설계 항목이 없는 경우의 기존 완료 검증은 이 경로만으로 일괄 무효화되지 않는다. 이는 검색 결과 전체가 동일하다는 보장이 아니다. 근거: `backend/src/main/java/com/beez/backend/validation/service/PublicationImpactAdapter.java`, `docs/design/11-validation-rollback.md`.
- 실행 중 분석에서 기준과 입력을 다시 확인한다. 특히 정책 분석은 최종 기준이 바뀌면 결과를 폐기할 수 있다. 새로운 정책이나 검색 후보는 이전 검증의 인용 출처에 없을 수 있어 출처 참조만으로 모든 검색 결과 변화를 추적한다고 가정할 수 없다. 근거: `backend/src/main/java/com/beez/backend/validation/service/AnalysisResultApplier.java`, `docs/design/policy-gap-analysis-S15P31A108-186.md`.
- 담당자 인계, 작업 취소·재개에도 각각의 검증 무효화 경로가 있으며 DELTA 등 세부 조건이 다르다. 근거: `backend/src/main/java/com/beez/backend/task/service/TaskHandoverService.java`, `TaskStateService.java`, `docs/design/11-validation-rollback.md`. 모든 입력 조합의 무효화 완전성은 이 조사로 검증하지 않았다.

### 확인 가능한 회귀 테스트

아래는 해당 동작을 확인하는 기존 테스트 위치이며, 이 문서 작성 중 테스트를 새로 실행했다는 뜻은 아니다.

| 확인 항목 | 테스트 |
| --- | --- |
| 규칙 전용 PASS의 권한·규칙 재확인과 정책 모드 제외 | `backend/src/test/java/com/beez/backend/validation/ValidationRulesReuseTest.java`; `ValidationApiIntegrationTests.java`의 관련 재사용 통합 테스트 |
| 재시도 시 새 이력·사용량, 중복·동시 요청, 권한과 변경된 입력 | `backend/src/test/java/com/beez/backend/validation/ValidationRetryIntegrationTests.java`: `timeoutThenRetry_runsTheNextAttemptAndPasses_keepingEachAttemptsHistoryAndCost`, `sameRequestSentAgain_returnsTheAttemptItOpened_andNeverOpensAnother`, `twoRetriesAtTheSameTime_openOnlyOneAttempt`, `refusesWrongCallerAndBadInput`, `refusesWhenThePlanOrTheReferencedDesignChanged` |
| 시도 제한, 컨텍스트 패킷, 가짜 분석기 | 같은 파일의 `sameInputCanBeAnalysedOnlyThreeTimes_serverInterruptionsDoNotCount`, `changingTheContextPacketMakesItANewInputForTheLimit`, `fakeAnalyzerRunsDoNotCount_soAfterSwitchingToTheRealAiTheSamePlanCanBeValidatedAgain` |
| MCP 키 재생과 입력 충돌 | `backend/src/test/java/com/beez/backend/validation/ValidationApiIntegrationTests.java`: `mcp_validateTaskPlan_sameKeyReplays_differentInputConflicts`; `ValidationRetryIntegrationTests.java`: `mcp_retryValidation_sameKeyReplays_andFollowsTheSameRules` |
| 동일 계획 승계 및 검색 제목·패킷 변경 | `backend/src/test/java/com/beez/backend/task/PlanFreshnessIntegrationTests.java`: `titleOnlyChange_keepsValidation_andStartResumeAcceptTheOldNumber`, `searchBackedPassIsNotCarriedWhenSearchTitleChanges`, `contextPacketChangeRequiresRevalidationEvenWhenPlanHashMatches` |
| 실행 중 변경과 폐기·동시성 | `backend/src/test/java/com/beez/backend/validation/ValidationApiIntegrationTests.java`: `worker_resultAfterPlanChanged_isDiscarded_andValidationStale`, `titleOnlyPlanChangeDuringSearchBackedAnalysis_discardsOldSearchResult`, `relatedTaskPlanChangedDuringAnalysis_discardsResult_andStartIsRefused`, `newRelatedTaskDuringAnalysis_discardsResult`, `resultApply_holdsProjectLock_soRelatedPlanChangeCannotSlipInBetweenRecheckAndSave` |

## 향후 검색·모델 결과 재사용: 협의 제안, 미확정

다음은 검색·모델 결과 재사용을 위한 설계 출발점이며 백엔드 담당자·검색/입력 담당자와 합의가 필요하다. 현재 제공되는 기능이나 확정된 캐시 키가 아니다. 동등성을 입증할 수 없는 정보가 하나라도 있으면 캐시 미스로 처리하는 방안을 제안한다.

1. **권한과 범위**: 요청 때와 반환 직전에 현재 프로젝트 구성원·작업 담당자 및 관련 자료 접근 권한을 다시 확인한다. 프로젝트 간 공유는 하지 않고 우선 같은 프로젝트·같은 작업에 한정한다. 계정/토큰 범위, 권한 철회와 원본 근거의 노출 가능성도 검증한다.
2. **요청과 계획**: 트리거와 완료 변경분, 정규화된 계획 전체 및 해시, 제목·접근 방식, 컨텍스트 패킷, 참조 항목과 정확한 버전·관계, 규칙 및 기능 설정을 비교한다. 관련 작업의 집합·상태·계획과 그 영향도 확인한다. 단순 계획 해시나 검증 입력 해시 하나만으로는 부족하다.
3. **검색과 근거**: 검색 쿼리와 구성, 인덱스/검색 시점과 준비 상태, 후보 집합, 근거 문서·항목의 내용과 버전, 출처 접근 권한이 동등해야 한다. 후보 추가나 검색 색인 변경을 감지하지 못하면 재사용하지 않는다. 현재 모든 의존성을 완전하게 식별할 수 있는지는 미확인이다.
4. **모델과 프로필**: 모델 식별자·버전, 프롬프트, 출력 스키마, 분석 코드와 도구, 설정·프로필 및 관련 기능 플래그를 비교한다. 동일 모델의 비결정적 출력이나 외부 서비스의 변경까지 동일함을 보장할 수 있는지는 미확인이다.
5. **기준 변경**: 검색 동등성까지 증명되기 전에는 기준 버전이 조금이라도 바뀌면 보수적으로 미스 처리하는 방안을 제안한다. 기준이 같더라도 색인·권한·모델 등의 독립적 변경은 별도로 확인해야 한다. 현재 발행 무효화 동작을 이 제안으로 확대 해석하지 않는다.
6. **결과 자격과 동시성**: 완전히 완료되고 출처와 입력을 검증할 수 있는 결과만 후보로 삼는다. RUNNING·STALE·ERROR·PARTIAL, 가짜 분석 결과나 근거 추적이 불완전한 결과는 제외한다. 조회와 반환 사이 입력이 바뀌지 않도록 버전 검사와 잠금 경계를 정하고, 원본 실행과 재사용 여부를 감사 가능하게 남기며 재사용을 새 모델 호출 비용으로 기록하지 않는다.

검색·모델 결과에 대해 PASS만 허용할지 또는 NEEDS_INPUT·NEEDS_REVIEW도 허용할지, 보존 기간과 만료, 실패/부정 결과 처리, 동시 요청 합치기, 사용자 표시와 `cache_hit` 계약 확장, 근거 보관·삭제 및 운영 지표는 **미결정**이다. 검색 의존성과 권한 경계, 캐시 키·무효화 책임을 먼저 합의하고 변경·권한 철회·동시성·인덱스 갱신·모델 변경을 포함한 회귀 검증이 필요하다. 평가의 정확도와 시간·사용량은 별도 시나리오 결과와 실제 실행 근거를 보고 판단하며, 위의 조건만으로 성능이나 정확도 개선을 주장하지 않는다.
