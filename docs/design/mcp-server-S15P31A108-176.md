# MCP 서버 기반과 도구 추가 방법

작성: 전진 · Jira: S15P31A108-176 · 대상: MCP 도구를 붙이는 사람(-179 어댑터, 각 서비스 담당자)

Claude Code가 붙는 `/mcp` 서버의 구성, 모든 도구가 함께 쓰는 공통 처리(호출자·인수·오류·로그), 도구를 하나 추가하는 방법을 적는다. 도구 인수·응답 모양은 [MCP Tools](../api/02-mcp-tools.md), 어느 서비스 메서드를 부르는지는 [08 MCP ↔ 업무 서비스 계약](08-mcp-service-contract.md)이 기준이다.

## 1. 구성

| 항목 | 선택 | 이유 |
| --- | --- | --- |
| 라이브러리 | MCP Java SDK `io.modelcontextprotocol.sdk:mcp:2.1.0` (`mcp-core` + `mcp-json-jackson3`) | Spring Boot 4.1.1 BOM 아래에서 충돌 없이 해석된다(reactor 3.8.7, Jackson 3.1.5로 맞춰짐). 서블릿용 Streamable HTTP 전송과 시험용 클라이언트가 들어 있다. Spring AI MCP 스타터(2.0.2)는 같은 SDK를 감싸지만 `spring-ai-model`·WebFlux 전송·자동 설정을 함께 끌고 오고, 오류를 08 계약 형식으로 바꾸려면 결국 직접 감싸야 해서 쓰지 않았다 |
| 전송 | Streamable HTTP, 경로 `/mcp`, 세션 있음(stateful) | 02-mcp-tools "연결과 인증". MCP 세션 ID는 계정·토큰과 다른 식별자로 남는다(-178에서 연결 상태에 쓴다) |
| 인증 | 기존 `SecurityConfig.mcpChain` + `AgentTokenAuthenticationFilter`(-175) | 서블릿 앞에서 매 요청 토큰을 검증한다. 세션을 연 뒤에 토큰을 폐기해도 다음 요청이 401이다 |
| 요청 상한 | 2 MiB (`McpServerConfig.REQUEST_MAX_BYTES`) | REST 요청 전체 상한·Nginx `/mcp`의 `client_max_body_size 2m`과 같다. SDK 기본은 16 MiB |

요청이 지나가는 순서:

```
RequestIdFilter (X-Request-Id, MDC) → mcpChain (Bearer 토큰 → AgentPrincipal, 실패 401)
  → MCP 서블릿 (SDK: initialize · tools/list · tools/call)
    → McpToolHandler (공통 처리) → McpTool 구현 (인수 전달) → 담당 서비스
```

- MCP 서블릿은 DispatcherServlet과 따로 등록된다. `@RestControllerAdvice`를 타지 않으므로 도구 오류는 `McpToolHandler`가 결과로 바꾼다.
- 세션은 서버 메모리에 있다. 서버를 재시작하면 기존 세션 ID는 404가 되고, 클라이언트는 다시 `initialize`한다(MCP 명세 동작). 서버를 여러 대로 늘리면 세션 고정이 필요하다.
- **GET·HEAD `/mcp`(서버 → 클라이언트 대기 스트림)는 405로 닫는다**(서블릿의 HEAD는 GET 처리를 그대로 부른다). MCP 명세상 서버가 고를 수 있고 클라이언트는 POST만으로 동작한다. 열어 두면 끝나지 않는 비동기 요청이 되어, Spring Boot의 graceful shutdown이 진행 중인 async 요청을 기다리느라 에이전트가 붙어 있는 동안 배포마다 종료 대기 시간(30초)을 다 채운다. 서버가 먼저 보낼 기능(알림·elicitation)이 생기면 종료 처리와 함께 연다.
- 그래서 연결 유지 ping도 끈다(보낼 통로가 없다). GET 스트림이 없으면 SDK 정리기가 두 정리 사이에 요청이 없던 세션을 닫으므로, 정리 주기를 1시간으로 늘려 마지막 요청 후 1~2시간 유지한다(웹 세션 2시간과 비슷하게). 닫힌 세션에 요청하면 404이고, 클라이언트는 MCP 명세대로 다시 `initialize`해야 한다 — 배포 재시작 때와 같은 동작이다. 대신 끊긴 POST 응답을 GET + `Last-Event-ID`로 이어받는 경로도 함께 닫힌다(도구 호출은 수십 ms라 드물고, 변경 도구는 `idempotency_key`로 다시 부르면 같은 결과를 받는다). POST 응답 스트림은 도구 결과를 보내면 끝나므로 Nginx `/mcp`의 `proxy_buffering off`·`proxy_read_timeout 1h`(-123)와 맞는다.

