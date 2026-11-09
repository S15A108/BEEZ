# 작업·검증 인수 기준 검증 (AC-01·04·05·06·09·13)

S15P31A108-207 · 담당 변준성 · 준비 2026-11-09 · 실제 확인 11/10~11/13

## 0. 이 문서가 하는 일

기획서의 인수 기준 중 작업·검증 쪽 6개가 **실제로 그렇게 동작하는지** 증거를 모은다. 새 기능은 만들지 않는다.

증거는 두 갈래다.

1. **자동 테스트** — 이미 있는 통합 테스트가 기준을 확인한다. 아래 표에 테스트 이름을 적었다. `.\gradlew clean test integrationTest` 전체 통과 기록이 증거다.
2. **실제 환경 확인** — 자동 테스트는 빨리 돌리려고 일부를 흉내 낸다(가짜 AI 분석기, SQL 로 넣은 PASS·발행, 서비스 직접 호출). 흉내 낸 부분만 개발 마감(11/9) 뒤 dev 서버에서 진짜 AI·진짜 에이전트(MCP)·화면으로 돌려 확인한다.

결과는 기준마다 "결과" 칸에 적고, 기대와 다르면 버그 이슈를 만들어 담당자를 지정한다. 정리한 결과는 완료 증거 정리(박민용, POL-15)에 낸다.

## 1. 한눈에 보기

| 기준 | 내용 | 자동 테스트 | 실제 환경에서 더 볼 것 |
| --- | --- | --- | --- |
| AC-01 | 저장소·PR·개인 기록이 없는 첫 작업도 승인 설계와 계획만으로 검증·시작된다 | 있음 | 진짜 에이전트 + 진짜 AI 로 처음부터 끝까지 |
| AC-04 | 같은 API 응답 구조를 다르게 바꾸려는 두 작업에서 관련 작업·담당자와 차이가 표시된다 | 규칙 판정은 있음, AI 판정은 선택 실행 | 진짜 AI 가 두 계획의 차이를 찾는지, 화면에 관련 작업·담당자가 보이는지 |
| AC-05 | 두 작업이 동시에 PASS 뒤 시작하면 한 번에 하나씩 처리되고 두 번째는 다시 확인된다 | 있음 (실제 DB 동시성) | 두 에이전트가 거의 동시에 시작 |
| AC-06 | PASS 뒤 설계·문서 버전이 바뀌면 이전 결과가 STALE 이 되고 옛 검증 번호로는 시작이 거부된다 | 있음 | 팀장이 화면에서 승인·발행 → 에이전트가 옛 번호로 시작 |
| AC-09 | AI 시간 초과·문서 조회 실패·시작 응답 유실 때 자동 통과 없이 ERROR·처리 중으로 보이고, 다시 보내도 안전하다 | 있음 (실패는 흉내) | AI 서버를 실제로 멈추거나 늦춤, MCP 로 같은 요청 재전송 |
| AC-13 | 계획 제목만 바꾸면 검증 유지, 계약·완료 기준을 바꾸면 무효 | 있음 (테스트에 AC-13 표기) | 화면에서 제목·완료 기준 수정 |

## 2. 기준별 상세

### AC-01 첫 작업도 승인 설계와 계획만으로 검증·시작

테스트용 프로젝트에는 저장소·PR·개인 기록(context_record)이 하나도 없다. 그래서 아래 테스트는 모두 "첫 작업" 상황이다.

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `mcp.tool.McpToolsIntegrationTests#taskFlow_planValidateStart_overMcp` | 실제 MCP 연결로 계획 저장 → 검증 요청 → 결과 조회 → 시작 보고까지 이어진다 |
| `validation.ValidationApiIntegrationTests#rules_readsOnly_isPass_andStartGateAcceptsIt` | 읽기만 하는 계획은 규칙만으로 PASS, 시작 관문이 그 PASS 를 받아들인다 |
| `task.TaskStateApiIntegrationTests#start_withPass_becomesInProgress_andRepeatIsIdempotent` | PASS 로 시작하면 진행 중이 된다 |

