# 작업·검증 인수 기준 검증 (AC-01·04·05·06·09·13)

S15P31A108-207 · 담당 변준성 · 준비 2026-11-09 · 실제 확인 11/10~11/13

## 0. 이 문서가 하는 일

기획서의 인수 기준 중 작업·검증 쪽 6개가 **실제로 그렇게 동작하는지** 증거를 모은다. 새 기능은 만들지 않는다.

증거는 두 갈래다.

1. **자동 테스트** — 이미 있는 통합 테스트가 기준을 확인한다. 아래 표에 테스트 이름을 적었다. `.\gradlew clean test integrationTest` 전체 통과 기록이 증거다.
2. **실제 환경 확인** — 자동 테스트는 빨리 돌리려고 일부를 흉내 낸다(가짜 AI 분석기, SQL 로 넣은 PASS·발행, mock 한 문서 검색, 서비스 직접 호출). 흉내 낸 부분만 개발 마감(11/9) 뒤 dev 서버에서 진짜 AI·진짜 에이전트(MCP)·화면으로 돌려 확인한다.

결과는 기준마다 "결과" 칸에 적고, 기대와 다르면 버그 이슈를 만들어 담당자를 지정한다. 정리한 결과는 완료 증거 정리(박민용, POL-15)에 낸다.

**기대 결과는 지금 코드 기준이다.** 기준 문장과 지금 코드·화면 범위가 다른 곳은 "범위 확인 필요"로 따로 적었다. 그 항목은 실제 확인에서 실패로 기록하지 않고 팀 결정이나 후속 작업으로 넘긴다(5절).

## 1. 한눈에 보기

| 기준 | 내용 | 자동 테스트 | 실제 환경에서 더 볼 것 |
| --- | --- | --- | --- |
| AC-01 | 저장소·PR·개인 기록이 없는 첫 작업도 승인 설계와 계획만으로 검증·시작된다 | 있음 | 진짜 에이전트 + 진짜 AI 로 처음부터 끝까지 |
| AC-04 | 같은 API 응답 구조를 다르게 바꾸려는 두 작업에서 관련 작업·담당자와 차이가 표시된다 | 규칙 판정(A 진행 중)과 가짜 AI 판정 있음 | A 가 시작 전일 때 진짜 AI 가 관련 작업을 찾는지. 담당자·차이 표시는 범위 확인 필요 |
| AC-05 | 두 작업이 동시에 PASS 뒤 시작하면 한 번에 하나씩 처리되고 두 번째는 다시 확인된다 | 있음 (실제 DB 동시성) | 두 에이전트가 거의 동시에 시작 |
| AC-06 | PASS 뒤 설계·문서 버전이 바뀌면 이전 결과가 STALE 이 되고 옛 검증 번호로는 시작이 거부된다 | 있음 | 팀장이 화면에서 승인·발행 → 에이전트가 옛 번호로 시작 |
| AC-09 | AI 시간 초과·문서 조회 실패·시작 응답 유실 때 자동 통과 없이 ERROR·처리 중으로 보이고, 다시 보내도 안전하다 | 있음 (AI 실패·문서 검색 실패는 흉내) | AI 서버 중단·지연, 문서 검색 실패, MCP 로 같은 키 재전송 |
| AC-13 | 계획 제목만 바꾸면 검증 유지, 계약·완료 기준을 바꾸면 무효 | 있음 (테스트에 AC-13 표기) | 규칙 PASS 와 실제 검색 PASS, 시작 전·진행 중 작업으로 나눠서 |

## 1-1. 자동 테스트 실행 기록

| 항목 | 값 |
| --- | --- |
| 실행 | 2026-11-09 10:07~10:15 (KST), 로컬 Windows + Docker(Testcontainers PostgreSQL), `.\gradlew clean test integrationTest` |
| 코드 | 브랜치 `test/backend-task-acceptance-S15P31A108-207` `1b3d6779` = dev `137975bd`(11/9 개발 마감 직전) + 이 문서 |
| 빠른 테스트 | 428개 중 실패 0, 건너뜀 2 |
| 통합 테스트 | 952개 중 실패 0, 건너뜀 24 (실제 AI·Kafka·외부 서버가 있어야 하는 선택 실행 테스트) |
| 이 문서가 증거로 든 테스트 | 25개 모두 위 실행에서 통과 |

기준별 자동 테스트 결과: AC-01 통과 · AC-04 규칙 판정·가짜 AI 판정 통과(진짜 AI 판정은 실제 환경 확인으로) · AC-05 통과 · AC-06 통과 · AC-09 통과 · AC-13 통과.

