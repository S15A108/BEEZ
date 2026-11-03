# 답변 반영 설계 초안 (#134)

권한을 확인한 **문서 고정 버전·원문**, #133 **질문·답변**, BE가 가진 **기존 설계 항목 버전**을 입력으로 받아 미승인 **설계 항목 초안(DRAFT)** 과 **답변 전후 변경 목록**을 만든다. 저장·ID 발급·승인·발행은 BE(오서현) 담당이고 이 모듈은 입력을 바꾸거나 저장하지 않는다.

계약 초안: [docs/design/answer-design-draft-contract-S15P31A108-134.md](../../../../docs/design/answer-design-draft-contract-S15P31A108-134.md). 예시: `docs/api/examples/design-drafts/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/design_drafts.py` | 요청(기존 항목·답변된 질문)·초안·변경·결과 pydantic strict 모델, DesignItemContent v1 검증 |
| `proposer.py` | 공통 `LLMClient`를 주입받는 모델 제안 어댑터(`LLMDraftProposer`)와 출력 모델 |
| `service.py` | 파싱 → 모델 제안 → 인용·기준 항목·답변 키 검증 → 변경 목록 → 정렬·상한 → 봉투 |
| `app/routers/design_drafts.py` | 기본 비활성 내부 경로 `POST /v1/design-drafts` |

## 동작

1. `parse_source`로 블록·행 범위를 만들고 모델에 블록 원문, 기존 항목(ID·키·종류·상태·본문), `ANSWERED` 질문(키·문구·답변·근거 인용)을 넘긴다. 미답변·닫힌 질문은 개수만 전달한다.
2. 모델 제안의 각 항목을 검증한다.
   - 본문은 DesignItemContent v1(`description` 필수, API `method`·`path`, DATA `entity`·`fields`)이어야 하고, 기준 항목 ID는 입력에 있어야 하며 종류가 같아야 하고, 답변 참조는 `ANSWERED` 질문 키여야 한다. 어기면 `invalid_output`(usage는 실패로 기록).
   - 인용은 블록 원문의 부분 문자열일 때만 남기고 나머지는 버린다. 남은 인용·답변 참조로 `support`를 정한다.
3. 기준 항목이 있으면 `before`(기존 본문)와 `after`(초안)의 최상위 필드 차이를 `changed_fields`로 만든다. 차이가 없으면 초안이 아니므로 버리고 기준 항목을 `untouched_existing_ids`에 둔다. 기준 항목당 초안은 하나다.
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