## 2. 공통 처리 (`McpToolHandler`)

모든 도구 호출에 똑같이 적용된다. 도구 구현은 이 부분을 다시 만들지 않는다.

| 단계 | 하는 일 |
| --- | --- |
| 호출자 | 필터가 검증한 토큰 주체에서 `McpCall(caller = CallerContext.agent(계정, 토큰), projectId = 토큰 프로젝트, sessionId)`를 만든다. 도구 인수의 `project_id`로 프로젝트를 정하지 않는다 |
| 인수 | 도구 인수(JSON 객체)를 Spring `ObjectMapper`(REST와 같은 snake_case)로 인수 레코드에 넣는다. 타입이 틀리거나 enum 이름이 없거나 **모르는 키**가 있으면 `validation_failed` |
| 결과 | 성공 payload를 snake_case JSON으로 `structuredContent`와 `content[0].text`에 같이 싣는다 |
| 오류 | 아래 3절 형식의 도구 실패 결과(`isError: true`) |
| 로그 | `com.beez.mcp` 한 줄: 도구·프로젝트·계정·토큰 ID·결과 코드·지연. 인수·결과 본문은 남기지 않는다(비밀값·개인 대화 원문, MCP-09). 요청 ID는 MDC로 붙고, `com.beez.access`의 `POST /mcp -> 상태` 줄과 같은 ID다 |
| 감사 | 성공·실패 모두 `mcp_audit_log` 한 줄(-177, 8절) |

**모르는 인수 키는 거부한다**(-179 결정). 인수 변환에만 `FAIL_ON_UNKNOWN_PROPERTIES`를 켜고 중첩 객체(`plan`·`items` 등)에도 적용된다. 메시지는 칸 경로로 낸다(예: `taskid 은(는) 이 도구에 없는 인수입니다`). REST는 Boot 기본대로 무시하지만, 도구에서 선택 인수 오타를 무시하면 뜻이 바뀐 채 성공한다 — `upsert_task_plan`의 `task_id` 오타는 계획 수정 대신 새 작업을 만든다. 그래서 인수 레코드는 스키마의 모든 키를 칸으로 가져야 하고, 스키마에는 `additionalProperties: false`를 적는다. 자유 객체 칸(`Map`, 예: `proposed_content`·`delta`)은 그대로 받는다.

SDK의 입력 스키마 검사는 끈다(`validateToolInputs(false)`). 켜면 실패가 SDK 문구 텍스트로 나가 공통 오류 형식과 어긋난다. 스키마는 에이전트에게 주는 안내이고, 검증은 인수 변환과 서비스의 검증 표시(`InputValidator`)가 한다(08 계약 1절).

## 3. 오류 형식

### 업무 오류 — 도구 실패 결과

HTTP는 200이고 JSON-RPC 응답 안의 도구 결과가 실패다. 본문은 REST 오류 본문과 같다.

```json
{
  "content": [{ "type": "text", "text": "{\"error\":{\"code\":\"not_found\",\"message\":\"리소스가 없거나 프로젝트 소속·접근 권한이 없음\"}}" }],
  "structuredContent": { "error": { "code": "not_found", "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음" } },
  "isError": true
}
```