최종 확인은 개발이 완전히 마감된 dev 로 한 번 더 돌려 이 표를 바꾼다(3절 1번).

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

작업 간 충돌의 증거는 검증 결과의 `findings[].related_task_id`·`findings[].message`와 `related_task_refs`다. `conflicting_design_refs`는 승인 항목 둘 사이의 정책 모순용이라 이 기준의 증거가 아니다.

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `validation.ValidationApiIntegrationTests#rules_changesOccupiedByRunningTask_isNeedsReview` | 작업 A 가 API-01 을 바꾸며 **진행 중**일 때 작업 B 도 같은 항목을 바꾸면 규칙만으로 NEEDS_REVIEW, 발견 항목의 `related_task_id`=A, `related_task_refs`에 A |
| `validation.ValidationApiIntegrationTests#relatedTaskStartedDuringAnalysis_isNeedsReview_byRulesReevaluatedAtApply` | 분석 중에 관련 작업이 시작돼도 결과 반영 때 다시 판정 |
| `validation.ValidationBridgeIntegrationTest#blockingFindingForDependencyOnlyCandidateRequiresReview` | 관련 작업 후보와 충돌하는 BLOCKING 발견 항목(`related_task_id`, `message`)을 AI 가 내면 사람 검토가 필요하다 (가짜 분석기) |
| `task.TaskStateApiIntegrationTests#concurrentStart_onSameChangesItem_onlyOneWins` | 같은 항목을 바꾸는 두 작업은 동시에 진행될 수 없다 |

흉내 낸 부분: A 가 아직 **시작 전(READY)**일 때 두 계획의 내용 차이를 찾는 것은 AI 판정이다. 자동 테스트는 가짜 분석기가 정해 둔 결과를 쓴다.

실제 환경 확인
- 사전 조건: API-01(회원 조회) 승인. 작업 A·B 담당자 다름. **A 는 READY(시작 전)** — A 가 진행 중이면 규칙 판정으로 끝나 AI 판정을 볼 수 없다
- 입력: A 는 "응답에 nickname 추가", B 는 "응답에서 email 제거"로 같은 API-01 을 바꾸는 계획. B 검증 요청(진짜 AI)
- 기대 결과: B 결과가 NEEDS_REVIEW 또는 NEEDS_INPUT, 발견 항목 `related_task_id`=A, `message`에 두 계획의 차이 설명, `related_task_refs`에 A
- 실패 조건: PASS 로 통과, 또는 관련 작업 A 가 결과에 없음
- 증거: 검증 결과 응답(`findings`, `related_task_refs`)
- 협조: 양지훈(AI 판정)

범위 확인 필요 (5절): 기준 문장의 "담당자"와 "차이 표시"
- API: `related_task_refs`는 작업 번호와 계획 리비전만 준다. 담당자는 그 작업 상세(TSK-A03)를 따로 읽어야 한다
- 화면: 검증 결과 카드는 지금 `related_task_refs`를 그리지 않는다(박민용 확인)
- 그래서 지금 범위에서는 "응답에 관련 작업 A 와 차이 설명(`message`)이 있다"까지를 확인한다

결과: (11/10~ 기록)

### AC-05 동시 시작 직렬화와 두 번째 요청 재확인

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `task.TaskStateApiIntegrationTests#concurrentStart_onSameChangesItem_onlyOneWins` | 두 작업이 같은 항목을 바꾸며 PASS 뒤 동시에 시작 → 하나만 성공, 다른 하나는 409 `task_conflict` |
| `task.TaskStateApiIntegrationTests#readsOnSameItem_doNotConflict` | 읽기만 겹치면 둘 다 시작 |
| `task.TaskStateApiIntegrationTests#start_afterDesignChanged_is409VersionConflict_butUnrelatedTaskStarts` | 시작 때 참조 버전 최신성을 다시 본다 |

흉내 낸 부분: PASS 를 SQL 로 넣는다. 동시성 자체는 실제 DB 에서 확인한다.

실제 환경 확인
- 입력: 두 에이전트가 같은 항목을 바꾸는 작업으로 각각 PASS 를 받은 뒤 거의 동시에 START
- 기대 결과: 하나는 진행 중, 다른 하나는 409 `task_conflict`(충돌 작업 표시)
- 실패 조건: 둘 다 진행 중
- 증거: 두 MCP 응답, 작업 목록 상태

결과: (11/10~ 기록)

