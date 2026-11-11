# BEEZ MCP Tools

버전: 0.7 · 갱신일: 2026-11-09 · 개발 계약 초안

2026-10-28: 화면 수정안(v2) 요청을 반영했다(S15P31A108-218). 기준 v1 전에는 작업 계획을 받지 않는다. 기준 v1 뒤 문서 변경은 회의 결과를 포함해 모두 변경 제안 발행이라 get_design_changes 계약은 그대로다. 바뀐 부분은 '수정안 요청'으로 표시했다.

2026-11-09: 작업 조회 도구 [get_task](#mcp-16)(MCP-16)를 더했다(S15P31A108-335). 작업을 넘겨받은 새 에이전트가 현재 계획과 `lock_version`을 MCP만으로 읽고 다시 검증해 RESUME한다. 기존 도구의 요청·응답은 바뀌지 않았다.

Claude Code가 backend의 `/mcp`에 연결해 호출하는 도구를 정의한다. [공통 규약·권한·담당자](00-common.md)와 [동일 업무 REST 계약](01-backend-rest.md)을 참조한다.

## 연결과 인증

- 전송: MCP Streamable HTTP, 서버 경로 `/mcp`.
- 인증: `Authorization: Bearer {agent_token}`. 매 요청 토큰 폐기·현재 멤버십·객체 소속을 확인한다.
- 연결 설정: 서버가 반환한 `mcp_url`에 클라이언트에서 토큰을 설정하고 초기화·도구 조회로 시험한다. 사용자 계정·토큰·MCP 세션 ID는 서로 다른 식별자다.
- (S15P31A108-178 구현) 연결 기록: 초기화가 성공하면 MCP 세션 ID·토큰·클라이언트 이름(`clientInfo`)을 연결 기록(`agent_session`) 한 행으로 남긴다. 초기화 응답의 `instructions`는 작업 절차 지침(AGT-A01 `claude_md_snippet`과 같은 글)이다. 연결 시험은 [check_connection](#mcp-15)이다.
- (S15P31A108-178 구현) 1인 1에이전트(Q06): 한 계정이 한 프로젝트에 가진 살아 있는 연결은 하나다. 토큰이 달라도(다른 PC) 같은 사람이면 **나중 연결이 이긴다**. 밀려난 연결의 다음 요청은 HTTP 409 `{"error":{"code":"agent_session_replaced",…}}`다. 404로 주면 클라이언트가 다시 초기화해 두 클라이언트가 서로 밀어내므로 409로 끊는다. 클라이언트를 다시 시작하면 그쪽이 새 연결이 된다. 다른 팀원의 연결은 건드리지 않는다.
- (S15P31A108-178 구현) 세션은 만든 토큰에만 쓸 수 있다. 다른 토큰으로 남의 `Mcp-Session-Id`를 보내면(이어 쓰기·DELETE로 끊기) 없는 세션과 같은 HTTP 404 `{"error":{"code":"not_found",…}}`다. 클라이언트는 MCP 명세대로 다시 초기화한다.
- (S15P31A108-178 구현) 해제: 클라이언트가 DELETE `/mcp`로 닫으면 연결이 끝난다. 단 Claude Code는 종료할 때 DELETE를 보내지 않으므로(11-09 확인) 연결 여부는 통신 시각으로 판단한다. 토큰을 폐기(AGT-A03)하면 열려 있던 세션도 다음 요청부터 401 `token_revoked`다. 마지막 통신 시각(`last_report_at`)은 요청마다 1분 단위로 남는다. 팀원 목록의 `agent_connected`는 최근 2시간 안에 통신한 살아 있는 연결이 있을 때 true다. 연결 현황 목록(AGT-A05)과 작업 현황(MON-A05)도 같은 기준(끝나지 않았고 2시간 안에 통신)을 쓰고, IN_PROGRESS 작업을 맡은 채 30분 넘게 조용하면 AGT-A05가 `status_check_required`로 표시한다(S15P31A108-75).
- 세션·프로토콜 버전 협상·전송 봉투는 채택 SDK의 MCP 계약을 따른다. 아래 JSON은 봉투 안의 업무 인수·결과다.
- 프로젝트는 토큰에서 결정한다. `project_id`를 받으면 동일성만 확인한다.
- 변경 도구의 `idempotency_key`는 사용자·프로젝트·도구 범위에서 식별한다. 같은 키·같은 입력은 기존 결과, 다른 입력은 업무 충돌이다. 재요청도 인증·권한을 재검사한다.
- 조회 도구도 감사 대상이며 호출자·프로젝트·도구·시각·대상·결과·지연을 기록한다. 토큰·비밀번호·개인 대화 원문은 기록하지 않는다.

## 업무 오류

인증 실패는 전송 인증 단계에서 401로 거부한다. 업무 오류의 `error.code/message`(명세가 정한 오류는 `error.details`까지)는 REST와 같은 본문으로 SDK의 도구 실패 결과에 전달한다. 도구별 Error 표의 숫자는 REST 대응 상태 의미다. MCP 성공 payload에 가짜 HTTP 상태를 넣지 않는다. 구체 오류 봉투와 input/output schema 등록은 전진이 주관한다. 오류 봉투(`structuredContent.error`·`isError`)와 도구 등록 방법은 [MCP 서버 기반](../design/mcp-server-S15P31A108-176.md) 3·4절.

## 작업 호출 순서

1. `get_development_context`로 승인 설계·근거 패킷을 얻는다.
2. `upsert_task_plan`으로 계획과 정확한 참조 버전을 저장한다.
3. `validate_task_plan`을 호출하고 RUNNING이면 `get_validation`으로 같은 ID를 조회한다.
4. PASS 뒤 `report_task_state(START)`를 호출한다. 서버는 시작 직전 최신성·선행·점유를 다시 검사한다.
5. 구현 중 필요한 공유 판단은 `write_context`, 설계 변경은 `propose_design_change`로 제출한다.
6. 변경이 발행되면 다음 호출에서 확인하고 계획 수정·재검증 후 RESUME한다.
   - (S15P31A108-335) 작업을 넘겨받았거나 `revision_conflict`를 받았으면 `get_task`로 현재 계획과 `lock_version`을 다시 읽고, 그 값으로 `validate_task_plan`을 다시 요청한 뒤 RESUME한다.
7. 완료 결과·계획 대비 변경을 `report_task_state(COMPLETED)`로 보고한다.

`get_validation`은 시스템 플로우 F2의 조회 도구다. `retry_validation`은 `validate_task_plan`과 나눈 별도 도구다(변준성 확정, S15P31A108-271). 접근 제어(MCP-08)·감사(MCP-09)는 모든 도구에 적용하는 공통 처리다.

## 탐색

- [get_development_context](#mcp-01)
- [get_document](#mcp-02)
- [get_design_changes](#mcp-03)
- [propose_design_change](#mcp-04)
- [find_context](#mcp-05)
- [read_context](#mcp-06)
- [write_context](#mcp-07)
- [upsert_task_plan](#mcp-10)
- [validate_task_plan](#mcp-11)
- [get_validation](#mcp-13)
- [retry_validation](#mcp-14)
- [report_task_state](#mcp-12)
- [check_connection](#mcp-15)
- [get_task](#mcp-16)
- [list_my_tasks](#mcp-17)

---

<a id="mcp-01"></a>

## MCP-01. 개발 컨텍스트 조회

### `get_development_context`

개발 컨텍스트 조회 기능을 제공한다.

관련 요구사항: MCP-01, TRC-02·03  
담당: BE: 전진(도구·AI 연결 -399) / 업무 저장: 변준성 / AI: 박상은(검색·의존 설계·JSON) / FE: —  
상태: 구현(S15P31A108-399). BE가 현재 기준 스냅샷을 모아 AI-A14(`/v1/development-context-selections`)로 패킷을 구성하고 저장한다. 구성 방식은 [AI-A14](03-ai-internal.md#ai-a14)

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `request` | `string` | Y | 개발 요청. 2,000 Unicode 코드 포인트 이하(넘으면 잘라 저장하지 않고 400). 패킷에 저장되어 같은 프로젝트 멤버에게 공유되므로 비밀번호·토큰 등 비밀값을 넣지 않는다 |
| `project_id` | `ID` | N | 토큰 프로젝트와 일치해야 함 |
| `task_id` | `ID` | N | 관련 작업 |

아래는 도구 인수 객체다.

```json
{
  "request": "로그인 화면을 구현해 주세요.",
  "project_id": "101",
  "task_id": "501"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 패킷 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `task_id` | `ID` | N | 연결 작업 |
| `baseline_id` | `ID` | Y | 조회 당시 기준 |
| `request` | `string` | Y | 개발 요청 |
| `design_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 승인 설계 |
| `constraints` | `string[]` | Y | 제약 |
| `unresolved` | `string[]` | Y | 미확정 항목 |
| `related_tasks` | [RelatedTask[]](00-common.md#schema-relatedtask) | Y | 관련 작업 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `created_at` | `Time` | Y | 생성 시각 |
| `content_hash` | `string` | Y | 패킷 해시 |

```json
{
  "id": "701",
  "project_id": "101",
  "task_id": "501",
  "baseline_id": "401",
  "request": "로그인 화면을 구현해 주세요.",
  "design_items": [
    {
      "id": "311",
      "design_item_id": "301",
      "key": "API-001",
      "kind": "API_CONTRACT",
      "version_no": 1,
      "status": "APPROVED",
      "schema_version": 1,
      "content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "이메일·비밀번호 로그인"
      },
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    }
  ],
  "constraints": [
    "승인 계약을 변경하지 않는다."
  ],
  "unresolved": [],
  "related_tasks": [
    {
      "task_id": "502",
      "plan_revision_id": "512",
      "status": "IN_PROGRESS"
    }
  ],
  "sources": [
    {
      "document_id": "201",
      "document_version_id": "211",
      "version_no": 1,
      "location": "3. 계정 관리 / ACC-02"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z",
  "content_hash": "sha256:example-packet-hash"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 404 | `baseline_not_published` | 현재 승인 기준 없음 |
| 409 | `baseline_conflict` | 선택에 사용한 기준이 저장 전 변경됨. 패킷을 저장하지 않고 최신 기준으로 다시 구성한다 |
| 503 | `context_not_ready` | 문서 색인 미준비, 컨텍스트 구성 AI 미설정·실패·시간 초과, 스냅샷이 구성 상한 초과. 패킷을 저장하지 않는다. 검색을 마쳤는데 근거가 0건이면 오류가 아니라 정상 응답의 `unresolved`(`NO_MATCH`)로 표시한다 ([서비스 연결 계약](../design/08-mcp-service-contract.md), [AI-A14](03-ai-internal.md#ai-a14)) |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "baseline_not_published",
    "message": "현재 승인 기준 없음"
  }
}
```

#### 처리 규칙

- 토큰 프로젝트의 현재 승인 설계와 의존 항목·미확정·관련 작업을 읽는다.
- 불변 패킷과 참조 버전을 저장하고 반환한다. ID·출처·버전을 유지한다.
- 검색 실패와 근거 없음은 구분한다. 패킷은 계획 리비전이 참조한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-02"></a>

## MCP-02. 승인 문서 조회

### `get_document`

승인 문서 조회 기능을 제공한다.

관련 요구사항: MCP-02, DOC-03  
담당: BE: 전진(도구) / 오서현(문서) / AI: — / FE: —  
상태: 설계 초안

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 문서 |
| `version_no` | `integer` | N | 문서 버전. 생략 시 현재 승인본 |

아래는 도구 인수 객체다.

```json
{
  "document_id": "201",
  "version_no": 1
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document` | [Document](00-common.md#schema-document) | Y | 문서 |
| `version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | 원문 버전 |

```json
{
  "document": {
    "id": "201",
    "project_id": "101",
    "kind": "REQUIREMENTS",
    "title": "요구사항 명세서",
    "domain": "account",
    "approved_version": {
      "id": "211",
      "document_id": "201",
      "version_no": 1,
      "status": "APPROVED",
      "file_name": "requirements.md",
      "media_type": "text/markdown",
      "content_hash": "sha256:example-document-hash",
      "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
      "created_at": "2026-10-23T03:00:00Z"
    }
  },
  "version": {
    "id": "211",
    "document_id": "201",
    "version_no": 1,
    "status": "APPROVED",
    "file_name": "requirements.md",
    "media_type": "text/markdown",
    "content_hash": "sha256:example-document-hash",
    "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
    "created_at": "2026-10-23T03:00:00Z"
  }
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 404 | `not_found` | 승인본 또는 지정 버전 없음 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "not_found",
    "message": "승인본 또는 지정 버전 없음"
  }
}
```

#### 처리 규칙

- 생략 시 현재 baseline_document의 승인본만 반환한다.
- 지정 시 승인된 적 있는 버전(APPROVED·SUPERSEDED)만 반환한다. DRAFT·REJECTED는 `not_found`다. REST DOC-A02는 문서 검토용이라 이 제한을 두지 않는다.
- 조회 이력을 `context_access_log`에 남긴다(`target_kind=DOCUMENT_VERSION`).
- 바이너리는 파일 정보와 읽을 수 있는 추출 내용만 제공한다. 원문 변환 범위 Q07.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-03"></a>

## MCP-03. 발행 변경·작업 영향 조회

### `get_design_changes`

발행 변경·작업 영향 조회 기능을 제공한다.

관련 요구사항: MCP-03, NTF-04·05  
담당: BE: 전진(도구) / 변준성(변경·최신성) / AI: — / FE: —  
상태: 설계 초안

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `since_version` | `integer` | Y | 마지막으로 확인한 기준 번호. 최초 0 |
| `task_id` | `ID` | N | 작업 범위 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100. 영향 결과(`impact_updates`)에도 같은 크기 |
| `result_cursor` | `string` | N | 이전 응답의 `next_result_cursor`. 처음이면 생략 — 결과 없이 시작점만 받는다(지금부터) |

아래는 도구 인수 객체다.

```json
{
  "since_version": 0,
  "task_id": "501",
  "cursor": "page-token",
  "limit": 20,
  "result_cursor": "result-token"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | Y | 현재 기준 |
| `items` | [PublishedChange[]](00-common.md#schema-publishedchange) | Y | 발행 변경 이력 |
| `validation_validity` | [ValidationValidity[]](00-common.md#schema-validationvalidity) | Y | 호출자 작업의 검증 유효성 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 조회 cursor |
| `impact_updates` | [ImpactUpdate[]](00-common.md#schema-impactupdate) | Y | `result_cursor` 이후 정해진 발행 후 영향 분석 결과. 처음(생략)이면 빈 배열 |
| `result_has_more` | `boolean` | Y | 영향 결과가 더 있음 |
| `next_result_cursor` | `string` | Y | 다음에 보낼 `result_cursor` |

```json
{
  "current_baseline": {
    "id": "401",
    "version_no": 1,
    "created_at": "2026-10-23T03:00:00Z"
  },
  "items": [
    {
      "baseline": {
        "id": "401",
        "version_no": 1,
        "created_at": "2026-10-23T03:00:00Z"
      },
      "proposal_id": "71",
      "changed_items": [
        {
          "design_item_id": "301",
          "before_version_id": "311",
          "after_version_id": "312"
        }
      ],
      "affected_task_ids": [
        "501"
      ]
    }
  ],
  "validation_validity": [
    {
      "validation_id": "601",
      "status": "STALE",
      "reason": "참조 설계 버전이 변경되었습니다."
    }
  ],
  "has_more": false,
  "impact_updates": [
    {
      "result_seq": 7,
      "baseline": {
        "id": "401",
        "version_no": 1,
        "created_at": "2026-10-23T03:00:00Z"
      },
      "proposal_id": "71",
      "analysis_status": "COMPLETED",
      "evaluation_status": "COMPLETED",
      "candidates": [
        {
          "origin": "RULE",
          "task_id": "501",
          "design_item_id": "301",
          "member_id": "21"
        }
      ],
      "candidates_truncated": 0,
      "analyzed_at": "2026-10-23T03:01:00Z"
    }
  ],
  "result_has_more": false,
  "next_result_cursor": "result-token"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `invalid_version` | 음수 또는 현재보다 큰 기준 번호 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "invalid_version",
    "message": "음수 또는 현재보다 큰 기준 번호"
  }
}
```

#### 처리 규칙

- 발행된 승인 변경만 반환한다. pending 제안을 승인된 변경으로 표시하지 않는다.
- 페이지 마지막까지 읽은 뒤 기준 번호를 갱신한다. 실패를 변경 없음으로 처리하지 않는다.
- 작업을 지정하면 그 작업, 지정하지 않으면 호출자의 열린 작업 전체의 검증 현재성을 포함한다.
- (BE 구현, -221) 발행 후 영향 분석 결과는 발행 변경과 **따로** `result_cursor`로 이어 받는다. 결과가 정해질 때(완료·미평가·최종 실패) 프로젝트 안에서 1씩 오르는 결과 순번이 붙고, 순번은 커밋 순서대로 오르므로 "마지막으로 받은 순번 다음"만 읽어도 빠지는 결과가 없다. 재시도 대기·진행 중은 결과가 아니다.
  - 처음(`result_cursor` 생략)은 **지금부터**다. 이미 정해진 결과는 주지 않고 지금의 마지막 순번을 `next_result_cursor`로 준다. 과거 후보는 대시보드에서 본다.
  - `result_has_more`이면 `next_result_cursor`로 이어 부른다. 발행 변경(`has_more`·`next_cursor`)과 영향 결과는 각자 끝까지 읽는다. 받은 결과를 처리한 뒤 `next_result_cursor`를 저장한다. 같은 `result_seq`를 다시 받으면 같은 결과다.
  - `task_id`를 주면 그 작업이 후보에 든 결과만, 후보도 그 작업 것만 준다. `result_cursor`는 만든 작업 범위(작업 하나 / 프로젝트 전체)에서만 쓸 수 있다 — 다르면 400 `validation_failed`. 범위를 바꾸면 `result_cursor` 없이 다시 시작한다.
  - 후보는 규칙(RULE)·AI 둘 다, 같은 출처·같은 대상은 한 번만, 결과당 최대 10개(규칙 먼저·작업·항목 번호 순). 넘치면 `candidates_truncated`에 빠진 수를 주고 전체는 대시보드에서 본다.
  - 미평가(`NOT_EVALUATED`)·실패(`FAILED`)도 결과로 준다 — 영향 0건이 아니다. 후보가 없으므로 `task_id` 범위에는 나오지 않는다.
  - 후보는 승인된 설계가 아니고 작업 상태·검증을 바꾸지 않는다(도구 안내에도 적음). 직접 참조 영향(D5)은 기존대로 `affected_task_ids`·검증 STALE·WAITING(REVALIDATION)이다.
  - 이번에 하지 않은 것: 결과가 재분석으로 바뀔 때 빠진 후보 작업에 알리기(지금은 재분석 경로가 없어 결과가 한 번만 정해짐), 미완료·재시도 대기 상태 노출, 권한별 전체 후보 REST 페이지, 결과 보존 기간·오래된 cursor 처리. [14 설계 변경 영향 결과](../design/14-design-changes-impact-updates.md) 참고.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-04"></a>

## MCP-04. 설계 변경 제안

### `propose_design_change`

설계 변경 제안 기능을 제공한다.

195의 공용 업무 진입점은 `ProposalCommandService.create(CallerContext, projectId, ProposalDtos.Input, idempotencyKey)`다. MCP 어댑터는 검증한 토큰으로 CallerContext.agent를 구성하며 인수에서 계정·actor_type·토큰 ID를 신뢰하지 않는다. 서비스도 멤버십·프로젝트·토큰 폐기를 다시 검사한다. 도구 등록·MCP 전송은 전진 담당이며 195는 서비스 직접 호출까지 검증한다.

응답은 [ChangeProposal](00-common.md#schema-changeproposal)의 `key`, `revision`, `snapshot_hash` 및 해석된 원문을 포함한다. after_content 제출도 서버가 DRAFT 문서 버전과 색인 작업을 만들어 proposed_document_version_id를 반환한다(규칙은 CHG-A01과 같다). `items=[]`인 원문 단독 변경도 허용한다. Idempotency-Key는 프로젝트·계정·동작·토큰별 24시간 보존한다. 이미 제출된 내용은 잠기며 반려 후 같은 제안 계정만 `ProposalCommandService.resubmit`에 expected_revision/expected_snapshot_hash를 전달해 재제출할 수 있다. 거둔 제안에서 새로 생성할 때 선택 previous_proposal_id를 전달할 수 있으며 같은 프로젝트·작성 계정의 WITHDRAWN만 허용한다. 별도 재제출·거두기 MCP 도구는 이번 티켓에서 등록하지 않는다.

관련 요구사항: MCP-04, CHG-01·02  
담당: BE: 전진(도구) / 오서현(제안) / AI: 양지훈(영향 분석) / FE: —  
상태: 설계 초안

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `key` | `string` | Y | 제안 표시 키 |
| `revision` | `integer` | Y | 제출 리비전 |
| `snapshot_hash` | `string` | Y | 불변 제출 내용의 SHA-256 |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [ResolvedDocumentChange[]](00-common.md#schema-resolveddocumentchange) | Y | 해석된 원문·내용 해시·문서 버전(항상 반환) |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED\|WITHDRAWN` | Y | 제안 상태 |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |

```json
{
  "id": "71",
  "key": "CP-71",
  "revision": 1,
  "snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "project_id": "101",
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다.",
      "content_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
  ],
  "status": "PROPOSED",
  "proposer_account_id": "11",
  "actor_type": "HUMAN",
  "current_baseline_id": "401",
  "requires_refresh": false,
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_conflict` | 기준 변경 |
| 409 | `idempotency_conflict` | 같은 키에 다른 제안 입력 |
| 409 | `document_already_proposed` | 수정본이 다른 변경 제안에 연결됨 |
| 401 | `token_revoked` | 토큰 폐기·비활성 상태 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "baseline_conflict",
    "message": "기준 변경"
  }
}
```

#### 처리 규칙

- 현재 기준과 소속을 검사하고 제안만 생성한다.
- 에이전트의 팀장 소유 여부와 관계없이 이 도구로 승인·발행하지 않는다.
- 서버 REST CHG-A01과 같은 업무 검증을 사용한다.
- (수정안 요청) 에이전트가 올린 제안에는 agent{agent_session_id, name, member_id}를 채워 변경 제안 화면이 에이전트 이름과 연결한 팀원을 보인다. 항목은 proposed_text 문장 하나로 보낼 수 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-05"></a>

## MCP-05. 공유 작업 기록 검색

### `find_context`

공유 작업 기록 검색 기능을 제공한다.

관련 요구사항: MCP-05, TRC-05  
담당: BE: 전진(도구 계약) / 변준성(최소 저장·조회) / AI: 박상은(검색) / FE: —  
상태: 컨텍스트 계약 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `query` | `string` | Y | 검색어 |
| `task_id` | `ID` | N | 작업 |
| `kinds` | `string[]` | N | 기록 종류 필터 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

아래는 도구 인수 객체다.

```json
{
  "query": "로그인 오류 처리",
  "task_id": "501",
  "kinds": [
    "HANDOVER",
    "DECISION"
  ],
  "cursor": "page-token",
  "limit": 20
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ContextRecordSummary[]](00-common.md#schema-contextrecordsummary) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "801",
      "root_id": "801",
      "revision_no": 1,
      "task_id": "501",
      "kind": "HANDOVER",
      "summary": "로그인 UI 연결 완료",
      "author_account_id": "11",
      "visibility": "SHARED",
      "is_latest_revision": true,
      "references_current": true,
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "에이전트 토큰이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 권한 필터를 검색 전에 적용한다. 타인의 PRIVATE 기록은 요약·건수에도 드러내지 않는다.
- 최신 기록 여부와 참조 설계 현재성을 구분한다. 패킷 검색 포함 여부 Q10.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-06"></a>

## MCP-06. 패킷·기록 상세

### `read_context`

패킷·기록 상세 기능을 제공한다.

관련 요구사항: MCP-06, TRC-03·05  
담당: BE: 전진(도구) / 변준성(업무) / AI: — / FE: —  
상태: 컨텍스트 계약 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `PACKET\|RECORD` | Y | 조회 대상 종류 |
| `id` | `ID` | Y | 패킷 또는 기록 ID |

아래는 도구 인수 객체다.

```json
{
  "type": "PACKET",
  "id": "701"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `PACKET\|RECORD` | Y | 대상 종류 |
| `packet` | [ContextPacket](00-common.md#schema-contextpacket) | N | type=PACKET 필수 |
| `record` | [ContextRecord](00-common.md#schema-contextrecord) | N | type=RECORD 필수 |

```json
{
  "type": "PACKET",
  "packet": {
    "id": "701",
    "project_id": "101",
    "task_id": "501",
    "baseline_id": "401",
    "request": "로그인 화면을 구현해 주세요.",
    "design_items": [
      {
        "id": "311",
        "design_item_id": "301",
        "key": "API-001",
        "kind": "API_CONTRACT",
        "version_no": 1,
        "status": "APPROVED",
        "schema_version": 1,
        "content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "이메일·비밀번호 로그인"
        },
        "source": {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      }
    ],
    "constraints": [
      "승인 계약을 변경하지 않는다."
    ],
    "unresolved": [],
    "related_tasks": [
      {
        "task_id": "502",
        "plan_revision_id": "512",
        "status": "IN_PROGRESS"
      }
    ],
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "created_at": "2026-10-23T03:00:00Z",
    "content_hash": "sha256:example-packet-hash"
  }
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 404 | `not_found` | 객체 또는 접근 권한 없음 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "not_found",
    "message": "객체 또는 접근 권한 없음"
  }
}
```

#### 처리 규칙

- type과 일치하는 payload 하나만 반환한다.
- 다른 프로젝트 또는 권한 없는 PRIVATE는 not_found다.
- 당시 불변 패킷·리비전을 최신 내용으로 덮어쓰지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-07"></a>

## MCP-07. 공유 작업 기록 작성·수정

### `write_context`

공유 작업 기록 작성·수정 기능을 제공한다.

관련 요구사항: MCP-07, TRC-05  
담당: BE: 전진(도구 계약) / 변준성(업무) / AI: 박상은(맥락 정책) / FE: —  
상태: 컨텍스트 계약 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `task_id` | `ID` | N | 연결 작업 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 기록 종류 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `summary` | `string` | Y | 검색용 공유 요약 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "task_id": "501",
  "plan_revision_id": "511",
  "source_packet_id": "701",
  "kind": "HANDOVER",
  "visibility": "SHARED",
  "summary": "로그인 UI 연결 완료",
  "detail": "오류 응답은 승인된 message 필드로 표시합니다.",
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ],
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 리비전 ID |
| `root_id` | `ID` | Y | 최초 리비전 ID |
| `revision_no` | `integer` | Y | 리비전 번호 |
| `task_id` | `ID` | N | 관련 작업 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 종류 |
| `summary` | `string` | Y | 공유 요약 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `is_latest_revision` | `boolean` | Y | 최신 기록 리비전 |
| `references_current` | `boolean` | Y | 참조 설계의 현재성 |
| `created_at` | `Time` | Y | 작성 시각 |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

```json
{
  "id": "801",
  "root_id": "801",
  "revision_no": 1,
  "task_id": "501",
  "kind": "HANDOVER",
  "summary": "로그인 UI 연결 완료",
  "author_account_id": "11",
  "visibility": "SHARED",
  "is_latest_revision": true,
  "references_current": true,
  "created_at": "2026-10-23T03:00:00Z",
  "plan_revision_id": "511",
  "source_packet_id": "701",
  "detail": "오류 응답은 승인된 message 필드로 표시합니다.",
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ]
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 같은 이전 리비전의 후속 기록 존재 |
| 409 | `idempotency_conflict` | 같은 idempotency_key로 다른 입력 전송. 같은 입력이면 처음 결과를 반환 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "같은 이전 리비전의 후속 기록 존재"
  }
}
```

#### 처리 규칙

- 수정은 이전 본인 리비전을 참조하는 새 기록이다. 같은 previous_id 경합은 거절한다.
- 사용자·프로젝트·작성 토큰은 인증 주체에서 결정한다.
- DECISION도 공유 판단일 뿐 승인 설계가 아니다. 계획 변경은 upsert_task_plan을 사용한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-10"></a>

## MCP-10. 작업 계획 등록·수정

### `upsert_task_plan`

작업 계획 등록·수정 기능을 제공한다.

관련 요구사항: MCP-10, TSK-01~04  
담당: BE: 전진(도구) / 변준성(업무) / AI: — / FE: —  
상태: 작업·검증 담당자 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `plan` | [TaskPlanInput](00-common.md#schema-taskplaninput) | Y | 작업 계획 |
| `task_id` | `ID` | N | 수정할 작업. 최초 생략 |
| `expected_revision_no` | `integer` | N | 수정 시 필수 현재 계획 번호 |
| `expected_lock_version` | `integer` | N | 수정 시 필수 작업 잠금 번호 |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "plan": {
    "title": "로그인 화면 구현",
    "goal": "승인 계약대로 로그인 화면을 연결한다.",
    "included_scope": [
      "로그인 폼과 오류 표시"
    ],
    "excluded_scope": [
      "회원가입"
    ],
    "approach": "승인 API 계약을 사용한다.",
    "completion_criteria": [
      "로그인 성공 시 프로젝트 목록으로 이동"
    ],
    "dependency_task_ids": [],
    "design_refs": [
      {
        "design_item_id": "301",
        "design_item_version_id": "311",
        "relation": "IMPLEMENTS"
      }
    ],
    "unlinked": [],
    "no_contract_change": true,
    "context_packet_id": "701"
  },
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | N | 담당 멤버. (수정안 요청 · 11-04 사용자 결정) 담당자가 나가거나 제외돼 미배정이면 생략(MEM-A09·A10) |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "READY",
  "lock_version": 1,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "NOT_RUN",
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 리비전 경합 |
| 409 | `dependency_cycle` | 선행 순환 |
| 409 | `idempotency_conflict` | 같은 idempotency_key로 다른 입력 전송. 같은 입력이면 처음 결과를 반환 |
| 409 | `baseline_missing` | 기준 v1 발행 전 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "리비전 경합"
  }
}
```

#### 처리 규칙

- 생성 시 본인 작업을 만들고 수정 시 본인 담당·현재 계획/상태를 검사한다.
- 수정은 불변 새 리비전이다. 진행 중 수정은 STALE·WAITING(REVALIDATION)이며 점유 유지다.
- (BE 구현, -162) 위 무효화는 검증 입력(제목·접근 방식을 뺀 계획 내용)이 바뀐 수정에만 적용한다. 규칙·가짜 검증에서 제목만 바꾸는 수정은 검증을 유지하고 새 리비전으로 이어 받으므로, 쥐고 있던 `validation_id` 로 그대로 `report_task_state(START·RESUME)` 할 수 있다(응답 `task.validation_status` 로 확인). 실제 검색 검증은 제목·접근 방식·패킷이 바뀌면 재검증한다. 완료 보고(delta)의 DELTA 검증이 아직 도는 동안은 수정이 `400 invalid_transition` 이다 — 결과를 기다리거나 RESUME. 결과가 보완 필요·검토 필요·오류로 끝났으면 수정할 수 있다. 규칙은 REST TSK-A04·[04 §2.5](../design/04-task-lifecycle.md)와 같다.
- design_refs는 항목당 가장 강한 관계 하나로 확정한다. 입력·정규화 세부 Q02.
- (수정안 요청) 기준 v1 발행 전에는 409 baseline_missing이다(REST TSK-A01·구현과 같음).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-11"></a>

## MCP-11. 작업 검증 접수

### `validate_task_plan`

작업 검증 접수 기능을 제공한다.

관련 요구사항: MCP-11, VAL-01·02  
담당: BE: 전진(도구) / 변준성(실행·상태·저장) / AI: 양지훈(분석) / FE: —  
상태: BE 업무 서비스 구현 반영안 (S15P31A108-159, `ValidationMcpService.validateTaskPlan`). 도구 등록·어댑터 연결은 -179

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 본인 작업 |
| `plan_revision_id` | `ID` | Y | 현재 계획 |
| `expected_lock_version` | `integer` | Y | 현재 작업 잠금 번호 |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "task_id": "501",
  "plan_revision_id": "511",
  "expected_lock_version": 1,
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 접수 응답에서 기존 검증을 재사용했는지 여부. 현재는 규칙 전용 PASS만 대상이며 조회·재시도 응답은 false |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "RUNNING",
  "current_attempt": 1,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `invalid_transition` | 완료·취소된 작업 (BE 구현, -159) |
| 409 | `revision_conflict` | 현재 계획 아님 |
| 409 | `validation_attempt_limit` | 의미 분석이 필요한데 같은 작업·같은 입력으로 실제 검증 AI를 정한 횟수(기본 3)만큼 부름. 재시도(MCP-14)와 같은 수 (BE 구현, -161) |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "현재 계획 아님"
  }
}
```

#### 처리 규칙

- 검증을 관리하고 RUNNING이면 동일 validation_id를 get_validation으로 조회한다.
- 규칙으로 즉시 완료할 수 있으며 판정은 [공통 6절 판정표](00-common.md#검증시작)를 따른다. 빈 입력은 NEEDS_INPUT, 명확한 계약 불일치·CHANGES 점유는 NEEDS_REVIEW다. AI가 finding을 내지 않은 것만으로는 PASS가 아니다.
- AI 실패는 ERROR다. 규칙 finding을 AI가 해제하지 못한다.
- (BE 구현, -159) 접수 규칙·판정·큐·워커는 REST [VAL-A01](01-backend-rest.md#val-a01) 처리 규칙과 같다. 도구 결과에는 200/202 구분이 없고 `status`(RUNNING 이면 큐에 들어감)로 본다. `idempotency_key`는 계정·프로젝트·동작 범위로 24시간 보존하고 같은 키·다른 입력은 409 `idempotency_conflict`, 재요청도 현재 담당자인지 확인한다(공통 5절).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-13"></a>

## MCP-13. 검증 결과 조회

### `get_validation`

검증 결과 조회 기능을 제공한다.

관련 요구사항: 시스템 플로우 F2, VAL-02  
담당: BE: 전진(도구) / 변준성(조회) / AI: — / FE: —  
상태: 작업·검증 담당자 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `validation_id` | `ID` | Y | 검증 ID |

아래는 도구 인수 객체다.

```json
{
  "validation_id": "601"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 접수 응답에서 기존 검증을 재사용했는지 여부. 현재는 규칙 전용 PASS만 대상이며 조회·재시도 응답은 false |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "PASS",
  "current_attempt": 1,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z",
  "finished_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "에이전트 토큰이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회로 새로운 실행을 만들지 않는다. RUNNING·STALE·ERROR를 정확히 반환한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-14"></a>

## MCP-14. 검증 실행 재시도

### `retry_validation`

검증 실행 재시도 기능을 제공한다.

관련 요구사항: VAL 재시도 계약  
담당: BE: 전진(도구) / 변준성(재시도) / AI: 양지훈(실패 정보) / FE: —  
상태: 도구명·분리 확정 — 별도 도구 `retry_validation`(변준성, S15P31A108-271 리뷰: 받는 조건(ERROR·전부 RETRY·입력 그대로)과 오류 코드 의미가 새 검증과 달라 합치지 않는다). BE 업무 서비스 구현 반영안 (S15P31A108-161, `ValidationMcpService.retryValidation`) — 도구 연결 S15P31A108-271 (`RetryValidationTool`)

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `validation_id` | `ID` | Y | 현재 실패 attempt의 ERROR finding이 RETRY인 ERROR 검증 |
| `expected_attempt` | `integer` | Y | 현재 시도 (1 이상). 같은 값으로 다시 보내면 새 시도를 만들지 않는다 |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "validation_id": "601",
  "expected_attempt": 1,
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 접수 응답에서 기존 검증을 재사용했는지 여부. 현재는 규칙 전용 PASS만 대상이며 조회·재시도 응답은 false |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "RUNNING",
  "current_attempt": 2,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `invalid_transition` | ERROR가 아니거나(이미 재실행 중 포함) 현재 실패 실행의 오류가 RETRY 대상이 아님. 완료·취소된 작업, 같은 갈래(DELTA / 계획 검증)의 더 최근 검증이 있음 |
| 409 | `revision_conflict` | 입력·참조 변경. `expected_attempt`가 현재 시도도, 현재 시도 - 1(재전송)도 아님 |
| 409 | `validation_attempt_limit` | 같은 작업·같은 입력으로 실제 검증 AI를 정한 횟수(기본 3)만큼 부름 (REST VAL-A01 처리 규칙) |
| 409 | `idempotency_conflict` | 같은 `idempotency_key`에 다른 인수 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "invalid_transition",
    "message": "현재 실패 실행이 재시도 대상이 아니거나 이미 재실행 중입니다."
  }
}
```

#### 처리 규칙

- 현재 실패 attempt의 ERROR finding이 RETRY인 경우에만 같은 validation의 새 run을 접수한다. 과거 실행의 RETRY finding으로 허용하지 않는다.
- 현재 실행에 HUMAN_REVIEW 오류가 하나라도 있으면 invalid_transition으로 거부한다. 근거 부족·미지원 내용은 보완한 뒤 새 검증을 요청한다. REST와 같은 서비스 규칙을 적용한다.
- STALE·입력 변경은 새 검증을 요청한다.
- 기존 attempt·lease의 늦은 결과는 반영하지 않는다.
- (BE 구현, -161) REST VAL-A04와 같은 서비스(`ValidationRetryService`)다 — 순서·입력 확인·횟수 제한·중복 요청 규칙은 VAL-A04 처리 규칙을 따른다. `idempotency_key`가 같고 인수가 같으면 처음 결과를 그대로 돌려주고(그 사이 담당자가 바뀌었으면 거부), 키가 달라도 `expected_attempt`가 현재 시도 - 1이면(바로 앞 요청의 재전송) 새 시도를 만들지 않고 지금 검증을 돌려준다. 워커가 검색할 때 쓰는 요청자는 다시 돌린 에이전트(토큰)로 바뀐다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-12"></a>

## MCP-12. 작업 상태 보고

### `report_task_state`

작업 상태 보고 기능을 제공한다.

관련 요구사항: MCP-12, AGT-07  
담당: BE: 전진(도구) / 변준성(전이·점유) / AI: — / FE: —  
상태: 작업·검증 담당자 검토

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 본인 작업 |
| `event` | `START\|RESUME\|WAIT\|COMPLETED\|CANCEL` | Y | 전이 이벤트 |
| `plan_revision_id` | `ID` | Y | 현재 계획 |
| `expected_lock_version` | `integer` | Y | 조회한 lock_version |
| `validation_id` | `ID` | N | START/RESUME 필수 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY` | N | WAIT이면 필수. WAIT는 IN_PROGRESS에서만 가능. REVALIDATION은 시스템 전용이라 지정하면 400 invalid_transition |
| `summary` | `string` | N | 진행/완료 요약 |
| `delta` | `object[]` | N | 완료 시 계획 대비 변경. 있으면 WAITING(REVALIDATION)으로 두고 delta 검증이 PASS하면 서버가 COMPLETED로 전이 |
| `completion_checks` | [CompletionCheck[]](00-common.md#schema-completioncheck) | N | 완료 기준 확인 |
| `changed_design_item_ids` | `ID[]` | N | COMPLETED에서만. 실제로 바꾼 설계 항목(최대 200). 계획과 대조해 응답 `completion_review`에 범위 이탈 후보를 보여 준다(완료는 막지 않음). 보내지 않으면 범위를 판단하지 않는다. 다른 이벤트에 보내거나 이 프로젝트에 없는 항목이면 400 `validation_failed` |
| `idempotency_key` | `string` | Y | 같은 업무 요청의 재시도 식별값 |

아래는 도구 인수 객체다.

```json
{
  "task_id": "501",
  "event": "START",
  "plan_revision_id": "511",
  "expected_lock_version": 1,
  "validation_id": "601",
  "idempotency_key": "request-101"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task` | [TaskDetail](00-common.md#schema-taskdetail) | Y | 처리된 작업 |
| `changes` | [DesignChanges](00-common.md#schema-designchanges) | N | 관련 변경과 유효성 요약. 기준 설계가 한 번도 없으면 생략 |
| `changes_since_version` | `integer` | N | `changes`를 계산한 기준 번호. `changes`가 있으면 반환 |

```json
{
  "task": {
    "id": "501",
    "key": "TASK-001",
    "project_id": "101",
    "assignee_member_id": "21",
    "status": "IN_PROGRESS",
    "lock_version": 1,
    "current_plan_revision": {
      "id": "511",
      "task_id": "501",
      "revision_no": 1,
      "plan": {
        "title": "로그인 화면 구현",
        "goal": "승인 계약대로 로그인 화면을 연결한다.",
        "included_scope": [
          "로그인 폼과 오류 표시"
        ],
        "excluded_scope": [
          "회원가입"
        ],
        "approach": "승인 API 계약을 사용한다.",
        "completion_criteria": [
          "로그인 성공 시 프로젝트 목록으로 이동"
        ],
        "dependency_task_ids": [],
        "design_refs": [
          {
            "design_item_id": "301",
            "design_item_version_id": "311",
            "relation": "IMPLEMENTS"
          }
        ],
        "unlinked": [],
        "no_contract_change": true,
        "context_packet_id": "701"
      },
      "created_at": "2026-10-23T03:00:00Z",
      "created_by": "11"
    },
    "validation_status": "PASS",
    "validation": {
      "id": "601",
      "task_id": "501",
      "plan_revision_id": "511",
      "baseline_id": "401",
      "status": "PASS",
      "current_attempt": 1,
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "related_task_refs": [
        {
          "task_id": "502",
          "plan_revision_id": "512"
        }
      ],
      "findings": [],
      "cache_hit": false,
      "created_at": "2026-10-23T03:00:00Z",
      "finished_at": "2026-10-23T03:00:00Z"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "updated_at": "2026-10-23T03:00:00Z",
    "started_validation_id": "601"
  },
  "changes": {
    "current_baseline": {
      "id": "401",
      "version_no": 1,
      "created_at": "2026-10-23T03:00:00Z"
    },
    "items": [
      {
        "baseline": {
          "id": "401",
          "version_no": 1,
          "created_at": "2026-10-23T03:00:00Z"
        },
        "proposal_id": "71",
        "changed_items": [
          {
            "design_item_id": "301",
            "before_version_id": "311",
            "after_version_id": "312"
          }
        ],
        "affected_task_ids": [
          "501"
        ]
      }
    ],
    "validation_validity": [
      {
        "validation_id": "601",
        "status": "STALE",
        "reason": "참조 설계 버전이 변경되었습니다."
      }
    ],
    "has_more": false
  },
  "changes_since_version": 1
}
```

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 참조 설계 변경 |
| 409 | `revision_conflict` | 계획·상태 경합 |
| 409 | `dependency_not_completed` | 선행 미완료 |
| 409 | `task_conflict` | 점유 충돌 |
| 409 | `validation_not_passed` | 유효 PASS 없음 |
| 409 | `idempotency_conflict` | 같은 idempotency_key로 다른 입력 전송. 같은 입력이면 처음 결과를 반환 |

업무 실패 내용 예시다. 아래 객체를 SDK의 도구 실패 결과로 전달한다. 표의 업무 상태는 REST 대응 의미이며 HTTP 성공 여부를 뜻하지 않는다.

```json
{
  "error": {
    "code": "version_conflict",
    "message": "참조 설계 변경"
  }
}
```

#### 처리 규칙

- START/RESUME는 현재 계획의 PASS·참조 현재성·선행 완료·점유를 잠금 아래 검사한다.
- 같은 task/validation START는 기존 결과다. 이 확인을 expected_lock_version 검사보다 먼저 한다. WAIT·CANCEL·COMPLETED 규칙은 REST TSK-A07과 같다.
- 완료 델타가 있으면 보고를 보존하고 WAITING(REVALIDATION)으로 전환한다. delta 검증이 PASS하면 서버가 COMPLETED로 전이한다.
- (BE 구현, -336) START·RESUME이 성공하면(같은 검증으로 다시 보낸 START 포함) 서버는 이 호출의 연결(`Mcp-Session-Id`)에 그 작업을 "지금 하는 작업"으로 적는다. 그 뒤 이 연결의 통신이 그 작업의 보고로 쳐져, 상태 전이 없이 일하는 동안에도 팀장 취소의 "보고 끊김"(04 §4.1)으로 판정되지 않고, 에이전트 작업 현황(MON-A05)의 작업 칸이 채워진다. 한 연결은 작업 하나만 가리킨다 — 다른 작업을 시작하면 옮겨 간다. 다시 연결하면(initialize) 새 연결이 같은 사람의 바로 이전 연결이 하던 작업을, 그 작업이 아직 그 사람이 맡은 진행 중·대기 작업이면 이어 받는다 — 재시작한 에이전트가 START 를 다시 보내지 않아도 된다.
- (BE 구현, -269) COMPLETED에 `changed_design_item_ids`를 실으면 결과 `task.completion_review`에 계획과 규칙으로 대조한 범위 이탈 후보가 나온다([CompletionReview](00-common.md#schema-completionreview)). 표시만 하고 완료를 막지 않는다. 코드 품질을 보증하지 않는다. 규칙은 REST TSK-A07과 같다.
- `changes`는 그 작업의 현재 계획을 세운 시점의 기준 설계 이후 발행된 변경과 그 작업 검증의 유효성이다. 기준 설계가 한 번도 없으면 생략한다([서비스 연결 계약](../design/08-mcp-service-contract.md), S15P31A108-166).
- `changes.has_more`이면 [MCP-03](#mcp-03)을 `since_version=changes_since_version`, 같은 `task_id`, `cursor=changes.next_cursor`로 불러 이어 본다. 이 이어 보기에는 since=0이어도 처음 연결 규칙(최근 1건)을 적용하지 않는다.
- (수정안 요청) summary는 AgentWork.last_summary로 HUD·대시보드에 원문 그대로 보인다. AI로 다시 요약하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-15"></a>

## MCP-15. 연결 확인

### `check_connection`

연결 시험 도구. 이 토큰·세션이 누구로 어느 프로젝트에 붙었는지 돌려준다. 아무것도 바꾸지 않는다.

관련 요구사항: AGT-05  
담당: BE: 전진  
상태: 구현(S15P31A108-178)

#### 인증

에이전트 토큰. 다른 도구와 같은 토큰 검증·멤버십 검사를 거친다. 그래서 이 도구가 성공하면 업무 도구도 같은 조건으로 부를 수 있다.

#### Request

받는 칸이 없다. 모르는 키는 `validation_failed`다.

```json
{}
```

#### Response

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `connected` | `boolean` | Y | 항상 true. 실패는 401 또는 도구 실패 결과로 온다 |
| `project_id` | `ID` | Y | 토큰의 프로젝트 |
| `account_id` | `ID` | Y | 토큰 소유 계정 |
| `member_id` | `ID` | Y | 그 프로젝트의 멤버 ID |
| `agent_token_id` | `ID` | Y | 토큰 ID |
| `token_label` | `string` | N | 토큰 이름. 이름 없는 토큰이면 생략 |
| `session_id` | `string` | Y | MCP 세션 ID(`Mcp-Session-Id`) |
| `agent_session_id` | `ID` | N | 연결 기록 ID. 연결 현황(AGT-A06)과 활동의 `agent_session_id`에 쓰인다. 기록이 없으면 생략 |
| `client_name` | `string` | N | 초기화 때 클라이언트가 보낸 이름(예: `claude-code`). 클라이언트가 보낸 값 그대로다 |
| `client_version` | `string` | N | 클라이언트 버전 |
| `connected_at` | `Time` | N | 이 연결이 열린 시각 |

```json
{
  "connected": true,
  "project_id": "101",
  "account_id": "7",
  "member_id": "21",
  "agent_token_id": "41",
  "token_label": "개발 PC",
  "session_id": "3f0c…",
  "agent_session_id": "51",
  "client_name": "claude-code",
  "client_version": "2.1.0",
  "connected_at": "2026-11-06T05:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 없거나 모르는 토큰 |
| 401 | `token_revoked` | 폐기·멤버십 종료·프로젝트 삭제·탈퇴 뒤의 토큰 |
| 404 | `not_found` | 다른 토큰이 연 세션 ID를 보냄(전송 단계) |
| 409 | `agent_session_replaced` | 같은 계정이 이 프로젝트에 새로 연결해 이 세션은 끊김(전송 단계) |

#### 처리 규칙

- 401·404·409는 전송 단계의 HTTP 응답이라 도구 결과가 아니다. 감사 행도 없다.
- 호출은 다른 도구처럼 `mcp_audit_log`에 남는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-16"></a>

## MCP-16. 작업 조회

### `get_task`

작업의 현재 상태·잠금 번호·현재 계획을 읽는 조회 도구다. 아무것도 바꾸지 않는다. (S15P31A108-335) 작업을 넘겨받은 새 에이전트가 `validate_task_plan`·`report_task_state`에 넣을 `expected_lock_version`과 현재 계획을 MCP만으로 얻게 한다. 이 도구가 생기기 전에는 `revision_conflict` 문구를 읽거나 웹(TSK-A03)에서 봐야 했다.

관련 요구사항: TSK-01~03, AC-11  
담당: BE: 전진(도구) / 변준성(조회) / AI: — / FE: —  
상태: 구현(S15P31A108-335)

#### 인증

`AGENT`. 토큰의 현재 멤버십·프로젝트·대상 소속을 매 호출 검사한다. REST TSK-A03과 같다. 담당자가 아니어도 같은 프로젝트 멤버면 읽는다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 작업 ID |

아래는 도구 인수 객체다.

```json
{
  "task_id": "501"
}
```

#### Response

도구 성공 결과의 업무 payload. MCP 전송 봉투와 분리한다. REST TSK-A03 응답과 같은 [TaskDetail](00-common.md#schema-taskdetail)이다. 칸 정의는 그 스키마 하나로 관리하고 여기서 다시 적지 않는다. `validation`(현재 계획의 가장 최근 검증)·`delta_validation_id`·`completion_review`도 TSK-A03과 같은 조건으로 실린다. 예시는 [MCP-10 Response](#mcp-10)와 같은 모양이다.

에이전트가 이어 갈 때 쓰는 칸은 `lock_version`(→ `expected_lock_version`), `current_plan_revision.id`(→ `plan_revision_id`), `current_plan_revision.revision_no`(→ 계획 수정 때 `expected_revision_no`), `current_plan_revision.plan`(현재 계획 본문)이다.

#### Error

| 업무 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 에이전트 토큰이 유효하지 않음 |
| 404 | `not_found` | 작업이 없거나 다른 프로젝트 작업. 공통 거부 문구로 응답하고 감사에는 `DENIED`(`task_not_in_project`)로 남는다 |
| 400 | `validation_failed` | `task_id` 누락·형식 오류, 모르는 인수 키 |

#### 처리 규칙

- 조회로 상태·검증·잠금 번호를 바꾸지 않는다.
- 인계 뒤 순서: `get_task` → (계획을 고칠 때만 `upsert_task_plan`) → 응답의 `lock_version`과 `current_plan_revision.id`로 `validate_task_plan` → PASS면 `report_task_state(RESUME)`. 다른 도구에서 `revision_conflict`를 받았을 때도 `get_task`로 다시 읽는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mcp-17"></a>

## MCP-17. 내 작업 목록

`list_my_tasks`는 토큰이 연결된 프로젝트에서 현재 멤버가 담당하는 열린 작업을 조회한다. 대상 상태는 DRAFT, READY, IN_PROGRESS, WAITING이다. 프로젝트나 담당자 ID를 인수로 받지 않는다.

인수는 선택적인 `cursor`와 `limit`이다. 응답은 `items`에 [TaskDetail](00-common.md#schema-taskdetail) 목록을 담고, `has_more`와 선택적 `next_cursor`를 반환한다.

목록에서 선택한 작업의 `id`로 `get_task`를 호출한다. 구현 계획을 보완할 때 `upsert_task_plan`에 같은 `task_id`와 현재 리비전·잠금 번호를 넣는다. `validate_task_plan`에서 PASS를 받은 뒤 시작하거나 재개한다.

시작 또는 재개가 `task_conflict`로 차단되면 현재 작업을 다시 읽고 새 `idempotency_key`로 검증을 요청한다. 검증에서 저장한 차단 충돌은 기존 충돌 회의 흐름으로 이어진다.
