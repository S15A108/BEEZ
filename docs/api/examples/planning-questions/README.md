# 요구사항 누락 후보·보완 질문 계약 예시 (#133)

`request.json`은 `POST /v1/planning-questions`에 BE가 보낼 본문(가상 기획서 `ai/evaluations/data/plan.md`, 문서 301 버전 311)이다. `response.json`은 실제 라우터·파서·검증 코드가 **대본(scripted) 가짜 공급자**로 만든 응답이고, `rules-only-response.json`은 같은 문서를 `mode: RULES_ONLY`로 보낸 응답(모델 호출 없음)이다. `errors.json`은 상태별 오류 봉투다. 원문·ID·토큰 수는 합성 예시이며 `usage.request_key`는 재현을 위해 고정한 값이다.

`response.json`의 `status: UNVERIFIED` 후보 둘은 인용이 원문에 없거나(`QUOTE_NOT_IN_BLOCK`) 근거가 없어(`NO_EVIDENCE`) 미확인으로 남은 것이고, 그 질문에는 `basis: UNVERIFIED`가 붙는다. `rule-*` 후보는 모델 없이 규칙으로 찾은 것이며 질문 `origin`이 `TEMPLATE`이다. `model-<해시>` 키는 종류와 확인된 인용에서 만들어 출력 순서와 무관하다.

`tests/test_planning_questions_api.py::test_published_examples_match_the_http_contract`가 네 파일과 실제 HTTP 결과를 대조한다. 재생성은 `ai/`에서 `python -m scripts.generate_planning_question_examples`.

이 예시는 BE 저장이나 화면 동작을 검증하지 않는다. 계약과 저장 제안은 [계약 초안](../../../design/planning-question-answer-contract-S15P31A108-133.md)을 참조한다.
