# 답변 반영 설계 초안 (#134)

권한을 확인한 **문서 고정 버전·원문**, #133 **질문·답변**, BE가 가진 **기존 설계 항목 버전**을 입력으로 받아 미승인 **설계 항목 초안(DRAFT)** 과 **답변 전후 변경 목록**을 만든다. 저장·ID 발급·승인·발행은 BE(오서현) 담당이고 이 모듈은 입력을 바꾸거나 저장하지 않는다.

계약 초안: [docs/design/answer-design-draft-contract-S15P31A108-134.md](../../../../docs/design/answer-design-draft-contract-S15P31A108-134.md). 예시: `docs/api/examples/design-drafts/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/design_drafts.py` | 요청(기존 항목·답변된 질문)·초안·변경·결과 pydantic strict 모델, DesignItemContent v1 검증 |
| `proposer.py` | 공통 `LLMClient`를 주입받는 모델 제안 어댑터(`LLMDraftProposer`)와 출력 모델 |
| `service.py` | 파싱 → 모델 제안 → 인용·기준 항목·답변 키 검증 → 변경 목록 → 정렬·상한 → 봉투 |
| `app/routers/design_drafts.py` | 기본 비활성 내부 경로 `POST /v1/design-drafts`. 요청 스키마는 `app/core/openapi.py`로 `$defs` 참조를 풀어 OpenAPI 문서에 싣는다 |

## 동작

1. `parse_source`로 블록·행 범위를 만들고 모델에 블록 원문, 기존 항목(ID·키·종류·상태·본문), `ANSWERED` 질문(키·문구·답변·근거 인용)을 넘긴다. 미답변·닫힌 질문은 개수만 전달한다.
2. 모델 제안의 각 항목을 검증한다.
   - 같은 기준 항목을 가리키는 제안은 등장 순서대로 하나로 묶는다(`group_proposals`). 모델이 한 항목의 변경을 여러 제안으로 나눠 돌려줘도 버려지지 않는다.
   - 기준 항목 ID는 입력에 있어야 하며 종류가 같아야 하고, 답변 참조는 `ANSWERED` 질문 키여야 한다. 갱신이면 기준 항목 본문에 모델이 돌려준 내용을 차례로 합친다(`merge_content` → `apply_patch`): 생략한 키는 기존 값을 보존하고, 객체 값은 모든 깊이에서 키 단위로 합치며, 배열·스칼라는 통째로 교체하고, JSON `null`은 그 키를 삭제한다. 삭제 규칙은 모델 패치에만 적용되고 기존 본문의 `null` 값 필드는 입력 그대로 둔다. 묶인 제안의 확인된 인용·답변 참조는 중복 없이 모두 모으고 `rationale`은 이어 붙인다. 두 제안이 같은 경로에 다른 값을 주거나 통째로 바꾼 값(빈 객체 포함) 안의 키를 정하면 뒤의 값이 남고 그 최상위 필드는 `conflicting_fields`(`conflicting_fields()`)에 적힌다. 충돌 끝에 기존 값으로 돌아온 초안도 검토용으로 남긴다. 합친 본문은 `description`이 있어야 하고 종류가 요구하는 필드(API `method`·`path`, DATA `entity`·`fields`)만 BE `DesignContentValidator`와 같은 규칙으로 검사하며 나머지 JSON 필드는 그대로 둔다. 다만 BE `DesignJson`이 저장을 거부하는 본문(모든 깊이의 빈 필드명, 32단계 초과 중첩, JSON이 아닌 값, 정규화 JSON 1 MiB 초과)은 `check_json_content`로 함께 거부한다. 어기면 `invalid_output`(usage는 실패로 기록).
   - 인용은 블록 원문의 부분 문자열일 때만 남기고 나머지는 버린다. 남은 인용·답변 참조로 `support`를 정한다.
3. 기준 항목이 있으면 `before`(기존 본문)와 `after`(초안)의 최상위 필드 차이를 `changed_fields`로 만든다. 각 항목은 `ADDED`·`CHANGED`·`REMOVED`로 구분하며, 비교는 JSON 직렬화 기준이라 `true`와 `1`도 다르고 필드 없음과 `null`도 다르다(BE가 둘을 다른 JSON으로 보존). 차이도 충돌도 없으면 초안이 아니므로 버리고 기준 항목을 `untouched_existing_ids`에 둔다. 기준 항목당 초안은 하나다.
4. 근거 등급(`DOCUMENT_AND_ANSWER` → `ANSWER` → `DOCUMENT` → `NONE`) 순으로 정렬하고 `max_items`로 자른다. 잘린 수는 `omitted_items`.

승인본은 바뀌지 않는다. 입력의 `APPROVED` 항목은 `before`에 그대로 나오고 출력에는 `DRAFT` 상태만 있다. 초안 키 `draft-<해시>`는 종류·기준 항목·본문에서 만들어 출력 순서와 무관하다.

## 사용

```python
from app.schemas.design_drafts import DesignDraftRequest
from app.services.design_drafts import LLMDraftProposer, generate_design_drafts

outcome = await generate_design_drafts(request, proposer)
outcome.to_api_dict()  # {"result", "origin": "MODEL", "model", "usage"}
```

`LLMDraftProposer(client, context=LLMContext(project_id, feature="BEE_DRAFT", request_key), model, call_key, timeout_seconds)`는 `context.project_id`가 요청·문서의 프로젝트와 같아야 한다. 요청은 strict 모델이므로 JSON 본문은 `DesignDraftRequest.model_validate_json`으로 읽는다.

HTTP 경로는 `DESIGN_DRAFTS_ENABLED=true`, 내부 토큰, `GMS_KEY`가 있어야 동작한다. 본문 상한 `DESIGN_DRAFTS_MAX_BODY_BYTES`(기본 2 MiB), 모델 시간 `DESIGN_DRAFTS_LLM_TIMEOUT_SECONDS`(기본 45), 출력 토큰 `DESIGN_DRAFTS_LLM_OUTPUT_TOKEN_LIMIT`(기본 4096).

## 검증

```text
python -m pytest tests/test_design_drafts_schema.py tests/test_design_drafts_service.py tests/test_design_drafts_api.py tests/test_design_drafts_evaluation.py -q
python -m evaluations.design_drafts.run                 # 가짜 공급자 검토 보고서 출력
python -m scripts.generate_design_draft_examples        # 예시 JSON·검토 보고서 재생성
```

실제 모델 평가는 아직 하지 않았다. `evaluations/design_drafts/README.md`를 따른다.