| 원인 | code | 비고 |
| --- | --- | --- |
| 서비스가 던진 `ApiException` | 그 `code`·`message`·`details` 그대로 | REST와 같은 본문이다. `details`는 명세가 정한 오류만 채우고 없으면 키가 빠진다(-267). 숫자 상태는 내보내지 않는다(02-mcp-tools "가짜 HTTP 상태를 넣지 않는다") |
| 인수 타입·enum 변환 실패, 모르는 키 | `validation_failed` | 메시지는 칸 경로만 적는다. 예: `mode 값이 올바르지 않습니다`, `completion_checks[0].status 값이 올바르지 않습니다`, `plan.titel 은(는) 이 도구에 없는 인수입니다` |
| 인수 레코드를 만들 수 없음(레코드 정의 오류) | `internal` | 입력 탓이 아니라 서버 버그라 `validation_failed`로 숨기지 않고 스택을 남긴다 |
| 그 밖의 예외 | REST `GlobalExceptionHandler`와 같은 code | 같은 대응표를 그대로 쓴다. 낙관적 잠금 충돌 → `revision_conflict`, DB 제약 → `validation_failed`, 나머지 → `internal`(서버 로그에 스택) |

### 인증 실패 — HTTP 401

전송 단계에서 거부한다. MCP 세션이 열리지 않고, 열린 세션도 그 요청은 처리되지 않는다.

```json
{ "error": { "code": "unauthenticated", "message": "에이전트 토큰이 유효하지 않음" } }
```

토큰 없음·모르는 토큰은 `unauthenticated`, 폐기·멤버십 종료·프로젝트 보관은 `token_revoked`(-175).

### 프로토콜 오류

없는 도구 이름, `Accept` 헤더 누락, 없는 세션 ID 같은 MCP 규약 위반은 SDK가 JSON-RPC 오류로 응답한다. 업무 오류 형식을 따르지 않는다.

## 4. 도구 추가 방법

도구 하나 = `McpTool<A>`를 구현한 `@Component` 하나. `McpServerConfig`가 빈을 모아 자동으로 등록한다. 도구 이름이 겹치면 서버가 기동하지 않는다.

```java
package com.beez.backend.mcp.tool;

/** MCP-12 report_task_state (02-mcp-tools). 계약: 08 문서 2절 */
@Component
class ReportTaskStateTool implements McpTool<ReportTaskStateArgs> {

    private final TaskMcpService tasks;

    ReportTaskStateTool(TaskMcpService tasks) {
        this.tasks = tasks;
    }

    @Override public String name() { return "report_task_state"; }

    @Override public String description() {
        return "작업 상태 전이(START·COMPLETED 등)를 보고한다. idempotency_key 는 필수다.";   // 예시 — 실제 스키마는 -179
    }

    @Override public String inputSchema() {
        return """
                {"type":"object","required":["task_id","event","idempotency_key"],
                 "properties":{
                   "task_id":{"type":"string"},
                   "event":{"type":"string","enum":["START","RESUME","WAIT","COMPLETED","CANCEL"]},
                   "idempotency_key":{"type":"string"}}}""";
    }

    @Override public Class<ReportTaskStateArgs> argsType() { return ReportTaskStateArgs.class; }

    @Override public Object call(McpCall call, ReportTaskStateArgs args) {
        return tasks.reportTaskState(call.caller(), call.projectId(), args);
    }
}
```

지킬 것 (08 계약 1절):

1. **인수 → 서비스, 결과 → 반환만 한다.** 권한 확인·검증·멱등 처리는 서비스가 한다. 어댑터에 업무 규칙을 복제하지 않는다.
2. **트랜잭션을 열지 않는다.** `@Transactional`을 붙이지 않는다. 서비스 메서드 하나가 트랜잭션 하나다.
3. **오류는 `ApiException`으로 던진다.** 결과에 직접 `isError`를 만들지 않는다.
4. **인수 레코드 칸은 camelCase, 스키마 키는 snake_case.** `task_id` ↔ `taskId`. 인수 레코드는 `mcp/dto`의 기존 것(`ReportTaskStateArgs` 등)을 쓴다.
5. **반환값은 JSON 객체가 되는 값**(레코드·Map). 목록 하나만 돌려줘야 하면 `{"items": [...]}`처럼 감싼다 — `structuredContent`는 객체여야 한다.
6. **감사 기록(mcp_audit_log)을 도구 안에서 남기지 않는다.** -177이 `McpToolHandler`에 공통으로 붙인다.

