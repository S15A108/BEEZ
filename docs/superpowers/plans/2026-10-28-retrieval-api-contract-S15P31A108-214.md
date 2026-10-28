# 청킹·임베딩 HTTP 연동 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #129의 파서·청커와 임베딩을 BE가 검증 가능한 내부 HTTP 계약으로 제공한다.

**Architecture:** 청킹의 외부 DTO, 순수 매핑 및 제한된 HTTP 라우터를 추가해 기존 파서·청커를 호출한다. 임베딩 API에는 함께 사용되는 선택 필드만 추가하고 기존 응답을 보존한다. 원문 span 보존과 실제 BE 저장·검증 변경은 분리한다.

**Tech Stack:** Python, FastAPI, Pydantic, pytest, 기존 Markdown 파서 및 tiktoken.

**Spec:** `docs/superpowers/specs/2026-10-28-retrieval-api-contract-S15P31A108-214-design.md`

## Global Constraints

- AI 범위만 수정하고 BE 코드·DB·Jira·기존 사용자 미추적 파일은 변경하지 않는다.
- `utf8-lf-v1`, `markdown-v1`, `md-structure-v1`: 최대 600 tokens, 1024 chunks, heading/table context off.
- 요청 body 8 MiB, content UTF-8 1 MiB, 정규화 100,000 code points, 최종 JSON 8 MiB; 청킹 10초.
- 토크나이저는 사전 준비된 로컬 파일만 사용한다. 유료 모델을 호출하지 않고 테스트 provider를 사용한다.
- 기존 임베딩 호출 형식·사용량·실패 동작을 유지하고 profile/purpose는 둘 다 생략하거나 둘 다 제공한다.
- 원문 spans는 정규화 텍스트 기준 0-based code point, 끝 제외. 표시 locator는 1-based 행 포함이며 복원에 사용하지 않는다.
- HTTP 오류와 테스트에 원문·인증 정보·공급자 응답을 노출하지 않는다.

## Review Focus

- JSON body에 Content-Length가 없거나 거짓이어도 실제 수신 바이트를 제한한다 (Task 2).
- Unicode surrogate, BOM/CRLF, 비 BMP 글자 및 긴 한 행의 부분 범위를 검증한다 (Task 1, 2).
- 동일 내부 limit code의 입력 크기와 파서 복잡도 한도를 413/422로 구분한다 (Task 2).
- 대용량 최종 JSON 직렬화와 크기 검사는 worker에서 수행하고 같은 바이트를 응답한다 (Task 1, 2).
- legacy 임베딩과 부분/null 필드 및 profile 불일치가 유료 호출 전 실패하는지 확인한다 (Task 3).

## 파일 구조

- `ai/app/schemas/document_chunks.py`: 엄격한 외부 청킹 요청 DTO.
- `ai/app/services/retrieval/document_chunks.py`: 요청 매핑, 고정 프로필, source parsing/chunking, 최종 응답 바이트 생성.
- `ai/app/routers/document_chunks.py`: bounded body 수신, 인증, 제한 시간, 안전한 오류 응답 및 request ID.
- `ai/app/main.py`: 라우터 등록.
- `ai/app/schemas/embedding_api.py`, `ai/app/routers/embeddings.py`: 호환 가능한 profile/purpose 확장.
- `ai/tests/test_document_chunks_api.py`, `ai/tests/test_embeddings_api.py`: HTTP·실패·동시성 계약 검증.
- `docs/api/03-ai-internal.md`, `docs/api/00-common.md`, `ai/app/services/retrieval/README.md`: 새 계약 및 BE 인계.

### Task 1: 청킹 서비스와 근거 보존

**Files:** Create `ai/app/schemas/document_chunks.py`, `ai/app/services/retrieval/document_chunks.py`; Test `ai/tests/test_document_chunks_api.py`.

**Interfaces:** `DocumentChunksRequest`는 spec의 평면 요청 필드를 받는다. `render_document_chunks(body: DocumentChunksRequest, counter: TokenCounter) -> bytes`는 기존 `parse_source`와 `build_chunks`를 호출해 전체 JSON을 UTF-8 bytes로 반환한다. 토크나이저는 주입한다.

