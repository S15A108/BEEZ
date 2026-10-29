# BEEZ AI Internal API

버전: 0.6 · 갱신일: 2026-10-29 · 개발 계약 초안

2026-10-28: 화면 수정안(v2) 요청을 반영했다(S15P31A108-218). 비비 필수 문서 검토 AI-A10을 추가하고 AI-A08에 회의 정리(SUMMARY)를 더했다. 바뀐 부분은 '수정안 요청'으로 표시했다.

2026-10-29: AI-A10을 dev BE 문서 검토 호출의 경로(`/v1/document-reviews`)·입출력·검증에 맞췄다. 화면에 더 필요한 mode·steps·reply·category·severity·evidence는 '수정안 요청' 확장 필드다.


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

대화·설명은 backend의 템플릿 폴백을 허용하고 origin=TEMPLATE로 구분한다. 검증 실패는 ERROR, 파싱 실패는 FAILED다. 빈 성공이나 자동 PASS로 바꾸지 않는다. AI-A04와 문서 청킹은 구현되며 다른 업무 API의 구현 상태는 각 항목을 확인한다.

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
- [/v1/document-reviews](#ai-a10)

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
- (수정안 요청) RELATED_PLAN_CONFLICT 검사의 BLOCKING CONFLICT(related_task_id 있음)는 BE가 비비 회의를 여는 근거로 쓸 수 있다(Q17). message는 팀장이 바로 이해할 한국어 한두 문장으로 쓰고, BE가 회의 첫 기록(CONFLICT_BRIEF)으로 옮긴다.
- (수정안 요청) severity=BLOCKING finding은 next_action(고칠 방법 한 줄)을 반드시 채운다. 작업 현황 화면이 막힌 항목마다 보여 준다.

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
| `embedding_profile_id` | `string` | 조건부 | 원문 RAG에서는 필수. 서버의 실제 `gms-small-{dimension}-v1`과 일치하며 purpose와 함께 생략하거나 함께 전송 |
| `purpose` | `DOCUMENT\|QUERY` | 조건부 | 원문 RAG에서는 필수. embedding_profile_id와 함께 전송하며 null 불가 |

원문 RAG는 profile과 purpose를 함께 보낸다. 두 필드를 생략한 기존 요청과 응답은 기존 형식을 유지한다. 확장 요청의 result에는 실제 `embedding_profile_id`와 `purpose`가 추가된다. profile/model/dimension이 서버 설정과 다르면 공급자를 호출하기 전에 422다. BE는 입력과 반환 key 집합의 일치·중복 없음·벡터 차원·유한값·0이 아닌 norm을 검증한다. 문서에는 청킹 결과의 `embedding_text`를, 질의에는 질의 텍스트를 사용한다. 아래 3차원은 형식 설명용이며 운영 차원은 배포 설정과 맞춘다.

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
  "model": "text-embedding-3-small",
  "dimension": 3,
  "embedding_profile_id": "gms-small-3-v1",
  "purpose": "DOCUMENT"
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
    "model": "text-embedding-3-small",
    "dimension": 3,
    "embedding_profile_id": "gms-small-3-v1",
    "purpose": "DOCUMENT",
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
  "model": "text-embedding-3-small",
  "usage": [
    {
      "request_key": "ai-a04-request-1:emb:example:0:1",
      "feature": "EMBEDDING",
      "model": "text-embedding-3-small",
      "input_tokens": 12,
      "output_tokens": 0,
      "latency_ms": 100,
      "failed": false,
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
- 전체 처리 예산은 90초, 공급자 호출당 20초다. BE read timeout은 100초 이상 또는 비동기 접수 방식으로 별도 결정해야 한다. 연결 종료가 공급자의 처리·과금 취소를 보장하지 않는다.

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
- (수정안 요청) BE는 citations의 evidence_id를 BeeCitation(문서·버전·행 범위·인용 문장)으로 바꿔 화면의 [n] 표시와 잇는다. 본문에 [n]을 AI가 넣을지 BE가 claim 순서로 붙일지는 박상은·오서현이 정한다.
- (수정안 요청) 근거를 찾지 못하면 citations=[]와 unresolved로 답하고 짐작해 답하지 않는다. BE는 grounded=false로 표시한다.

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
| `mode` | `QUESTIONS\|MINUTES\|SUMMARY` | Y | 분석 목적. SUMMARY는 수정안 요청 |
| `entries` | [MeetingEntry[]](00-common.md#schema-meetingentry) | Y | 회의 입력 스냅샷 |
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 관련 승인 설계 |
| `change_proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | N | 관련 제안 |
| `related_tasks` | [TaskDetail[]](00-common.md#schema-taskdetail) | N | SUMMARY 필수. 충돌한 작업. 수정안 요청 |
| `documents` | `object[]` | N | SUMMARY 필수. 관련 승인 문서 {document_id, document_version_id, text(utf8-lf-v1 정규화 원문)}. 수정안 요청 |
| `previous_summary` | [MeetingSummary](00-common.md#schema-meetingsummary) | N | 수정 요청이면 이전 정리. 수정안 요청 |
| `feedback` | `string` | N | 수정 요청이면 팀장이 쓴 고칠 점. 수정안 요청 |

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
- (수정안 요청) SUMMARY는 팀장이 요청한 회의 정리다. conflicts·decisions·document_changes·action_items를 돌려준다. document_changes.after는 회의를 끝내면 BE가 만드는 변경 제안(MTG-A13)에 들어갈 문장이고, locator는 입력 documents의 행 범위, evidence_entry_ids는 입력 entries의 ID만 쓴다. before는 그 범위 원문과 정확히 같아야 한다.
- (수정안 요청) 수정 요청이면 previous_summary와 feedback을 함께 받아 다시 정리한다.
- (수정안 요청) QUESTIONS·MINUTES는 회의실 수정안에서 쓰지 않는다(MTG-A07·A08 폐지 예정).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a09"></a>

<a id="document-chunks"></a>

## AI-A09. 원문 구조 파싱·검색 청킹

### POST `/v1/document-chunks` (#214)

상태: AI 구현 계약. BE 담당자의 계약 합의 및 실제 DB 통합은 별도다. `Authorization: Bearer {AI_INTERNAL_TOKEN}` 필수, `X-Request-Id` 선택. 모델 호출과 GMS 키는 필요 없다. BE가 프로젝트 권한과 문서 소속을 검증한다.

| 요청 필드 | 규칙 |
| --- | --- |
| `project_id`, `document_version_id` | 양의 십진 문자열, 최대 19자리 |
| `request_key` | 1~128자 상관관계 키. 저장 또는 멱등성 보장은 BE 책임 |
| `kind` | `PLAN`, `REQUIREMENTS`, `API_SPEC`, `ERD` |
| `media_type` | `text/markdown`만 허용 |
| `content` | 엄격히 UTF-8 디코딩한 원문 문자열. BOM/CRLF 허용 |
| `content_hash` | 원본 바이트 SHA-256 소문자 hex 64자리. `sha256:` 접두사 허용. BE가 원본과 대조 |
| `text_hash` | 정규화 텍스트 UTF-8의 SHA-256 소문자 hex 64자리. `sha256:` 접두사 허용. AI가 대조 |
| `normalization_profile` | `utf8-lf-v1`: 맨 앞 BOM 제거, CRLF/CR을 LF로 변환 |
| `parser_profile` | 선택. 생략 시 `markdown-v1` |
| `chunking_profile_id` | `md-structure-v1` |

추가 필드는 거부한다. 고정 청킹 프로필은 로컬 tiktoken, 청크당 600토큰, 최대 1,024청크이며 heading/table 문맥 보강은 꺼져 있다. 같은 입력·문서 버전·실제 프로필과 파서/청커 버전은 같은 chunk key를 만든다.

성공 응답은 `result`, `origin: "PARSER"`, `usage: []`다. result는 `contract_version: "document-chunks-v1"`, 요청 식별자·text_hash·프로필, `parser_version`, `manifest`, `excluded_ranges`, `diagnostics`, `chunks`를 포함한다. `result.text_hash`는 접두사를 포함해 요청값 그대로 반환하고, 내부 `manifest`의 해시는 접두사 없는 64자리다. manifest에는 실제 전체 청킹 프로필 및 예상 청크 key 목록이 있다. 각 청크에는 `chunk_key`, `chunk_no`, `document_version_id`, `text`, `embedding_text`, `token_count`, `source_spans`, `context_refs`, 표시용 `locator`가 있다.

`source_spans`의 시작·끝은 정규화 문자열의 Unicode code point 0-based, 끝 제외다. span별 `locator`는 1-based 행 양끝 포함이다. Java에서는 UTF-16 인덱스로 변환한 뒤 원문을 자른다. `text`는 span별 원문 조각을 순서대로 결합한 값이다. 청크의 `locator`는 첫 span부터 마지막 span까지의 **표시 범위**이며 전체 행을 결합해 text를 복원하면 안 된다. 한 행 안에서 분할한 청크들은 이 표시 범위가 겹칠 수 있다. `excluded_ranges`는 빈 행, 구분선, 컨테이너 표시 등 청크에 속하지 않는 원문을 명시한다.

`context_refs`는 heading/table header의 위치·해시와 문맥 적용 여부를 제공한다. 인용 근거는 원문 span이며 임베딩에는 `embedding_text`를 사용한다. 요청·응답의 실행 가능한 예시는 [청킹 계약 fixture](examples/document-chunks/)에 있다.

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | JSON·필드 형식 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 413 | `file_too_large` | 실제 HTTP body 8 MiB 또는 content UTF-8 1 MiB 초과 |
| 415 | `unsupported_media_type` | 미지원 kind/media_type |
| 422 | `invalid_ai_input` | 해시·프로필·문법, 정규화 100,000자·파서·청크·최종 응답 8 MiB 한도 실패 |
| 503 | `processing_unavailable` | 로컬 토크나이저 이용 불가 |
| 504 | `processing_timeout` | JSON 검증·처리 10초 초과 |

오류는 `error.code/message` 및 선택적 `details.reason/locator`이며 부분 결과는 반환하지 않는다. 원문·키는 오류에 포함하지 않는다. 시간 초과 후 작업 스레드가 잠시 계속될 수 있으므로 프록시에서 동시성 및 요청 크기를 제한한다.

**BE 인계:** 기존 worker의 전체 행 복원 및 모든 비빈 행 포함 검사를 span·excluded_ranges 기반 검증으로 교체한다. manifest key·개수·scope·해시·프로필, 범위 순서/중복/coverage, 원문 복원을 확인하고 source span과 context 참조의 저장·검색 매핑을 결정한다. 문서 임베딩은 `embedding_text`, 질의는 같은 전체 임베딩 프로필을 사용한다. worker/lease/READY 원자적 발행, read timeout, 실제 DB 통합 검증은 BE에서 이어간다.

---


SOURCE 성공과 INDEX 성공은 분리한다. BE는 청크·벡터 전체를 검증하고 현재 run/attempt/hash/profile일 때만 READY를 발행한다. 질문 경로는 AI-A04 QUERY → BE 허용 집합 검색 → AI-A05 scope/retrieved_evidence → 최종 권한/baseline 검사 순서다. AI는 DB에 접근하지 않으며 이 API에서 설계 항목을 자동 추출하지 않는다.

---

<a id="ai-a10"></a>

## AI-A10. 문서 검토·수정 제안

### POST `/v1/document-reviews`

공용 문서함의 작성 내용을 승인 문서와 비교해 보완 제안을 만든다. DOC-A16(검토)이 부른다. 기본 필드와 검증은 dev BE 구현(`AiClient.documentReview`, `DocumentReviewService`의 reviewInput·validateResult)과 같다. 화면에 더 필요한 필드는 '수정안 요청' 확장으로 붙였고, DOC-A22(대화)·DOC-A23(직접 요청)도 같은 경로를 쓴다. PR #37은 AI 내부 연동을 담당자 합의 대기로 두었으므로 AI 구현 전에 이 절로 합의한다.

관련 요구사항: DOC-01·08 (화면 PNL-02-m-review-*)  
담당: BE: 오서현 / AI: 박상은 / FE: —  
상태: BE 호출·검증 구현 · AI 구현 전 · 확장 필드는 수정안 요청 (협의 필요)

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
| `project_id` | `ID` | Y | 권한 검사를 마친 프로젝트 |
| `document_id` | `ID` | Y | 검토 대상 문서 |
| `revision` | `integer` | Y | 검토할 작성 리비전(DocumentWorkingCopy.revision) |
| `kind` | `string` | Y | 대상 문서 종류(API_SPEC·ERD·REQ_SPEC 등) |
| `content` | `string` | Y | 지금 작성 내용. 정규화한 본문 텍스트 |
| `request_key` | `string` | Y | BE가 호출마다 새로 만드는 분석 요청 식별값(`document-review-{document_id}-{uuid}`) |
| `reference_hash` | `string` | Y | 참고 승인본 집합 해시. 응답에 그대로 돌려준다 |
| `unavailable_references` | `string[]` | Y | API_SPEC·ERD·REQ_SPEC 중 승인본이 없어 비교하지 못하는 종류 |
| `references` | [DocumentReviewReference[]](00-common.md#schema-documentreviewreference) | Y | 같은 프로젝트의 현재 승인본. 미승인 문서는 넣지 않는다. 없으면 [] |
| `mode` | `REVIEW\|RERUN\|DISCUSS\|REQUEST` | N | 수정안 요청. 없으면 REVIEW. 등록 검토 / 반려 뒤 재검토 / 제안 대화 / 직접 요청 |
| `rejection_submission_id` | `ID` | N | 수정안 요청. RERUN 필수. 사유를 읽을 반려 제출(DOC-A16 선택 규칙) |
| `rejection_reason` | `string` | N | 수정안 요청. RERUN 필수. 그 제출의 반려 사유 원문 |
| `suggestion` | [DocumentReviewSuggestion](00-common.md#schema-documentreviewsuggestion) | N | 수정안 요청. DISCUSS 필수. 대화 대상 제안 |
| `messages` | [ReviewMessage[]](00-common.md#schema-reviewmessage) | N | 수정안 요청. DISCUSS 필수. 지금까지의 대화와 새 메시지 |
| `request_text` | `string` | N | 수정안 요청. REQUEST 필수. '비비에게 직접 요청하기' 내용 |

```json
{
  "project_id": "101",
  "document_id": "201",
  "revision": 3,
  "kind": "API_SPEC",
  "content": "# 알림 API 명세서\n\n## GET /api/notifications\n알림 목록을 조회한다.\n\n## 오류\n| HTTP | code | 상황 |\n| --- | --- | --- |\n| 401 | `unauthenticated` | 로그인이 필요함 |\n",
  "request_key": "document-review-201-0f8e5c2a-7b3d-4c1e-9a6f-2d8b7e4c1a90",
  "reference_hash": "sha256:5d41402abc4b2a76b9719d911017c592ae7f2c1b3d4e5f60718293a4b5c6d7e8",
  "unavailable_references": ["API_SPEC"],
  "references": [
    {
      "document_id": "203",
      "document_version_id": "231",
      "kind": "ERD",
      "version_no": 1,
      "content": "# ERD\n\nnotification(id, account_id, read_at)\n"
    },
    {
      "document_id": "204",
      "document_version_id": "241",
      "kind": "REQ_SPEC",
      "version_no": 2,
      "content": "# 요구사항 명세서\n\nREQ-007 사용자가 알림을 열면 읽음으로 표시한다.\n"
    }
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | `object` | Y | 아래 result 필드 |
| `origin` | `MODEL` | Y | 실제 모델 결과만 받는다. TEMPLATE이면 BE가 버린다 |
| `model` | `string` | N | 공통 성공 본문의 모델 이름. BE 검증은 usage[].model을 본다 |
| `usage` | [Usage[]](00-common.md#schema-usage) | Y | 실제 모델 호출 기록 1~20개. failed=false이고 model이 있는 항목이 1개 이상. feature=BEE_DRAFT. request_key는 입력 request_key이거나 `{request_key}-`로 시작 |

result 필드:

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 입력 그대로 |
| `revision` | `integer` | Y | 입력 그대로 |
| `reference_hash` | `string` | Y | 입력 그대로 |
| `request_key` | `string` | Y | 입력 그대로 |
| `summary` | `string` | Y | 검토 요약. 비어 있지 않은 10,000자 이하 |
| `suggestions` | `object[]` | Y | 보완 제안 0~30개. 없으면 [] |
| `suggestions[].explanation` | `string` | Y | 보완 이유. 비어 있지 않은 10,000자 이하 |
| `suggestions[].original_text` | `string` | Y | 바꿀 원문. 비어 있지 않고 입력 content 안에 정확히 한 번 나온다 |
| `suggestions[].replacement_text` | `string` | Y | 대체문. 100,000자 이하. 빈 문자열이면 original_text를 지운다 |
| `suggestions[].category` | `OMISSION\|CONSISTENCY\|READABILITY\|REJECTION_FIX` | N | 수정안 요청. 누락·일관성·가독성·반려 사유 반영 |
| `suggestions[].severity` | `MAJOR\|MINOR` | N | 수정안 요청 |
| `suggestions[].evidence` | `object[]` | N | 수정안 요청. 근거 [{document_version_id, quote}]. document_version_id는 입력 references 안의 버전이고 quote는 그 버전 content에 그대로 있는 문장 |
| `steps` | `object[]` | N | 수정안 요청. DocumentBeeReview.steps와 같은 {label, description, result} |
| `reply` | `object` | N | 수정안 요청. DISCUSS 필수. {text, proposed_text?} |

```json
{
  "result": {
    "document_id": "201",
    "revision": 3,
    "reference_hash": "sha256:5d41402abc4b2a76b9719d911017c592ae7f2c1b3d4e5f60718293a4b5c6d7e8",
    "request_key": "document-review-201-0f8e5c2a-7b3d-4c1e-9a6f-2d8b7e4c1a90",
    "summary": "요구사항 REQ-007의 읽음 처리 조건이 빠져 있어 1건을 제안합니다. 승인된 API 명세서가 없어 그 비교는 하지 못했습니다.",
    "suggestions": [
      {
        "explanation": "요구사항 REQ-007의 읽음 처리 조건이 빠졌습니다.",
        "original_text": "알림 목록을 조회한다.",
        "replacement_text": "알림 목록을 조회하고, 사용자가 연 알림은 읽음으로 표시한다.",
        "category": "OMISSION",
        "severity": "MAJOR",
        "evidence": [
          {
            "document_version_id": "241",
            "quote": "REQ-007 사용자가 알림을 열면 읽음으로 표시한다."
          }
        ]
      }
    ],
    "steps": [
      {"label": "문서 읽기", "description": "작성 내용을 문단으로 나눕니다", "result": "문단 3개"},
      {"label": "승인된 API 명세서와 비교", "description": "같은 종류의 승인본과 맞춰 봅니다", "result": "승인본 없음"},
      {"label": "ERD와 맞춰 보기", "description": "테이블·필드와 맞춰 봅니다", "result": "어긋남 없음"},
      {"label": "요구사항과 비교", "description": "요구사항이 빠지지 않았는지 봅니다", "result": "빠진 조건 1개"},
      {"label": "수정 제안 정리", "description": "바꿀 문장을 정리합니다", "result": "제안 1개"}
    ]
  },
  "origin": "MODEL",
  "model": "example-model",
  "usage": [
    {
      "request_key": "document-review-201-0f8e5c2a-7b3d-4c1e-9a6f-2d8b7e4c1a90-call-1",
      "feature": "BEE_DRAFT",
      "model": "example-model",
      "input_tokens": 1830,
      "output_tokens": 412,
      "latency_ms": 2140,
      "failed": false,
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
| 422 | `invalid_ai_input` | 본문 업무 구조 부적합 또는 입력 크기 초과 |
| 502 | `model_unavailable` | 외부 모델 호출 실패 또는 잘못된 출력 |
| 504 | `model_timeout` | 모델 시간 초과 |

```json
{
  "error": {
    "code": "model_timeout",
    "message": "모델 시간 초과"
  }
}
```

#### 처리 규칙

- BE는 응답을 이렇게 검증한다(validateResult). origin=MODEL, usage 조건, result의 document_id·revision·reference_hash·request_key가 입력과 같음, summary·suggestions 개수와 필드, original_text가 content 안에 정확히 한 번. 하나라도 어기면 BE가 결과 전체를 버리고 DOC-A16에서 502 invalid_ai_output을 낸다.
- TEMPLATE 결과는 받지 않는다. 모델을 부르지 못했으면 오류(502·504)로 돌려주고 BE가 503 review_unavailable로 알린다. 템플릿 결과로 검토 완료를 대신하지 않는다는 DOC-A16 정책이며, 이 문서 첫머리의 대화 템플릿 폴백은 AI-A10에 쓰지 않는다. DISCUSS도 같다.
- original_text로 위치를 잡고 행 번호를 쓰지 않는다. 앞 제안을 반영해도 뒤 제안이 어긋나지 않는다(DOC-A17 APPLY 규칙). replacement_text는 그 원문을 그대로 바꿀 문장이다. 반영·저장·승인은 BE와 사람이 한다.
- 표·문단 옮기기는 옮길 부분부터 도착 위치까지를 한 original_text로 잡고 순서를 바꾼 문장을 replacement_text로 낸다.
- (수정안 요청) evidence는 입력 references 안의 버전만 쓴다. quote가 그 버전 content에 없으면 잘못된 출력이다. BE는 references의 document_id·version_no를 붙이고 quote를 location에 넣어 DocumentReviewSuggestion.sources로 옮긴다.
- (수정안 요청) 지금 BE는 summary와 제안의 explanation·original_text·replacement_text만 저장한다. category·severity·evidence·steps·reply를 화면에 보이려면 BE 저장을 넓혀야 한다.
- (수정안 요청) mode별 결과: REVIEW·RERUN은 suggestions 목록, REQUEST는 suggestions 1개다. DISCUSS는 reply가 필수이고 suggestions는 []다. 네 mode 모두 result의 식별값 4개와 summary를 돌려준다.
- (수정안 요청) steps는 BE가 DOC-A16의 단계 표시(DocumentBeeReview.steps)에 쓴다. REVIEW 기본: 문서 읽기 → 승인된 API 명세서와 비교 → ERD와 맞춰 보기 → 요구사항과 비교 → 수정 제안 정리. RERUN: 반려 사유 읽기 → 관련 문단 찾기 → ERD와 맞춰 보기 → 수정 제안 정리. 비교할 승인본이 없는 단계는 result에 그 사유를 쓴다.
- (수정안 요청) RERUN은 BE가 DOC-A16 선택 규칙으로 고른 rejection_submission_id와 그 rejection_reason을 넣는다. 비비는 사유와 이어진 문단을 찾아 category=REJECTION_FIX 제안을 만든다. 팀장은 문단을 고르지 않는다.
- 이 문서의 동기 응답 원칙을 따른다. 실제 단계별 진행률을 보이려면 BE가 단계마다 나눠 부르거나 스트리밍이 필요하며, 방식은 Q19다.
- 입력 크기·비용 상한과 실패 때 재시도는 Q11·Q19다.

[공통 규약·권한·담당자 목록](00-common.md)
