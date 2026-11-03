# 문서 청킹 계약 예시 (#214)

`request.json`과 `response.json`은 `POST /v1/document-chunks`의 실제 로컬 파서·토크나이저 결과다. `plain-request.json`과 `plain-response.json`은 같은 API에 `media_type: text/plain`으로 보낸 `.txt` 입력(`<user>` 태그 포함)의 `plain-v1` 결과다(#223). 원문·ID는 합성 예시다. 응답의 key와 프로필은 입력 문서, 파서·청커·토크나이저 버전에 결합된다. 변경 시 새 fixture로 계약 차이를 검토한다.

`response.json`의 각 청크는 `source_spans`로 정규화 원문을 복원한다. 청크 `locator`는 표시용이다. `excluded_ranges`는 청크로 반환하지 않은 원문 행을 설명한다. `test_published_fixture_matches_http_contract`와 `test_published_plain_text_fixture_matches_http_contract`는 문서 예시와 실제 HTTP 결과가 일치하는지 확인한다.

이 fixture는 BE 소비 구현이나 실제 DB 통합을 검증하지 않는다. 세부 규칙은 [청킹 API](../../03-ai-internal.md#document-chunks)를 참조한다.