- [ ] 정상 Markdown 4종과 BOM/CRLF/emoji, 한 행이 600토큰을 넘어 나뉘는 입력에서 manifest와 각 span을 정규화 원문으로 복원하는 실패 테스트를 작성한다. 같은 입력은 같은 키, 문서 ID·프로필 변경은 다른 키여야 한다. 제외 범위는 원문의 나머지를 정확히 덮는다.
- [ ] `python -m pytest tests/test_document_chunks_api.py -q`를 실행해 미구현 실패를 확인한다.
- [ ] DTO 및 매핑·고정 프로필·parser diagnostics·표시 locator·최종 JSON 8 MiB 검사를 구현한다. content bytes 초과는 별도로 식별한다.
- [ ] 동일 테스트를 통과시킨다.

### Task 2: 청킹 HTTP 계약과 처리 제한

**Files:** Create `ai/app/routers/document_chunks.py`; Modify `ai/app/main.py`; Test `ai/tests/test_document_chunks_api.py`.

**Interfaces:** `POST /v1/document-chunks`는 Bearer 인증, 유효한 `X-Request-Id`, `result/origin=PARSER/usage=[]` 및 spec 오류 코드를 제공한다. `render_document_chunks`는 worker thread에서 호출하고 반환 bytes를 그대로 쓴다.

- [ ] 인증, 잘못된 JSON/ID/필드, 미지원 kind/media/profile, 해시 불일치, Content-Length 없이 8 MiB 초과, content 1 MiB 초과, 100,000자·AST·결과 한도, 잘못된 Unicode에 대한 HTTP 실패 테스트를 작성한다. 413/422를 분리하고 결과 및 원문이 오류에 없음을 확인한다.
- [ ] 이벤트 barrier 테스트로 worker가 대기하는 동안 health가 응답하고, 10초 timeout 시 504 및 부분 결과 부재를 검증한다. 모델 호출은 없어야 한다.
- [ ] 테스트를 실행해 실패를 확인하고 요청 스트림 제한·인증·thread timeout·오류 분류·라우터 등록을 구현한다. 기존 재사용이 가능하면 request ID helper를 분리하되 임베딩 회귀를 피한다.
- [ ] 청킹 HTTP 테스트를 통과시킨다.

### Task 3: 임베딩 호환 확장과 인계

**Files:** Modify `ai/app/schemas/embedding_api.py`, `ai/app/routers/embeddings.py`, `ai/tests/test_embeddings_api.py`, `docs/api/03-ai-internal.md`, `docs/api/00-common.md`, `ai/app/services/retrieval/README.md`.

**Interfaces:** `EmbeddingRequest.embedding_profile_id`와 `purpose`는 모두 생략 또는 모두 유효한 값이어야 한다. 새 응답 result에는 실제 profile ID와 DOCUMENT/QUERY purpose가 있으며 legacy 응답의 필드는 변하지 않는다.

- [ ] 기존 응답 필드가 정확히 유지됨을 확인하고, DOCUMENT/QUERY 전달, 부분 필드/null, profile/model/dimension 불일치, 기존 오류·usage 유지 테스트를 작성한다. 실패 요청이 provider를 호출하지 않는지 확인한다.
- [ ] 테스트 실패 확인 후 DTO·라우터에 확장을 구현하고 기존 provider·timeout 정책을 재사용한다.
- [ ] API 문서와 예시에 정확한 offset·제외 범위·프로필·오류·시간 제한을 기록한다. BE의 기존 전체 행 검증 수정, `embedding_text`, 저장/검색 및 실제 DB 검증을 인계하며 담당자 합의를 주장하지 않는다.
- [ ] 임베딩·청킹 회귀 테스트, 전체 AI pytest, Ruff check/format 및 `git diff --check`를 실행한다. 사용자 미추적 파일을 보존하고 변경 파일만 검토한다.

## 실행 방식

사용자가 설계 반영과 구현 시작을 명시적으로 요청했다. 현재 세션에서 직접 순차 구현하고 완료 후 독립 검토한다. 구현 결과를 확인하기 전 BE 통합 완료나 티켓 완료를 주장하지 않는다.
