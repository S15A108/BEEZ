# MCP 도구 ↔ 업무 서비스 연결 계약

작성: 변준성 · Jira: S15P31A108-166 · 대상: 전진(MCP 서버 -176, 접근 제어 -177, 도구 연결 -179)

MCP 도구가 부를 업무 서비스의 입구·입력·출력·오류를 적는다. 도구 인수·응답의 모양은 [MCP Tools](../api/02-mcp-tools.md)가 기준이고, 이 문서는 "어느 자바 메서드를 어떻게 부르는가"만 정한다.

## 1. 공통 규칙

| 항목 | 규칙 | 근거 |
| --- | --- | --- |
| 호출자 | 어댑터가 토큰 인증 뒤 `CallerContext.agent(계정, 토큰)`를 만들어 넘긴다 | [06 문서](06-project-access.md) |
| 프로젝트 | 토큰의 프로젝트 번호를 `projectId` 인수로 넘긴다. 도구 인수의 `project_id`는 서비스가 같은지만 본다(다르면 not_found) | MCP Tools "연결과 인증" |
| 입력 검증 | 서비스가 DTO 검증 표시(@NotBlank 등)를 직접 적용한다(`InputValidator`). 어댑터는 JSON → 인수 객체 변환만 한다 | 컨트롤러를 거치지 않으므로 |
| 오류 | 서비스는 `ApiException(상태, code, message)`을 던진다. 어댑터는 `code`·`message`를 도구 실패 결과로 옮긴다. 숫자 상태는 REST 대응 의미 | MCP Tools "업무 오류" |
| `idempotency_key` | 변경 도구(MCP-10·12·07)는 필수(비면 400). 키 범위는 계정·프로젝트·동작. 같은 키·같은 입력은 처음 결과를 그대로, 다른 입력은 409 `idempotency_conflict`. 재요청도 현재 권한을 먼저 확인한다(작업 대상 도구는 현재 담당자인지까지). 결과는 `mcp_command`(V8)에 24시간 보존 — 문서·설계 항목의 `*_http_command`와 같은 범위 한정 결정 | 공통 5절, Q12 |
| 트랜잭션 | 서비스 메서드 하나 = 트랜잭션 하나. 어댑터는 트랜잭션을 열지 않는다 | — |
| 조회 이력 | 설계 내용을 돌려주는 도구는 서비스 안에서 `context_access_log`에 남긴다. 어댑터가 따로 남기지 않는다 | -165, REST CTX-A06 |
| 감사 기록 | `mcp_audit_log`는 어댑터(-177) 몫. 서비스는 남기지 않는다 | MCP-08·09 |

## 2. 작업 도구

| 도구 | 서비스 메서드 | 입력 | 출력 |
| --- | --- | --- | --- |
| MCP-10 `upsert_task_plan` | `TaskMcpService.upsertTaskPlan(caller, projectId, args)` | `UpsertTaskPlanArgs(plan, taskId, expectedRevisionNo, expectedLockVersion, idempotencyKey)` | `TaskDetailResponse` |
| MCP-12 `report_task_state` | `TaskMcpService.reportTaskState(caller, projectId, args)` | `ReportTaskStateArgs(taskId, event, planRevisionId, expectedLockVersion, validationId, waitingReason, summary, delta, completionChecks, idempotencyKey)` | `ReportTaskStateResult(task, changes, changesSinceVersion)` |
| MCP-03 `get_design_changes` | `DesignChangesService.getChanges(caller, projectId, query)` | `DesignChangesQuery(sinceVersion, taskId, cursor, limit)` | `DesignChangesResponse` |
| MCP-11 `validate_task_plan` | **-159에서 구현**(Sprint 4). 계약은 MCP-11 명세 그대로 | — | — |

