# 13. 작업 완료 검증 (작은 범위)

S15P31A108-269 · VAL-07 · 담당 변준성 · 2026-11-05

## 1. 무엇을 하나

에이전트가 작업을 끝냈다고 보고할 때(`report_task_state(COMPLETED)`, REST TSK-A07), "실제로 바꾼 설계 항목"을 함께 받는다.
서버는 이것을 그 작업의 계획과 **규칙으로** 대조해, 사람이 확인할 거리를 응답과 작업 상세에 보여 준다.

| 보여 주는 것 | 기준 |
| --- | --- |
| 범위 이탈 후보 | 보고된 변경 중 계획이 바꾸기로 하지 않은 항목. 계획이 "계약 변경 없음"을 선언 → `NO_CONTRACT_CHANGE_DECLARED` / 계획에 없음 → `NOT_IN_PLAN` / 계획은 읽기·구현으로만 다룸 → `NOT_PLANNED_TO_CHANGE` |
| 바꾸기로 했는데 보고 안 한 항목 | 계획의 CHANGES 항목 중 변경 보고에 없는 것 |
| 못 채운 완료 기준 | `completion_checks` 에서 `met=false` |
| 확인 안 한 완료 기준 | 계획의 `completion_criteria` 중 `completion_checks` 에 없는 것 |
| 인수인계 기록 | 이 작업의 공유 `HANDOVER` 기록(write_context)이 있는가. 없으면 경고만 |

응답 모양은 [CompletionReview](../api/00-common.md#schema-completionreview).

## 2. 정한 것 (11/5)

- **표시만 한다.** 범위 이탈 후보가 있어도 완료 전이는 그대로다. 담당자 확인 전 완료 보류(AC-18안)는 하지 않는다.
- **입력 칸을 더한다.** `changed_design_item_ids`(선택). 보내지 않으면 범위를 판단하지 않고 `changes_reported=false` 로 알린다 — "0건"과 "판단 안 함"을 구분한다.
- **AI를 쓰지 않는다.** 비용 0. 코드 품질·동작을 보증한다고 말하지 않는다(AC-15와 같은 원칙). 응답 `notice` 에 그 안내가 들어 있다.
- **결과는 저장하지 않는다.** 완료 보고·계획 리비전·계획 참조가 모두 불변이라 조회할 때 다시 계산해도 같다. 인수인계 기록 유무만 지금 상태를 본다(완료 뒤에 남기면 경고가 사라진다). 대조 규칙을 바꾸면 `review_version` 을 올린다.

## 3. 저장

`task_completion_report.changed_design_item_ids JSONB` (V28). NULL = 보내지 않음, `[]` = 바꾼 항목 없음.
정렬·중복 제거해 저장한다. 같은 delta 완료 보고의 재전송 판정에 이 값도 들어간다(순서 무관).
없는 항목·다른 프로젝트 항목은 400 `validation_failed` (둘을 구분하지 않는다).

## 4. 어디에 보이나

- COMPLETED 보고 응답 (TSK-A07, MCP-12 `task.completion_review`)
- 작업 상세 (TSK-A03) — COMPLETED 이거나, WAITING(REVALIDATION) 에서 delta 보고의 DELTA 검증을 기다리는 중일 때만
- 목록에는 없다

## 5. 이번에 안 한 것

| 항목 | 이유·다음 |
| --- | --- |
| 설계 일치 검사 (보고된 변경 vs 현재 승인 계약, 다르면 CHG 연결) | 보고에 "어떻게 바꿨는지"가 없다. 범위 이탈 후보의 사유가 그 신호를 대신한다 |
| 포함/제외 범위(문장) 대조 | 문장 비교는 AI 영역. -268(검증 강도)과 함께 정한다 |
| 범위 이탈 시 완료 보류 (AC-18안) | 11/5 결정으로 표시만 |
| TRC-07 기록 분리 (G1 계획 vs 설계 / G3 완료 vs 계획) | 추적 화면 작업과 함께 |
| 화면 표시 | FE(박민용) — 작업 상세 응답의 `completion_review` 를 쓰면 된다 |

## 6. 코드 자리

- `task/service/CompletionReviewer` — 입력 확인, 대조 규칙, 보여 줄 보고 고르기
- `task/service/TaskStateService` — COMPLETED 보고에 저장·재전송 비교·응답에 결과 붙이기
- `task/service/TaskQueryService#getTaskDetail` — 상세에 결과 붙이기
- 테스트: `task/CompletionReviewIntegrationTests`, `mcp/tool/McpToolsIntegrationTests#taskFlow_planValidateStart_overMcp`