시험: `McpServerIntegrationTests`처럼 SDK 클라이언트로 붙어 `tools/call`을 부른다. 서비스 규칙은 이미 서비스 시험에 있으니, 도구 시험은 "인수가 서비스까지 제대로 가는가"와 08 계약의 MCP 경유 시나리오만 본다.

## 5. 로컬에서 연결해 보기

1. backend를 띄운다(`./gradlew bootRun`, local 프로필이 compose의 Postgres를 띄운다).
2. 웹 또는 REST로 로그인한 뒤 프로젝트 에이전트 토큰을 발급한다: `POST /api/projects/{projectId}/agent-tokens` → 응답의 `token`(한 번만 보인다).
3. Claude Code에 서버를 등록한다.

```bash
claude mcp add --transport http beez http://localhost:8080/mcp --header "Authorization: Bearer <발급한 토큰>"
```

4. Claude Code에서 `/mcp`로 `beez`가 connected인지, 도구 목록이 보이는지 확인한다. 도구는 `mcp/tool` 패키지의 13개다(7절).
5. Claude Code에게 "BEEZ `check_connection` 불러 줘"라고 하면 프로젝트·계정·토큰 이름·클라이언트 이름이 나온다(MCP-15). 같은 계정으로 다른 곳에서 새로 연결하면 이쪽 연결은 409 `agent_session_replaced`로 끊긴다(9절). 그때는 `/mcp`에서 다시 연결한다.

SDK 클라이언트로 확인하는 방법은 `McpServerIntegrationTests.connect()`를 본다(`HttpClientStreamableHttpTransport` + `Authorization` 헤더).

## 6. 다음 티켓으로 넘기는 것

| 항목 | 티켓 |
| --- | --- |
| ~~도구 호출마다 감사 기록, 권한 실패 응답 형식 확인~~ | -177 완료 — 8절 |
| ~~세션과 토큰을 묶기~~ | -178 완료 — 9절 |
| ~~1인 1에이전트, 연결 기록·해제, 작업 절차 안내(`instructions`)~~ | -178 완료 — 9절 |
| 연결 현황 조회(AGT-A05·A06), 통신이 끊긴 세션의 판정 | -75 |
| 도메인 도구 어댑터(MCP-01\~07·10\~13)와 입력 스키마 | -179 (7절) |

## 7. 붙은 도구 (-179)

| 도구 | 어댑터(`mcp/tool`) | 부르는 서비스 | 담당 |
| --- | --- | --- | --- |
| MCP-01 `get_development_context` | `GetDevelopmentContextTool` | `ContextMcpService.getDevelopmentContext` — 구성 모듈 연결 전에는 `context_not_ready` | 박상은(-131·-132) / 변준성 |
| MCP-02 `get_document` | `GetDocumentTool` | `DocumentApplicationService.getForAgent` — MCP 전용(REST DOC-A02 `get`은 문서 검토용이라 따로 둠) | 오서현(-192) |
| MCP-03 `get_design_changes` | `GetDesignChangesTool` | `DesignChangesService.getChanges` | 변준성 |
| MCP-04 `propose_design_change` | `ProposeDesignChangeTool` | `ProposalCommandService.create` (REST CHG-A01과 같음) | 오서현(-195) |
| MCP-05 `find_context` | `FindContextTool` | `ContextMcpService.findContext` | 변준성 |
| MCP-06 `read_context` | `ReadContextTool` | `ContextMcpService.readContext` — 패킷 조회도 이 도구(별도 패킷 도구 없음) | 변준성 |
| MCP-07 `write_context` | `WriteContextTool` | `ContextMcpService.writeContext` | 변준성 |
| MCP-10 `upsert_task_plan` | `UpsertTaskPlanTool` | `TaskMcpService.upsertTaskPlan` | 변준성 |
| MCP-11 `validate_task_plan` | `ValidateTaskPlanTool` | `ValidationMcpService.validateTaskPlan` | 변준성 |
| MCP-13 `get_validation` | `GetValidationTool` | `ValidationQueryService.get` (REST VAL-A02와 같음, 08 계약 2절) | 변준성 |
| MCP-14 `retry_validation` | `RetryValidationTool` | `ValidationMcpService.retryValidation` (REST VAL-A04와 같은 `ValidationRetryService`, -161). 연결은 -271 — 별도 도구로 확정(변준성) | 변준성 |
| MCP-12 `report_task_state` | `ReportTaskStateTool` | `TaskMcpService.reportTaskState` | 변준성 |
| MCP-15 `check_connection` | `CheckConnectionTool` | `AgentSessionService.check` (-178, 9절) | 전진 |
| MCP-16 `get_task` | `GetTaskTool` | `TaskQueryService.getTaskDetail` (REST TSK-A03과 같음, -335) — 인계받은 에이전트가 현재 계획·`lock_version`을 읽는 조회 | 전진 / 변준성 |