### AC-06 PASS 뒤 설계가 바뀌면 STALE, 옛 번호로 시작 거부

시작 관문은 G1(그 검증이 현재 계획의 PASS 인가)부터 보고, 첫 실패의 코드로 답한다. 실제 발행은 옛 버전을 본 검증을 먼저 STALE 로 바꾸므로, 그 뒤 옛 번호로 시작하면 G1 에서 **409 `validation_not_passed`**로 막힌다. `version_conflict`(G2, 참조 버전이 현재 기준과 다름)는 검증이 아직 PASS 로 남아 있을 때만 나온다.

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `validation.PublicationImpactIntegrationTests#publication_stalesValidationsOnOldVersion_andHoldsOnlyInProgressTasks` | 실제 발행 처리로 옛 버전을 본 검증이 STALE, 진행 중 작업은 재검증 대기 |
| `task.TaskStateApiIntegrationTests#start_afterDesignChanged_is409VersionConflict_butUnrelatedTaskStarts` | 발행을 SQL 로만 해 검증이 PASS 로 남은 경우에도 G2 가 409 `version_conflict`로 막는다(두 번째 방어선). 상관없는 작업은 시작됨 |
| `validation.ValidationApiIntegrationTests#worker_lateResult_isDiscarded_whenValidationWentStale` | STALE 이 된 뒤 늦게 온 AI 결과는 버려진다 |
| `task.PlanFreshnessIntegrationTests#startGate_treatsMissingBaselineItemAsStale_notAsUnchanged` | 항목이 기준에서 빠져도 바뀐 것으로 본다 |
| `mcp.TaskMcpServiceIntegrationTests#report_returnsTask_andChangesPublishedAfterThePlan` | 상태 보고 응답에 계획 뒤 발행된 변경이 실린다 |

흉내 낸 부분: 일부 테스트는 발행을 SQL 로 한다.

실제 환경 확인
- 입력: 작업이 PASS 를 받은 뒤(시작 전), 팀장이 화면에서 그 작업이 참조한 항목의 변경 제안을 승인·발행. 에이전트가 옛 검증 번호로 START
- 기대 결과: 검증 STALE(무효 사유 `DESIGN_PUBLISHED`), START 409 `validation_not_passed`, `get_design_changes`에 그 변경이 보임
- 실패 조건: 옛 번호로 시작됨, 또는 검증이 PASS 그대로
- 증거: 발행 전후 검증 상태, START 응답, 설계 변경 조회 응답, 작업 화면의 STALE 표시

화면 증거 전 손볼 것(4절): 무효 사유 문구가 `ASSIGNEE_CHANGED`만 있어 `DESIGN_PUBLISHED`·`PLAN_CHANGED`가 코드값 그대로 보인다.

결과: (11/10~ 기록)

### AC-09 AI 시간 초과·문서 조회 실패·시작 응답 유실

| 상황 | 자동 테스트 | 확인하는 것 |
| --- | --- | --- |
| AI 시간 초과 | `validation.ValidationApiIntegrationTests#worker_timeout_isErrorRetry_withTimeoutRun` | ERROR(`ai_timeout`, 재시도 가능), 통과 아님 |
| AI 응답 형식 이상 | `validation.ValidationApiIntegrationTests#worker_malformedResponses_areInvalidOutput_notPass` | 이상한 응답은 PASS 가 아니라 오류 |
| 문서 조회 실패 | `validation.ValidationRetryIntegrationTests#failuresBeforeTheModelIsCalledDoNotCount` | 근거 문서 검색 장애 → ERROR(`evidence_fetch_failed`), AI 를 부르지 않고 재시도 횟수에 안 셈 |
| 워커가 죽음 | `validation.ValidationApiIntegrationTests#worker_expiredLease_isRecoveredAsErrorRetry_andLateResultIsIgnored` | 처리 중으로 남았다가 오류로 닫히고 늦은 결과는 무시 |
| 저장·실패 기록 둘 다 실패 | `validation.ValidationResilienceIntegrationTests#whenSavingTheResultAndRecordingTheFailureBothFail_leaseRecoveryClosesIt_andRetryFinishesIt` | 회수가 오류로 닫고 재시도로 PASS |
| 재시도 | `validation.ValidationRetryIntegrationTests` 전체 | 오류 → 재시도 → 통과, 같은 입력 3번 제한 |
| 시작 응답 유실 (REST) | `task.TaskStateApiIntegrationTests#start_withPass_becomesInProgress_andRepeatIsIdempotent` | 같은 검증으로 START 를 다시 보내면 같은 결과 |
| 시작 응답 유실 (MCP) | `mcp.TaskMcpServiceIntegrationTests#report_sameKeyRetry_returnsFirstResult_insteadOfConflict` | 같은 키로 다시 보내면 처음 결과 |