흉내 낸 부분: 첫 테스트는 검증 요청 뒤 PASS 를 SQL 로 넣는다(워커·AI 를 돌리지 않음).

실제 환경 확인
- 사전 조건: 새 프로젝트, 설계 v1 발행, 개인 기록·저장소 연결 없음. 팀원 에이전트 토큰 발급
- 입력: 에이전트(Claude Code)가 MCP 로 `upsert_task_plan` → `validate_task_plan` → `get_validation`(PASS 까지) → `report_task_state(START)`
- 기대 결과: 진짜 AI 분석을 거쳐 PASS, 작업이 진행 중
- 실패 조건: 개인 기록·저장소가 없어서 검증이 ERROR·NEEDS_INPUT 이 되거나 시작이 막힘
- 증거: MCP 호출 기록(mcp_audit_log), 검증 번호와 결과, 작업 상세 화면

결과: (11/10~ 기록)

### AC-04 같은 API 응답 구조를 다르게 바꾸려는 두 작업

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `validation.ValidationApiIntegrationTests#rules_changesOccupiedByRunningTask_isNeedsReview` | 작업 A 가 API-01 을 바꾸며 진행 중일 때 작업 B 도 같은 항목을 바꾸면 NEEDS_REVIEW, 발견 항목에 관련 작업(A)·사람 확인 필요 |
| `validation.ValidationApiIntegrationTests#relatedTaskStartedDuringAnalysis_isNeedsReview_byRulesReevaluatedAtApply` | 분석 중에 관련 작업이 시작돼도 결과 반영 때 다시 판정 |
| `task.TaskStateApiIntegrationTests#concurrentStart_onSameChangesItem_onlyOneWins` | 같은 항목을 바꾸는 두 작업은 동시에 진행될 수 없다 |
| `task.LiveValidationPipelineIntegrationTests#conflictingSourceMustReachAiAsVerifiedEvidence` | **선택 실행(실제 AI 필요, 평소 건너뜀)** — 충돌 근거가 실제 AI 에게 전달된다 |

흉내 낸 부분: 둘 다 아직 시작 전인 두 계획의 "내용 차이"를 찾는 것은 AI 판정이라, 평소 테스트는 가짜 분석기로 돈다.

실제 환경 확인
- 사전 조건: API-01(회원 조회) 승인. 작업 A·B 담당자 다름
- 입력: A 는 "응답에 nickname 추가", B 는 "응답에서 email 제거"로 같은 API-01 을 바꾸는 계획. B 검증 요청
- 기대 결과: B 결과가 NEEDS_REVIEW, 발견 항목에 관련 작업 A, 화면에 A 의 담당자와 두 변경 내용 차이
- 실패 조건: PASS 로 통과, 또는 관련 작업·담당자·차이 중 하나가 화면에 없음
- 증거: 검증 결과 응답(findings 의 related_task_id·conflicting_design_refs), 검증 결과 화면
- 협조: 양지훈(AI 판정), 박민용(검증 결과 화면)

결과: (11/10~ 기록)

### AC-05 동시 시작 직렬화와 두 번째 요청 재확인

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `task.TaskStateApiIntegrationTests#concurrentStart_onSameChangesItem_onlyOneWins` | 두 작업이 같은 항목을 바꾸며 PASS 뒤 동시에 시작 → 하나만 성공, 다른 하나는 409 `task_conflict` |
| `task.TaskStateApiIntegrationTests#readsOnSameItem_doNotConflict` | 읽기만 겹치면 둘 다 시작 |
| `task.TaskStateApiIntegrationTests#start_afterDesignChanged_is409VersionConflict_butUnrelatedTaskStarts` | 시작 때 최신성을 다시 본다 |