- MCP-05~07의 범위는 08 계약 3절(변준성 -166)을 그대로 따른다: 요약 문자열 검색(의미 검색은 박상은 모듈 연결 때), 패킷은 검색하지 않음(Q10).
- `get_document`는 버전을 생략하면 현재 기준 설계의 `baseline_document` 버전을 준다(새 승인으로 SUPERSEDED가 됐어도 기준이 다시 발행되기 전까지는 그 버전). 버전을 지정하면 승인된 적 있는 버전(APPROVED·SUPERSEDED)만 주고 DRAFT·REJECTED는 `not_found`다. 문서 화면용 칸 중 `document.preview_text`(작업 중 사본)·`latest_draft`는 빼고 `approved_version`은 현재 기준 버전으로 바꾸며, 조회 이력을 `target_kind=DOCUMENT_VERSION`으로 같은 트랜잭션에 남긴다.
- 등록하지 않은 것: 제안 재제출·거두기 도구(02-mcp-tools MCP-04 "이번 티켓에서 등록하지 않는다").
- `propose_design_change` 스키마에는 서비스가 지금 받는 칸만 싣는다. 명세의 수정안 요청 칸 `items[].proposed_text`·`meeting_id`는 서비스(`ProposalDtos`)에 아직 없어, 보내면 모르는 키로 거부된다.
- 출력 스키마(`outputSchema`)는 싣지 않는다. 실패 결과도 `structuredContent`(`{"error":…}`)를 쓰므로 성공 모양의 출력 스키마를 걸면 스키마를 검사하는 클라이언트에서 실패 결과가 어긋난다. 응답 모양은 02-mcp-tools가 기준이다.
- 시험: `McpToolsIntegrationTests`(실제 MCP 클라이언트 경유). 서비스 규칙은 각 서비스 시험에 있다.

## 8. 접근 제어·감사 기록 (-177)

**접근 제어는 서비스가 한다.** 도구 어댑터와 `McpToolHandler`는 권한을 다시 판정하지 않는다(08 계약 1절). 한 호출이 지나는 검사는 아래 셋이고, 처음 걸린 곳의 응답이 나간다(05 문서 참고 2 검사 순서).

| 단계 | 하는 곳 | 거부 |
| --- | --- | --- |
| 토큰 폐기·멤버십 종료·프로젝트 보관 | `AgentTokenAuthenticationFilter` → `StoredAgentTokenVerifier` (-175) | HTTP 401 (`token_revoked`), 감사 행 없음(도구까지 오지 않음) |
| 토큰 프로젝트 = 호출 프로젝트, 활성 멤버, 역할 | 서비스 첫 줄 `ProjectAccessChecker.requireMember` | 도구 실패 `not_found`·`forbidden` |
| 대상 객체 소속(작업·검증·기록·문서) | 서비스의 `…AndProjectId` 조회·소속 확인 | 도구 실패 `not_found` |