- MCP-10: `task_id`가 없으면 새 작업(TSK-A01), 있으면 새 계획 리비전(TSK-A04). 수정이면 `expected_revision_no`·`expected_lock_version` 필수(없으면 400). 나머지 규칙·오류는 REST TSK-A01·A04와 같다.
- MCP-12: 전이 규칙·오류는 REST TSK-A07과 같다. REST의 202(완료 델타 → 재검증 대기)는 도구 결과에서 구분하지 않는다 — `task.status`·`waiting_reason`으로 본다.
- MCP-12 `changes`: 그 작업의 **현재 계획을 세운 시점의 기준 설계 이후** 발행된 변경과 그 작업 검증의 유효성이다. 기준점은 계획 리비전을 저장할 때 남긴 당시 기준 설계(`task_plan_revision.base_baseline_id`, V8)이고, 근거 패킷이 더 옛 기준이면 그 기준이다. 시각 비교로 추정하지 않는다(앱·DB 시계가 어긋나면 변경이 빠진다). V8 이전에 저장된 리비전은 근거 패킷이 있으면 그 기준으로 채우고, 없으면 "알 수 없음"(NULL)으로 두어 변경을 처음부터 전부 보여 준다(누락보다 과다가 안전). 명세 MCP-12에 기준점이 없어 담당자 결정으로 정했다(명세 반영 요청). 기준 설계가 한 번도 없는 프로젝트면 `changes`와 `changes_since_version`을 생략한다.
- MCP-12 이어 보기: `changes`는 20건 단위로 자른다. `changes.has_more=true`이면 MCP-03 `get_design_changes`를 `since_version = changes_since_version`, `task_id = 같은 작업`, `cursor = changes.next_cursor`로 부른다. 이 cursor에는 since=0이어도 "처음 연결(최근 1건)" 규칙을 적용하지 않는다.
- 영향 작업 기준(MCP-03·12 공통, 리뷰 반영): 계획이 바뀐 항목의 **변경 전 버전 또는 그보다 옛 버전**을 참조하면 영향이다(버전 번호 비교). 계획이 v1을 참조하면 v1→v2, v2→v3 발행이 모두 잡힌다. `affected_task_ids`는 완료·취소 작업을 빼고, `task_id`를 지정한 목록(MCP-12 `changes` 포함)은 작업 상태와 무관하게 그 작업이 영향받은 변경을 준다.

## 3. 컨텍스트 도구

| 도구 | 서비스 메서드 | 입력 | 출력 |
| --- | --- | --- | --- |
| MCP-01 `get_development_context` | `ContextMcpService.getDevelopmentContext(caller, projectId, args)` | `GetDevelopmentContextArgs(request, projectId, taskId)` — 도구 인수 그대로 | `ContextPacketResponse` |
| MCP-05 `find_context` | `ContextMcpService.findContext(caller, projectId, args)` | `FindContextArgs(query, taskId, kinds, cursor, limit)` | `PageResponse<ContextRecordSummaryResponse>` |
| MCP-06 `read_context` | `ContextMcpService.readContext(caller, projectId, type, id)` | `type`(PACKET\|RECORD), `id` | `ReadContextResult(type, packet, record)` |
| MCP-07 `write_context` | `ContextMcpService.writeContext(caller, projectId, args)` | `WriteContextArgs(previousId, taskId, planRevisionId, sourcePacketId, kind, visibility, summary, detail, designRefs, idempotencyKey)` | `ContextRecordResponse` |

