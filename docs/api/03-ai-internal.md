# BEEZ AI Internal API

버전: 0.2 · 갱신일: 2026-10-23 · 개발 계약 초안

backend가 FastAPI ai 서비스에 요청하는 내부 계약이다. [공통 규약·담당자](00-common.md)와 [서버 REST 계약](01-backend-rest.md)을 참조한다.

## 통신·책임

- Base URL: backend의 `AI_BASE_URL` 설정. 로컬 기본값 `http://localhost:8000`.
- 인증: `Authorization: Bearer {AI_INTERNAL_TOKEN}`. 외부 웹·에이전트가 직접 호출하지 않는다.
- `X-Request-Id`는 추적용이며 `request_key`는 분석 요청·실제 LLM 사용량의 중복 식별을 연결한다. 실제 모델 재시도는 별도 호출 key를 기록한다.
- 입력에는 backend가 권한 검사한 최소 원문·승인 항목·작업만 보낸다. 사용자 비밀번호·에이전트 토큰·외부 LLM 자격증명은 포함하지 않는다.
- AI는 텍스트·구조화 후보·임베딩·실제 사용량만 반환한다. 최종 ID 발급·검증 판정·상태 전이·승인·DB 저장은 backend가 담당한다.
- 모든 업무 요청은 HTTP 동기 응답이다. 장시간 검증의 큐·재시도·attempt·lease는 backend 워커가 관리한다. 검증 결과 callback은 사용하지 않는다.
- 모델별 출력 ID·스키마·원문 위치·배열 개수·벡터 차원을 backend가 검증한다.

## 성공·실패

성공 본문은 `{result, origin, model?, usage?}`다. `usage`는 실제 LLM 호출별 Usage 배열이다. 한 요청에서 여러 모델 호출·재시도를 했다면 각각 기록하고 같은 호출을 중복 합산하지 않는다. 모델을 호출했으면 model/usage를 제공하며 토큰 수를 모르면 tokens_known=false로 기록한다.

HTTP 오류는 `{error: {code, message}}`를 기본으로 한다. 모델 호출이 발생한 뒤 실패했으면 내부 응답에 선택적 `usage: Usage[]`를 추가해 실패 호출 비용도 보존한다. backend는 외부 응답에는 공통 오류만 내보낸다. 모델 timeout으로 응답 자체를 받지 못한 경우 호출 사실과 사용량 미확인을 별도로 기록하며 0토큰으로 단정하지 않는다.

대화·설명은 backend의 템플릿 폴백을 허용하고 origin=TEMPLATE로 구분한다. 검증 실패는 ERROR, 파싱 실패는 FAILED다. 빈 성공이나 자동 PASS로 바꾸지 않는다. 현재 ai 구현의 health 외 업무 API·내부 인증·공통 오류 처리는 구현 대상이다.

## 운영 상태 확인

`GET /health` → HTTP 200:

```json
{"status":"ok","message":"Server is running"}
```

기동 관측은 개별 모델의 정상 응답·분석 완료를 보장하지 않는다.

## 탐색