흉내 낸 부분: PASS 를 SQL 로 넣는다. 동시성 자체는 실제 DB 에서 확인한다.

실제 환경 확인
- 입력: 두 에이전트가 같은 항목을 바꾸는 작업으로 각각 PASS 를 받은 뒤 거의 동시에 START
- 기대 결과: 하나는 진행 중, 다른 하나는 409 `task_conflict`(충돌 작업 표시)
- 실패 조건: 둘 다 진행 중
- 증거: 두 MCP 응답, 작업 목록 상태

결과: (11/10~ 기록)

### AC-06 PASS 뒤 설계가 바뀌면 STALE, 옛 번호로 시작 거부

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `task.TaskStateApiIntegrationTests#start_afterDesignChanged_is409VersionConflict_butUnrelatedTaskStarts` | 참조한 항목이 새로 발행되면 옛 PASS 로 시작 시 409 `version_conflict`, 상관없는 작업은 시작됨 |
| `validation.PublicationImpactIntegrationTests#publication_stalesValidationsOnOldVersion_andHoldsOnlyInProgressTasks` | 발행 때 옛 버전을 본 검증이 STALE, 진행 중 작업은 재검증 대기 |
| `validation.ValidationApiIntegrationTests#worker_lateResult_isDiscarded_whenValidationWentStale` | STALE 이 된 뒤 늦게 온 AI 결과는 버려진다 |
| `task.PlanFreshnessIntegrationTests#startGate_treatsMissingBaselineItemAsStale_notAsUnchanged` | 항목이 기준에서 빠져도 바뀐 것으로 본다 |
| `mcp.TaskMcpServiceIntegrationTests#report_returnsTask_andChangesPublishedAfterThePlan` | 상태 보고 응답에 계획 뒤 발행된 변경이 실린다 |

흉내 낸 부분: 일부 테스트는 발행을 SQL 로 한다.

실제 환경 확인
- 입력: 작업이 PASS 를 받은 뒤, 팀장이 화면에서 그 작업이 참조한 항목의 변경 제안을 승인·발행. 에이전트가 옛 검증 번호로 START
- 기대 결과: 검증 STALE, START 409 `version_conflict`, `get_design_changes` 에 그 변경이 보임
- 실패 조건: 옛 번호로 시작됨, 또는 검증이 PASS 그대로
- 증거: 발행 전후 검증 상태, START 응답, 설계 변경 조회 응답

결과: (11/10~ 기록)

### AC-09 AI 시간 초과·문서 조회 실패·시작 응답 유실

| 상황 | 자동 테스트 | 확인하는 것 |
| --- | --- | --- |
| AI 시간 초과 | `validation.ValidationApiIntegrationTests#worker_timeout_isErrorRetry_withTimeoutRun` | ERROR(`ai_timeout`, 재시도 가능), 통과 아님 |
| AI 응답 형식 이상 | `validation.ValidationApiIntegrationTests#worker_malformedResponses_areInvalidOutput_notPass` | 이상한 응답은 PASS 가 아니라 오류 |
| 문서 조회 실패 | `validation.ValidationRetryIntegrationTests#failuresBeforeTheModelIsCalledDoNotCount` | 근거 문서 검색 장애 → ERROR(`evidence_fetch_failed`), 재시도 횟수에 안 셈 |
| 워커가 죽음 | `validation.ValidationApiIntegrationTests#worker_expiredLease_isRecoveredAsErrorRetry_andLateResultIsIgnored` | 처리 중으로 남았다가 오류로 닫히고 늦은 결과는 무시 |
| 저장·실패 기록 둘 다 실패 | `validation.ValidationResilienceIntegrationTests#whenSavingTheResultAndRecordingTheFailureBothFail_leaseRecoveryClosesIt_andRetryFinishesIt` | 회수가 오류로 닫고 재시도로 PASS |
| 재시도 | `validation.ValidationRetryIntegrationTests` 전체 | 오류 → 재시도 → 통과, 같은 입력 3번 제한 |
| 시작 응답 유실 (REST) | `task.TaskStateApiIntegrationTests#start_withPass_becomesInProgress_andRepeatIsIdempotent` | 같은 검증으로 START 를 다시 보내면 같은 결과 |
| 시작 응답 유실 (MCP) | `mcp.TaskMcpServiceIntegrationTests#report_sameKeyRetry_returnsFirstResult_insteadOfConflict` | 같은 키로 다시 보내면 처음 결과 |

