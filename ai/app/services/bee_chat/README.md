# 비비 초기화·설계 대화·근거 응답 (#135)

프로젝트 생성 뒤 **비비 인스턴스(페르소나·고정 시스템 프롬프트·프로젝트 프로필)** 를 결정적으로 만들고, 팀원의 설계 질문에 BE가 권한 검사를 마친 **검색 근거(READY 청크)·승인 설계 항목·관련 작업**만으로 답한다. 모든 인용·항목·작업 참조를 입력과 대조하고, 원문에 없는 인용이나 인용·참조가 뒷받침하지 않는 사실(숫자·키·경로·식별자·코드)이 하나라도 있으면 출력 전체를 거부하며, 기본으로 **판정 모델 호출**을 한 번 더 해 문장마다 인용문이 뜻까지 뒷받침하는지 확인한다. 근거를 하나도 대지 않은 답은 고정 문구("찾지 못했어요")로 바꾼다. 저장·answer_id 발급·피드백·대화 보존은 BE(오서현) 담당이고 이 모듈은 아무것도 저장하지 않는다.

계약 초안: [docs/design/bee-chat-contract-S15P31A108-135.md](../../../../docs/design/bee-chat-contract-S15P31A108-135.md). 예시: `docs/api/examples/bee-chat/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/bee_chat.py` | 인스턴스 요청·결과, 질문 요청(검색 범위·근거·승인 항목·관련 작업·이력), 인용·검증 수준·결과 pydantic strict 모델 |
| `instance.py` | 페르소나·인사말과 **고정** 시스템 프롬프트(`render_system_prompt`), 인스턴스 생성(`build_instance`, 모델 호출 없음) |
| `proposer.py` | 공통 `LLMClient`를 주입받는 답변 어댑터(`LLMAnswerProposer`)와 판정 어댑터(`LLMClaimVerifier`), 모델 출력 모델(`ModelAnswer`, `ModelVerdicts`). 프로젝트 프로필·근거·항목·작업·이력·claim은 사용자 메시지의 JSON 데이터이고 긴 텍스트는 `clip_text`로 2,000자 절단 |
| `service.py` | 근거 선택(상한) → 모델 답변 → 인용·참조·사실 토큰 검증(거부 또는 번호 정리) → 문장별 claim 구성(`claims_from`) → 판정(`check_verdicts`) → 고정 문구 → 봉투 |
| `app/routers/bee_chat.py` | 기본 비활성 내부 경로 `POST /v1/bee/instances`, `POST /v1/bee/chat`. 요청 스키마는 `app/core/openapi.py`로 `$defs` 참조를 풀어 OpenAPI 문서에 싣는다 |

## 동작

