# BEEZ AI Internal API

버전: 0.5 · 갱신일: 2026-10-27 · 개발 계약 초안


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
- [/v1/document-chunks](#ai-a09)

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
| `origin` | `MODEL` | Y | 생성 출처. 템플릿 폴백 없음. 모델을 부르지 못하면 502/504 |
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
      "feature": "DOCUMENT_PARSE",
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
상태: 내부 계약 검토안(S15P31A108-107, 변준성 합의 전)

검토 범위는 검증 요청·분석 결과·실패 응답과 개발용 예시다. 공통 스키마와 [상황별 고정 JSON](examples/validation/README.md)을 함께 검토한다. 문서·예시 공유와 변준성의 합의가 107번 완료 조건이며, 이 문서 갱신만으로 API 구현·팀 합의가 완료되지는 않는다.

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
| `checks` | [ValidationCheck[]](00-common.md#schema-validationcheck) | Y | BE가 지정한 필수 검사 범위. 비어 있지 않고 key가 유일해야 함 |
| `evidence` | [ValidationEvidence[]](00-common.md#schema-validationevidence) | Y | 권한·버전을 검증한 원문 발췌. 없으면 []이며 검색 미발견을 정책 부재로 간주하지 않음 |
| `execution_budget_ms` | `integer` | Y | 모든 모델 호출·출력 검증을 포함한 AI 처리 예산. 양수이며 BE 배포 설정의 상한 이내 |

`checks`의 항목·계획 버전 ID는 각각 `approved_items`, `related_plans`에 실제로 포함되어야 한다. `related_plans`에는 검증 대상 작업 자신을 제외하고 열린 다른 작업의 불변 계획을 전달한다. 전달한 모든 근거·검사 범위를 입력 스냅샷에 포함한다.

`content`만으로 검사가 충분하면 `evidence=[]`도 유효하다. BE의 원문 조회 장애는 AI의 정보 부족으로 바꾸지 않고 BE 실행 오류로 기록한다. 호출 전에 확인한 필수 참조·근거 구조 오류, 알려진 미지원 kind/schema, 입력 크기 초과는 422이며 모델을 호출하지 않는다.

`approved_items[].content`는 해당 검사의 비교에 필요한 필드를 포함해야 한다. API 요청·응답 필드, 타입, 필수 여부 등 kind별 필수 키·지원 범위는 Q01에서 확정하며, 아래 content는 비교 흐름을 설명하는 예시이지 Q01의 최종 스키마가 아니다. 미지원 본문을 조용히 생략한 뒤 검사 완료로 처리하지 않는다. `unlinked`의 상세 형식은 Q02에서 함께 확정한다.

`evidence`는 기존 RAG `RetrievedEvidence`를 BE가 승인 항목 버전·`Source`와 연결해 만든 요청 스냅샷이다. PR #18의 원문 파서·locator를 재사용하며 AI가 DB나 원본 저장소를 재조회하지 않는다. `text_hash`는 정규화 원문 전체의 SHA-256이고 원본 bytes의 `content_hash`와 별개다. `utf8-lf-v1`은 선두 BOM 제거와 CRLF/CR → LF만 수행하며 trim/NFKC를 하지 않는다. locator는 정규화 텍스트의 1-based, 끝 행 포함 범위이고 `text`는 해당 행의 LF까지 포함해 복원한 발췌다. BE가 원문·해시·범위를 검증한다.

사람이 편집한 승인 `content`는 구조화 계약의 기준이다. 원문과 다르면 차이를 명시하며 원문으로 승인 내용을 덮어쓰지 않는다. 항목의 출처가 연결됐다는 사실만으로 원문 승인까지 추정하지 않는다. 수동 입력·기존 승인 항목을 사용할 수 있고, 자동 설계 항목 추출(-128/후속 E2)의 완료를 전제로 하지 않는다.

아래는 실제 모델 실행 결과가 아닌 고정 연동 예시다. 대상 작업은 `no_contract_change=true`를 선언했지만 접근 방식에 토큰 전달 변경을 적어, 의미 분석에서 불일치와 다른 작업 영향을 찾는 상황이다. 관련 작업은 대상 501과 다른 작업 502의 계획 리비전 512다. 작업 502의 READS 소비 관계를 관련 계획에 포함하는 범위와 `checks` 선정 기준은 Q02 확인 사항이다. 이 예시는 분석 입출력 검토용이며, AI 호출 대상·관련 계획 선정 정책의 합의 완료를 뜻하지 않는다.

```json
{
  "project_id": "101",
  "request_key": "ai-a02-request-mismatch",
  "validation_id": "601",
  "attempt": 1,
  "plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 API 구현",
      "goal": "승인 계약대로 로그인 API를 구현한다.",
      "included_scope": [
        "POST /api/auth/login 구현"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "로그인 성공 응답의 body.accessToken을 제거하고 Set-Cookie로만 전달한다.",
      "completion_criteria": [
        "로그인 성공 시 인증 결과를 반환한다."
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
    "created_at": "2026-10-26T03:00:00Z",
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
        "description": "이메일·비밀번호 로그인",
        "request_fields": [
          {
            "name": "email",
            "location": "body",
            "type": "string",
            "required": true
          },
          {
            "name": "password",
            "location": "body",
            "type": "string",
            "required": true
          }
        ],
        "responses": [
          {
            "status_code": "200",
            "fields": [
              {
                "name": "accessToken",
                "location": "body",
                "type": "string",
                "required": true
              },
              {
                "name": "userId",
                "location": "body",
                "type": "integer",
                "required": true
              }
            ]
          }
        ]
      },
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "인증 API / POST /api/auth/login / 성공 응답"
      }
    }
  ],
  "related_plans": [
    {
      "id": "512",
      "task_id": "502",
      "revision_no": 1,
      "plan": {
        "title": "로그인 화면 연동",
        "goal": "로그인 API의 승인 응답을 화면에서 사용한다.",
        "included_scope": [
          "로그인 화면 및 API 응답 연동"
        ],
        "excluded_scope": [
          "회원가입 화면"
        ],
        "approach": "로그인 성공 응답 본문의 accessToken을 읽어 다음 요청에 사용한다.",
        "completion_criteria": [
          "응답 본문의 accessToken을 읽어 인증 요청을 보낼 수 있다."
        ],
        "dependency_task_ids": [],
        "design_refs": [
          {
            "design_item_id": "301",
            "design_item_version_id": "311",
            "relation": "READS"
          }
        ],
        "unlinked": [],
        "no_contract_change": true,
        "context_packet_id": "702"
      },
      "created_at": "2026-10-26T03:00:00Z",
      "created_by": "12"
    }
  ],
  "rule_findings": [],
  "unresolved": [],
  "checks": [
    {
      "key": "plan-completeness",
      "kind": "PLAN_COMPLETENESS",
      "design_item_version_ids": [],
      "related_plan_revision_ids": []
    },
    {
      "key": "design-api-login",
      "kind": "DESIGN_ALIGNMENT",
      "design_item_version_ids": [
        "311"
      ],
      "related_plan_revision_ids": []
    },
    {
      "key": "related-task-502",
      "kind": "RELATED_PLAN_CONFLICT",
      "design_item_version_ids": [
        "311"
      ],
      "related_plan_revision_ids": [
        "512"
      ]
    }
  ],
  "evidence": [
    {
      "evidence_id": "evidence-login-response",
      "design_item_version_id": "311",
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "인증 API / POST /api/auth/login / 성공 응답"
      },
      "locator": {
        "type": "text_lines",
        "start_line": 3,
        "end_line": 8
      },
      "text": "### 성공 응답\n상태 코드: 200\n| 필드 | 타입 | 필수 | 설명 |\n| --- | --- | --- | --- |\n| accessToken | string | 예 | 발급한 접근 토큰 |\n| userId | integer | 예 | 사용자 식별자 |\n",
      "text_hash": "6cc7fd2817a76191efd014ccdf0d78926e59182d172c922a9ab2b8a2c302c5bb",
      "normalization_version": "utf8-lf-v1"
    }
  ],
  "execution_budget_ms": 30000,
  "input_snapshot_hash": "sha256:validation-input-mismatch"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | [ValidateResult](00-common.md#schema-validateresult) | Y | 업무 결과 |
| `origin` | `MODEL` | Y | 생성 출처. 템플릿 폴백 없음. 모델을 부르지 못하면 502/504 |
| `model` | `string` | Y | 실제 호출한 모델 |
| `usage` | [Usage[]](00-common.md#schema-usage) | Y | 실제 모델 호출별 사용량 |

`result`는 `analysis_status`, `checked_keys`, `unassessed`, `findings`를 모두 반환한다. 요청 `checks[].key`는 `checked_keys`와 `unassessed[].check_key`에 누락·중복 없이 정확히 한 번씩 나타나야 한다. finding은 `checked_keys`에 있는 `check_key`에만 연결하고, `evidence_ids`·항목·작업·출처는 입력 집합에서만 참조한다.

`analysis_status=COMPLETE`는 지정 검사를 수행했다는 뜻이며 문제가 없다는 뜻이 아니다. `unassessed=[]`일 때만 COMPLETE다. HTTP 200 COMPLETE/PARTIAL은 모두 실제 모델 호출 후에만 반환하며 model/usage가 필요하다. 형식상 유효한 입력을 의미 분석한 뒤 판단 근거 부족·미지원 내용으로 미검사 항목이 남으면 PARTIAL과 사유를 반환한다. PARTIAL은 검증 수행 범위용 메타데이터이며 문서 파싱·색인 job 상태를 바꾸지 않는다. 실행 실패·시간 초과·잘린 모델 출력은 PARTIAL 성공 응답으로 바꾸지 않는다.

검증 호출의 `usage[].request_key`는 `llm-validate-{validation_id}-attempt-{n}-call-{k}`로 실제 호출 순번을 포함한다. 같은 실행에서 모델을 두 번 호출하면 서로 다른 k를 사용하고 동일 validation/attempt 범위에서 k를 재사용하지 않는다. 같은 실제 호출의 usage 전송만 재시도할 때는 같은 key를 유지하고, 모델을 다시 호출하면 새 key를 부여한다.

`checks`, `checked_keys`, `unassessed`는 적용 attempt/run과 함께 `validation.summary` JSONB에 보존하는 안을 기준으로 저장 위치·매핑을 -159에서 확정한다. 실패는 `validation_run.error`와 BE가 생성하는 `kind=ERROR` finding의 `resolution`·`next_action`으로 추적한다. Validation 응답 DTO의 `error_code`는 ERROR에서 필수이고 그 외 상태에서는 생략한다. 현재 실패 attempt의 [공통 실행 오류 분류](00-common.md#validation-execution-errors)에서 BE가 조립하는 값이며, `validation.error_code`라는 DB 컬럼을 뜻하지 않는다. 재시도 허용은 이 코드가 아니라 현재 실패 실행의 ERROR finding resolution으로 결정한다. HTTP 오류의 `error.code`는 별도 전송 계약으로 유지한다. 후보의 `check_key`·`evidence_ids` 영속화 구조와 새 마이그레이션 번호는 -159에서 머지 직전에 결정하며, 이번 문서 PR에는 DDL을 추가하지 않는다.

```json
{
  "result": {
    "analysis_status": "COMPLETE",
    "checked_keys": [
      "plan-completeness",
      "design-api-login",
      "related-task-502"
    ],
    "unassessed": [],
    "findings": [
      {
        "check_key": "design-api-login",
        "kind": "MISMATCH",
        "severity": "BLOCKING",
        "message": "승인 계약은 응답 본문에 accessToken을 반환하지만 계획은 해당 필드를 제거하고 쿠키로만 전달한다.",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "sources": [
          {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "인증 API / POST /api/auth/login / 성공 응답"
          }
        ],
        "evidence_ids": [
          "evidence-login-response"
        ],
        "suggested_action": "응답 본문의 accessToken을 유지하도록 계획을 보완하거나, 계약 변경이 필요하면 변경 제안과 팀장 승인을 요청하세요."
      },
      {
        "check_key": "related-task-502",
        "kind": "CONFLICT",
        "severity": "BLOCKING",
        "message": "작업 502의 계획은 응답 본문의 accessToken을 읽으므로 작업 501의 변경 계획과 충돌한다.",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "related_task_id": "502",
        "sources": [
          {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "인증 API / POST /api/auth/login / 성공 응답"
          }
        ],
        "evidence_ids": [
          "evidence-login-response"
        ],
        "suggested_action": "작업 501과 502의 담당자가 토큰 전달 방식에 합의하고 필요한 계획 수정 또는 설계 변경 제안을 진행하세요."
      }
    ]
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1-call-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "latency_ms": 1800,
      "failed": false,
      "tokens_known": false
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 입력 타입·필수 값 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 본문 업무 구조·검사 참조·근거 구조 부적합 또는 호출 전 입력/컨텍스트 한도 초과 |
| 502 | `model_unavailable` | 외부 모델 호출 실패, 잘린 출력 또는 출력 스키마·검사 범위·참조 검증 실패 |
| 504 | `model_timeout` | 모델 또는 AI 전체 처리 예산 초과 |

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
- 규칙 finding을 유지하고 AI 후보만 추가한다. 규칙 finding의 해제·심각도 하향·중복 합산을 하지 않는다. 응답 ID는 입력 객체 안에서만 참조한다.
- backend가 스키마, checks의 완전한 분할, 근거·버전·참조를 검사한 뒤 [공통 6절 판정표](00-common.md#검증시작)로 판정한다. 빈 findings만으로 PASS가 되지 않는다.
- 규칙만으로 NEEDS_INPUT/NEEDS_REVIEW가 확정된 요청은 해당 결과를 바로 반환한다. 아래 AI 응답 매핑은 의미 분석이 필요한 RUNNING 실행에 적용하며, 확정 규칙 결과를 AI 응답으로 다시 판정하지 않는다.
- `MISSING_INPUT`은 작성자가 보완해야 하는 필수 계획 정보 누락이며 BLOCKING으로 반환한다. COMPLETE일 때 BE가 NEEDS_INPUT으로 연결한다. 선택적인 개선 제안을 필수 입력 누락으로 분류하지 않는다.
- `MISSING_POLICY`는 원문에 미정으로 명시되었거나 합의한 정책 확인 범위에서 부재를 확인한 경우에만 BLOCKING으로 반환하고 NEEDS_REVIEW로 연결한다. 전달 근거가 부족하면 정책 부재를 단정하지 않고 `unassessed.reason=EVIDENCE_INSUFFICIENT`로 구분한다.
- 다른 BLOCKING 불일치·충돌도 NEEDS_REVIEW다. 검토 필요와 입력 부족이 함께 있거나 기존 규칙이 검토 필요로 결정했으면 NEEDS_REVIEW를 유지하고 보완 내용도 표시한다. AI의 STALE_REF는 BE 최신성 재확인 신호이며 추론만으로 STALE을 확정하지 않는다.
- PARTIAL은 PASS 불가다. BE는 미검사 사유를 실패 실행에 남기고 ERROR를 기록하며, 부분 AI 후보는 진단 이력에만 남긴다. BE가 생성하는 ERROR finding의 `resolution=HUMAN_REVIEW`로 근거 보완·지원 범위 조정 후 새 검증을 안내한다. 정상 finding 적용이나 부분 성공 저장으로 취급하지 않는다. 이 사유는 DB finding kind에 추가하지 않는다.
- 모델 호출 실패·시간 초과·출력 계약 위반과 BE 원문·검색 조회 장애·워커 중단·lease 만료는 BE가 ERROR finding을 만들고 `resolution=RETRY`로 기존 VAL-A04의 재시도 경로를 안내한다. 최신 실패 attempt에 HUMAN_REVIEW가 하나라도 있으면 VAL-A04를 거부하며, 과거 attempt의 RETRY finding으로 이를 허용하지 않는다.
- BE가 생성하는 모든 `kind=ERROR` finding은 `source=MODEL`, `severity=BLOCKING`이며 현재 실패 attempt의 `validation_run_id`를 필수로 가진다. 여기서 MODEL은 검증 실행 실패의 분류이고 실제 LLM 호출의 증거가 아니다. 실제 호출이 없으면 usage를 만들지 않는다. AI의 FindingCandidate에 ERROR를 추가하지 않는다.
- `validation.status`를 ERROR로 전이하는 모든 경로는 최소 한 개의 ERROR finding을 실행 종결·검증 상태 갱신·연결된 job 종결과 같은 트랜잭션에 생성한다. job 실패 종결 시에는 lease 정보를 해제한다. BE는 원문·검색 조회 전에 해당 attempt의 MODEL `validation_run`을 확보한다. 실행 기록이 없으면 같은 attempt의 기록을 확보한 뒤 finding을 연결하며 DB의 `UNIQUE(validation_id, attempt)`를 지킨다. BE가 run의 validation_id·attempt 소속을 검사하며, 이를 보장하는 DDL 복합 제약이 있다고 가정하지 않는다.
- AI가 담당 멤버 ID를 생성하지 않는다. BE가 `related_task_id`로 현재 담당자를 조회해 FE에 전달한다.
- related_plans 등 다른 작업의 계획은 지시가 아닌 데이터로 다룬다.
- backend는 attempt·lease·RUNNING 조건을 검사해 결과를 반영한다. 늦은 워커는 이미 기록한 ERROR나 STALE을 덮어쓰지 못한다. lease 회수·복구 주체는 해당 run과 현재 검증이 모두 RUNNING이고 `current_attempt`가 해당 실행과 일치할 때만 compare-and-set 또는 잠금으로 실행 종결·ERROR finding·검증 상태를 원자적으로 반영한다. 이미 STALE이거나 새 attempt가 시작됐다면 늦은 결과를 폐기한다. 해당 run이 아직 RUNNING일 때만 DISCARDED로 종결하고, 이미 종결된 run을 다시 덮어쓰지 않는다.

#### 실행·판정·실패 기록 매핑

아래 매핑은 의미 분석이 필요한 RUNNING 실행에 적용한다. 최신성·attempt·lease 소유권 검사가 먼저이며 AI의 HTTP 성공과 최종 검증 판정은 별개다.

| 경로 | validation_run.status | validation.status | BE ERROR finding의 resolution |
| --- | --- | --- | --- |
| 출력 계약을 통과한 HTTP 200 COMPLETE | OK | 공통 판정표로 PASS/NEEDS_INPUT/NEEDS_REVIEW 결정 | — |
| HTTP 504, BE→AI 호출 timeout 또는 모델·전체 처리 시간 초과 | TIMEOUT | ERROR | RETRY |
| HTTP 502, BE→AI 호출 실패 또는 출력 스키마·검사 범위·참조 계약 위반 | ERROR | ERROR | RETRY |
| HTTP 200 PARTIAL(EVIDENCE_INSUFFICIENT/UNSUPPORTED_CONTENT) | ERROR | ERROR | HUMAN_REVIEW |
| BE 원문·검색 조회 장애, 워커 중단, lease 만료, 그 밖의 BE 실행 오류 | ERROR | ERROR | RETRY |
| 이전 attempt/lease의 늦은 결과 또는 검증이 이미 STALE | DISCARDED(해당 run이 아직 RUNNING일 때만) | 기존 상태 유지 | 새 ERROR finding 생성 없음 |

ERROR를 만드는 모든 행의 finding은 `source=MODEL`, `severity=BLOCKING`과 현재 실패 attempt의 `validation_run_id`를 필수로 가진다. ERROR finding 생성·run 종결·검증 ERROR 전이·연결된 job 종결은 같은 트랜잭션이다. 마지막 행은 이미 종결된 run을 DISCARDED로 재변경하지 않는다.

#### 시간 예산·재시도

- `execution_budget_ms`는 요청 안의 모든 모델 호출과 결과 검증에 적용한다. 호출 전 입력 한도를 검사하고, 초과한 입력을 조용히 잘라 분석하지 않는다. 예산 안에 끝내지 못하면 504다.
- BE HTTP 제한시간은 AI가 예산 초과 오류와 usage를 전달할 여유를 두고 설정한다. 예시의 30000ms는 설명용이며 서비스 SLA·운영 기본값의 확정이 아니다. 예산 상한·전달 여유·lease 갱신 방식은 Q11에서 합의한다.
- 검증 경로의 AI/모델 SDK 자동 재시도는 끈다. 초기 BE도 자동 재시도하지 않고 [VAL-A04](01-backend-rest.md#val-a04)의 명시적 ERROR 재시도로 새 run/attempt를 만든다. 재시도는 최신 실패 attempt가 RETRY 경로인 경우에만 가능하며 HUMAN_REVIEW가 있으면 거부한다. 입력·근거 보완 또는 지원 범위 조정 후에는 새 검증이다. 후속 자동 재시도는 오류별 대상·횟수·전체 예산을 Q11에서 확정한 뒤 추가한다.
- 성공·실패 모두 실제 모델 호출별 usage를 남긴다. SDK/BE 중첩 재시도로 호출을 숨기거나 중복 합산하지 않고, 사용량을 모르면 tokens_known=false로 기록한다. 연결이 끊긴 뒤에도 실제 호출 비용은 기존 실행 이력에 보존한다.

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
      "feature": "CHANGE_IMPACT",
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
| `embedding_profile_id` | `string` | 조건부 | 원문 RAG에서는 필수. 모델/리비전·차원·문서/질의 전처리·거리 기준을 고정한 프로필 |
| `purpose` | `DOCUMENT\|QUERY` | 조건부 | 원문 RAG에서는 필수. 같은 프로필의 문서/질의 전처리를 선택 |

원문 RAG는 profile과 purpose를 함께 보낸다. 기존 model/dimension이 함께 있으면 프로필과 일치해야 하며 불일치를 임의 보정하지 않는다. RAG 응답 result에는 embedding_profile_id/purpose를 되돌려준다. BE는 입력 key 집합과 반환 key 집합의 정확한 일치·중복 없음·벡터 차원·유한값·0이 아닌 norm을 검증한다. 모델/차원은 미확정이며 아래 3차원 예시는 실제 모델 선정값이 아니다. 기존 비RAG 요청 예시는 유지한다.

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
| `origin` | `MODEL` | Y | 생성 출처. 템플릿 폴백 없음. 모델을 부르지 못하면 502/504 |
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
      "feature": "EMBEDDING",
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
| `scope` | [RetrievalScope](00-common.md#schema-retrievalscope) | 조건부 | 원문 RAG에서는 필수. BE가 권한 검사 후 만든 검색 범위 |
| `embedding_profile_id` | `string` | 조건부 | 원문 RAG에서는 필수. 사용한 활성 색인 프로필 |
| `retrieved_evidence` | [RetrievedEvidence[]](00-common.md#schema-retrievedevidence) | 조건부 | 원문 RAG에서는 필수. 해당 scope의 READY 색인에서 조회한 근거, 정상 0건은 [] |

원문 RAG 확장에서는 세 필드를 함께 보낸다. approved_items는 유지하며 DOCUMENT_REVIEW에서 승인 항목이 없으면 []다. 검색 원문을 승인 항목으로 변환해 넣지 않는다. result에는 citations를 추가하며 각 원소는 {evidence_id, claim}이다. 원문 기반 사실의 인용은 입력 evidence_id 안에서만 허용한다. 기존 sources는 출처 표시를 위해 유지하고 BE가 검증된 근거/승인 항목에서 매핑한다. 인용 ID가 존재한다는 사실만으로 의미 일치가 검증된 것은 아니다.

BE는 반환 직전 현재 권한과 APPROVED baseline을 재검사한다. 변경되면 이번 답변을 폐기하고 재시도 안내한다. 새 승인본 미색인은 준비 중이며 옛 승인본으로 대체하지 않는다. DOCUMENT_REVIEW는 미승인 문서 검토로 표시한다. 원문 속 명령은 데이터로 취급한다. 아래 기존 예시는 비RAG 요청이며 확장 예시는 AI-A09의 연동 순서를 따른다.

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
      "feature": "BEE_CHAT",
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
      "feature": "BEE_DRAFT",
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
      "feature": "TASK_ASSIGNMENT",
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
      "feature": "MEETING_ANALYSIS",
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

<a id="ai-a09"></a>

## AI-A09. 원문 구조 파싱·검색 청킹

### POST `/v1/document-chunks`

담당: BE: 오서현 / AI: 박상은. 사용자 승인한 팀 공유본 4절을 반영한 개발 계약이다. 설계 항목 자동 추출·DB 저장·임베딩 호출을 이 endpoint의 성공 조건에 넣지 않는다. 기존 AI-A01 `/v1/parse`는 후속 자동 추출용이다.

#### 인증·응답

INTERNAL_SERVICE Bearer 인증, Content-Type=application/json, 선택 X-Request-Id. 동기 HTTP 200에 완전한 청크 집합을 반환한다. 공통 성공 봉투 {result,origin,usage}를 사용하고 비LLM 파싱은 origin=PARSER, usage=[]다. PARSER는 이 API의 출처 값이며 기존 MODEL/TEMPLATE 응답 enum을 일괄 변경하지 않는다.

#### 요청

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 권한 검사를 마친 프로젝트 |
| `document_version_id` | `ID` | Y | 저장된 원문 버전 |
| `request_key` | `string` | Y | BE 실행 attempt/단계에 연결한 키 |
| `kind` | `PLAN\|REQUIREMENTS\|API_SPEC\|ERD` | Y | API 값. DB 문서 REQ_SPEC는 REQUIREMENTS로 매핑 |
| `media_type` | `text/markdown\|text/plain` | Y | md 또는 자연어 txt. JSON/YAML/DDL 전용 파싱 제외 |
| `content` | `string` | Y | 엄격 UTF-8 디코딩·정규화된 원문 |
| `content_hash` | `string` | Y | 원본 bytes SHA-256. AI는 bytes를 받지 않으므로 검증했다고 주장하지 않음 |
| `text_hash` | `string` | Y | 정규화 UTF-8 content의 SHA-256. AI가 재계산해 검증 |
| `normalization_profile` | `utf8-lf-v1` | Y | 첫 BOM 제거, CRLF/CR→LF. trim/NFKC 금지 |
| `chunking_profile_id` | `string` | Y | 양쪽이 설정한 불변 분할 프로필 ID |

```json
{
  "project_id": "101",
  "document_version_id": "211",
  "request_key": "index-211-attempt-1-chunks",
  "kind": "REQUIREMENTS",
  "media_type": "text/markdown",
  "content": "# 요구사항\nREQ-01: 로그인한 사용자만 프로젝트를 조회한다.\n",
  "content_hash": "sha256:cb0951a395a40f48f3eb0cc2a42611e7800168f14da1268091e0d4667604770c",
  "text_hash": "sha256:cb0951a395a40f48f3eb0cc2a42611e7800168f14da1268091e0d4667604770c",
  "normalization_profile": "utf8-lf-v1",
  "chunking_profile_id": "md-structure-v1"
}
```

#### 응답

| result 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 입력과 동일 버전 |
| `request_key` | `string` | Y | 입력과 동일 키 |
| `text_hash` | `string` | Y | 검증한 텍스트 해시 |
| `chunking_profile_id` | `string` | Y | 실제 사용 프로필 |
| `parser_version` | `string` | Y | 실제 파서 버전 |
| `chunks` | `object[]` | Y | 전체 성공 집합. 각 원소 chunk_key/string, chunk_no/integer, text/string, locator/SourceLocator |
| `diagnostics` | `object[]` | Y | 진단 {code:string,message:string}. 없음은 [] |

```json
{
  "result": {
    "document_version_id": "211",
    "request_key": "index-211-attempt-1-chunks",
    "text_hash": "sha256:cb0951a395a40f48f3eb0cc2a42611e7800168f14da1268091e0d4667604770c",
    "chunking_profile_id": "md-structure-v1",
    "parser_version": "md-parser-v1",
    "chunks": [{
      "chunk_key": "211:0",
      "chunk_no": 0,
      "text": "# 요구사항\nREQ-01: 로그인한 사용자만 프로젝트를 조회한다.\n",
      "locator": {"type": "text_lines", "start_line": 1, "end_line": 2}
    }],
    "diagnostics": []
  },
  "origin": "PARSER",
  "usage": []
}
```

#### 오류·검증·다음 단계

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | validation_failed | 필수 필드/enum/프로필 형식 오류 |
| 401 | invalid_internal_token | 내부 인증 실패 |
| 422 | text_hash_mismatch | 정규화 원문 해시 불일치 |
| 422 | invalid_ai_input | 지원 불가 내용/분할·예산 초과·원문 범위 표현 불가 |

- chunk_no는 0부터 연속, chunk_key는 집합 안에서 유일. 같은 버전/해시/파서/프로필의 동일 입력은 같은 키/결과를 재현한다. BE는 반환 배열 순서에 의존하지 않는다.
- locator는 [SourceLocator](00-common.md#schema-sourcelocator). 원문 누락·지원 불가 내용·일부 실패를 성공 집합으로 반환하지 않는다. 기본 프로필은 원문 연속 범위, 문맥 보강 off다. 더 정밀한 locator는 별도 계약 변경으로 도입한다.
- SOURCE 성공과 INDEX 성공은 분리한다. BE는 이 결과를 검증하고 AI-A04 purpose=DOCUMENT로 벡터를 얻은 뒤 현재 run/attempt/hash/profile일 때만 청크 전량과 READY manifest를 게시한다. AI는 DB에 접근하지 않는다.
- 질문은 AI-A04 purpose=QUERY → BE 허용 집합 검색 → AI-A05 scope/retrieved_evidence → 최종 권한/baseline 검사 순서다. 원문 자동 항목 추출은 실행하지 않는다.
- 입력 한도·600토큰 등의 초기 값·embedding 모델/차원은 별도 정책 결정값이며 이 예시가 확정하지 않는다. 오류를 빈 성공이나 자동 승인으로 바꾸지 않는다.
