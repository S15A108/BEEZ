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
- [/v1/design-reference-recommendations](#ai-a11)
- [/v1/document-revisions](#ai-a12)
- [/v1/document-revision-blocks](#ai-a13)

---

<a id="ai-a12"></a>

## AI-A12. 선택 범위 문서 수정 초안

### POST `/v1/document-revisions`

상태: S15P31A108-137 AI 내부 구현. 기존 AI-A06 `/v1/bee/draft`와
별도 경로다. 기존 CHG-A07은 유지하며, 선택 범위용 BE 경로와 FE 화면은
이 경로를 사용한다. 이 응답은 검토할 초안이며 AI는 저장, 승인 또는
발행하지 않는다.

#### 인증과 공통 제한

`Authorization: Bearer {AI_INTERNAL_TOKEN}`과 JSON 본문이 필요하다.
`X-Request-Id`는 선택적 추적 헤더다. 본문 한도는 2 MiB다.
BE는 호출 전에 프로젝트·문서 접근을 허가하고 승인된 원문을 준비해야 한다.
AI의 버전 비교는 요청 내부 값의 일치만 검사하므로, BE는 모델 호출 후에도
현재 승인 버전과 선택 범위가 유효한지 확인해야 한다.

#### 요청

최상위와 `source`는 아래 필드만 허용한다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `source` | Y | 권한 확인된 원문 스냅샷. 아래 필드를 모두 포함 |
| `expected_document_version_id` | Y | 1~256자. `source.document_version_id`와 일치 |
| `selected_block_ids` | Y | 동일 스냅샷에서 파싱된 블록 ID 1~30개. 중복 불가 |
| `instruction` | Y | 공백만으로 구성되지 않은 수정 지시, 최대 4,000자 |
| `agreements` | N | 합의 문자열 목록, 최대 30개. 각 항목은 공백만일 수 없고 최대 4,000자 |
| `related_proposals` | N | 관련 제안 문자열 목록. 개수·항목 제한은 `agreements`와 같음 |
| `request_key` | Y | 공백만으로 구성되지 않은 요청 식별자, 최대 128자 |

| `source` 필드 | 설명 |
| --- | --- |
| `project_id`, `document_version_id` | 각 1~256자 문자열 |
| `kind` | `PLAN`, `REQUIREMENTS`, `API_SPEC`, `ERD` 중 하나 |
| `media_type` | `text/markdown` 또는 `text/plain` |
| `content` | `utf8-lf-v1`로 정규화된 원문. 정규화 후 최대 100,000자, 입력 최대 1 MiB |
| `content_hash` | 원본 콘텐츠의 SHA-256, 접두사 없는 소문자 16진수 64자 |
| `text_hash` | 정규화된 `content`의 SHA-256, 접두사 없는 소문자 16진수 64자. AI가 원문과 비교 |
| `normalization_version` | `utf8-lf-v1` |
| `parser_profile` | Markdown은 `markdown-v1`, 일반 텍스트는 `plain-v1` |

블록 ID는 문서 버전, 원문 해시와 파서 프로필 등에 종속된다. AI-A13에서
같은 스냅샷의 선택 가능한 블록을 조회할 수 있다. BE의 선택 범위용
외부 API와 FE 선택 UI가 이를 이용한다.
범위가 없거나 스냅샷에 없는 ID를 전체 문서 선택으로 해석하지 않는다.
모델에는 선택 블록 원문과 수정 지시·합의·제안만 전달한다.

#### 응답

HTTP 200은 `origin: "MODEL"`, 실제 `model`, 호출별 `usage`,
`result`를 반환한다. `usage`의 `request_key`는 실제 모델 호출의 고유
식별자다. 공통 Usage 필드와 실패 시 사용량 기록은 위 성공·실패 규약을 따른다.

| `result` 필드 | 설명 |
| --- | --- |
| `document_version_id` | 입력 원문의 기준 버전 ID |
| `after_content` | 선택 블록을 교체하고 선택 밖 원문을 이어 붙인 전체 초안 |
| `changes[]` | 각 변경의 `block_id`, `before`, `after`, `reason`, `evidence[]` |
| `unresolved[]` | 해결할 수 없거나 확인이 필요한 내용. 변경 없이 반환할 수 있음 |

`evidence`는 입력된 합의 또는 제안에 실제 포함된 문자열만 허용한다.
모델이 범위 밖 ID, 중복 ID, 유효하지 않은 근거 또는 줄 경계를 침범하는
출력을 내면 실패 처리한다. Markdown 초안은 다시 파싱하여 선택하지 않은
블록의 위치와 유형, 제목·표·목록 등 구조 맥락과 토큰을 비교하며
참조 정의의 변경이 선택하지 않은 블록에 영향을 줄 수 있는 경우에도 거부한다.
이는 구조 검증이며 요청의 의미와 문서의 정확성을 판정하지 않으므로
사람이 전체 초안을 검토해야 한다.

#### 오류

| HTTP | 코드 | 설명 |
| --- | --- | --- |
| 400 | `validation_failed` | JSON 또는 필드 형식 오류 |
| 401 | `invalid_internal_token` | 내부 토큰 누락 또는 불일치 |
| 413 | `file_too_large` | 본문이 2 MiB 초과 |
| 422 | `invalid_ai_input` | 버전 불일치, 원문·해시·프로필 오류, 선택 범위 등 의미상 입력 오류 |
| 502 | `model_unavailable` | 모델 사용 불가 또는 모델 응답 검증 실패 |
| 502 | `model_output_truncated` | 모델 답이 출력 토큰 상한(`finish_reason=length`)에서 끊겨 쓸 수 없음. 형식 오류와 구분한다(S15P31A108-382) |
| 504 | `model_timeout` | 모델 호출 시간 초과 |

모델 호출 후 실패했으면 오류에 실제 `usage`가 포함될 수 있다. 모델
실제 서버 연결은 합성 문서와 jsdom FE 폼으로 확인했다. 실제 브라우저와
다양한 실데이터를 이용한 품질 검토는 별도 검증이 필요하다.

---

<a id="ai-a13"></a>

## AI-A13. 문서 수정용 선택 블록 조회

### POST `/v1/document-revision-blocks`

상태: S15P31A108-137 AI 내부 구현. AI-A12에 전달할 선택 블록의 ID,
유형, 원문 위치와 텍스트를 반환한다. 이 API는 사용자의 문서 접근이나
승인 상태를 판정하지 않는다. BE가 권한과 현재 승인 버전을 확인하고
인가된 스냅샷만 전달해야 한다. 선택 범위용 BE·FE 경로가 이 응답을
사용한다.

#### 인증 및 요청

`Authorization: Bearer {AI_INTERNAL_TOKEN}`과 JSON 본문이 필요하며
`X-Request-Id`는 선택 추적 헤더다. 요청 본문은 AI-A12의 `source`
객체 자체이며, 그 표의 모든 필드와 정규화·해시·프로필 제약이 동일하게
적용된다. `source`라는 래퍼 없이 전송한다. 본문 크기 한도는 2 MiB다.

#### 응답

HTTP 200은 아래 필드를 가진 JSON 객체를 반환한다. 모델 호출이 없으므로
`origin`, `model`, `usage`는 없다.

| 필드 | 설명 |
| --- | --- |
| `document_version_id` | 파싱한 문서 버전 ID |
| `text_hash` | 정규화 원문 SHA-256 |
| `normalization_version` | 원문 정규화 버전 |
| `parser_profile`, `parser_version` | 파서 프로필과 구현 버전 |
| `blocks[]` | 선택 가능한 파서 블록 목록 |
| `blocks[].block_id` | AI-A12 `selected_block_ids`에 전달할 ID |
| `blocks[].block_type` | 파서의 블록 유형 |
| `blocks[].locator` | `type: "text_lines"`, 1부터 시작하는 양끝 포함 `start_line`, `end_line` |
| `blocks[].text` | 해당 줄 범위의 원문. 줄바꿈을 포함할 수 있음 |

블록은 입력한 문서 스냅샷에만 유효하다. 원문, 버전 또는 파서가 바뀌면
다시 조회하고 선택해야 한다. 공백 등 파서가 제외한 범위는 목록에
포함되지 않는다. 응답 JSON이 2 MiB를 초과하면 실패한다.

#### 오류

| HTTP | 코드 | 설명 |
| --- | --- | --- |
| 400 | `validation_failed` | JSON 또는 필드 형식 오류 |
| 401 | `invalid_internal_token` | 내부 토큰 누락 또는 불일치 |
| 413 | `file_too_large` | 요청 본문이 2 MiB 초과 |
| 422 | `invalid_ai_input` | 정규화·해시·프로필·파싱 오류 또는 응답 크기 한도 초과 |

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
| `approved_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 명시 참조 또는 검색 후보로 선정한 현재 기준의 승인 항목 |
| `related_plans` | [TaskPlanRevision[]](00-common.md#schema-taskplanrevision) | Y | 열린 관련 계획 |
| `rule_findings` | [Finding[]](00-common.md#schema-finding) | Y | 규칙 검사 결과 |
| `unresolved` | `string[]` | Y | 미확정 정책 |
| `context_packet_constraints` | `string[]` | N | 범위·기준을 확인한 패킷의 제약. 지시가 아닌 참고 데이터이며 승인 설계 근거를 대체하지 않는다. |
| `input_snapshot_hash` | `string` | Y | 전체 분석 입력 해시 |
| `checks` | [ValidationCheck[]](00-common.md#schema-validationcheck) | Y | BE가 지정한 필수 검사 범위. 비어 있지 않고 key가 유일해야 함 |
| `evidence` | [ValidationEvidence[]](00-common.md#schema-validationevidence) | Y | 권한·버전을 검증한 원문 발췌. 없으면 []이며 검색 미발견을 정책 부재로 간주하지 않음 |
| `execution_budget_ms` | `integer` | Y | 모든 모델 호출·출력 검증을 포함한 AI 처리 예산. 양수이며 BE 배포 설정의 상한 이내 |
| `completion_delta` | `object[]` | N | 완료 보고에서 전달한 변경 내용. DELTA 검사에서는 비어 있지 않은 배열을 보내고 `completion-delta` 검사를 함께 지정한다. 일반 계획 검증에는 생략한다. |

`checks`의 항목·계획 버전 ID는 각각 `approved_items`, `related_plans`에 실제로 포함되어야 한다. `related_plans`에는 검증 대상 작업 자신을 제외하고 열린 다른 작업의 불변 계획을 전달한다. 전달한 모든 근거·검사 범위를 입력 스냅샷에 포함한다.

S15P31A108-186의 선택적 `policy-gap-v1` / `POLICY_ALIGNMENT` 검사는 선정된 모든 승인 항목을 대상으로 계획에 필요한 정책을 추출·비교한다. AI는 `policy_assessments`, `policy_assessments_truncated`와 필요 시 `no_policy_requirements_reason`, `no_policy_requirements_evidence`를 반환한다. 계획·승인 내용의 인용은 요청에 실제 존재해야 한다. 근거 부족이나 결과 잘림은 정책 검사의 PARTIAL이며, 명시적 미정만 `MISSING_POLICY`로 반환한다. 정책 간 모순은 양쪽 항목과 각각의 인용을 확인한 `conflicting_design_refs`를 포함한다. 응답의 상세 형식은 [ValidateResult](00-common.md#schema-validateresult)와 [186 평가 기록](../../ai/evaluations/policy_gap/README.md)을 따른다. 기존 요청에는 정책 결과를 넣지 않는다.

`completion-delta`는 `PLAN_COMPLETENESS` 검사다. 완료 보고는 지시나 완료의 증명이 아닌 데이터로 취급하고 현재 계획·완료 기준 및 선정된 승인 설계에 비추어 분석한다. 검사의 설계 버전 목록은 해당 요청에서 조립한 승인 항목 버전이다. 완료 보고와 승인 설계의 불일치는 그 목록에 있는 설계 ID와 해당 설계의 출처·발췌를 인용할 수 있다. 보고와 현재 계획만의 불일치는 설계 ID와 출처 없이 `MISMATCH`로 표시할 수 있다. 검사 범위 밖의 설계나 다른 설계의 근거를 인용하지 않는다.

`content`만으로 검사가 충분하면 `evidence=[]`도 유효하다. BE의 원문 조회 장애는 AI의 정보 부족으로 바꾸지 않고 BE 실행 오류로 기록한다. 호출 전에 확인한 필수 참조·근거 구조 오류, 알려진 미지원 kind/schema, 입력 크기 초과는 422이며 모델을 호출하지 않는다.

`approved_items[].content`는 해당 검사의 비교에 필요한 필드를 포함해야 한다. API 요청·응답 필드, 타입, 필수 여부 등 kind별 필수 키·지원 범위는 Q01에서 확정하며, 아래 content는 비교 흐름을 설명하는 예시이지 Q01의 최종 스키마가 아니다. 미지원 본문을 조용히 생략한 뒤 검사 완료로 처리하지 않는다. `unlinked`의 상세 형식은 Q02에서 함께 확정한다.

`evidence`는 BE가 원문과 `Source`를 확인해 만든 요청 스냅샷이다. 승인 항목에 명시적으로 연결된 근거에는 `design_item_version_id`가 있으며, 검색된 미연결 문서의 발췌에는 이 필드를 생략한다. 미연결 발췌는 설계 항목이나 직접 참조로 승격하지 않으며 `plan-completeness`에서 설계 ID 없이 원문 맥락으로만 인용할 수 있다. 검색 발췌는 최대 50개·발췌당 16,000바이트·합계 60,000바이트이며, 전체 입력 한도를 넘지 않도록 일부가 추가로 생략될 수 있다. 생략된 맥락은 검색 전체의 부재를 뜻하지 않는다. AI가 DB나 원본 저장소를 재조회하지 않는다. `text_hash`는 정규화 원문 전체의 SHA-256이고 원본 bytes의 `content_hash`와 별개다. `utf8-lf-v1`은 선두 BOM 제거와 CRLF/CR → LF만 수행하며 trim/NFKC를 하지 않는다. locator는 정규화 텍스트의 1-based, 끝 행 포함 범위이고 `text`는 해당 행의 LF까지 포함해 복원한 발췌다. BE가 원문·해시·범위를 검증한다.

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

**#188 구현 제안 — BE 계약 협의 필요:** 기존 필드를 유지하면서 요청의 변경 사유 `reason`, 결과의 근거 상태 `result.evidence_status`, 후보의 `validation_id`를 추가한다. `validation_id`는 전달된 작업의 검증 ID만 사용할 수 있다. 근거 상태는 제공된 입력에 대한 평가이며 관련 작업이나 관계 전체가 검색됐다는 보장은 아니다. AI 결과와 BE의 규칙 후보·관계 경로는 별도로 보존한다. #197 저장 및 외부 API 형식은 이 제안만으로 확정하지 않는다.

#195는 변경 제안의 불변 리비전 스냅샷과 현재 항목 projection에 `schema_version`을 보존한다. #188 내부 구현 초안은 현재 제출 리비전의 스냅샷·리비전 번호·해시를 확인하고 실제 CREATE·UPDATE 입력에 그 값을 전달한다. 스키마 버전이나 변경 내용이 없는 제안은 값을 추측하지 않고 AI 호출 없이 미평가로 남긴다. 현재 AI-A03에는 문서 변경 본문을 전달하는 확정 계약이 없으므로 문서만 변경하거나 설계와 문서를 함께 변경한 제안도 미평가이며 영향 0건이 아니다. #188 초안의 AI 요청에 추가하는 `proposal_revision`·`proposal_snapshot_hash`는 내부 추적 정보로서 #197 저장·공개 계약의 확정을 뜻하지 않는다. 구조화 출력 `json_schema`는 공급자가 지원할 때 선택하며 기본값은 `json_object`다.

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
| `reason` | `string` | N | 변경 제안 사유. #188 제안 필드 |
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

#188 제안 결과의 `result.evidence_status`는 `SUFFICIENT` 또는 `INSUFFICIENT`다. 승인된 기존 내용·변경안·작업 계획·문서 근거가 부족하면 `INSUFFICIENT`로 표현한다. `candidates`가 비어 있어도 프로젝트 전체에 영향이 없음을 의미하지 않는다. 후보에는 입력된 작업의 검증을 식별하기 위한 `validation_id`가 선택적으로 포함될 수 있다. 기존 `ImpactResult`·`ImpactCandidate` 공통 DTO 반영 및 #197 저장 방법은 담당자 합의가 필요하다.

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
| 413 | `invalid_ai_input` | #188 제안: 설정된 요청 본문 크기 초과 |
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

관련 요구사항: PLN-06, BEE-08  
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
- 호출자는 변경 제안의 문서 수정 초안(CHG-A07)이다. 기획 설계 초안(PLN-A05)이 같은 모듈을 쓰는지는 S15P31A108-232에서 확정한다.
- 회의 끝내기(MTG-A13)는 이 API를 부르지 않는다. 변경 제안의 문안은 회의 정리(MTG-A11, AI-A08 SUMMARY)의 document_changes.after에서 온다. MTG-A09가 폐지 예정이라 회의 연결 호출자가 없으므로 `decisions`는 현재 쓰는 곳이 없다(필드는 유지).
- 모델 출력이 최대 길이로 잘렸으면(`finish_reason=length` 등) 성공 응답으로 돌려주지 않고 502 `model_unavailable`로 응답한다. 응답 모양에는 종료 사유 필드가 없어 backend는 잘림 여부를 알 수 없으므로 이 오류가 유일한 신호다. 잘린 수정안을 성공으로 돌려주면 원문 대신 승인본이 될 수 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a07"></a>

## AI-A07. 작업 담당 후보 추천

S15P31A108-136 구현·확장 제안: [역할 분담 추천 계약](../design/role-recommendation-S15P31A108-136.md).
현재 작업 목록·전체성 표시와 후보별 대안은 아래 초안을 확장한 필드이며 계약 리뷰가 필요하다.
AI 경로는 기본 비활성이고 실제 배정은 하지 않는다.

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

상태: 내부 계약 초안. SUMMARY 는 BE 가 호출하는 경계를 구현했다(S15P31A108-235). AI 서버는 2026-11-05 기준 SUMMARY 를 아직 받지 않아(`SUMMARY contract is not agreed`) 실제 연동은 AI 구현 뒤 확인한다

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
- 원문 기록 ID/입력 범위를 고정한다. 실제 설계 수정안은 박상은의 draft 모듈을 사용한다. 단 SUMMARY의 document_changes.after는 이 API가 직접 만들고 draft 모듈(AI-A06)을 부르지 않는다.
- 전사 입력 범위 Q09. 이 요청은 텍스트 기록을 입력받는다.
- (수정안 요청) SUMMARY는 팀장이 요청한 회의 정리다. conflicts·decisions·document_changes·action_items를 돌려준다. document_changes.after는 회의를 끝내면 BE가 만드는 변경 제안(MTG-A13)에 들어갈 문장이고, locator는 입력 documents의 행 범위, evidence_entry_ids는 입력 entries의 ID만 쓴다. before는 그 범위 원문과 정확히 같아야 하며 각 줄의 LF를 포함한다(원문이 a\nb\n이면 1행의 before는 a\n, 문서가 LF로 끝나지 않으면 마지막 줄만 LF 없음). 회의 끝내기(MTG-A13)도 같은 기준을 쓴다.
- (수정안 요청) 수정 요청이면 previous_summary와 feedback을 함께 받아 다시 정리한다.
- (수정안 요청) QUESTIONS·MINUTES는 회의실 수정안에서 쓰지 않는다(MTG-A07·A08 폐지 예정).
- (BE 구현, S15P31A108-235) BE가 SUMMARY로 보내는 입력: `entries`는 정리 범위의 TEXT·CONFLICT_BRIEF 기록(최근 것부터 최대 200건), `related_tasks`는 회의가 가리키는 작업, `approved_items`는 그 작업의 현재 계획이 참조하는 설계 항목의 지금 기준 승인 버전(최대 100개), `documents`는 그 항목의 출처 문서의 지금 기준 승인본 원문(utf8-lf-v1 정규화, 최대 10개·문서당 300,000바이트, 통째로만), 수정 요청이면 `previous_summary`·`feedback`(4,000자 이하). `entries`는 최소 한 건을 보장하도록 설계 항목보다 먼저 예산을 잡고, 한 건도 담을 수 없으면 AI 호출 전에 400으로 끝낸다. 요청 본문은 바이트 예산(기본 900,000바이트, 설정값)을 기록·문서·항목이 나눠 쓰며 AI 쪽 한도(1 MiB)보다 약 14% 낮다 — 실연동 때 조정한다. `change_proposal`은 보내지 않는다.
- (BE 구현, S15P31A108-235) BE가 검증하는 출력: `result`는 객체, `origin`은 MODEL/TEMPLATE, `conflicts`·`decisions`·`document_changes`·`action_items`는 배열이어야 한다. `decisions`는 문자열 배열이면 BE가 `{text}`로 감싸고 `{text, decided_by_member_id}` 객체도 받는다. `document_changes[]`는 입력 `documents`의 문서·버전이고 `locator`가 `text_lines` 줄 범위 안이며 `before`가 그 범위 원문과(각 줄의 LF 포함) 정확히 같고 `evidence_entry_ids`가 입력 `entries`의 ID이며 같은 문서 안에서 줄 범위가 겹치지 않아야 한다. 틀린 항목은 버려 `unresolved`에 개수를 남기고, 한 목록이 통째로 쓸 수 없으면 502 `ai_unavailable`로 응답하며 아무것도 저장하지 않는다. `result.sources`는 입력 문서·버전과 같은 것만 남긴다.

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
| `media_type` | `text/markdown` 또는 `text/plain`. 파서 프로필을 정한다 |
| `content` | 엄격히 UTF-8 디코딩한 원문 문자열. BOM/CRLF 허용 |
| `content_hash` | 원본 바이트 SHA-256 소문자 hex 64자리. `sha256:` 접두사 허용. BE가 원본과 대조 |
| `text_hash` | 정규화 텍스트 UTF-8의 SHA-256 소문자 hex 64자리. `sha256:` 접두사 허용. AI가 대조 |
| `normalization_profile` | `utf8-lf-v1`: 맨 앞 BOM 제거, CRLF/CR을 LF로 변환 |
| `parser_profile` | 선택. 생략 시 media_type의 프로필(`text/markdown`은 `markdown-v1`, `text/plain`은 `plain-v1`). 명시하면 그 프로필과 같아야 한다 |
| `chunking_profile_id` | `md-structure-v1` |

추가 필드는 거부한다. 고정 청킹 프로필은 로컬 tiktoken, 청크당 600토큰, 최대 1,024청크이며 heading/table 문맥 보강은 꺼져 있다. 같은 입력·문서 버전·실제 프로필과 파서/청커 버전은 같은 chunk key를 만든다.

파서 프로필은 media_type마다 하나다. `markdown-v1`은 CommonMark + pipe table 구조(제목·목록·표·코드·참조 정의)를 블록으로 나누며 HTML·이미지는 `unsupported_construct`로 전체 실패한다. `plain-v1`은 `.txt` 업로드용 일반 텍스트 프로필이다. 빈 줄로 구분한 문단만 `paragraph` 블록으로 삼고 HTML·이미지·Markdown 문법을 전혀 해석하지 않으므로 `<user>` 같은 태그 문자열도 원문 그대로 색인한다. `excluded_ranges`의 reason은 `blank`뿐이고 `heading_path`·`context_refs`·`diagnostics`는 비어 있으며 문단은 4,000개까지다. `source_spans`·`locator`·`text_hash`·manifest 구조와 청킹 프로필은 두 프로필이 같고 manifest의 `parser_profile`/`parser_version`(`beez-plain/1.0.0`)으로 구분한다. 같은 원문이라도 프로필이 다르면 block/chunk key가 다르다. BE는 `.md`를 `text/markdown`, `.txt`를 `text/plain`으로 보내고 manifest의 `parser_profile`이 보낸 media_type의 프로필과 같은지 검증한다.

성공 응답은 `result`, `origin: "PARSER"`, `usage: []`다. result는 `contract_version: "document-chunks-v1"`, 요청 식별자·text_hash·프로필, `parser_version`, `manifest`, `excluded_ranges`, `diagnostics`, `chunks`를 포함한다. `result.text_hash`는 접두사를 포함해 요청값 그대로 반환하고, 내부 `manifest`의 해시는 접두사 없는 64자리다. manifest에는 실제 전체 청킹 프로필 및 예상 청크 key 목록이 있다. 각 청크에는 `chunk_key`, `chunk_no`, `document_version_id`, `text`, `embedding_text`, `token_count`, `source_spans`, `context_refs`, 표시용 `locator`가 있다.

`source_spans`의 시작·끝은 정규화 문자열의 Unicode code point 0-based, 끝 제외다. span별 `locator`는 1-based 행 양끝 포함이다. Java에서는 UTF-16 인덱스로 변환한 뒤 원문을 자른다. `text`는 span별 원문 조각을 순서대로 결합한 값이다. 청크의 `locator`는 첫 span부터 마지막 span까지의 **표시 범위**이며 전체 행을 결합해 text를 복원하면 안 된다. 한 행 안에서 분할한 청크들은 이 표시 범위가 겹칠 수 있다. `excluded_ranges`는 빈 행, 구분선, 컨테이너 표시 등 청크에 속하지 않는 원문을 명시한다.

`context_refs`는 heading/table header의 위치·해시와 문맥 적용 여부를 제공한다. 인용 근거는 원문 span이며 임베딩에는 `embedding_text`를 사용한다. 요청·응답의 실행 가능한 예시는 [청킹 계약 fixture](examples/document-chunks/)에 있다. Markdown은 `request.json`/`response.json`, `<user>` 태그가 든 일반 텍스트는 `plain-request.json`/`plain-response.json`이다.

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

### 216 BE 검토 계약 v2 — 222 AI 구현

222에서 아래 BE v2 계약을 따르는 AI 라우트와 모델 호출을 구현했다. 실제 배포 환경 연동 및 제품 정책 합의의 완료를 뜻하지 않는다. 이하 기존 v1 기본 필드·화면 확장 예시만으로 호출하면 v2 필수 출력 누락으로 BE가 거절한다.

| 구분 | v2 계약 |
| --- | --- |
| 요청 | 기존 project_id/document_id/revision/kind/content/request_key에 contract_version=2와 content_hash 추가. content_hash는 전송 문자열의 정규화 없는 UTF-8 SHA-256 |
| 참고 | 승인본만 전달. references[]에 reference_id=`document-version-{version ID}`, status=APPROVED, 전송 content_hash 추가 |
| 집합 해시 | reference_id ASCII 문자열 오름차순. 각 원소는 content를 제외한 content_hash/document_id/document_version_id/kind/reference_id/status/version_no를 모두 포함. 객체 키를 재귀적으로 사전순 정렬한 공백 없는 JSON의 UTF-8 SHA-256. null/키 생략 없음. 빈 집합은 [] |
| 응답 식별 | document_id/revision/**content_hash**/reference_hash/request_key echo 필수. BE가 저장 결과에 contract_version=2 부여 |
| 단계 | steps에 BODY/API_SPEC/ERD/REQ_SPEC 각각 1개. `{step,status,reason,reference_ids}`. BODY=COMPLETE/REVIEWED/[]; 동일 종류 비교=SKIPPED/SAME_KIND/[]; 승인본 없는 나머지 비교=PARTIAL 또는 SKIPPED/REFERENCE_UNAVAILABLE/[]; 승인본 있는 비교=COMPLETE/REVIEWED/해당 종류의 입력 ID 전체 |
| 전체 | 같은 종류 제외 후 자료 부족 단계가 하나라도 있으면 review_status=PARTIAL, 아니면 COMPLETE. references_used는 실제 비교 reference_ids 합집합. BE가 재계산·대조 |
| 제안 | type=PATCH/COMMENT, step, explanation, sources[] 필수. sources는 `{reference_id,quote?}`. BODY는 [], 비교 제안은 해당 단계 승인본 1개 이상. BE가 버전 메타데이터를 입력에서 조립하며 인용은 해당 본문에서 검증 |
| PATCH | original_text는 입력 본문에서 유일한 비어 있지 않은 구간. replacement_text는 빈 문자열 삭제 허용. 원문 구간 간 겹침, 적용 후 빈 문서·제어 문자·크기 초과 또는 다른 제안의 원문이 중복되는 결과는 전체 거절 |
| COMMENT | original_text/replacement_text/decision 없음 또는 null. 자동 적용/선택하지 않음. 미검토 단계의 제안은 허용하지 않음 |
| 실패/사용량 | origin=MODEL 및 유효한 성공 호출 usage 필요. 모델 실패·잘린 응답·스키마 위반을 PARTIAL 성공으로 반환하지 않음. 현재 feature=BEE_DRAFT, 전용 feature 전환은 별도 TODO |

[정상 완료 fixture](../../backend/src/test/resources/document/review-contract-complete.json), [자료 부족 fixture](../../backend/src/test/resources/document/review-contract-partial.json), [UTF-8 해시 벡터](../../backend/src/test/resources/document/review-reference-hash-vectors.json)를 222 인계 기준으로 사용한다. fixture의 MODEL/usage는 대역 시험용 합성값이며 실제 모델 실행 증거가 아니다. DISCUSS/REQUEST는 아래 BE 제안(S15P31A108-231)을 기준으로, RERUN은 아래 수정안 요청을 기준으로 AI 경로를 구현했다(S15P31A108-329). 계약 합의와 실제 배포 환경 연동의 완료를 뜻하지 않는다.

#### DISCUSS·REQUEST BE 제안 (S15P31A108-231, AI 담당과 합의 전)

BE는 v2 요청 필드(contract_version=2, 지금 본문, content_hash, reference_hash, references, request_key `document-chat-…`/`document-request-…`)에 아래를 더해 같은 `/v1/document-reviews`를 부른다. 응답은 v2 식별값(document_id·revision·content_hash·reference_hash·request_key)을 그대로 돌려주고 `origin=MODEL`, 실제 호출 `usage[]`(feature=BEE_DRAFT)를 가져야 한다. 템플릿 결과는 받지 않는다. 합의되면 이 절을 확정 계약으로 바꾼다.

- DISCUSS 요청: `mode=DISCUSS`, `suggestion`(`{id,type,step,explanation,original_text,replacement_text,decision}`), `messages`(`[{author_kind: HUMAN|BEE, text, proposed_text?}]`, 지금까지의 대화 + 새 사람 메시지, 최대 40개). 응답 result: `reply: {text(≤10,000자), proposed_text?(≤100,000자, 빈 문자열이면 그 부분을 지운다)}`. `suggestions`는 없거나 []이어야 하며 본문은 바꾸지 않는다.
- REQUEST 요청: `mode=REQUEST`, `request_text`(≤5,000자). 응답 result: `suggestions` 정확히 1개 `{type: PATCH, step, explanation, original_text, replacement_text, sources[]}`. step은 해당 검토에서 완료된 단계, sources는 그 단계의 출처 규칙(BODY는 [], 비교 단계는 해당 단계 승인본 1개 이상)을 따른다. original_text는 입력 본문에서 한 곳과만 일치하고 아직 처리하지 않은 다른 제안의 구간과 겹치지 않아야 한다(요청에는 그 구간을 싣지 않으며, BE가 저장 전에 검증하고 어기면 502 invalid_ai_output으로 거절한다).

329 AI 구현은 BE가 기존 검토의 완료 단계와 미처리 구간을 요청에 싣지 않으므로 REQUEST에서 본문과 사용자 요청만 모델에 전달하고 `step=BODY`, `sources=[]`인 PATCH 하나만 반환한다. 참고 문서를 근거로 한 비교 제안은 이 경로가 지원하지 않으며, 다른 미처리 제안과의 겹침은 BE가 검사한다. DISCUSS는 대화 응답과 적용 가능한 `PENDING` PATCH의 대체문을 제안하며 문서를 직접 바꾸지 않는다. 현재 요청에는 기존 제안의 출처가 없어 답변과 제안 출처의 일치를 검증하지 못한다.

RERUN은 양의 문자열 `rejection_submission_id`와 비어 있지 않은 `rejection_reason`(Java UTF-16 기준 최대 10,000자)을 필수로 받아 반려 사유를 데이터로 모델에 제공한다. RERUN 및 mode를 생략한 기존 호출을 포함한 REVIEW의 모든 제안에 category·severity를 요구하고 열거형을 검증한다. `REJECTION_FIX`는 반려 맥락이 있는 RERUN에만 허용하며, 해결된 반려 사유에 대해 제안을 강제로 만들지 않는다. BE는 가장 최근 제출이 REJECTED인 경우 이 필드를 전달하고, 제안의 분류 쌍을 검증·저장·반환한다. 기존 모델과 과거 제안에는 두 필드를 모두 생략할 수 있다. FE 표시와 실제 모델 연동 및 계약 합의는 별도 확인이 필요하다.


222 구현은 내부 토큰 인증과 최대 8 MiB 요청 본문을 적용하고, 입력 해시와 참고 자료 집합을 검증한 뒤 공통 LLM 클라이언트로 모델을 호출한다. AI 호출 제한은 설정값 `document_review_llm_timeout_seconds`(기본 30초, 0 초과 120 이하, 잘못된 값은 503 `document_review_not_configured`)다. BE 읽기 40초, nginx `/api/` 60초보다 짧게 유지한다(S15P31A108-382). 오류와 사용량은 공통 클라이언트 계약을 따른다. 문서 내용은 모델에 데이터로 전달하고, 검토 범위 확인과 응답의 출처·PATCH·저장 크기를 검증한다. BE/DB와 AI HTTP 경계는 가짜 모델을 사용하는 opt-in 통합 테스트로 검증한다. 2026-11-02 합성 문서로 실제 GMS COMPLETE·PARTIAL 호출과 BE → AI → GMS → 임시 DB의 COMPLETE 저장 및 사용량 기록을 확인했다. 배포 환경 연동, timeout/한도 합의 및 PARTIAL 제출 정책 확정은 별도 확인이 필요하다.

각 PATCH의 적용·건너뛰기와 적용 순서에 따른 중간 문서는 BE와 같은 UTF-8 1 MiB, 정규화 후 100,000 코드포인트, 비어 있지 않은 텍스트 및 제어 문자 기준을 지켜야 한다. 업로드 후에도 선두 BOM이 남아 있을 수 있으며, 이 경우 BE와 같이 PATCH 적용마다 선두 BOM을 최대 하나 제거해 결과와 남은 원문의 유일성을 검증한다. 검증 작업량이 상한을 넘거나, 선두 BOM이 새로 노출되거나 PATCH 경계를 가로지르는 CRLF 정규화로 적용 결과가 달라질 수 있어 안전성을 확인할 수 없으면 전체 응답을 `invalid_output`으로 거절하고 사용량은 실패 기록에 남긴다.

문서 검토 모델 호출 주소는 `GMS_CHAT_ENDPOINT`로 설정하며 생략하면 기존 GMS Chat Completions 주소를 사용한다. `infra/compose.yaml`은 배포 env 파일 또는 셸에서 이 값을 AI 컨테이너로 전달한다. GMS 키 전송을 보호하기 위해 HTTPS `gms.ssafy.io`의 기본 포트 또는 443만 허용하고 사용자 정보·쿼리·fragment는 거부한다. 경로는 `/chat/completions`로 끝나야 하며 잘못된 설정은 문서 검토에서 `model_unavailable`로 실패한다.

연동 회귀 테스트: `backend`에서 `BEEZ_RUN_AI_E2E=true`를 설정하고 `./gradlew integrationTest --tests '*DocumentReviewFastApiE2ETest'`를 실행한다. Docker와 AI 의존성이 설치된 `ai/.venv`가 필요하며, 다른 Python 실행 파일은 `BEEZ_E2E_PYTHON`으로 지정한다. 테스트는 로컬 FastAPI, 가짜 모델 및 임시 DB를 사용한다.

실제 GMS 검증은 `ai/.env`에 `GMS_KEY`를 설정한 후 `ai`에서 `python -m scripts.document_review_live_smoke --execute`를 실행한다. 실제 호출은 비용이 발생할 수 있으며 합성 fixture만 전송한다. 전체 BE·AI·GMS 경계는 `backend`에서 `BEEZ_RUN_REAL_GMS_E2E=true`와 `./gradlew integrationTest --tests '*DocumentReviewRealGmsE2ETest'`로 검증하며, 실제 모델 1회와 임시 DB를 사용한다. 키는 로그나 저장소에 포함하지 않는다.

### POST `/v1/document-reviews`

공용 문서함의 작성 내용을 승인 문서와 비교해 보완 제안을 만든다. DOC-A16(검토)이 부른다. 기본 필드와 검증은 dev BE 구현(`AiClient.documentReview`, `DocumentReviewService`의 reviewInput·validateResult)과 같다. 화면에 더 필요한 필드는 '수정안 요청' 확장으로 붙였고, DOC-A22(대화)·DOC-A23(직접 요청)도 같은 경로를 쓴다. 아래 기존 v1 예시와 화면 확장 필드는 v2 전체 계약이 아니며, 확장 기능은 별도 합의가 필요하다.

관련 요구사항: DOC-01·08 (화면 PNL-02-m-review-*)  
담당: BE: 오서현 / AI: 양지훈 / FE: —

상태: BE 호출·검증 및 AI 라우트 구현 · 합성 문서로 실제 GMS 연동 확인 · 배포 환경 연동 전 · 확장 필드는 수정안 요청 (협의 필요)

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
- BE는 제안의 category·severity를 한 쌍으로 검증해 저장·반환한다. 과거 미분류 제안은 null이다. evidence와 화면 표시는 별도 연동이 필요하다.
- (수정안 요청) mode별 결과: REVIEW·RERUN은 suggestions 목록, REQUEST는 suggestions 1개다. DISCUSS는 reply가 필수이고 suggestions는 []다. 네 mode 모두 result의 식별값 4개와 summary를 돌려준다.
- (수정안 요청) steps는 BE가 DOC-A16의 단계 표시(DocumentBeeReview.steps)에 쓴다. REVIEW 기본: 문서 읽기 → 승인된 API 명세서와 비교 → ERD와 맞춰 보기 → 요구사항과 비교 → 수정 제안 정리. RERUN: 반려 사유 읽기 → 관련 문단 찾기 → ERD와 맞춰 보기 → 수정 제안 정리. 비교할 승인본이 없는 단계는 result에 그 사유를 쓴다.
- (수정안 요청) RERUN은 BE가 DOC-A16 선택 규칙으로 고른 rejection_submission_id와 그 rejection_reason을 넣는다. 비비는 사유와 이어진 문단을 찾아 category=REJECTION_FIX 제안을 만든다. 팀장은 문단을 고르지 않는다.
- 이 문서의 동기 응답 원칙을 따른다. 실제 단계별 진행률을 보이려면 BE가 단계마다 나눠 부르거나 스트리밍이 필요하며, 방식은 Q19다.
- 입력 크기·비용 상한과 실패 때 재시도는 Q11·Q19다.

<a id="ai-a11"></a>

## AI-A11. 작업 시작 설계 근거 추천 (#185 제안)

### POST `/v1/design-reference-recommendations`

**상태: AI 내부 계약 구현·BE 연동 전.** 승인된 설계 후보를 선택하고 추천 이유를 반환한다. 공개 CTX-A05의 변경이나 #131·#223·프론트 연동이 완료됐다는 뜻은 아니다. 상세 필드·요청/응답 예시는 [#185 내부 계약](../design/design-reference-recommendation-185.md)을 따른다.

BE는 권한과 현재 baseline을 확인한 뒤 검색 결과와 명시적 관계를 기반으로 허용된 승인 설계 후보를 전달하고, 응답을 공개하기 전에 권한·기준 버전을 재확인한다. 검색 준비 중 또는 검색 실패를 BE가 알면 **AI를 호출하지 않는다.** 공개 `search_not_ready`·`search_failed`는 BE가 발생시키는 오류이며 공개 API의 상태 코드는 BE·FE와 협의한다.

요청에는 프로젝트·요청·호출 ID, 자연어 요청, 검색 관찰·잘림·매핑 상태, 알려진 생략 후보와 미조회 범위, 승인 설계 후보를 포함한다. 항목의 저장 출처와 검색 원문은 별도로 전달한다. `VERIFIED`는 BE가 실제 위치를 검증했다는 표시이며 AI의 원문 확인을 뜻하지 않는다. 문서 출처 없는 승인 설계도 추천할 수 있고 위치를 추측하지 않는다.

응답 `result.status`는 `RECOMMENDED`·`EMPTY`·`INSUFFICIENT`다. 부분 결과에는 이유와 확인된 생략 ID·선택적 설계 키를 보존한다. `unexamined_count`는 식별된 생략 후보와 별개의 **전체 미조회 설계 후보 총수**를 확실히 알 때만 기록하며, 검색이 잘린 현재 경로에서는 `null`이다. AI는 미조회 후보의 ID·키를 추측하지 않는다. 모델 호출이 없는 경우 `origin=TEMPLATE`, `usage=[]`다.

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 형식 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 422 | `invalid_ai_input` | 후보·출처·검색 상태의 업무 구조 모순 또는 호출 전 AI 입력 오류. `NOT_READY`·`FAILED`를 AI에 전달한 경우 포함 |
| 502 | `model_unavailable` | 모델 호출 실패 또는 출력 계약 위반 |
| 503 | `recommendation_not_enabled` | 모델 호출이 필요한데 추천 기능 비활성화 |
| 503 | `recommendation_not_configured` | 모델 호출 설정·키 미구성 |
| 504 | `model_timeout` | 모델 시간 초과 |

`recommendation_not_enabled`·`recommendation_not_configured`는 모델 호출 전 오류다. 이미 호출한 모델의 실패는 확인 가능한 usage를 포함한다. 알려진 분석 대상은 본문을 조용히 잘라 원문으로 취급하지 않으며 16,000바이트·20개 분석 한도를 넘는 후보는 사유와 함께 생략한다. 입력 자체의 안전 한도와 최종 공개 오류 매핑은 BE·FE와 정한다.

**BE 공개 오류 매핑 제안 — AI 응답 코드가 아님:**

| HTTP | code | 상황 |
| --- | --- | --- |
| 503 | `search_not_ready` | BE가 검색 색인이 준비되지 않았다고 판단. AI 미호출 |
| 502 | `search_failed` | BE 검색 호출 실패. AI 미호출 |

공개 CTX-A05와 화면에서 위 코드·상태를 사용하는지는 BE·FE 확인이 필요하다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ai-a14"></a>

## AI-A14. 작업 계획 문서 초안

### POST `/v1/task-plan-drafts`

**상태: AI 내부 계약 구현(S15P31A108-385), 계약 합의 전 초안.** BE가 고른 승인 문서 발췌와 승인 설계 항목으로 작업 계획 칸 초안, 확인된 인용, 연결 후보, 신규 설계 항목 후보를 만든다. 아무것도 저장하거나 승인하지 않는다. 공개 API는 [TSK-A08](01-backend-rest.md#tsk-a08)이다.

관련 요구사항: PLN-02·03·06, TSK 계획 작성
담당: BE: 오서현 / AI: 확인 필요 / FE: —

#### 인증

`INTERNAL_SERVICE`. 내부 Bearer 토큰으로 인증한다. 외부 사용자 세션이나 에이전트 토큰은 허용하지 않는다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 프로젝트 |
| `request_key` | `string` | Y | 호출 추적 키. 사용량 `request_key`에 이 값이 포함된다 |
| `task` | object | Y | `title`(1~200자), `memo`(선택, 최대 2000자) |
| `excerpts` | object[] | Y | 1~12개. `excerpt_id`, `document_id`, `document_type`(`PLAN\|REQUIREMENTS\|API_SPEC\|ERD`), `document_version_id`, `version_no`, `location`, `text`(1~4000자, 원문 그대로) |
| `design_items` | object[] | N | 최대 100개. 현재 baseline의 승인 항목 `design_item_id`, `kind`, `key`, `summary`(최대 300자) |

본문은 알 수 없는 필드를 받지 않는다. 발췌와 항목의 ID는 각각 중복될 수 없다. 본문 최대 크기는 `TASK_PLAN_DRAFTS_MAX_BODY_BYTES`(기본 524,288)다.

#### Response

HTTP `200`. `{result, origin: "MODEL", model, usage[]}`. `result`는 [TSK-A08 응답](01-backend-rest.md#tsk-a08)과 같은 모양이다. 다만 `design_ref_candidates`는 `design_item_id`와 `reason`만 가지며 버전 ID·키·제목은 BE가 채운다.

AI는 모델 출력을 입력에 비춰 확인한다.

- `evidence`·신규 항목의 `evidence`: 발췌 `excerpt_id`가 입력에 있고 `quote`가 그 발췌 `text`에 그대로 들어 있어야 한다. 아니면 버린다. 문서 ID·버전·위치는 발췌에서 가져온다.
- 계획 칸에 글은 있지만 확인된 인용이 없으면 `unsupported_fields`에 담는다. 글을 지우지는 않는다.
- `design_ref_candidates`: 입력의 `design_items`에 있는 ID만, 중복 없이 남긴다.
- `new_design_item_candidates`: `DesignItemContent` 검증(종류별 필수 필드 포함)을 통과하고 확인된 인용이 하나 이상 있어야 한다.
- 계획 칸이 모두 비어 있으면 `status`는 `INSUFFICIENT`다.
- 출력은 목표 400자, 접근 방법 600자, 목록 6개·항목당 200자, 인용 8개, 연결 후보 5개, 신규 후보 3개, `unlinked` 5개로 제한한다(입력 3~8천·출력 1천 토큰 목표).
- **예산을 조금 넘긴 응답은 넘친 부분만 걸러 성공으로 돌려준다.** 설정한 출력 예산(목표·접근 방법 글자 수, 목록 개수·항목 글자 수, 인용 글자 수, 후보 개수)을 넘긴 응답은 글을 중간에서 자르지 않고 전체 항목 단위로만 처리한다. 개수가 넘치면 앞에서부터 예산만큼 남기고, 글자 수가 넘친 목록 항목·인용·후보 이유는 그 항목만 버리고, 글자 수가 넘친 목표·접근 방법은 그 칸을 비운다. 인용은 원문 확인이 목적이라 자르지 않는다. 버려진 인용의 칸은 `unsupported_fields`에 올라간다. 예산을 **2배 넘게**(예산 0·1은 2개 넘게) 어긴 응답은 지시를 따르지 않은 것으로 보고 고치지 않고 `model_unavailable`로 거절한다. 구조·타입·ID·인용 검증은 걸러 낸 뒤에도 그대로 한다. 걸러 낸 개수는 진단 로그(`task_plan_trimmed`)에 항목 이름별로만 남기고 내용은 남기지 않는다.

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 형식 오류 |
| 401 | `invalid_internal_token` | 내부 인증 실패 |
| 413 | `request_too_large` | 본문 크기 초과 |
| 415 | `unsupported_media_type` | JSON이 아님 |
| 422 | `invalid_ai_input` | 발췌·항목 ID 중복 등 업무 구조 모순 |
| 502 | `model_unavailable` | 모델 호출 실패 또는 출력 계약 위반(실제 사용량은 `failed`로 남김) |
| 502 | `model_output_truncated` | 모델 답이 출력 토큰 상한에서 끊김(S15P31A108-382) |
| 503 | `task_plan_drafts_not_configured` | 시간 제한·출력 상한 설정값이 범위를 벗어남 |
| 504 | `model_timeout` | 모델 시간 초과 |

설정(요청마다 범위를 확인한다): `TASK_PLAN_DRAFTS_LLM_TIMEOUT_SECONDS`(기본 30, 0 초과 120 이하), `TASK_PLAN_DRAFTS_LLM_OUTPUT_TOKEN_LIMIT`(기본 4096, 양의 정수, 공급자 모델 한도 적용), `TASK_PLAN_DRAFTS_LLM_OUTPUT_TOKEN_PARAMETER`(`max_completion_tokens` 또는 `max_tokens`), `TASK_PLAN_DRAFTS_LLM_REASONING_EFFORT`(기본 `low`, 빈 값이면 옵션 생략). 출력 토큰 예산은 내부 추론을 포함한다. 기존 GMS 연결을 사용하며 자동 증액·추가 재호출하지 않는다.

운영 응답 예산은 아래 환경변수로 조정한다. 프롬프트와 응답 검증에 동일하게 적용한다. 상한을 넘긴 응답을 임의로 잘라 성공 처리하지 않는다. API 스키마의 최대 크기는 호환성을 위한 별도 계약으로 유지한다.

| 환경변수 (`TASK_PLAN_DRAFTS_` 접두사) | 기본값 | 허용 범위 |
| --- | --- | --- |
| `GOAL_CHARS` | 160 | 1~400 |
| `APPROACH_CHARS` | 240 | 1~600 |
| `LIST_ITEMS` | 3 | 1~6 (범위·완료 기준 각각) |
| `LIST_ENTRY_CHARS` | 100 | 1~200 |
| `EVIDENCE_ITEMS` | 5 | 0~8 |
| `QUOTE_CHARS` | 160 | 1~1000 (신규 항목 근거 포함) |
| `LINK_CANDIDATES` | 3 | 0~5 |
| `REASON_CHARS` | 100 | 1~200 |
| `NEW_ITEMS` | 3 | 0~3 |
| `UNLINKED_ITEMS` | 3 | 0~5 |

BE 입력 예산도 환경변수로 관리한다: `TASK_PLAN_DRAFT_RETRIEVAL_LIMIT`(12, 1~12), `TASK_PLAN_DRAFT_MAX_EXCERPT_CHARS`(4000, 1~4000), `TASK_PLAN_DRAFT_TOTAL_EXCERPT_CHARS`(12000, 단일 발췌 예산 이상), `TASK_PLAN_DRAFT_MAX_DESIGN_ITEMS`(100, 0~100), `TASK_PLAN_DRAFT_DESIGN_SUMMARY_CHARS`(300, 1~300). 문서 발췌는 글자 예산이며 모델 토큰 수와 같지 않다. 계층형 색인 확장은 이번 범위에서 제외한다. 설정 변경 후 해당 서비스를 재시작한다. 로컬 AI는 `ai/.env`, Compose는 실행 시 읽는 환경변수/`--env-file`에서 설정한다.

385 입력 최적화: BE는 검색 점수와 질의 단어 일치로 재정렬하고, 제목만 있는 청크·본문 중복을 제외한다. 단독 Markdown 표는 일치하는 행과 원래 헤더를 보낸다(문장 혼합·의미 검색만 일치하는 표는 유지). `TASK_PLAN_DRAFT_SELECTED_EXCERPTS`(6, 1~12), `TASK_PLAN_DRAFT_SIMILARITY_DROP`(0.15, 0~2: 최상위 유사도와 허용 차이), `TASK_PLAN_DRAFT_LEXICAL_WEIGHT`(0.2, 0~1)를 조정할 수 있다. 질의 단어가 있는 청크는 상대 점수 필터의 예외다. 이는 전체 DB 하이브리드 검색이나 검색 품질 보증이 아닌, 이미 검색된 후보의 로컬 재정렬이다.

승인 설계는 현재 baseline 전체에서 질의 단어·원문에 명시된 키로 관련 항목을 선택하고 확정된 명시 의존을 1단계만 추가한다. 모델 입력 개수 제한과 신규 후보 중복 검사 범위를 분리한다. 원문 인용은 압축된 입력뿐 아니라 BE의 원래 청크에서도 실제 부분 문자열인지 대조한다. 유효한 인용이 사라진 필드는 `unsupported_fields`로 표시하고, 유효 근거 없는 신규 후보는 제외한다.

AI의 `TASK_PLAN_DRAFTS_INPUT_TOKEN_LIMIT`(4096), `TASK_PLAN_DRAFTS_INPUT_RESERVE_TOKENS`(128)는 시스템 지시문·JSON·프레이밍 예비분을 포함한 **오프라인 cl100k 추정 예산**이다. 실제 gpt-6-luna/GMS 청구 토큰과 같음을 보장하지 않는다. 순위가 높은 발췌와 설계를 번갈아 넣고, 넘치는 항목은 통째로 제외한다. 발췌가 하나도 들어가지 않으면 유료 생성 호출 전 422로 종료한다. 원문·인증 정보를 로그에 쓰지 않으며, `task_plan_input`과 `task_plan_completion`의 `call_hash`로 추정 입력량·실제 입출력/추론 토큰·종료 사유·생성 시간을 대조한다. 추론 토큰이 공급자 응답에 없으면 0이 아닌 미확인(`None`)이다. usage API/DB 계약에는 진단 필드를 추가하지 않는다.

사용량 `feature`는 `BEE_DRAFT`다. 문서 발췌는 신뢰할 수 없는 입력이라 모델에 지시가 아닌 데이터로 알린다.

[공통 규약·권한·담당자 목록](00-common.md)