에이전트 토큰에는 프로젝트 말고 다른 권한 범위(scope)가 없다. 인수의 `project_id`는 토큰 프로젝트와 같은지만 본다.

**감사 기록.** `McpToolHandler.handle`의 `finally`에서 호출마다 `mcp_audit_log`에 한 줄 쓴다(`McpAuditEntry` → `McpAuditRecorder`).

| 칸 | 값 |
| --- | --- |
| `agent_token_id`·`account_id`·`project_id` | 토큰 주체 |
| `tool` | 도구 이름 |
| `target_ids` | 인수 중 `id`·`*_id`·`*_ids` 칸의 값만 `[{"field":"task_id","id":"12"}]`. 중첩 인수도 보고, 숫자 ID(십진 19자리 이하)가 아니면 버린다 — 토큰·키 같은 영숫자 값이 모르는 `*_id` 칸으로 들어와도 남지 않게. 최대 50개 |
| `result` | `OK` / `DENIED`(`AccessErrors`가 만든 거부 — `AccessDeniedApiException`) / `ERROR`(그 밖의 실패) |
| `deny_reason` (V38) | `DENIED`일 때 서버만 아는 사유의 종류(`not_member`·`task_not_in_project`·`packet_project_mismatch` 등). 로그 사유의 첫 낱말이라 번호가 없다 |
| `error_code` (V38) | 실패면 응답 `error.code`, 성공이면 NULL |
| `latency_ms`·`called_at` | 지연·시각 |

- **남기지 않는 것**: 인수 본문(요청 문장·기록 원문·제안 내용)·멱등 키·토큰 값·응답 본문(MCP-09, Jira -177 작업 4).
- 없는 ID와 다른 프로젝트 ID는 같은 404라 둘 다 `DENIED`다(존재를 숨기는 정책 그대로). 문서 ID(`get_document`)도 `AccessErrors`로 거부해 `DENIED` + `document_not_in_project`다(-322).
- 자기 프로젝트 안의 **업무 상태 404**는 거부가 아니라 `ERROR`로 남는다: 기준 미발행(`baseline_not_published`), 현재 기준에 그 문서 승인본 없음·지정 버전 없음·초안 버전(`get_document`의 `not_found`, MCP-02 "승인본 또는 지정 버전 없음"). 숨길 존재가 없어서다. 이렇게 둔 곳은 코드에 이유를 주석으로 남겼다(-322).
- **트랜잭션**: 업무는 서비스 안에서 이미 끝났다. 감사는 새 트랜잭션(REQUIRES_NEW)이라 업무가 롤백된 실패도 남는다. **감사 저장이 실패해도 도구 결과는 그대로 돌려준다**(전진 결정 — 이미 커밋된 변경을 실패로 알리면 에이전트가 다시 부른다). 그 호출은 `com.beez.mcp` 요청 줄과 `mcp audit write failed` 오류 로그로 추적한다.
- 감사 행은 고치지 않는다(V38 UPDATE 금지 트리거).
- 인증 실패(HTTP 401)·프로토콜 오류(없는 도구·세션)는 도구 처리 전에 끝나 감사 행이 없다. 요청 로그(`com.beez.access`)에만 남는다.

**이번에 하지 않은 것**: 감사 로그 조회 API(팀장 공개안 — REST 명세 없음), 권한 실패 응답의 `details`(명세 없음 — 지금 형식 유지), 보존 기간·정리.

시험: 결과 분류·저장 실패 처리는 `McpToolHandlerTest`, 대상 ID는 `McpAuditEntryTest`, 실제 MCP 연결의 거부·감사 행·위조 `result_cursor`는 `McpToolsIntegrationTests`.

## 9. 연결·식별·해제 (-178)

SDK 2.1.0에는 서버가 initialize를 받아 볼 훅이 없다(도구 필터도 transport context만 받는다). 그래서 **토큰 검증(mcpChain) 뒤, MCP 서블릿 앞**에 `McpSessionFilter`를 두고, 기록은 `AgentSessionService`가 `agent_session`(V2 테이블에 V41로 칸 추가)에 쓴다.

