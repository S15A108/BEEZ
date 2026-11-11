# 12. 검증 장애·동시성 — 비동기 AI 작업 계약·실행 중 변경 전파·장애 시나리오

> 담당: 변준성 · S15P31A108-65 · 상태: 담당자 결정(1·2절은 양지훈·박상은 확인 필요) · 관련: [11 검증 롤백](11-validation-rollback.md), [00-common 6절](../api/00-common.md#validation-execution-errors), [01 VAL-A01~A04](../api/01-backend-rest.md#val-a01)

검증 실행이 실패하거나 겹칠 때 서버가 어떻게 동작하는지를 한곳에 모은다. 새 규칙을 만들지 않는다. 이미 구현된 규칙을 계약으로 적고, 각 경우를 어느 시험이 확인하는지 표로 남긴다.

## 1. 비동기 AI 작업 계약 (NFR-11)

### 1-1. 흐름

| 단계 | 하는 일 | 응답·상태 |
| --- | --- | --- |
| 접수 (VAL-A01·MCP-11) | 입력을 고정하고 규칙을 검사한다. 의미 분석이 필요하면 시도 1의 실행(run)과 큐 작업(job)을 만든다 | 규칙만으로 끝나면 200 + 확정 상태. 의미 분석이면 202 + `RUNNING` |
| 꺼냄 (워커) | 큐 작업 하나를 리스(기본 120초)로 잡는다. `FOR UPDATE SKIP LOCKED`라 여러 워커가 같은 작업을 잡지 않는다 | 작업 `LEASED` |
| AI 호출 | DB 트랜잭션 밖에서 부른다. 처리 예산(`execution_budget_ms`, 기본 15초)을 요청에 싣는다 | 연결 시간 초과 3초, 읽기 시간 초과 20초 (리스보다 짧다) |
| 반영 | 프로젝트 행을 잠그고, 아직 `RUNNING`·같은 시도·같은 리스·리스 유효기간 안·입력 불변인지 확인한 뒤 한 트랜잭션으로 저장한다 | `PASS`·`NEEDS_INPUT`·`NEEDS_REVIEW`·`ERROR`, 입력이 바뀌었으면 `STALE` |
| 조회 (VAL-A02·A03·MCP-13·TSK-A03) | 새 실행을 만들지 않는다 | `RUNNING`이면 같은 ID로 다시 조회 (화면은 1.5초 간격) |
| 재시도 (VAL-A04·MCP-14) | `ERROR`이고 현재 시도의 오류가 모두 `RETRY`일 때만 같은 검증의 다음 시도를 연다 | 202 + `RUNNING`, `current_attempt`+1 |

### 1-2. 상태

| 대상 | 상태 | 뜻 |
| --- | --- | --- |
| 검증(validation) | `RUNNING` → `PASS`·`NEEDS_INPUT`·`NEEDS_REVIEW`·`ERROR`·`STALE` | 작업 시작은 `PASS`만 받는다. `ERROR`·`STALE`은 통과로 보지 않는다 |
| 실행(validation_run) | `RUNNING` → `OK`·`TIMEOUT`·`ERROR`·`DISCARDED` | 시도마다 하나. `DISCARDED`는 늦은 결과·입력 변경·취소로 버린 실행 |
| 큐 작업(validation_job) | `QUEUED` → `LEASED` → `DONE`·`FAILED`·`CANCELLED` | 검증마다 하나. 재시도는 같은 행을 다시 `QUEUED`로 돌린다 |

### 1-3. 실패 응답

실패는 HTTP 오류가 아니라 검증 결과로 돌려준다. 검증이 `ERROR`가 되고 `error_code`와 오류 항목(`kind=ERROR`)이 붙는다. 코드표와 처리 방법(`resolution`)은 [00-common 6절](../api/00-common.md#validation-execution-errors)이 기준이다.

- `RETRY`: 같은 입력으로 다시 돌리면 나아질 수 있는 실패다. `ai_timeout`·`ai_unavailable`·`ai_invalid_output`·`evidence_fetch_failed`·`evidence_collection_failed`·`validation_lease_expired`·`validation_worker_interrupted`·`validation_execution_failed`가 여기에 든다. 재시도 버튼이 보인다.
- `HUMAN_REVIEW`: 근거 부족·지원하지 않는 내용(부분 분석)이다. 사람이 보완한 뒤 새 검증을 요청해야 하고, 재시도는 거절한다.
- 서버도 AI도 스스로 재시도하지 않는다. 같은 입력으로 실제 검증 AI를 부르는 횟수는 새 검증과 재시도를 합쳐 3번까지다(VAL-A01 처리 규칙).

S15P31A108-400: 모델을 부르기 전 검색 범위·원문 확보가 불완전한 실패는 `evidence_collection_failed`와 `execution_failure` 원인 목록을 반환한다. 화면은 원인 안내와 재시도를 제공하며 계획 수정이나 사람 검토를 요구하지 않는다. 과거 `ai_evidence_insufficient` 결과 중 정확한 수집 실패 문구로 식별되는 것만 같은 재시도 경로로 호환한다. 실제 모델의 부분 분석 `HUMAN_REVIEW` 결과는 그대로 유지한다. [계획 수정안·수집 실패 분리](task-plan-revision-proposals-S15P31A108-400.md)를 참고한다.
- AI 응답(성공·오류 본문)으로 사용량을 받으면 그 실행에 남긴다(비용 조회에 잡힌다). 판정을 버린 늦은 결과도 같다. 응답을 받지 못하면 모델이 이미 돌았어도 사용량을 받을 수 없어 비용 조회에서 빠진다. 횟수 제한에 세는지는 오류 종류로 갈린다(`AiValidationAnalyzer` 분류 → `ValidationRepository.countedModelAttempts`).
  - 읽기 시간 초과(`SocketTimeoutException`·`HttpTimeoutException`) → `ai_timeout`, 실행 `TIMEOUT` → 사용량이 없어도 **센다**(요청이 AI에 닿았다고 본다).
  - 그 밖의 통신 오류(연결 거부·연결 시간 초과, 응답을 기다리다 끊김 같은 `SocketException` 등) → `ai_unavailable`, 실행 `ERROR` → 사용량이 없으면 **세지 않는다**. 요청이 AI에 닿은 뒤 끊긴 경우도 연결 실패와 구분할 수 없어 같이 빠진다(4절).
  - AI가 오류 본문에 사용량을 담아 보낸 5xx·504는 사용량이 있으므로 센다.

> 확인 요청 (양지훈·박상은): 위 흐름·상태·실패 응답이 AI 쪽 계약(03-ai-internal 재시도 규칙, AI-A02 오류 규약)과 같은지 이 문서 리뷰로 확정한다.

## 2. 실행 중 변경 전파 (NTF-07) — 결정

**MVP는 당겨 가는 방식(조회)으로 전파하고, 실시간 밀어 주기와 로컬 실행 제어는 하지 않는다.**

| 범위 | 무엇이 | 어떻게 |
| --- | --- | --- |
| 서버가 막는다 (구현됨) | 실행 중에 설계가 발행되거나 계획·관련 작업이 바뀜 | 끝난 검증은 `STALE`이 되고, 도는 검증은 반영 단계에서 결과를 버리고 `STALE`로 닫는다. 진행 중 작업은 `WAITING(REVALIDATION)`. 시작·재개 관문(G1·G2)이 낡은 검증을 거절한다 |
| 에이전트가 조회로 안다 (구현됨) | 무엇이 바뀌었는지 | 상태 보고(MCP-12)의 응답에 설계 변경(`changes`)이 실려 온다. 검증 결과는 `get_validation`, 설계 변경은 `get_design_changes`로 조회한다. 보고할 때마다 최신 상태를 받는다 |
| 하지 않는다 | 에이전트가 코드를 쓰는 중에 서버가 즉시 알리거나 멈추게 하기 | 서버는 사용자 컴퓨터의 에이전트 실행을 멈출 수 없다. 밀어 주기(SSE·WebSocket 등)는 전송 수단 결정(Q18)과 함께 발표 이후로 둔다 |

에이전트 안내(토큰 발급 때 주는 작업 절차)는 "검증이 PASS일 때만 다음으로 가고, 설계 변경이 발행되면 `get_design_changes`로 확인해 계획을 고치고 다시 검증한 뒤 RESUME으로 이어 간다"는 순서다. 실행 중 변경이 생기면, 에이전트는 다음 보고나 조회 때 알게 된다. 그 전에 쓴 코드는 서버가 되돌리지 않는다(11 검증 롤백 — 업무 롤백은 상태 복귀이며 소스 파일을 되돌리지 않는다).

## 3. 장애 시나리오와 시험

| 분류 | 경우 | 기대 동작 | 시험 |
| --- | --- | --- | --- |
| AI 실패 | 시간 초과 — AI가 오류 본문에 사용량을 담아 응답(504) | `ERROR`(ai_timeout, RETRY), 실행 `TIMEOUT`, 받은 사용량 보존 | `ValidationApiIntegrationTests.worker_timeout_isErrorRetry_withTimeoutRun` |
| | 시간 초과 — 응답을 받지 못함(읽기 시간 초과) | `ERROR`(ai_timeout, RETRY), 실행 `TIMEOUT`, 사용량은 비어 있음(받지 못함), 횟수 제한에는 셈 | `ValidationRetryIntegrationTests.readTimeoutWithoutUsageStillCounts_becauseTheRequestReachedTheAi` |
| | 연결 실패·응답 대기 중 끊김(시간 초과가 아닌 통신 오류) | `ERROR`(ai_unavailable, RETRY), 사용량 없음, 횟수 제한에 세지 않음 | `ValidationRetryIntegrationTests.failuresBeforeTheModelIsCalledDoNotCount` |
| | 출력 계약 위반·본문 깨짐 | `ERROR`(ai_invalid_output, RETRY). 빈 결과를 PASS로 보지 않음 | `…worker_contractViolation_isErrorRetry`, `…worker_malformedResponses_areInvalidOutput_notPass` |
| | 부분 분석(근거 부족) | `ERROR`(HUMAN_REVIEW), 재시도 거절 | `…worker_partial_isErrorForHumanReview`, `ValidationRetryIntegrationTests.refusesWhenRetryingCannotHelp` |
| | 연결 실패(요청이 닿지 않음) | `ERROR`(ai_unavailable, RETRY), 횟수에 안 셈 | `ValidationRetryIntegrationTests.failuresBeforeTheModelIsCalledDoNotCount` |
| 문서 조회 실패 | 근거 검색 장애 | `ERROR`(evidence_fetch_failed, RETRY), 자동 통과 없음 | `ValidationRetryIntegrationTests.failuresBeforeTheModelIsCalledDoNotCount` |
| 중복 요청 | 같은 키의 MCP 검증 요청 | 처음 결과를 그대로 | `ValidationApiIntegrationTests.mcp_validateTaskPlan_sameKeyReplays_differentInputConflicts` |
| | 같은 재시도의 재전송·동시 재시도 | 시도 하나만 생김 | `ValidationRetryIntegrationTests.sameRequestSentAgain_…`, `…twoRetriesAtTheSameTime_openOnlyOneAttempt` |
| | 두 워커가 동시에 꺼냄 | 한쪽만 잡고 모델은 한 번만 부름 | **`ValidationResilienceIntegrationTests.twoWorkersPollingAtTheSameTime_…`** (신규) |
| 오래된 입력 | 분석 중 계획·관련 작업 변경 | 결과 버림, `STALE` | `…worker_resultAfterPlanChanged_isDiscarded_andValidationStale`, `…relatedTaskPlanChangedDuringAnalysis_…`, `…resultApply_holdsProjectLock_…` |
| | 분석 중 설계 발행 | `STALE`, DELTA는 새 기준으로 다시 접수 | `PublicationImpactIntegrationTests.publicationDuringDeltaValidation_…` |
| | 늦은 결과(이전 시도·회수 뒤) | 상태를 바꾸지 않고 사용량만 남김 | `…worker_expiredLease_isRecoveredAsErrorRetry_andLateResultIsIgnored`, `ValidationRetryIntegrationTests.lateResultOfTheEarlierAttempt_isIgnored_afterRetry` |
| 응답 유실 | 시작(START) 응답 유실 후 재전송 | 같은 결과, 두 번 시작하지 않음 | `TaskStateApiIntegrationTests.start_withPass_becomesInProgress_andRepeatIsIdempotent` |
| | MCP 상태 보고 응답 유실 | 같은 키면 처음 결과 | `TaskMcpServiceIntegrationTests.report_sameKeyRetry_returnsFirstResult_insteadOfConflict` |
| | 완료 보고 응답 유실 | 같은 보고 재전송만 받음 | `TaskStateApiIntegrationTests.completedResend_isIdempotentOnlyForTheSamePendingReport` |
| | 재시도 응답 유실 | 바로 앞 시도 번호로 재전송하면 같은 결과 | `ValidationRetryIntegrationTests.sameRequestSentAgain_…` |
| 롤백 실패 | 결과 저장과 실패 기록이 모두 실패(DB 장애) | `RUNNING`·`LEASED`로 남았다가 리스 만료 회수가 `ERROR`(RETRY)로 닫음, 작업은 `READY` 그대로, 재시도로 완료 | **`ValidationResilienceIntegrationTests.whenSavingTheResultAndRecordingTheFailureBothFail_…`** (신규) |
| | 결과 저장만 실패 | 실패로 닫고 사용량 보존 | `…worker_applyCrash_isClosedAsExecutionFailure_withUsageKept` |
| | 같은 만료 리스를 두 회수가 동시에 | 한 번만 닫힘 | **`ValidationResilienceIntegrationTests.twoRecoveriesOfTheSameExpiredLease_closeItOnce`** (신규) |
| 동시 실행 | 같은 항목을 바꾸는 두 작업의 동시 시작 (AC-05) | 하나만 시작 | `TaskStateApiIntegrationTests.concurrentStart_onSameChangesItem_onlyOneWins` |
| 취소 | 대기 중 취소 | 실행 `DISCARDED`, 작업 `CANCELLED` | `…queuedValidation_taskCancelled_closesRunAsDiscarded` |
| | 모델이 도는 중 취소 | 늦은 PASS 버림, 취소 유지, 비용은 남김 | **`ValidationResilienceIntegrationTests.cancellingTheTaskWhileTheModelRuns_…`** (신규) |
| 재시도 | 오류 → 재시도 → 통과, 횟수 제한 | 시도별 이력·비용, 3번 제한 | `ValidationRetryIntegrationTests` 전체 |
| 실패 뒤 작업 상태 | 오류·재시도 중 시작 | `validation_not_passed`, 작업 `READY`·잠금 번호 그대로 | `ValidationRetryIntegrationTests.failedValidationCannotStartTheTask_andTheTaskStaysReady` |
| | DELTA 오류 뒤 | `WAITING` 유지, 재시도 또는 새 검증으로 재개 | `ValidationRetryIntegrationTests.deltaRetry_…`, `PublicationImpactIntegrationTests.resumeAfterErrorDelta_…` |

재현 방법은 시험 코드가 그대로 담고 있다. 가짜 분석기로 AI 응답을 정하고, 워커는 `runOnce`·`recoverExpired`를 직접 부른다. 리스 만료는 `validation_job.lease_until`을 과거로 옮겨 흉내 낸다.

## 4. 범위 밖·알려진 한계

- **결과 재사용(AC-16)은 규칙 전용 PASS로 제한한다.** 같은 작업·현재 계획 리비전·기준과 입력 해시가 같고 현재 규칙을 다시 확인한 경우에만 가장 최근의 기존 결과를 돌려준다. 검색·모델 결과는 의존성을 증명할 수 없어 재사용하지 않고 새 모델 호출과 횟수 제한을 따른다. 재사용 여부는 접수 응답에만 표시하며 별도 이력을 남기지 않아 적중 횟수·비율은 사후 집계할 수 없다.
- **AI 응답을 받지 못한 호출의 사용량은 남지 않는다.** 요청을 보낸 뒤 응답을 받지 못하면 모델이 이미 돌아 비용이 났을 수 있지만, 백엔드는 사용량을 받지 못해 비용 조회에서 빠진다. 실제 비용은 AI 쪽 사용량 기록과 비교해야 알 수 있다.
- **응답을 기다리다 끊긴 호출은 횟수 제한에 세지 않는다.** 읽기 시간 초과(`ai_timeout`)는 세지만, 시간 초과가 아닌 통신 오류(`ai_unavailable`)는 사용량이 없으면 세지 않는다. 요청이 AI에 닿은 뒤 끊긴 경우를 연결 자체가 안 된 경우와 구분할 수 없어서다. 그래서 이런 끊김이 반복되면 실제 모델 호출이 3번을 넘을 수 있다.
- **결과 저장과 실패 기록이 모두 실패하면 그 호출의 사용량은 남지 않는다.** 두 트랜잭션이 함께 롤백되기 때문이다. 리스 만료로 닫힌 실행은 횟수 제한에도 세지 않는다. AI 쪽 사용량 기록과 비교해야 알 수 있다.
- **REST 검증 접수는 `Idempotency-Key`를 처리하지 않는다**(Q12). 접수 응답을 잃으면 작업의 검증 이력(VAL-A03)에서 확인한다. 다시 요청하면 새 검증이 생기고, 의미 분석이면 횟수에 들어간다. MCP 접수는 `idempotency_key`로 처리한다.
- 실시간 밀어 주기·로컬 실행 제어는 2절대로 하지 않는다.

## 변경 이력

- v1.0 (2026-11-05) 최초 작성 (변준성, -65). 구현된 비동기 작업 계약을 정리하고, 실행 중 변경 전파(NTF-07)를 조회 방식으로 결정했다. 장애 시나리오별 시험을 대응시켰고, 빠져 있던 네 경우(두 워커 동시 꺼냄, 회수 동시 실행, 모델 도는 중 취소, 이중 실패 뒤 회수)에 시험을 추가했다.
- v1.1 (2026-11-05) 리뷰 반영(양지훈): 사용량은 AI 응답으로 받은 것만 남는다고 바로잡음 — 1-3절, 3절 대응표(시간 초과를 둘로 나눔), 4절 한계에 반영.
- v1.2 (2026-11-05) 리뷰 반영(양지훈): 횟수 제한 집계를 오류 종류별로 나눔. 읽기 시간 초과는 세고, 시간 초과가 아닌 통신 오류는 사용량이 없으면 세지 않는다 — 1-3절, 3절 대응표, 4절 한계.