흉내 낸 부분: AI 실패는 가짜 분석기가 예외를 던지는 방식, 문서 조회 실패는 문서 검색(`HybridDocumentCandidateSearch`)을 mock 해 실패시키는 방식이다. 장애 경우별 대응표는 [12 검증 장애·동시성](../design/12-validation-resilience.md) 3절.

실제 환경 확인 (AI·문서 검색을 쓰는 계획, 예: IMPLEMENTS 참조나 미연결 내용이 있는 계획으로)
- AI 실패: dev 의 AI 서버를 멈추거나(연결 실패) 읽기 시간 초과를 짧게 해 검증 요청 → 오류(`ai_unavailable`·`ai_timeout`)와 재시도 버튼, 작업은 시작 못 함 / AI 서버를 되살리고 재시도 → PASS (협조: 양지훈)
  - 재시도 제한: 같은 입력의 실제 AI 호출은 새 검증·재시도를 합쳐 3번까지다. 읽기 시간 초과를 세 번 넘게 재현하면 **409 `validation_attempt_limit`**이 나는 것이 정상이다. 연결 실패(`ai_unavailable`, 사용량 없음)는 세지 않는다
  - 검색이 AI 호출보다 먼저 돈다. AI 서버를 멈췄을 때 검색이 먼저 실패하면 `evidence_fetch_failed`로 끝날 수 있다. 둘 다 "자동 통과 없음"이라 기준은 충족이고, 어느 코드였는지만 기록한다
- 문서 조회 실패: 문서 검색이 실패하게 만든 뒤(의미 검색이 쓰는 임베딩 쪽을 끊는 등, 방법은 양지훈·박상은과 확인) 검증 요청 → ERROR(`evidence_fetch_failed`), AI 호출 기록 없음, 재시도 횟수에 안 셈
- 시작 응답 유실: Claude Code 로 "응답 전에 끊기"는 재현이 어렵다. 같은 `idempotency_key`로 `report_task_state(START)`를 MCP 에 **직접 두 번** 보낸다 → 같은 결과, 작업 하나 (협조: 전진)
- 실패 조건: 오류인데 PASS·진행 중이 됨, 다시 보냈더니 두 번 처리됨
- 증거: 검증 상태·오류 코드, 실행 기록(validation_run), 화면, MCP 요청 기록

화면 증거 전 손볼 것(4절): `validation_attempt_limit`에 맞는 문구가 없어 일반 오류로 보이고 재시도 버튼도 계속 보인다.

결과: (11/10~ 기록)

### AC-13 제목만 바꾸면 검증 유지, 계약·완료 기준을 바꾸면 무효

지금 규칙(04-task-lifecycle.md §2.5, `TaskPlanService` 계획 변경 처리)
- 검증을 이어 받는 조건: 검증 입력(제목·접근 방식을 뺀 계획 내용)이 같고, **그 검증이 문서 검색을 쓰지 않았거나**(규칙·가짜 PASS) 검색에 쓰인 제목·접근 방식·근거 패킷도 같을 것
- 실제 AI 검증은 문서 검색을 쓴다(`summary.analysis_baseline_id`가 있음). 그래서 **제목만 바꿔도 이어 받지 않는다** — 바뀐 제목이 검색어가 되기 때문이다
- 이어 받지 못하면: 점유 중(진행 중·대기) 작업은 옛 검증이 STALE(`PLAN_CHANGED`)이 되고 IN_PROGRESS 는 재검증 대기. **시작 전(READY) 작업은 옛 검증을 STALE 로 바꾸지 않는다** — 새 리비전에 검증이 없어 옛 번호로 시작하면 G1 에서 409 `validation_not_passed`