흉내 낸 부분: AI 실패는 가짜 분석기가 예외를 던지는 방식이다. 장애 경우별 대응표는 [12 검증 장애·동시성](../design/12-validation-resilience.md) 3절.

실제 환경 확인
- AI 실패: dev 의 AI 서버를 멈추거나(연결 실패) 읽기 시간 초과를 짧게 해 검증 요청 → 화면에 오류와 재시도 버튼, 작업은 시작 못 함 / AI 서버를 되살리고 재시도 → PASS (협조: 양지훈)
- 응답 유실: 에이전트가 START 응답을 받기 전에 끊고 같은 idempotency_key 로 다시 보냄 → 같은 결과, 작업 하나 (협조: 전진)
- 실패 조건: 오류인데 PASS·진행 중이 됨, 다시 보냈더니 두 번 처리됨
- 증거: 검증 상태·오류 코드, 화면, MCP 요청 기록

결과: (11/10~ 기록)

### AC-13 제목만 바꾸면 검증 유지, 계약·완료 기준을 바꾸면 무효

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `task.PlanInputHasherTest#titleOnlyChange_sameHash` | 제목만 다르면 검증 입력이 같다 |
| `task.PlanInputHasherTest#proposedContentOrCriteriaChange_differentHash_approachOnly_sameHash` | 계약 변경 내용·완료 기준이 다르면 다른 입력 |
| `task.PlanFreshnessIntegrationTests#titleOnlyChange_keepsValidation_andStartResumeAcceptTheOldNumber` | 제목만 바꾸면 검증 유지, 옛 번호로 시작·재개 가능 |
| `task.PlanFreshnessIntegrationTests#criteriaChange_inProgress_invalidates_andOldValidationCannotResume` | 완료 기준을 바꾸면 무효, 옛 번호로 재개 불가 |
| `task.PlanFreshnessIntegrationTests#contractChange_ready_invalidatesEvenThoughOnlyProposedContentDiffers` | 계약 변경 내용만 달라도 무효 |

흉내 낸 부분: 거의 없음. 화면 동작만 확인한다.

실제 환경 확인
- 입력: PASS 받은 작업의 제목만 화면에서 수정 → 시작 / 다른 작업의 완료 기준 수정 → 시작
- 기대 결과: 제목 수정 뒤에도 검증 유지·시작 됨 / 완료 기준 수정 뒤 검증 STALE·시작 거부
- 증거: 수정 전후 검증 상태, 시작 응답

결과: (11/10~ 기록)

## 3. 실행 순서 (11/10~11/13)

1. dev 최신으로 전체 테스트 한 번 — 위 표의 자동 테스트 통과 기록을 남긴다
2. 선택 실행 테스트(`LiveValidationPipelineIntegrationTests`)를 실제 AI 주소로 돌린다 (`BEEZ_VALIDATION_LIVE_AI_URL`, 양지훈과 함께)
3. dev 서버에서 기준별 "실제 환경 확인"을 차례로 하고 결과 칸을 채운다. 화면 증거는 검증 결과·롤백 화면(박민용)이 들어온 뒤
4. 기대와 다른 것은 버그 이슈로 등록하고 담당자를 지정한다
5. 정리본을 완료 증거 정리(POL-15)에 낸다. 발표 자료(S15P31A108-113)의 근거로도 쓴다
