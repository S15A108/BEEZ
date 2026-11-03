# 비비 초기화·설계 대화·근거 응답 (#135)

프로젝트 생성 뒤 **비비 인스턴스(페르소나·고정 시스템 프롬프트·프로젝트 프로필)** 를 결정적으로 만들고, 팀원의 설계 질문에 BE가 권한 검사를 마친 **검색 근거(READY 청크)·승인 설계 항목·관련 작업**만으로 답한다. 모든 인용·항목·작업 참조를 입력과 대조하고, 원문에 없는 인용이 하나라도 있으면 출력 전체를 거부하며, 근거를 하나도 대지 않은 답은 고정 문구("찾지 못했어요")로 바꾼다. 저장·answer_id 발급·피드백·대화 보존은 BE(오서현) 담당이고 이 모듈은 아무것도 저장하지 않는다.

계약 초안: [docs/design/bee-chat-contract-S15P31A108-135.md](../../../../docs/design/bee-chat-contract-S15P31A108-135.md). 예시: `docs/api/examples/bee-chat/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/bee_chat.py` | 인스턴스 요청·결과, 질문 요청(검색 범위·근거·승인 항목·관련 작업·이력), 인용·결과 pydantic strict 모델 |
| `instance.py` | 페르소나·인사말과 **고정** 시스템 프롬프트(`render_system_prompt`), 인스턴스 생성(`build_instance`, 모델 호출 없음) |
| `proposer.py` | 공통 `LLMClient`를 주입받는 답변 어댑터(`LLMAnswerProposer`)와 모델 출력 모델(`ModelAnswer`). 프로젝트 프로필·근거·항목·작업·이력은 사용자 메시지의 JSON 데이터 |
| `service.py` | 근거 선택(상한) → 모델 답변 → 인용·참조 검증(거부 또는 번호 정리) → 고정 문구 → 봉투 |
| `app/routers/bee_chat.py` | 기본 비활성 내부 경로 `POST /v1/bee/instances`, `POST /v1/bee/chat`. 요청 스키마는 `app/core/openapi.py`로 `$defs` 참조를 풀어 OpenAPI 문서에 싣는다 |

## 동작

1. **의미 검색은 BE 몫이다.** BE가 AI-A04로 질문을 임베딩하고 허용 집합(현재 baseline 또는 명시 검토 버전)·READY·활성 프로필 안에서 top-k를 구해 `retrieved_evidence`로 보낸다. 이 모듈은 임베딩이나 DB를 호출하지 않는다. 요청 검증에서 근거와 승인 항목의 출처가 `scope` 안인지 확인한다(`DOCUMENT_REVIEW`는 출처 있는 승인 항목만).
2. **근거 선택**: BE 순서(검색 순위)를 그대로 두고 `max_evidence`(기본 12)까지만 모델에 보인다. 넘친 수는 `evidence_omitted`와 `notices`에 남는다.
3. **응답 생성**: 시스템 메시지는 프로젝트와 무관한 고정 규칙이다. 프로젝트 이름·소개(`instance.project`), 근거 원문, 승인 항목, 관련 작업(ID·키·상태·담당자·계획), 이력은 모두 사용자 메시지의 데이터로 간다. 모델은 `{status, text, citations[{evidence_id, quote, claim}], design_refs, task_refs, unresolved, next_actions}`를 돌려준다.
4. **검증**
   - 입력에 없는 `evidence_id`·설계 항목 버전 ID·작업 ID, **근거 원문에 없는 `quote`**, `citations`에 없는 본문 `[n]`, 빈 `ANSWERED` 본문은 모순이므로 **출력 전체를 거부**한다(`invalid_output`, usage는 실패로 기록, HTTP 502). 원문에 없는 인용을 그 인용만 지우면 그 주장이 본문에 남으므로 거부한다.
   - 같은 (근거, 인용)을 거듭 쓰면 한 번호로 합치고 본문 표식을 그 번호로 바꾼다.
   - `ANSWERED`인데 인용·승인 항목 참조·작업 참조가 하나도 없으면 `INSUFFICIENT_EVIDENCE`로 바꾸고 고정 문구를 쓴다(`unresolved`에 보류 사유 추가). 작업 상태·담당자처럼 관련 작업만으로 답한 경우는 `task_refs`만으로 근거가 된다.
   - `INSUFFICIENT_EVIDENCE`·`OUT_OF_SCOPE`는 각각의 고정 문구만 보이고 인용·참조는 비운다. 모델의 `unresolved`·`next_actions`는 남긴다.
   - `grounded`는 인용·설계 항목 참조·작업 참조 중 하나 이상일 때만 참이며 `ANSWERED`와 일치한다.
5. **출처**: `citations[]`(근거 ID·문서·버전·청크 키·행 범위·인용·제목·절 제목·주장)와 `design_refs[]`(설계 항목·버전·키·종류·저장 출처), `task_refs[]`(작업·키·상태), 그리고 앞의 둘에서 만든 00-common `sources[]`를 함께 돌려준다. BE는 `citations`를 화면의 BeeCitation으로 바꾼다.

## 사용

```python
from app.schemas.bee_chat import BeeChatRequest, BeeInstanceRequest
from app.services.bee_chat import LLMAnswerProposer, build_instance, generate_bee_chat

instance = build_instance(BeeInstanceRequest.model_validate(body))   # 결정적, 모델 없음
outcome = await generate_bee_chat(request, proposer)
outcome.to_api_dict()  # {"result", "origin": "MODEL", "model", "usage"}
```

`LLMAnswerProposer(client, context=LLMContext(project_id, feature="BEE_CHAT", request_key), model, call_key, timeout_seconds)`는 `context.project_id`가 요청의 프로젝트와 같아야 한다. 요청은 strict 모델이므로 JSON 본문은 `BeeChatRequest.model_validate_json`으로 읽는다.

HTTP 경로는 `BEE_CHAT_ENABLED=true`, 내부 토큰, 질문 경로는 `GMS_KEY`가 있어야 동작한다. 본문 상한 `BEE_CHAT_MAX_BODY_BYTES`(기본 2 MiB), 모델 시간 `BEE_CHAT_LLM_TIMEOUT_SECONDS`(기본 30), 출력 토큰 `BEE_CHAT_LLM_OUTPUT_TOKEN_LIMIT`(기본 2048).

## 검증

```text
python -m pytest tests/test_bee_chat_schema.py tests/test_bee_chat_instance.py tests/test_bee_chat_service.py tests/test_bee_chat_api.py tests/test_bee_chat_evaluation.py -q
python -m evaluations.bee_chat.run                 # 대표 질문 세트 검토 보고서 출력(가짜 공급자)
python -m scripts.generate_bee_chat_examples       # 예시 JSON·검토 보고서 재생성
```

실제 모델 평가는 아직 하지 않았다. `evaluations/bee_chat/README.md`를 따른다.