| 요청 | 하는 일 |
| --- | --- |
| 세션 ID 없는 POST(initialize) | 요청 본문과 **응답을 붙잡아 두었다가**, 응답에 새 `Mcp-Session-Id`가 있으면 한 행을 만든 뒤에 응답을 내보낸다(클라이언트가 세션 ID를 받기 전에 기록이 커밋되어 바로 이어지는 요청이 막히지 않는다). SDK는 initialize를 요청 스레드에서 JSON 한 번으로 응답한다. 행에는 세션 ID·토큰·`clientInfo.name/version`(200자로 자름)·`last_report_at`이 들어간다. 이때 **같은 계정·같은 프로젝트의 살아 있는 세션을 `REPLACED`로 끝낸다**(1인 1에이전트). 멤버십 행을 잠가(토큰 발급·팀원 제외와 같은 행) 동시 연결이 둘 다 살아남지 않게 하고, 잠근 뒤 멤버십이 끝났으면 기록하지 않는다. 기록이 실패하면 initialize는 500이다 |
| 세션 ID 있는 요청 | 기록이 없거나·다른 토큰의 세션이거나·이미 닫힌 세션이면 404 `not_found`(fail-closed). 기록이 없는 ID는 SDK까지 가지 않으므로 남의 세션과 없는 세션의 응답이 같다. `REPLACED`면 409 `agent_session_replaced`. 그 밖에는 `last_report_at`을 1분 단위로 갱신하고 SDK로 보낸다 |
| DELETE | 밀려난 세션이어도 SDK까지 보내 서버 메모리의 세션을 지우게 한다. 성공하면 `CLOSED`로 끝낸다. **Claude Code(2.1.318·2.1.319)는 `/exit`로 끝낼 때 DELETE를 보내지 않는다**(11-09 실측) — 이 경로를 타는 클라이언트는 명세대로 닫는 다른 클라이언트뿐이다 |

- **정한 것(전진 11-06, Q06)**: 단위는 토큰이 아니라 계정·프로젝트다. **나중 연결이 이긴다.**
  - 먼저 연결을 지키면, DELETE 없이 꺼진 클라이언트(Claude Code는 정상 종료도 이렇다)가 SDK 정리로 사라질 때까지(1~2시간) 다시 붙지 못한다.
  - 밀려난 쪽에 404를 주면 클라이언트가 조용히 다시 initialize해 두 클라이언트가 서로 끝없이 밀어낸다. 그래서 409로 끊는다.
  - 토큰 발급 한도 5개(-175)는 그대로 둔다. 토큰은 PC마다 둘 수 있지만, 동시에 살아 있는 연결은 하나다.
- 연결 시험은 MCP-15 `check_connection`이다(02-mcp-tools). 다른 도구와 같은 토큰 검증·`requireMember`를 거친다.
- 작업 절차 안내(AGT-04)는 initialize 응답의 `instructions`로 간다. 글은 토큰 발급 응답의 `claude_md_snippet`과 같은 `AgentTokenService.CLAUDE_MD_SNIPPET`이다.
- 토큰 폐기·멤버십 종료는 이 표가 아니라 토큰 검증이 매 요청 401로 막는다. 폐기된 토큰의 세션 행에는 `ended_at`이 찍히지 않는다. 연결 판정은 토큰의 `revoked_at`과 함께 본다.
- 세션 확인·기록이 DB 오류로 실패하면 500이다(오류 로그 `mcp session … failed`). 결속을 건너뛴 채 통과시키지 않는다(fail-closed) — 기록이 없는 세션을 통과시키면 결속이 기록 성공에 기댄다. 닫기(DELETE) 기록만은 실패해도 SDK 응답을 그대로 둔다(SDK는 이미 세션을 지웠다).
- 연결 기록을 읽는 곳 두 군데는 끝난 세션(`ended_at`)을 세지 않게 바꿨다.
  - 팀원 목록의 `agent_connected`: `ProjectMemberRepository.findAgentConnectedAccountIds`. **최근 2시간 안에 통신한 세션만** 연결로 본다 — DELETE 없이 꺼진 클라이언트·배포 재시작으로 사라진 세션은 끝남이 찍히지 않는데, SDK가 유휴 세션을 마지막 요청 후 1~2시간에 지우므로 그보다 오래 조용하면 세션이 남아 있지 않다.
  - 에이전트 작업 현황 MON-A05: `TaskStatusRepository.agentSessions`. 시간 창은 걸지 않았다 — 보고가 늦은 에이전트를 '확인 필요'로 보여야 하는 화면이라 판정은 -75·변준성과 맞춘다.

