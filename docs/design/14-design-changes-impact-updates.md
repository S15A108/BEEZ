# 14. 설계 변경 조회의 영향 분석 결과 이어 받기

S15P31A108-221 · MCP-03 `get_design_changes` · 담당 변준성 · 2026-11-05

## 1. 무엇을 하나

설계가 발행되면 발행 후 영향 분석(오서현 -197·-227)이 "이 변경으로 영향받을 수 있는 작업" 후보를 찾는다.
지금까지 에이전트는 이 결과를 받을 길이 없었다. `get_design_changes` 응답에 `impact_updates`를 더해, 분석 결과가 정해질 때마다 에이전트가 이어 받게 한다.

- 발행 변경(`items`)은 기존대로 `since_version`·`cursor`로 받는다.
- 영향 결과(`impact_updates`)는 **따로** `result_cursor`로 받는다. 분석은 발행보다 늦게 끝나고, 옛 발행의 분석이 나중에 끝날 수도 있어서 기준 번호로는 놓친다.

응답 모양은 [ImpactUpdate](../api/00-common.md#schema-impactupdate)·[PublishedImpactCandidate](../api/00-common.md#schema-publishedimpactcandidate), 처리 규칙은 [MCP-03](../api/02-mcp-tools.md#mcp-03).

## 2. 결과 순번 (V36)

| 정한 것 | 이유 |
| --- | --- |
| 결과가 정해질 때(COMPLETED·NOT_EVALUATED·FAILED로 바뀔 때) 프로젝트 안 순번을 붙인다 | 재시도 대기(PENDING)·진행 중(RUNNING)은 결과가 아니다 |
| 순번은 프로젝트별 카운터 행을 잠가 매긴다 | 잠금이 커밋까지 가므로 작은 번호가 먼저 커밋된다. 읽는 쪽이 N을 봤으면 N 이하는 다 보인다 — "마지막 순번 다음"만 읽어도 빠지는 결과가 없다. DB 시퀀스는 번호와 커밋 순서가 엇갈려 쓰지 않는다 |
| 트리거로 붙인다 | 결과를 쓰는 곳이 결과 저장·실패 확정·리스 만료 실패·제안 없는 발행의 미평가로 여러 곳이다. 한 곳에서 처리해 빠뜨리지 않는다 |
| 트리거 전에 끝난 결과는 순번이 없다 | 에이전트는 "지금부터" 받는다. 과거 후보는 대시보드에서 본다 |

잠금 순서: 분석 행을 바꾸는 트랜잭션은 모두 **프로젝트 행 → 분석 행 → 카운터 행** 순서로 잠근다(선점·결과 저장·실패 확정·제안 없는 발행의 미평가). 실패 확정도 분석 행을 바꾸기 전에 프로젝트 행을 잠근다(`PublishedAnalysisRepository.lockProjectOf`, 실패 처리 트랜잭션에서는 사용량 기록보다 먼저). 첫 카운터 행을 만들 때 외래 키 검사가 프로젝트 행을 보지만, 그 트랜잭션은 이미 프로젝트 행을 쥐고 있어 기다리지 않는다. 그래서 서로 기다리는 일이 없다(PR #167 리뷰, 시험 `failingTheFirstResultWhileAClaimHoldsTheProject_waitsInsteadOfDeadlocking`).

## 3. 응답에 담는 것

- 결과마다 발행, 제안, 분석 상태(`analysis_status`), 근거 충분 여부(`evaluation_status`), 실패 사유, 후보, 빠진 후보 수, 시각.
- 후보는 규칙(`explicit_result`)과 AI(`ai_result.candidates`) 둘 다. 같은 출처·같은 대상은 한 번, 규칙 먼저·작업·항목 번호 순, 최대 10개. AI 후보는 사유(`reason`)를 싣고 근거 원문(`sources`)은 싣지 않는다.
- `task_id`를 주면 그 작업이 후보에 든 결과만, 후보도 그 작업 것만.
- 미평가·실패도 결과로 준다(영향 0건과 구분). 후보가 없어 `task_id` 범위에는 나오지 않는다.
- 후보는 승인 설계가 아니고 작업 상태를 바꾸지 않는다. 도구 안내(description)에도 적었다.
- `report_task_state`(MCP-12)의 `changes`에는 싣지 않는다. 에이전트는 `get_design_changes`로 받는다.

## 4. 이번에 안 한 것

| 항목 | 이유·다음 |
| --- | --- |
| 재분석으로 결과가 바뀔 때 빠진 후보 작업에 알리기 | 지금은 결과가 한 번 정해지면 다시 분석하는 경로가 없다. 재분석이 생기면 이전 후보 작업도 범위 필터에 넣어야 한다 |
| 미완료·재시도 대기·오래 걸리는 분석 노출 | MVP 경계 문서에서 "담당자 합의 후"로 둔 항목. 대시보드(CHG-A03 `published_analysis`)에서 본다 |
| 권한별 전체 후보 REST 페이지 | `candidates_truncated`가 0보다 크면 대시보드를 안내만 한다 |
| 결과 보존 기간·오래된 cursor | 결과 행을 지우지 않으므로 지금은 해당 없음 |
| 진행 중 작업·계약 항목 우선 정렬 | 작업·항목 번호 순으로 고정. 화면 요구가 생기면 바꾼다 |

## 5. 코드 자리

- `V36__published_analysis_result_seq.sql` — 순번 칸·카운터 표·트리거
- `designchange/service/ImpactUpdateReader` — 결과 페이지·후보 정리
- `designchange/service/ImpactUpdatesCursor` — `result_cursor`
- `designchange/service/DesignChangesService#getChanges` — 응답에 붙이기
- `mcp/tool/GetDesignChangesTool` — 입력 칸·안내
- 테스트: `designchange/DesignChangesImpactUpdatesIntegrationTests`, `mcp/tool/McpToolsIntegrationTests#getDesignChanges_zeroIsSuccess_missingSinceIsFailure`
