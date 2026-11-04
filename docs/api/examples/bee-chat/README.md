# 비비 초기화·설계 대화·근거 응답 계약 예시 (#135)

| 파일 | 내용 |
| --- | --- |
| `instance-request.json` | `POST /v1/bee/instances`에 BE가 프로젝트 생성 뒤(ProjectCreatedEvent) 보낼 본문. 프로젝트 이름·소개 |
| `instance-response.json` | 결정적 응답(`origin: TEMPLATE`, `usage: []`). 페르소나·인사말·범위 안내·프로젝트 프로필·고정 시스템 프롬프트·해시. 프로젝트 이름·소개는 프롬프트에 들어가지 않으므로 해시는 모든 프로젝트에서 같다 |
| `request.json` | `POST /v1/bee/chat`에 BE가 보낼 본문. 가상 API 명세(문서 302 버전 312)에 대한 질문, 저장된 인스턴스 참조, 승인 범위(`baseline_id` 401, 문서 버전 4개), BE 검색 결과 6건, 승인 설계 항목 2건, 관련 작업 1건(00-common TaskDetail 그대로, 담당자 포함) |
| `response.json` | 실제 라우터·검증 코드가 **대본(scripted) 가짜 공급자**(답변·판정)로 만든 응답. 인용 2건(`[1]`, `[2]`)·승인 항목 API-001·작업 TASK-001 참조·`sources` 3건(인용 2건 + 범위 안 승인 항목 출처 1건; 범위 밖 출처는 `design_refs[].source`에만)·`verification.level=ENTAILMENT`(문장 3개 판정)·프롬프트 해시. `usage`는 답변 호출(`llm-bee-chat-…`)과 판정 호출(`llm-bee-verify-…`) 2건 |
| `insufficient-response.json` | 문서에 없는 주제(비밀번호 정책). `answer_status: INSUFFICIENT_EVIDENCE`와 고정 문구, `unresolved`에 없는 것, `verification.level=NONE`, 판정 호출 없음. HTTP 200 |
| `out-of-scope-response.json` | 프로젝트 밖 질문. `answer_status: OUT_OF_SCOPE`와 고정 문구. HTTP 200 |
| `errors.json` | 상태별 오류 봉투. 원문에 없는 인용(`502_model_unavailable_unverifiable_quote`), 인용이 뒷받침하지 않는 사실(`502_model_unavailable_unsupported_fact`, 30 MB 인용 + 50 MB 답변), 판정 모델이 모순으로 본 문장(`502_model_unavailable_contradicted_claim`, "리더만" 인용 + "누구나" 답변; `usage`에 답변·판정 2건, 답변 호출 실패 표시), 검토 범위에 승인 항목 포함(`422_invalid_ai_input_review_scope_with_approved_items`)을 포함한다. 모델 실패·거부(502/504)는 정보 부족(200)과 구분된다 |

원문·ID·토큰 수는 합성 예시이며 `usage.request_key`는 재현을 위해 고정한 값이다. 검색 결과는 실제 임베딩 검색이 아니라 파서 블록을 대본으로 고른 것이고, 판정도 대본이다.

`tests/test_bee_chat_api.py::test_published_examples_match_the_http_contract`가 일곱 파일과 실제 HTTP 결과를 대조한다. 재생성은 `ai/`에서 `python -m scripts.generate_bee_chat_examples`.

이 예시는 BE 저장·answer_id 발급·화면 동작을 검증하지 않는다. 계약과 담당자 확인 항목은 [계약 초안](../../../design/bee-chat-contract-S15P31A108-135.md)을 참조한다.
