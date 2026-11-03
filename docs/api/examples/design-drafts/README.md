# 답변 반영 설계 초안 계약 예시 (#134)

`request.json`은 `POST /v1/design-drafts`에 BE가 보낼 본문이다. 가상 기획서 `ai/evaluations/data/plan.md`(문서 301 버전 311)에 #133 질문 7건(답변 4건, 닫힘 1건, 미답변 2건)과 기존 설계 항목 3건(APPROVED 1, DRAFT 2)을 붙였다. `response.json`은 실제 라우터·파서·검증 코드가 **대본(scripted) 가짜 공급자**로 만든 응답이고 `errors.json`은 상태별 오류 봉투다. 원문·ID·토큰 수는 합성 예시이며 `usage.request_key`는 재현을 위해 고정한 값이다.

`response.json`의 모든 `items[].status`는 `DRAFT`다. `changes[]`는 항목과 같은 순서로 `before`(기존 본문)·`after`(초안)·`changed_fields`(필드별 `ADDED|CHANGED|REMOVED`)·`conflicting_fields`(같은 항목에 대한 제안들이 다른 값을 준 필드, 이 예시에서는 없음)·`answer_refs`를 담아 답변 전후 차이를 보여 준다. 승인 항목 1101은 `before`에 승인 본문 그대로 나오고 초안이 새 DRAFT 후보로만 제시된다. 인용이 원문에 없던 항목은 인용이 빠진 채 `support: ANSWER`로, 근거가 전혀 없는 항목은 `support: NONE`으로 맨 뒤에 온다.

`tests/test_design_drafts_api.py::test_published_examples_match_the_http_contract`가 세 파일과 실제 HTTP 결과를 대조한다. 재생성은 `ai/`에서 `python -m scripts.generate_design_draft_examples`.

이 예시는 BE 저장이나 화면 동작을 검증하지 않는다. 계약과 저장 제안은 [계약 초안](../../../design/answer-design-draft-contract-S15P31A108-134.md)을 참조한다.