1. **의미 검색은 BE 몫이다.** BE가 AI-A04로 질문을 임베딩하고 허용 집합(현재 baseline 또는 명시 검토 버전)·READY·활성 프로필 안에서 top-k를 구해 `retrieved_evidence`로 보낸다. 이 모듈은 임베딩이나 DB를 호출하지 않는다. 요청 검증에서 근거가 `scope` 안인지 확인한다. 승인 항목은 BE가 보존한 출처가 더 오래된 문서 버전이어도 받고(APPROVED), `DOCUMENT_REVIEW`에서는 승인 항목을 받지 않는다.
2. **근거 선택**: BE 순서(검색 순위)를 그대로 두고 `max_evidence`(기본 12)까지만 모델에 보인다. 넘친 수는 `evidence_omitted`와 `notices`에 남는다.
3. **응답 생성**: 시스템 메시지는 프로젝트와 무관한 고정 규칙이다. 프로젝트 이름·소개(`instance.project`), 근거 원문, 승인 항목, 관련 작업(ID·키·상태·담당자·계획), 이력은 모두 사용자 메시지의 데이터로 간다. 이력 턴과 계획 제목·목표는 모델에 앞 2,000자만 보인다(`clip_text`). 모델은 `{status, text, citations[{evidence_id, quote, claim}], design_refs, task_refs, unresolved, next_actions}`를 돌려준다.
4. **검증**
   - 각 문장의 인용에 속한 `version_no`와 `locator` 시작·끝 행을 해당 문장의 사실 토큰 검사에 포함하고, 판정 모델에도 원문 인용과 함께 인용별로 구조화해 전달한다. 다른 인용의 메타데이터는 섞지 않는다. 토큰 검사는 숫자의 존재만 확인하므로 버전·행·다른 수치 사이의 의미 관계는 판정 모델이 확인하며 `TOKENS` 모드에서는 확인하지 못한다.
   - 입력에 없는 `evidence_id`·설계 항목 버전 ID·작업 ID, **근거 원문에 없는 `quote`**, 1..인용 수 밖의 본문 `[n]`(대괄호 안 정수는 모두 표식; `[0]`·`[100]` 포함), 빈 `ANSWERED` 본문은 모순이므로 **출력 전체를 거부**한다(`invalid_output`, usage는 실패로 기록, HTTP 502). 원문에 없는 인용을 그 인용만 지우면 그 주장이 본문에 남으므로 거부한다.
   - **사실 토큰 검사(문장 단위)**(`claim_segments`·`support_tokens`·`unsupported_facts`): 본문을 줄과 문장으로 나누고(문장 끝 뒤의 `[n]`은 같은 줄의 앞 문장에, 줄 머리의 `[n]`은 그 줄에; 줄 머리의 목록 번호·글머리표는 서식) 각 문장의 숫자(쉼표는 세 자리 묶음일 때만 천 단위 구분자, `10,20`은 두 값), `TASK-001`류 키, `/` 경로(끝 문장 부호 제외), 점·밑줄·camelCase 식별자, 3자 이상 대문자 코드가 **그 문장의 `[n]`이 가리키는 인용문**(과 문서·절 제목·버전 번호·행 범위 시작과 끝) 또는 참조한 항목(키·종류·본문)·참조한 작업(키·상태·담당자·계획)의 토큰과 독립 토큰으로 일치해야 한다. 인용문이 속한 청크의 다른 문장은 근거가 아니다. 토큰은 공백 단위 단어 안에서 찾되 128자 넘는 영문자·숫자·밑줄 연속은 16자 요약 토큰으로 접어 통째로 비교하므로 긴 입력에도 선형 시간이고 긴 API 경로도 한 토큰으로 검사하며, 계획 제목·목표는 모델이 본 앞 2,000자만 근거로 친다. 모든 토큰은 대소문자를 구분해 비교하고(`/api/Users`≠`/api/users`, `user.userId`≠`user.userid`) 숫자만 천 단위 쉼표를 무시한다. 30 MB를 인용하고 50 MB라고 쓴 답, 150 MB 원문에 50 MB 답, `10,20 MB`에 `1020 MB` 답은 해당 숫자가 다른 허용 근거의 토큰이 아닌 한 여기서 걸린다. 질문은 근거로 치지 않는다.
   - **문장 판정**(`claims_from`·`check_verdicts`, 기본 켜짐): 토큰 검사를 통과한 답변의 문장마다 그 문장이 인용한 인용 번호(`[n]`이 없으면 모든 번호)를 붙여 판정 모델에 보낸다. 입력은 질문, 번호 붙은 인용문과 인용별 버전·행 범위 목록(한 번만), 모든 문장에 공통인 참조 항목·작업 텍스트(`shared_support`, 한 번만, 텍스트당 2,000자; 항목 본문은 그 문자열(`email` 같은 일반 필드명 포함)이 답변에 나오는 필드·목록 항목·문장들을 먼저 싣고(긴 문자열은 답변 단어의 어간이 든 문장과 그 앞뒤 문장을 점수 순으로 500자까지 원래 순서로, 긴 문장은 120자 조각으로 나눠 같은 규칙으로) 문자열 500자·목록 20개로 필드별 절단이고 참조 합계는 40,000자(넘으면 긴 참조부터 필드 예산을 줄여 다시 만들어 답변이 기댄 문장을 남김)라 `method`·`path`나 답변이 말한 21번째 `fields` 항목이 빠지지 않고, 작업은 제목·참조·목표 각 300자), 문장 목록뿐이라 어떤 요청에서도 256 KiB 안에 들고 근거 청크는 보내지 않는다. SUPPORTED / NOT_SUPPORTED / NOT_A_CLAIM 중 NOT_SUPPORTED가 하나라도 있으면 거부(답변 호출 usage를 실패로 표시하고 판정 호출 usage도 돌려줌). 판정 출력이 claim마다 하나가 아니면 판정 호출을 실패로 표시하고 거부. 토큰이 같은 의미 모순("리더만" → "누구나")은 여기서만 잡힌다. `verifier=None`(설정 `BEE_CHAT_CLAIM_VERIFICATION=tokens`)이면 생략하고 `verification.level=TOKENS`.
   - 본문 `[n]`이 가리키지 않는 인용은 버린다. 같은 (근거, 인용)을 거듭 쓰면 한 번호로 합치고 본문 표식을 그 번호로 바꾼다.
   - `ANSWERED`인데 인용·승인 항목 참조·작업 참조가 하나도 없으면 `INSUFFICIENT_EVIDENCE`로 바꾸고 고정 문구를 쓴다(`unresolved`에 보류 사유 추가). 작업 상태·담당자처럼 관련 작업만으로 답한 경우는 `task_refs`만으로 근거가 된다.
   - `INSUFFICIENT_EVIDENCE`·`OUT_OF_SCOPE`는 각각의 고정 문구만 보이고 인용·참조는 비운다. 모델의 `unresolved`·`next_actions`는 남긴다. 판정 모델은 부르지 않는다(`verification.level=NONE`).
   - `grounded`는 인용·설계 항목 참조·작업 참조 중 하나 이상일 때만 참이며 `ANSWERED`와 일치한다. 본문이 근거와 맞는지는 `verification.level`(`TOKENS`/`ENTAILMENT`)이 말한다.
