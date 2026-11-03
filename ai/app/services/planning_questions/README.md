# 요구사항 누락 후보·보완 질문 (#133)

권한을 확인한 **문서 고정 버전과 원문**을 입력으로 받아, 모호·누락·충돌 **검토 후보**와 후보별 **보완 질문**을 만든다. #127 파서(`app/services/document_parsing`)의 블록·`SourceLocator`가 근거 단위이며, 모든 인용은 해당 블록 원문의 부분 문자열이어야 한다. 저장·답변·확정은 BE(오서현) 담당이고 이 모듈은 아무것도 저장하지 않는다. #128 구조화 추출은 선행 조건이 아니다.

계약 초안: [docs/design/planning-question-answer-contract-S15P31A108-133.md](../../../../docs/design/planning-question-answer-contract-S15P31A108-133.md). 예시: `docs/api/examples/planning-questions/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/planning_questions.py` | 요청·결과·후보·근거·질문·답변 형식·답변 pydantic strict 모델, `validate_answer` |
| `rules.py` | 모델 없이 동작하는 결정적 탐지 규칙과 템플릿 질문 |
| `proposer.py` | 공통 `LLMClient`를 주입받는 모델 제안 어댑터(`LLMCandidateProposer`)와 출력 모델 |
| `service.py` | 파싱 → 규칙 탐지 → 모델 제안 인용 검증 → 병합 → 우선순위·상한 → 결과 봉투 |
| `app/routers/planning_questions.py` | 기본 비활성 내부 경로 `POST /v1/planning-questions` |

## 탐지 기준

근거 단위는 파서 블록이다. 근거 항목(`EvidenceItem`)은 문서 ID·버전·`block_id`·`locator`(1-based 행 범위)·표시 위치(제목 경로 + 행 범위)·원문 인용(`quote`)으로 이루어지며, `quote`는 `resolve_source(document, locator)`의 부분 문자열이어야 한다.

| 신호 | 종류 | 근거 | 질문 유형(템플릿) |
| --- | --- | --- | --- |
| `UNDECIDED_STATEMENT` | MISSING_CANDIDATE | 문장 단위. 과거형 미결("정하지 않았다", "합의하지 않았다", "결정되지 않았다"), "미정"·TBD·TODO, "추후 결정", "별도 정책에 따른다"·"후속 계약에서 정한다" 같은 유보. 현재형("정의하지 않는다", "포함하지 않는다")은 같은 문장에 "이 문서"·"아직" 등 문서 범위 표지가 있을 때만 잡는다(동작 설명과 구분) | ESSAY |
| `PLACEHOLDER_CELL` | MISSING_CANDIDATE | 표 행의 빈 셀 또는 "미정"·TBD·TODO·"추후"·"미확정"·"?" 셀. 행 전체를 인용(1,000자 상한)하고 헤더로 열 이름을 붙인다. 요약·질문에 넣는 행·열 이름은 80자, 위치는 120자로 줄이며 조합한 문장은 2,000자를 넘지 않는다. `-`는 "없음"의 뜻으로 쓰여 제외 | SHORT_ANSWER |
| `DUPLICATE_KEY` | CONFLICT | 같은 식별자(`REQ-003` 등)가 서로 다른 내용으로 2곳 이상에 있을 때. 동일 문장 반복은 제외 | CHOICE |
| `MODEL_PROPOSAL` | 모델이 고른 kind | 모델이 `block_id`+`quote`로 근거를 제시. 아래 검증을 통과한 인용만 남는다 | 모델이 고른 유형 |

규칙은 문서가 **스스로 미결이라고 말한 것**만 잡는다. 주제 부재(예: 개인정보 정책이 없음)는 모델 제안에 맡기며, 규칙 결과 0건이 완전성을 뜻하지 않는다.

## 검토 후보와 미확인

후보 상태는 `REVIEW_CANDIDATE`와 `UNVERIFIED` 둘뿐이다. 확정 상태는 스키마에 없다(완료 조건 "근거 없는 후보가 확정 표현으로 나가지 않음").

- 모델 인용은 `block_id`가 문서에 있고 `quote`(앞뒤 공백 제거)가 그 블록 원문에 있을 때만 근거로 남는다.
- 하나라도 확인되지 않으면 후보는 `UNVERIFIED`이며 `unverified_reason`(`BLOCK_NOT_FOUND`, `QUOTE_NOT_IN_BLOCK`, `NO_EVIDENCE`, `INSUFFICIENT_EVIDENCE`=CONFLICT인데 블록 2개 미만)을 붙인다. 확인되지 않은 인용문은 결과에 넣지 않는다.
- 질문에는 후보 상태가 `basis`로 복사된다. 화면은 `UNVERIFIED` 질문을 "근거 미확인"으로 표시해야 한다.
- 모델 출력의 구조 오류(필드 누락, 허용되지 않은 kind, CHOICE 선택지 부족 등)는 `invalid_output`으로 끝내고 usage를 보존한다.

