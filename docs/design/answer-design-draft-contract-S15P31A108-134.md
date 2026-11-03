# 답변 반영 설계 초안 계약 초안 (#134)

작성: 박상은 · Jira: S15P31A108-134 · 대상: 오서현(PLN-A05 답변 반영 설계 초안 생성, 저장·승인·발행), 화면 S15P31A108-202 · 선행: [#133 질문·답변 계약](planning-question-answer-contract-S15P31A108-133.md)

**제안 상태다.** 권한을 확인한 문서 고정 버전과 #133 질문·답변, BE가 보유한 설계 항목 버전을 입력으로 받아 **미승인 설계 항목 초안(DRAFT)**, 항목별 출처(원문 인용·답변 참조), **답변 전후 차이를 보여 주는 변경 목록**을 돌려주는 AI 쪽 계약을 적는다. 저장·ID 발급·승인·발행은 BE가 맡고 AI는 입력을 바꾸지 않는다. 실제 BE→AI 호출 위치와 저장 방식은 담당자 합의가 남았다(9절).

## 1. 흐름과 책임

1. BE가 PLN-A05 요청을 접수하면 권한을 확인한 **문서 고정 버전·원문**, 그 버전의 `planning_question` 행(질문·근거·답변 상태·답변), 그리고 문서에서 나온 **기존 설계 항목 버전**(DRAFT 또는 현재 APPROVED)을 AI에 보낸다. #128 구조화 추출이 없으면 기존 항목은 빈 목록이어도 된다.
2. AI는 원문을 #127 파서로 블록·행 범위로 만들고, 모델에 블록 원문·기존 항목·답변된 질문을 넘겨 초안 제안을 받은 뒤, 모든 인용을 블록 원문과 대조하고 기준 항목 ID·답변 키를 입력과 대조한다. 각 초안에 기준 항목의 **before**와 초안의 **after**, 바뀐 필드, 답변 참조를 붙인다.
3. BE는 초안을 DRAFT 항목 버전으로 저장한다(`origin=AI_PROPOSED`). 기준 항목이 APPROVED이면 그 버전은 그대로 두고 새 DRAFT 버전을 만든다. 승인·발행은 PLN-A10 등 기존 경로다. 사용자는 화면 202에서 before/after를 보고 초안을 고치거나 버린다.

구현: `ai/app/schemas/design_drafts.py`(모델), `ai/app/services/design_drafts/`(모델 제안·검증·변경 목록, [README](../../ai/app/services/design_drafts/README.md)), `ai/app/routers/design_drafts.py`(기본 비활성 경로). 예시: `docs/api/examples/design-drafts/`. 검토 자료: `ai/evaluations/design_drafts/`.

## 2. 요청: `POST /v1/design-drafts`

`INTERNAL_SERVICE` Bearer 인증, `Content-Type: application/json`, `X-Request-Id` 선택. 기본 비활성이며 `DESIGN_DRAFTS_ENABLED=true`와 `GMS_KEY`가 필요하다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id`, `request_key` | ID, string ≤ 256 | Y | #133과 같음. 실제 모델 호출 key는 `llm-design-drafts-<uuid>` |
| `document` | `{document_id, document_version_id, version_no}` | Y | 분석할 고정 버전 |
| `kind`, `media_type`, `content`, `content_hash`, `text_hash`, `normalization_version`, `parser_profile` | — | Y | #133 요청과 같음(#127 파서 계약) |
| `existing_items[]` | ExistingDesignItem | N | 문서의 기존 설계 항목 버전. 최대 500. `design_item_version_id`·`key`는 서로 달라야 한다 |
| `questions[]` | AnsweredQuestion | N | 저장된 #133 질문. 최대 200, `question_key` 중복 금지 |
| `max_items` | integer 1~100 | N | 초안 수 상한. 기본 50 |

ExistingDesignItem: `design_item_version_id`, `design_item_id`, `key`(표시 키), `kind`(5종), `version_no`, `status`(`DRAFT|APPROVED`), `schema_version`(1), `content`(DesignItemContent v1), `source?`(00-common Source). 종류별 필수 필드(API `method`·`path`, DATA `entity`·`fields`)가 없으면 400이다.

AnsweredQuestion: `question`(#133 `SupplementQuestion` 그대로), `evidence[]`(#133 `EvidenceItem`, 이 문서 버전 소속), `status`(`OPEN|ANSWERED|DISMISSED`), `answer`(#133 `QuestionAnswer`, `ANSWERED`일 때만). 답변은 질문의 `answer_format`에 맞아야 하며(`validate_answer`) 어긋나면 422 `invalid_ai_input`이다. 모델에는 `ANSWERED` 질문만 본문이 전달되고 나머지는 개수만 전달된다.

## 3. 응답

성공 본문은 `{"result", "origin": "MODEL", "model", "usage"}`이며 `usage`는 실제 호출별 Usage(`feature=BEE_DRAFT`)다. 모델 없는 경로는 없다.

`result`:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `document`, `kind`, `text_hash`, `parser_profile`, `max_items` | — | 요청 값. BE는 저장 전 같은 버전인지 대조한다 |
| `items[]` | DraftItem | 초안. 근거 등급 순(DOCUMENT_AND_ANSWER → ANSWER → DOCUMENT → NONE), 같은 등급 안에서는 모델 순서 |
| `changes[]` | DraftChange | `items`와 같은 순서·개수. 답변 전후 차이 |
| `untouched_existing_ids[]` | ID | 어떤 초안도 건드리지 않은 기존 항목 버전. BE는 그대로 둔다 |
| `unanswered_question_keys[]` | string | `OPEN`·`DISMISSED` 질문 키 |
| `unresolved[]` | string ≤ 2,000 | 문서와 답변으로 정하지 못한 점(모델 서술, 최대 50) |
| `omitted_items` | integer | `max_items` 상한으로 버린 초안 수 |

DraftItem:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `candidate_key` | string | `draft-<해시 12자리>`: 종류·기준 항목·본문의 해시. 결과 안의 임시 키이며 최종 ID가 아니다 |
| `kind` | 5종 | 기준 항목이 있으면 그 종류와 같다 |
| `status` | `DRAFT` | 유일한 값. 스키마에 다른 상태가 없다 |
| `schema_version` | 1 | DesignItemContent v1 |
| `content` | DesignItemContent | `description` 필수, 종류별 필수 필드 검증, 추가 필드 보존 |
| `base_design_item_version_id`, `base_status` | ID \| null, `DRAFT\|APPROVED` \| null | 갱신 대상 기존 버전과 그 상태. APPROVED 기준은 편집되지 않고 새 DRAFT 버전의 바탕일 뿐이다 |
| `sources[]` | EvidenceItem | **확인된 원문 인용만**(블록 ID·행 범위·위치·인용, 최대 5). 블록에 없는 인용은 버린다 |
| `answer_refs[]` | string | 이 초안이 기댄 답변의 `question_key`. 입력의 `ANSWERED` 질문만 허용 |
| `support` | `DOCUMENT_AND_ANSWER\|ANSWER\|DOCUMENT\|NONE` | 출처 등급. `NONE`은 검토자가 대조할 근거가 없는 항목 |
| `rationale` | string ≤ 2,000 | 문서·답변이 무엇을 확정했는지(한국어) |

DraftChange:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `candidate_key` | string | 초안 키 |
| `change` | `CREATE\|UPDATE` | 기준 항목이 있으면 UPDATE |
| `base_design_item_version_id` | ID \| null | UPDATE의 기준 버전 |
| `before` | DesignItemContent \| null | 기준 항목의 **현재 본문 그대로**(답변 전) |
| `after` | DesignItemContent | 초안 본문(답변 후). `items[].content`와 같다 |
| `changed_fields[]` | `{field, before, after}` | 최상위 본문 필드 중 JSON 값이 다른 것. UPDATE는 1개 이상 |
| `answer_refs[]` | string | 초안과 같음. 어떤 답변이 변경을 이끌었는지 |

## 4. 검증 규칙

- 인용(`sources`)은 `block_id`가 문서에 있고 `quote`가 그 블록 원문의 부분 문자열일 때만 남긴다. 아닌 인용은 조용히 버리고 `support`를 다시 계산한다(#133과 같은 기준).
- 기준 항목 ID가 입력에 없거나 종류가 다르면, 답변 참조가 `ANSWERED` 질문 키가 아니면, 본문이 종류별 필수 필드를 어기면 **출력 전체를 거부**한다(502 `model_unavailable`, usage는 실패로 기록). 모델이 ID·키를 지어낸 경우다.
- 기존 항목과 본문이 같은 "갱신"은 변경이 아니므로 버리고 그 항목을 `untouched_existing_ids`에 둔다. 같은 기준 항목에 대한 두 번째 초안도 버린다(기준 항목당 초안 1개).
- 종류·기준·본문이 같은 초안이 둘이면 첫 번째만 남긴다.
- 승인본 불변: 입력의 APPROVED 항목 본문은 `before`에 그대로 나오고, 출력 어디에도 `APPROVED` 상태의 항목은 없다. 테스트 `test_update_of_an_approved_item_keeps_the_approved_content_as_before`와 평가 검사 `approved_bases_kept_as_before`가 이를 확인한다.

## 5. 상한

| 항목 | 값 |
| --- | --- |
| 초안 수 | `max_items`(기본 50, 1~100). 초과분은 근거 등급이 낮은 순으로 버리고 `omitted_items`에 수를 남긴다 |
| 기존 항목 / 질문 | 500 / 200 |
| 초안당 인용 / 답변 참조 | 5 / 10 |
| `unresolved` | 50건, 각 2,000자 |
| 모델 입력 | 블록·기존 항목·답변 JSON 256 KiB 초과 시 422 `invalid_ai_input` |
| 본문 | `DESIGN_DRAFTS_MAX_BODY_BYTES` 기본 2 MiB |

## 6. 저장 제안 (오서현 합의 대상)

| AI 필드 | 저장 제안 |
| --- | --- |
| `items[]` | `design_item_version` 새 행: `status=DRAFT`, `origin=AI_PROPOSED`, `schema_version=1`, `content`, `title=content.description`, `source_document_version_id`=요청 문서 버전, `source_locator`=`sources[0].locator`(있을 때) |
| `base_design_item_version_id` | 같은 `design_item_id`의 다음 `version_no`로 저장. APPROVED 기준은 수정하지 않는다 |
| CREATE | 새 `design_item`(키 발급)과 v1 DRAFT |
| `sources[]`, `answer_refs[]`, `support`, `rationale` | 화면 202 검토용. 저장 위치(예: `design_item_version.content` 밖의 메타 JSONB 또는 별도 표)는 합의 필요 |
| `changes[]` | 저장하지 않아도 `before`는 기존 버전에서 재구성할 수 있다. 화면에는 응답 그대로 쓴다 |
| `candidate_key` | 한 분석 결과 안의 임시 키. 재분석 시 같은 종류·기준·본문이면 같은 키 |

## 7. 오류

형식은 `{"error": {"code", "message"}}`이며 모델 호출 뒤 실패면 `usage`를 덧붙인다. 예시: `docs/api/examples/design-drafts/errors.json`.

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `invalid_internal_token` | 내부 토큰 누락·불일치 |
| 503 | `design_drafts_not_configured` | 기본 비활성 또는 한도 설정 오류 |
| 400 | `validation_failed` | JSON·필드·허용 값 오류 |
| 422 | `invalid_ai_input` | 중복 항목·질문, 답변 형식 불일치, 파서 거부(`details.reason`), 모델 입력 256 KiB 초과 |
| 413 / 415 | `request_too_large` / `unsupported_media_type` | 본문 크기 / 미디어 타입 |
| 502 | `model_unavailable` | 모델 호출 실패, 출력 구조 위반, 입력과 모순되는 ID·키, `GMS_KEY` 없음 |
| 504 | `model_timeout` | `DESIGN_DRAFTS_LLM_TIMEOUT_SECONDS`(기본 45초) 초과 |
| 502 | `design_drafts_failed` | 그 밖의 AI 내부 오류 |

## 8. 예시와 검증 자료

- `docs/api/examples/design-drafts/request.json`: 가상 기획서 `plan.md`에 #133 질문 7건(답변 4건, 닫힘 1건, 미답변 2건)과 기존 항목 3건(APPROVED 1, DRAFT 2)을 붙인 요청. `response.json`은 대본 가짜 공급자로 만든 응답, `errors.json`은 상태별 오류.
- `ai/evaluations/design_drafts/results-2026-11-03-dry.md`: 답변 표와 초안·변경 목록 표. 승인본 갱신(before 보존), 기존 초안 갱신, 답변 기반 신규, 원문만 기반 신규, 조작 인용 제거, 근거 없는 항목(맨 뒤), 동일 재진술 제외를 모두 담았다.

## 9. 담당자 확인 필요·미완료

- 오서현: BE→AI 호출 위치(PLN-A05 job), 기존 항목 목록 범위(해당 문서 출처 항목만인지 프로젝트 전체인지), 6절 저장 방식, 화면 202의 before/after·`support`·`unresolved` 표시, DRAFT 저장 후 PLN-A06 목록 노출.
- `docs/api/03-ai-internal.md`에 AI-A13으로 추가할지, AI-A06 `/v1/bee/draft`(문서 수정안 텍스트)와의 관계 정리.
- `BEE_DRAFT` feature 사용 여부(기존 `llm_usage.feature` 값에 있음).
- 실제 모델 평가는 하지 않았다(가짜 공급자만). 프롬프트와 `unresolved` 서술 품질은 실측 뒤 조정한다.
- 영어 문서·`plain-v1` 입력의 품질은 미확인.

## 10. 검증

```text
cd ai
python -m pytest tests/test_design_drafts_schema.py tests/test_design_drafts_service.py tests/test_design_drafts_api.py tests/test_design_drafts_evaluation.py -q
python -m ruff check .
python -m ruff format --check .
python -m evaluations.design_drafts.run
```
