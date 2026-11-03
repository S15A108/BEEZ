# 비비 초기화·설계 대화·근거 응답 계약 예시 (#135)

| 파일 | 내용 |
| --- | --- |
| `instance-request.json` | `POST /v1/bee/instances`에 BE가 프로젝트 생성 뒤(ProjectCreatedEvent) 보낼 본문. 프로젝트 이름·소개 |
| `instance-response.json` | 결정적 응답(`origin: TEMPLATE`, `usage: []`). 페르소나·인사말·범위 안내·시스템 프롬프트·해시. 같은 입력이면 항상 같다 |
| `request.json` | `POST /v1/bee/chat`에 BE가 보낼 본문. 가상 API 명세(문서 302 버전 312)에 대한 질문, 저장된 인스턴스 참조, 승인 범위(`baseline_id` 401, 문서 버전 4개), BE 검색 결과 6건, 승인 설계 항목 2건, 관련 작업 1건(00-common TaskDetail 그대로) |
| `response.json` | 실제 라우터·검증 코드가 **대본(scripted) 가짜 공급자**로 만든 응답. 인용 2건(`[1]`, `[2]`)·승인 항목 API-001·작업 TASK-001 참조·`sources` 3건·인스턴스 해시 |
| `insufficient-response.json` | 모델이 문서에 없는 수치를 조작 인용으로 답한 경우. 인용이 제거돼 근거가 없으므로 `answer_status: INSUFFICIENT_EVIDENCE`와 고정 문구, `unresolved`에 보류 사유. HTTP 200 |
| `out-of-scope-response.json` | 프로젝트 밖 질문. `answer_status: OUT_OF_SCOPE`와 고정 문구. HTTP 200 |
| `errors.json` | 상태별 오류 봉투. 모델 실패(502/504)는 정보 부족(200)과 구분된다 |

원문·ID·토큰 수는 합성 예시이며 `usage.request_key`는 재현을 위해 고정한 값이다. 검색 결과는 실제 임베딩 검색이 아니라 파서 블록을 대본으로 고른 것이다.

`tests/test_bee_chat_api.py::test_published_examples_match_the_http_contract`가 일곱 파일과 실제 HTTP 결과를 대조한다. 재생성은 `ai/`에서 `python -m scripts.generate_bee_chat_examples`.

이 예시는 BE 저장·answer_id 발급·화면 동작을 검증하지 않는다. 계약과 담당자 확인 항목은 [계약 초안](../../../design/bee-chat-contract-S15P31A108-135.md)을 참조한다.