| 자동 테스트 | 확인하는 것 |
| --- | --- |
| `task.PlanInputHasherTest#titleOnlyChange_sameHash` | 제목만 다르면 검증 입력이 같다 |
| `task.PlanInputHasherTest#proposedContentOrCriteriaChange_differentHash_approachOnly_sameHash` | 계약 변경 내용·완료 기준이 다르면 다른 입력 |
| `task.PlanFreshnessIntegrationTests#titleOnlyChange_keepsValidation_andStartResumeAcceptTheOldNumber` | 규칙·가짜 PASS 는 제목만 바꾸면 이어 받아 옛 번호로 시작·재개 가능 |
| `task.PlanFreshnessIntegrationTests#searchBackedPassIsNotCarriedWhenSearchTitleChanges` | 검색을 쓴 PASS 는 제목만 바꿔도 이어 받지 않는다(진행 중 작업 → STALE·재검증 대기) |
| `task.PlanFreshnessIntegrationTests#criteriaChange_inProgress_invalidates_andOldValidationCannotResume` | 진행 중 작업의 완료 기준을 바꾸면 무효, 옛 번호로 재개 불가 |
| `task.PlanFreshnessIntegrationTests#contractChange_ready_invalidatesEvenThoughOnlyProposedContentDiffers` | 시작 전 작업의 계약 변경 내용만 달라도 옛 번호로 시작 불가 |

흉내 낸 부분: 평소 테스트는 규칙·가짜 PASS 기준이다. 검색 PASS 경로는 `searchBackedPass…` 테스트가 검증 요약에 검색 표시를 넣어 따로 확인한다.

실제 환경 확인 (네 갈래)

| 시나리오 | 사전 조건 | 입력 | 기대 결과 |
| --- | --- | --- | --- |
| ① 규칙 PASS + 제목만 | 읽기만 하는 계획(READS), 규칙만으로 PASS, 시작 전 | 화면에서 제목만 수정 → 옛 번호로 START | 검증 이어 받음, START 성공 |
| ② 검색 PASS + 제목만 | 진짜 AI 로 PASS 받은 작업(IMPLEMENTS 등), 진행 중 | 제목만 수정 | 재검증 대기(WAITING REVALIDATION), 옛 검증 STALE(`PLAN_CHANGED`), 옛 번호로 RESUME 거부 |
| ③ 완료 기준 변경, 진행 중 | 진행 중 작업 | 완료 기준 수정 | 재검증 대기, 옛 검증 STALE, 옛 번호로 RESUME 거부 |
| ④ 완료 기준 변경, 시작 전 | READY 작업, PASS 받음 | 완료 기준 수정 → 옛 번호로 START | 옛 검증은 STALE 로 바뀌지 않음, START 409 `validation_not_passed`(새 계획에 검증 없음) |

- 실패 조건: ①에서 시작이 막힘, ②~④에서 옛 번호로 시작·재개됨
- 증거: 수정 전후 검증 상태, 작업 상태, START·RESUME 응답

결과: (11/10~ 기록)

## 3. 실행 순서 (11/10~11/13)

1. 개발이 마감된 최신 dev 로 전체 테스트 한 번 — 1-1 실행 기록을 바꾼다
2. dev 서버에서 기준별 "실제 환경 확인"을 차례로 하고 결과 칸을 채운다. 화면 증거는 4절이 들어온 뒤
3. 기대와 다른 것은 버그 이슈로 등록하고 담당자를 지정한다. "범위 확인 필요"(5절)는 버그가 아니라 팀 결정으로 넘긴다
4. 정리본을 완료 증거 정리(POL-15)에 낸다. 발표 자료(S15P31A108-113)의 근거로도 쓴다

## 4. 화면 증거 전에 필요한 것 (박민용 확인, PR #209 리뷰)

| 기준 | 지금 dev 화면 | 필요한 것 |
| --- | --- | --- |
| AC-04 | 검증 결과 카드가 `related_task_refs`를 그리지 않는다 | 관련 작업 표시 (5절 결정에 따라) |
| AC-06 | STALE 표시·시작 차단 문구는 있음. 무효 사유 문구가 `ASSIGNEE_CHANGED`만 있다 | `DESIGN_PUBLISHED`·`PLAN_CHANGED` 문구 |
| AC-09 | `validation_attempt_limit` 문구가 없어 일반 오류로 보이고 재시도 버튼이 계속 보인다 | 제한 문구, 제한에 걸리면 재시도 버튼 숨김 |

## 5. 범위 확인 필요 (팀 결정)

| 항목 | 지금 | 선택지 |
| --- | --- | --- |
| AC-04 "담당자와 차이가 표시된다" | API 는 관련 작업 번호·리비전과 발견 항목 설명(`message`)만 준다. 담당자는 작업 상세를 따로 읽어야 하고, 화면은 관련 작업을 그리지 않는다 | (가) 지금 범위로 인정: 응답에 관련 작업과 차이 설명이 있으면 충족 / (나) 후속 작업: `related_task_refs`에 담당자를 싣고(백엔드) 화면에 관련 작업·담당자·차이를 그린다(프론트) |