5. **출처**: `citations[]`(근거 ID·문서·버전·청크 키·행 범위·인용·제목·절 제목·주장)와 `design_refs[]`(설계 항목·버전·키·종류·저장 출처), `task_refs[]`(작업·키·상태)를 돌려준다. 00-common `sources[]`는 인용의 출처와 **현재 범위 안의** 항목 저장 출처를 담는다. 범위 밖(이전 문서 버전) 출처는 `design_refs[].source`에만 둔다. BE는 `citations`를 화면의 BeeCitation으로 바꾼다.

## 사용

```python
from app.schemas.bee_chat import BeeChatRequest, BeeInstanceRequest
from app.services.bee_chat import (
    LLMAnswerProposer,
    LLMClaimVerifier,
    build_instance,
    generate_bee_chat,
)

instance = build_instance(BeeInstanceRequest.model_validate(body))  # 결정적, 모델 없음
outcome = await generate_bee_chat(
    request, proposer, verifier
)  # verifier=None이면 토큰 검사만
outcome.to_api_dict()  # {"result", "origin": "MODEL", "model", "usage": [답변, 판정]}
```

`LLMAnswerProposer(client, context=LLMContext(project_id, feature="BEE_CHAT", request_key), model, call_key, timeout_seconds)`와 `LLMClaimVerifier(client, context, model, call_key="llm-bee-verify-…", timeout_seconds)`는 `context.project_id`가 요청의 프로젝트와 같아야 한다. 요청은 strict 모델이므로 JSON 본문은 `BeeChatRequest.model_validate_json`으로 읽는다.

HTTP 경로는 `BEE_CHAT_ENABLED=true`, 내부 토큰, 질문 경로는 `GMS_KEY`가 있어야 동작한다. 본문 상한 `BEE_CHAT_MAX_BODY_BYTES`(기본 2 MiB), 모델 시간 `BEE_CHAT_LLM_TIMEOUT_SECONDS`(기본 30, 호출마다), 출력 토큰 `BEE_CHAT_LLM_OUTPUT_TOKEN_LIMIT`(기본 2048; 판정은 1024), 문장 판정 `BEE_CHAT_CLAIM_VERIFICATION`(기본 `entailment`, `tokens`면 판정 호출 생략).

## 검증

```text
python -m pytest tests/test_bee_chat_schema.py tests/test_bee_chat_instance.py tests/test_bee_chat_service.py tests/test_bee_chat_api.py tests/test_bee_chat_evaluation.py -q
python -m evaluations.bee_chat.run                 # 대표 질문 세트 검토 보고서 출력(가짜 공급자·가짜 판정)
python -m scripts.generate_bee_chat_examples       # 예시 JSON·검토 보고서 재생성
```

실제 모델 평가는 아직 하지 않았다. `evaluations/bee_chat/README.md`를 따른다.