## 우선순위와 상한

점수 = 상태(`REVIEW_CANDIDATE` 100, `UNVERIFIED` 0) + 종류(`CONFLICT` 30, `MISSING_CANDIDATE` 20, `CLARIFICATION` 10) + 근거 수(최대 3개 × 2). 동점은 첫 근거의 시작 행이 앞선 순, 그다음 `candidate_key` 순이다. 근거가 없는 후보는 맨 뒤다.

| 항목 | 값 |
| --- | --- |
| 질문 수 상한 `max_questions` | 요청값, 기본 20, 1~50. 후보당 질문 1개 |
| 결과 후보 수 | 최대 50. 넘치면 하위 후보를 버리고 `omitted_candidates`에 수를 남긴다 |
| 질문 없이 남은 후보 | `truncated_candidate_keys` |
| 모델 제안 수 | 최대 20, 후보당 근거 최대 5 |
| 모델 입력 | 블록 JSON 256 KiB(`invalid_input`), 문서 자체는 파서 한도(1 MiB) |

같은 종류·같은 블록에서 인용이 겹치는 검증 완료 모델 후보가 있으면 규칙 후보의 템플릿 질문 대신 모델 질문을 쓴다(규칙 후보 제거). 모델 후보가 미확인이면 규칙 후보를 유지한다.

## 후보 키

`candidate_key`는 한 결과 안의 임시 키이며 최종 ID가 아니다. 규칙 후보는 위치 기반(`rule-undecided-L29-1`)이고, 모델 후보는 `model_candidate_key()`가 **종류 + 확인된 근거(블록 ID·인용, 정렬) + 질문 유형·선택지 + 공백 정규화한 질문 문구**의 SHA-256 앞 12자리로 만든다(`model-3f9a1c0b2d4e`). 출력 순서와 무관하므로 같은 버전을 재분석해 같은 문장을 인용하고 같은 질문을 하면 같은 키가 나온다. 같은 문장을 근거로 서로 다른 질문을 하면 키가 다르며 둘 다 남는다. 종류·인용·질문 유형·선택지·문구까지 같은 제안이 둘이면 첫 번째만 남는다. 모델이 다른 문장을 인용하거나 질문을 바꿔 쓰면 키가 달라지므로 BE는 키를 "같은 질문" 판단의 힌트로만 쓰고 답변된 행을 덮어쓰지 않는다(계약 문서 8절).

## 사용

```python
from app.schemas.planning_questions import PlanningQuestionRequest
from app.services.planning_questions import (
    LLMCandidateProposer,
    generate_planning_questions,
)

outcome = await generate_planning_questions(request, proposer)  # mode=MODEL
outcome = await generate_planning_questions(request, None)  # mode=RULES_ONLY
outcome.to_api_dict()  # {"result", "origin", "model"?, "usage"?}
```

`LLMCandidateProposer(client, context=LLMContext(project_id, feature="DOCUMENT_PARSE", request_key), model, call_key, timeout_seconds)`는 `context.project_id`가 문서의 프로젝트와 같아야 한다. 파서 오류(`SourceParsingError`)와 `LLMError`는 그대로 올라오며 라우터가 HTTP로 매핑한다.

HTTP 경로는 `PLANNING_QUESTIONS_ENABLED=true`, 내부 토큰, `GMS_KEY`(모델 모드)가 있어야 동작한다. 본문 상한 `PLANNING_QUESTIONS_MAX_BODY_BYTES`(기본 2 MiB), 모델 시간 `PLANNING_QUESTIONS_LLM_TIMEOUT_SECONDS`(기본 30), 출력 토큰 `PLANNING_QUESTIONS_LLM_OUTPUT_TOKEN_LIMIT`(기본 2048).

## 검증

```text
python -m pytest tests/test_planning_questions_schema.py tests/test_planning_questions_rules.py tests/test_planning_questions_service.py tests/test_planning_questions_api.py tests/test_planning_questions_evaluation.py -q
python -m evaluations.planning_questions.run            # 가짜 공급자 검토 보고서 출력
python -m scripts.generate_planning_question_examples    # 예시 JSON·검토 보고서 재생성
```

실제 모델 평가는 아직 하지 않았다. `evaluations/planning_questions/README.md`를 따른다.