- [/v1/parse](#ai-a01)
- [/v1/validate](#ai-a02)
- [/v1/impact](#ai-a03)
- [/v1/embeddings](#ai-a04)
- [/v1/bee/chat](#ai-a05)
- [/v1/bee/draft](#ai-a06)
- [/v1/bee/assignments](#ai-a07)
- [/v1/bee/meeting-analysis](#ai-a08)

---

<a id="ai-a01"></a>

## AI-A01. 문서 파싱·설계 항목 추출

### POST `/v1/parse`

문서 파싱·설계 항목 추출 기능을 제공한다.

관련 요구사항: PLN-02~05, DOC-01  
담당: BE: 오서현 / AI: 박상은 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `document_version_id` | `ID` | Y | 권한 검사를 통과한 원본 버전 |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 문서 종류 |
| `media_type` | `string` | Y | 전달 텍스트 형식 |
| `content` | `string` | Y | 분석할 원문 |
| `content_hash` | `string` | Y | 원본 해시 |

```json
{
  "project_id": "101",
  "request_key": "ai-a01-request-1",
  "document_version_id": "211",
  "kind": "REQUIREMENTS",
  "media_type": "text/markdown",
  "content": "# 요구사항\n이메일과 비밀번호로 로그인한다.",
  "content_hash": "sha256:document-example"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [ParseResult](00-common.md#schema-parseresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "items": [
      {
        "candidate_key": "candidate-1",
        "kind": "API_CONTRACT",
        "schema_version": 1,
        "content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "로그인 계약"
        },
        "location": "2. 인증 API"
      }
    ],
    "questions": [
      {
        "question": "오류 응답 필드는 무엇인가요?",
        "location": "2. 인증 API"
      }
    ],
    "missing_candidates": [
      "오류 응답 필드 설명"
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 문서 위치를 보존한 구조화 후보·질문을 반환한다. 최종 ID·표시 키·승인 상태를 발급하지 않는다.
- 파싱 실패는 오류다. 임의로 빈 성공 결과를 반환하지 않는다.
- PDF/이미지 변환·추출 책임과 최대 원문 크기는 Q07이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a02"></a>

## AI-A02. 작업 계획 의미 분석

### POST `/v1/validate`

작업 계획 의미 분석 기능을 제공한다.

관련 요구사항: BEE-05, TRC-07, MCP-11  
담당: BE: 변준성 / AI: 양지훈 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `validation_id` | `ID` | Y | 검증 실행 대상 |
| `attempt` | `integer` | Y | backend 실행 시도 |
| `plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 불변 계획 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 참조한 승인 항목 |
| `related_plans` | [TaskPlanRevision[]](00-common.md#schema-taskplanrevision) | Y | 열린 관련 계획 |
| `rule_findings` | [Finding[]](00-common.md#schema-finding) | Y | 규칙 검사 결과 |
| `unresolved` | `string[]` | Y | 미확정 정책 |
| `input_snapshot_hash` | `string` | Y | 전체 분석 입력 해시 |

```json
{
  "project_id": "101",
  "request_key": "ai-a02-request-1",
  "validation_id": "601",
  "attempt": 1,
  "plan_revision": {
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
  "approved_items": [
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
  "related_plans": [
    {
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
    }
  ],
  "rule_findings": [
    {
      "id": "611",
      "source": "RULE",
      "category": "CONTRACT_MISMATCH",
      "severity": "BLOCKING",
      "message": "계획의 응답 필드가 승인 계약과 다릅니다.",
      "design_item_id": "301",
      "related_task_id": "502",
      "sources": [
        {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      ],
      "next_action": "계획을 수정하고 재검증하세요."
    }
  ],
  "unresolved": [],
  "input_snapshot_hash": "sha256:validation-input"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [ValidateResult](00-common.md#schema-validateresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "findings": [
      {
        "category": "CONTRACT_MISMATCH",
        "severity": "WARNING",
        "message": "계획과 승인 계약을 다시 확인해야 합니다.",
        "design_item_id": "301",
        "related_task_id": "502",
        "sources": [
          {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "3. 계정 관리 / ACC-02"
          }
        ],
        "suggested_action": "계획을 보완하세요."
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- backend 워커가 동기 HTTP로 호출한다. ai에서 큐 ID·콜백·최종 validation status를 만들지 않는다.
- 규칙 finding을 해제하지 않고 후보만 추가한다. 응답 ID는 입력 객체 안에서만 참조한다.
- backend는 attempt·lease·RUNNING 조건을 검사해 결과를 반영한다. 늦은 응답으로 STALE을 덮어쓰지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a03"></a>

## AI-A03. 변경 영향·담당자 분석

### POST `/v1/impact`

변경 영향·담당자 분석 기능을 제공한다.

관련 요구사항: BEE-06·09, CHG-05·08  
담당: BE: 오서현 / 작업 조회: 변준성 / AI: 양지훈 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `proposal_id` | `ID` | Y | 분석 제안 |
| `changes` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 대상 항목 변경 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 관련 승인 설계 |
| `explicit_dependencies` | [DesignDependency[]](00-common.md#schema-designdependency) | Y | 확정 관계 |
| `open_tasks` | [TaskDetail[]](00-common.md#schema-taskdetail) | Y | 열린 관련 작업 |

```json
{
  "project_id": "101",
  "request_key": "ai-a03-request-1",
  "proposal_id": "71",
  "changes": [
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
  "approved_items": [
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
  "explicit_dependencies": [
    {
      "from_design_item_id": "301",
      "to_design_item_id": "302",
      "relation": "DEPENDS_ON"
    }
  ],
  "open_tasks": [
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
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [ImpactResult](00-common.md#schema-impactresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "candidates": [
      {
        "task_id": "502",
        "member_id": "22",
        "design_item_id": "302",
        "reason": "로그인 응답을 사용하는 화면입니다.",
        "sources": [
          {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "3. 계정 관리 / ACC-02"
          }
        ]
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 명시 관계 기반 수신자 선정·알림은 backend가 먼저 처리한다.
- AI 결과는 추가 후보이며 자동 승인·작업 수정·전체 팀 호출 근거로 쓰지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a04"></a>

## AI-A04. 텍스트 임베딩

### POST `/v1/embeddings`

텍스트 임베딩 기능을 제공한다.

관련 요구사항: DOC-12, NFR-08  
담당: BE: 오서현(문서 접근) / 변준성(맥락 연계) / AI: 박상은 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `texts` | [EmbeddingText[]](00-common.md#schema-embeddingtext) | Y | 입력 key는 요청 안에서 유일 |
| `model` | `string` | N | 선택할 모델. 허용 설정 안에서만 사용 |
| `dimension` | `integer` | N | 요청 벡터 차원. 예시는 설명용 3 |

```json
{
  "project_id": "101",
  "request_key": "ai-a04-request-1",
  "texts": [
    {
      "key": "chunk-1",
      "text": "이메일·비밀번호로 로그인한다."
    }
  ],
  "model": "configured-embedding-model",
  "dimension": 3
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [EmbeddingResult](00-common.md#schema-embeddingresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "model": "configured-embedding-model",
    "dimension": 3,
    "vectors": [
      {
        "key": "chunk-1",
        "embedding": [
          0.12,
          -0.03,
          0.24
        ]
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 벡터는 backend가 저장한다. ai가 DB나 로컬 저장소를 직접 접근하지 않는다.
- key 집합·개수·유한 숫자·차원을 backend가 검증한다. 임베딩 모델·차원·문서 분할 계약 Q11.
- 예제의 3차원은 숫자 배열 형식 설명용이다. 운영 차원을 정하는 값이 아니다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a05"></a>

## AI-A05. 비비 설계 질의 응답

### POST `/v1/bee/chat`

비비 설계 질의 응답 기능을 제공한다.

관련 요구사항: BEE-02~04·07  
담당: BE: 오서현(문서 연결) / AI: 박상은 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `question` | `string` | Y | 질문 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 승인 설계 |
| `related_tasks` | [TaskDetail[]](00-common.md#schema-taskdetail) | Y | 관련 작업 |
| `unresolved` | `string[]` | Y | 이미 확인한 미확정 |

```json
{
  "project_id": "101",
  "request_key": "ai-a05-request-1",
  "question": "로그인 오류 응답을 어떻게 표시하나요?",
  "approved_items": [
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
  "related_tasks": [
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
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ],
  "unresolved": []
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [ChatResult](00-common.md#schema-chatresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "text": "오류 응답의 error.message를 표시합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "next_actions": [
      "현재 승인 API 계약을 확인한다."
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 문서·정확한 버전·위치를 근거로 답하고 정보 부족과 검색 실패를 구분한다.
- AI 실패 시 backend가 템플릿을 반환할 수 있다. 모델 실행처럼 origin을 기록하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a06"></a>

## AI-A06. 기획·문서 수정 초안 생성

### POST `/v1/bee/draft`

기획·문서 수정 초안 생성 기능을 제공한다.

관련 요구사항: PLN-06, BEE-08, MTG-09  
담당: BE: 오서현 / AI: 박상은 / 회의 연결: 양지훈 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `instruction` | `string` | Y | 생성 지시 |
| `document_version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | 기준 원문 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 관련 승인 항목 |
| `decisions` | `string[]` | N | 회의 등에서 합의한 내용 |
| `answers` | [PlanningQuestion[]](00-common.md#schema-planningquestion) | N | 기획 보완 질문·답변 |

```json
{
  "project_id": "101",
  "request_key": "ai-a06-request-1",
  "instruction": "오류 응답 설명을 보완해 주세요.",
  "document_version": {
    "id": "211",
    "document_id": "201",
    "version_no": 1,
    "status": "APPROVED",
    "file_name": "requirements.md",
    "media_type": "text/markdown",
    "content_hash": "sha256:example-document-hash",
    "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
    "created_at": "2026-10-23T03:00:00Z"
  },
  "approved_items": [
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
  "decisions": [
    "기존 API 구조는 유지한다."
  ],
  "answers": [
    {
      "id": "61",
      "question": "프로젝트 삭제 권한은 팀장만 가지나요?",
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      },
      "answer": "팀장만 삭제할 수 있습니다.",
      "answered_by": "11",
      "answered_at": "2026-10-23T03:00:00Z"
    }
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [DraftResult](00-common.md#schema-draftresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "after_content": "# 로그인\n오류 응답은 error.code와 error.message를 반환한다.",
    "reason": "합의된 오류 처리 기준을 문서에 반영했다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": []
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 반환 원문은 미승인 수정안이다. 문서 버전·baseline을 직접 바꾸지 않는다.
- 기획 초안의 항목 구조화는 필요 시 /v1/parse를 이어 호출한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a07"></a>

## AI-A07. 작업 담당 후보 추천

### POST `/v1/bee/assignments`

작업 담당 후보 추천 기능을 제공한다.

관련 요구사항: BEE-10  
담당: BE: 변준성 / AI: 박상은 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `tasks` | [TaskDetail[]](00-common.md#schema-taskdetail) | Y | 추천 대상 |
| `members` | [Member[]](00-common.md#schema-member) | Y | 현재 활성 멤버 |

```json
{
  "project_id": "101",
  "request_key": "ai-a07-request-1",
  "tasks": [
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
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ],
  "members": [
    {
      "id": "21",
      "account_id": "11",
      "name": "김개발",
      "role": "MEMBER",
      "domain": "backend",
      "role_label": "백엔드",
      "color": "#FFD54F",
      "agent_connected": true
    }
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [AssignmentsResult](00-common.md#schema-assignmentsresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "suggestions": [
      {
        "task_id": "501",
        "member_id": "21",
        "reason": "backend 담당 영역과 일치합니다."
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- 추천 ID는 요청 내 멤버·작업이어야 한다.
- 배정 결과를 저장하거나 멤버 역할을 바꾸지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a08"></a>

## AI-A08. 회의 질문·요약·결정 추출

### POST `/v1/bee/meeting-analysis`

회의 질문·요약·결정 추출 기능을 제공한다.

관련 요구사항: MTG-06·08  
담당: BE: 오서현 / AI: 양지훈 / FE: —  
상태: 내부 계약 초안

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Authorization | Bearer {AI_INTERNAL_TOKEN} |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 완료한 프로젝트 |
| `request_key` | `string` | Y | backend가 부여한 분석 요청 식별값. LLM별 실제 호출 key의 기준 |
| `mode` | `QUESTIONS\|MINUTES` | Y | 분석 목적 |
| `entries` | [MeetingEntry[]](00-common.md#schema-meetingentry) | Y | 회의 입력 스냅샷 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 관련 승인 설계 |
| `change_proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | N | 관련 제안 |

```json
{
  "project_id": "101",
  "request_key": "ai-a08-request-1",
  "mode": "MINUTES",
  "entries": [
    {
      "id": "911",
      "meeting_id": "901",
      "kind": "TEXT",
      "text": "현재 로그인 응답의 오류 필드 설명을 보완합니다.",
      "author_account_id": "11",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "approved_items": [
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
  "change_proposal": {
    "id": "71",
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
        "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
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
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [MeetingAnalysisResult](00-common.md#schema-meetinganalysisresult) | Y | 업무 결과 |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 모델 호출 시 필수 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "result": {
    "text": "오류 응답 구조를 유지하고 설명을 보완하기로 했다.",
    "decisions": [
      "기존 응답 구조 유지"
    ],
    "unresolved": [],
    "action_items": [
      {
        "text": "로그인 문서의 오류 응답 설명을 수정한다.",
        "member_id": "21"
      }
    ],
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "입력 타입·필수 값 오류"
  }
}
```

#### 처리 규칙

- QUESTIONS는 질문·누락 후보, MINUTES는 요약·결정·후속 행동을 반환한다.
- 원문 기록 ID/입력 범위를 고정한다. 실제 설계 수정안은 박상은의 draft 모듈을 사용한다.
- 전사 입력 범위 Q09. 이 요청은 텍스트 기록을 입력받는다.

[공통 규약·권한·담당자 목록](00-common.md)

---