**남긴 것**

- **SDK 메모리의 밀려난 세션**: SDK에 서버 쪽에서 세션을 닫는 공개 API가 없다. 그래서 밀려난 세션은 요청이 409로 막힐 뿐, 메모리에서는 정리 주기(1시간)까지 남는다. 같은 토큰으로 initialize를 반복하면 메모리 세션이 쌓인다. DB의 살아 있는 세션은 계정·프로젝트당 하나다.
- **끝남이 찍히지 않는 세션**: Claude Code는 종료 때 DELETE를 보내지 않고, 오래 쉬어 SDK가 지운 세션도 마찬가지라 **보통의 연결 끝은 `ended_at`이 비어 있다.** `ended_at`이 찍히는 것은 사실상 `REPLACED`뿐이다. 그래서 '지금 연결됨'은 `ended_at`만으로 판정할 수 없고 시간 창이 필요하다.
  - 팀원 목록은 2시간 창으로 거른다.
  - MON-A05(`TaskStatusRepository.agentSessions`)는 시간 창이 없어, 끝낸 세션을 같은 사람이 다시 연결해 밀어내기 전까지 계속 보여 준다. 연결 현황(AGT-A05·A06)과 함께 -75에서 판정 기준을 정한다(전진 11-09).
- **요청마다 트랜잭션 하나 더**: 세션 확인이 토큰 검증과 따로 돈다. 둘을 한 트랜잭션으로 묶는 것은 후속이다.
- **SDK의 없는 세션 404 본문**: SDK는 `McpError` 예외를 통째로 직렬화해 스택 트레이스까지 내보낸다. 기록이 있는 세션을 SDK가 이미 지웠을 때(배포 재시작·유휴 정리)만 이 응답이 나간다.
- **`last_report_at`의 뜻**: 04-task-lifecycle의 보고 끊김(30분)은 이 칸을 "마지막 상태 보고"로 읽고, AgentConnection은 "마지막 통신 시각"으로 읽는다. 지금은 통신 시각을 쓴다. -75에서 맞춘다.
- **활동 기록의 세션**: `activity_event.agent_session_id`는 -333에서 채운다. 세션 필터가 확인한 연결 기록 ID를 요청 속성 → transport context → `CallerContext.agentSessionId`로 넘긴다. AGT-A04 연결 안내 REST API는 후속이다.

시험: `AgentConnectionIntegrationTests`(실제 MCP 클라이언트).

- 연결 → 확인 → 해제, 팀원 목록 연결 표시(2시간 창 포함)
- 같은 사람의 재연결(409, 팀원은 유지, MON-A05에 새 세션만)
- 남의 세션 이어 쓰기·끊기(404, 없는 세션과 같은 응답)
- 폐기 토큰(401)

실제 Claude Code 확인(11-09, 로컬 백엔드, 같은 계정의 토큰 2개): 연결하면 `client_name=claude-code`·버전이 기록되고, 두 번째 연결 순간 첫 연결이 `REPLACED`가 된다. 밀려난 쪽에는 409 문구가 그대로 보이고 저절로 다시 연결하지 않는다. 폐기 토큰은 호출·`/mcp` 재연결 모두 401이다(Authorization 헤더를 넣은 설정이라 OAuth로 넘어가지 않는다). `/exit`는 DELETE를 보내지 않아 행이 열린 채 남는다.