- MCP-01: 어댑터는 도구 인수만 넘긴다(-179 "입출력 변환만"). 패킷 **내용 구성**(승인 설계 검색·의존 확장·미확정·관련 작업·근거)은 박상은 모듈(-131·-132)이 하고, 업무 서비스 안에서 호출한 뒤 불변 패킷으로 저장해 돌려준다. 구성 모듈이 붙기 전에는 **503 `context_not_ready`**(HTTP 상태는 공통 4절 처리 기반 이용 불가와 같음, 코드명은 전진 MCP 오류표 #40 B4)를 주고 패킷을 저장하지 않는다 — 빈 구성을 성공으로 주면 "근거 없음"으로 읽히기 때문이다(MCP-01 "검색 실패와 근거 없음은 구분한다"). 권한(404·사람 세션 403)과 인수 확인(요청 문장 400, `project_id` 불일치 404, `task_id` 형식 400·다른 프로젝트 404)은 먼저 한다. 구성 결과의 관련 작업·근거도 지금 값인지 확인한다: 관련 작업은 이 프로젝트 작업이어야 하고(아니면 404) 넘어온 계획 리비전·상태가 그 작업의 **현재** 계획 리비전·상태와 같아야 한다(아니면 400, 작업 행은 저장이 끝날 때까지 공유 잠금). 근거(`sources`)의 문서 버전은 패킷의 기준 설계에 들어 있는 문서 버전(`baseline_document`)이어야 하고 버전 번호도 맞아야 한다(아니면 400) — 같은 프로젝트라도 미승인 버전이나 지난 기준의 버전이 현재 승인 근거처럼 패킷에 남지 않게 한다. 구성 모듈 호출 방식과 주고받는 값은 모듈 연결 때 박상은과 정한다. 구성 결과 저장 시(`ContextPacketService.save`) `design_item_version_ids`는 모두 **현재 기준 설계**에 들어 있어야 한다(아니면 400). 기준이 없으면 404 `baseline_not_published`. 저장은 프로젝트 행을 공유 잠금(FOR SHARE)으로 잡고 현재 기준을 읽는다 — 발행(FOR UPDATE)과 겹치면 발행이 끝난 뒤의 기준으로 판단해, 옛 기준 ID와 바뀐 버전 상태가 한 패킷에 섞이지 않는다. 저장할 때 반환할 승인 설계(`design_items`)를 그 시점 그대로 content에 넣고, 조회는 다시 읽지 않고 이 스냅샷을 돌려준다(나중에 버전이 SUPERSEDED가 되어도 같은 패킷은 같은 내용). `content_hash`는 {project_id, task_id, request, baseline_id, content}의 정규 JSON(키 정렬, 목록 경계 유지) SHA-256이다. 사람 세션 호출은 403(CTX-A01 "웹에서 임의 패킷을 생성하지 않는다").
- MCP-05: 요약에 검색어가 들어간 기록(대소문자 무시) + 작업·종류 필터. 권한(공유 또는 내 것)은 페이지를 자르기 전에 건다. 남의 비공개는 건수에도 없다. 다른 프로젝트 `task_id`는 0건. 모든 리비전을 돌려주고 `is_latest_revision`으로 구분한다. 의미 검색(임베딩)은 박상은 모듈이 붙을 때 추가한다. 패킷은 검색하지 않는다(Q10).
- MCP-06: 다른 프로젝트·남의 비공개는 없는 것과 같은 404. 조회 이력을 남긴다.
- MCP-07: 수정은 `previous_id`를 잇는 새 불변 리비전이다. 남의 공유 기록 수정은 403, 남의 비공개는 404, 같은 이전 판의 두 번째 수정은 409 `revision_conflict`. 작성자 비교는 계정 ID. `design_refs`는 이 프로젝트 항목의 실제 버전이어야 한다(승인 여부는 보지 않음 — 당시 참조 그대로).

## 4. DB가 지키는 것 (V8)

다른 프로젝트 번호가 섞이지 않게 묶음 FK를 건다: 기록의 근거 패킷·이전 판·첫 판·작성 토큰, 패킷의 토큰. `*_ref` 표와 `task_plan_revision.context_packet_id`는 project_id 칸이 없어 서비스가 확인한다.

## 5. 아직 안 된 것

| 항목 | 언제 |
| --- | --- |
| MCP 경유 호출 테스트 (Jira -166 완료 조건) | MCP 서버(-176)·도구 연결(-179) 뒤. 지금은 서비스 직접 호출 테스트 |
| MCP-11 검증 요청 | -159 |
| 패킷 내용 구성(업무 서비스 안 호출 지점만 있음, 지금은 503) | 박상은 -131·-132 |
| 근거(`sources`)의 원문 발췌 계약: 발췌는 저장하지 않고 참조·해시로 검증하는 방향. 정확한 span·순서·정규화, 발췌 해시 범위(전체 원문 `text_hash`와 구분)는 미정이며 지금의 `SourceDto`·`content_hash`가 그 계약을 구현한 것은 아니다 | 박상은 -131·-132와 별도 계약(#40 검토) |
| 의미 검색·기록 임베딩 | 박상은 모듈 연결 때. 기록 임베딩 칸은 1024차원 고정이라 임베딩 설정과 차원 확인 필요 |
| REST CTX-A01~A04 | 같은 서비스를 쓰는 컨트롤러만 추가하면 된다. 별도 티켓 |
