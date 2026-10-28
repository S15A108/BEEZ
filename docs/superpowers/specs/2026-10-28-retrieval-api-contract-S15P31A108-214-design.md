# #214 청킹·임베딩 HTTP 연동 설계

작성: 2026-10-28 · 담당: 박상은 · 브랜치: `feature/ai-retrieval-api-contract-S15P31A108-214`

상태: 사용자 방식 선택 완료, 상세 설계 검토안. AI 구현 계약을 정의하며 BE 담당자의 합의나 실제 DB 통합 완료를 의미하지 않는다.

독립 구현 관점 검토: 구현 계획을 세우기 위해 추가로 필요한 주요 질문 없음. 상세 설계는 사용자 확인 대기다.

## 목적·범위

#129에서 제공한 원문 파서·청커·임베딩을 BE가 HTTP로 호출할 수 있도록 연결한다. #130의 평가 코드는 다시 구현하지 않는다. #214는 AI DTO·라우터·응답 매핑·오류·계약 테스트·API 문서·BE 인계까지 다룬다. DB migration, worker/lease/READY 발행, SQL 검색 및 실제 DB 통합 시험은 BE의 기존 구현 범위다.

근거: 최신 dev `de0bb9c`, BE `feature/backend-document-upload-S15P31A108-192`의 `f3362fe`. 이 BE worker는 평면 청킹 결과와 전체 행 locator를 기대하며 임베딩 요청/결과에 profile/purpose를 요구한다. 기존 AI에는 청킹 라우터가 없고 임베딩 DTO는 두 필드를 거부한다. 시작 기준 AI 테스트는 150개 통과했다.

## 접근 방식과 사용자 선택

1. **원문 범위 보존 + BE 수정 인계 — 사용자 선택.** 내부 `source_spans`·`context_refs`·manifest를 HTTP에 보존한다. 긴 한 행과 떨어진 원문 범위를 정확히 표현할 수 있다. BE의 전체 행 복원·겹침 검사와 저장/검색 응답 매핑 보완이 필요하다.
2. **기존 BE의 전체 행 형식으로 제한.** 바로 소비할 수 있지만 긴 행 분할과 여러 범위의 청크를 거부하거나 청킹 방식을 바꿔야 한다. 사용자 요구인 정확한 원문 범위 보존에 제약이 있어 선택하지 않았다.

내부 파서·청커 알고리즘은 유지하고 외부 DTO와 매핑 계층을 추가한다. 아래 세부 기본값과 호환 정책은 구현자의 제안이며 설계 승인 대상이다.

## 청킹 요청

`POST /v1/document-chunks`, 내부 Bearer 인증 및 `X-Request-Id` 규칙은 기존 임베딩 API를 따른다. 문서 파싱·청킹에는 GMS 키와 모델 호출이 필요하지 않다.

| 필드 | 규칙 |
| --- | --- |
| project_id, document_version_id | 양의 십진 문자열 ID, 최대 19자리. BE가 프로젝트 권한·문서 소속을 확인한다. |
| request_key | 비어 있지 않은 최대 128자 상관관계 키. 멱등 실행·저장 보장은 BE 책임이다. |
| kind | PLAN / REQUIREMENTS / API_SPEC / ERD. BE의 REQ_SPEC는 REQUIREMENTS로 변환한다. |
| media_type | text/markdown만 허용. text/plain 등은 명시적으로 거부한다. |
| content | 원문을 엄격하게 UTF-8 디코딩한 문자열. 원본 BOM/CRLF를 전달할 수 있다. |
| content_hash | 원본 bytes SHA-256. 64자리 소문자 hex 또는 `sha256:` 접두사 형식. 운반만 하며 BE가 원본과 대조한다. |
| text_hash | utf8-lf-v1 정규화 원문의 UTF-8 SHA-256. 같은 두 형식을 허용하고 AI가 다시 계산해 대조한다. |
| normalization_profile | utf8-lf-v1만 허용. 내부 normalization_version으로 매핑한다. |
| parser_profile | 생략 시 markdown-v1. 다른 값은 거부한다. |
| chunking_profile_id | 서버 등록값 md-structure-v1만 허용한다. |

요청 DTO는 추가 필드를 거부한다. 내부 프로필 `structure-v1`과 새 외부 ID를 무조건 같은 것으로 취급하지 않는다. 새 라우터는 `profile_id=md-structure-v1`, 실제 tokenizer ID, 최대 600토큰, 최대 1,024청크, heading/table 문맥 보강 off의 고정 설정으로 생성한다. 실제 프로필 전체를 manifest 및 청크 키 계산에 반영한다. 요청에서 크기/문맥 정책을 임의로 바꾸지 못한다.

## 청킹 응답·원문 규칙

응답은 `result`, `origin=PARSER`, `usage=[]`다. `result`에는 다음을 포함한다.

- `contract_version=document-chunks-v1`
- 요청의 `project_id`, `document_version_id`, `request_key`, `text_hash`, `normalization_profile`, `chunking_profile_id`
- 실제 `parser_version`, 내부 전체 `manifest`, `excluded_ranges`, parser `diagnostics`
- `chunks`: 내부 RetrievalChunk의 모든 필드와 표시용 `locator`

`result.text_hash`는 BE의 대조를 위해 요청값의 접두사를 보존한다. 내부 manifest의 해시는 접두사 없는 64자리로 정규화하며, 두 표기법은 동일 청크 식별자를 생성한다.

`chunks[].locator`는 첫 span의 시작 행부터 마지막 span의 끝 행까지를 가리키는 표시용 범위다. **이 범위의 전체 행을 이어 붙여 text를 복원하면 안 된다.** 한 행 안에서 분할된 청크끼리는 표시용 locator가 겹칠 수 있다.

`source_spans`의 offset은 정규화된 문자열의 Unicode code point 기준, 0-based, 끝 제외다. `text`는 나열된 span의 원문 slice를 순서대로 이어 붙인 값이다. Java의 UTF-16 인덱스로 곧바로 substring하지 않고 code point offset을 변환해야 한다. span별 locator는 1-based 시작/끝 행 포함이다.

청크의 실제 근거는 `text`/`source_spans`다. `context_refs`는 원문 heading/table_header 참조이고, `applied`는 보조 문맥의 적용 여부다. 임베딩에는 `embedding_text`를 사용한다. 기본 프로필에서는 보강을 끄지만 참조 메타데이터는 유지한다.

BE는 manifest의 key 집합·개수, 문서/프로젝트/프로필·버전/해시를 대조하고 정규화 원문의 span 경계·순서·겹침·복원 및 excluded_ranges 포함 coverage를 검증해야 한다. `thematic_break`/`container_marker`와 빈 행은 명시적 제외 범위가 될 수 있다. 비어 있지 않은 모든 행을 무조건 청크 소유로 요구하지 않는다.

## 임베딩 확장·기존 호출 호환

기존 `POST /v1/embeddings`를 확장한다. `embedding_profile_id`와 `purpose=DOCUMENT|QUERY`는 **함께 보내거나 함께 생략**한다. 둘 중 하나만 전달하거나 null/알 수 없는 purpose를 보내면 400이다.

- 새 형식: 서버의 실제 `embedding_profile(settings).profile_id`와 요청값을 대조한다. 임의 ID를 그대로 echo하지 않는다. model/dimension도 기존처럼 실제 설정과 대조한다. 불일치는 유료 호출 전 422다.
- purpose는 내부 `document`/`query`로 매핑해 provider에 전달한다. 현재 두 용도의 전처리는 identity-v1로 동일하다.
- 새 형식의 result에는 실제 `embedding_profile_id`, 요청 `purpose`, 기존 model/dimension/vectors를 반환한다.
- 두 필드를 생략한 기존 요청은 지금과 같은 응답 필드만 반환한다. 기존 내부 동작의 document 기본값을 유지한다. 기존 응답에 새 필드를 강제로 추가하지 않는다.
- profile ID는 `gms-small-{실제 dimension}-v1`, 모델은 현재 허용된 text-embedding-3-small이다. 다른 모델·전처리가 추가되면 프로필 식별 규칙도 별도 버전으로 바꿔야 한다.

중복 key·누락/중복 벡터·차원·NaN·영벡터·float32 범위 검증, 실제 호출별 usage와 실패 시 usage 보존을 유지한다. 부분 벡터로 성공 응답을 만들지 않는다. 요청 예시와 테스트는 fake provider를 사용하며 이번 구현에서 유료 모델을 호출하지 않는다.

## 처리 예산·오류·동시성

청킹은 HTTP body 최대 8 MiB, content UTF-8 1 MiB 및 정규화 100,000자, 결과 전체 JSON 8 MiB 한도를 둔다. JSON 이스케이프 증가와 content 한도를 구분한다. body는 JSON 변환 전 제한하며 Content-Length만 신뢰하지 않는다. content 한도와 결과 한도는 worker에서 다시 검사한다.

정규화·파싱·청킹·최종 응답 JSON 바이트 생성 및 크기 계산은 `asyncio.to_thread`에서 처리한다. 검사한 바이트를 그대로 응답하여 이벤트 루프에서 대용량 JSON을 다시 직렬화하지 않는다. 청킹 대기 예산은 검증/worker 시작부터 10초로 고정하고 초과 시 전체 실패한다. 모델 호출은 하지 않는다. 취소/시간 초과 후 이미 시작한 CPU 스레드가 즉시 중단되지는 않지만 그 결과를 응답하거나 저장하지 않는다. 프록시의 요청 크기·동시성 제한도 필요하다.

임베딩은 기존 검증 포함 전체 90초·공급자 호출별 20초·기존 재시도 정책을 유지한다. 새 자동 재시도는 추가하지 않고 타임아웃은 재시도하지 않는다. BE에는 AI 호출 전용 read timeout 100초 이상 또는 비동기 작업 접수 방식 중 선택이 필요함을 인계한다. 이 AI PR이 BE 설정을 변경하거나 합의를 대신하지 않는다. 클라이언트 연결 종료가 공급자 처리·과금 취소를 보장하지 않는다.

| HTTP | 청킹 code | 상황 |
| --- | --- | --- |
| 400 | validation_failed | JSON·필드·타입·ID 형식 오류 |
| 401 | invalid_internal_token | 기존 내부 인증 규칙 |
| 413 | file_too_large | HTTP body 또는 content 입력 크기 초과 |
| 415 | unsupported_media_type | 미지원 문서 kind/media_type |
| 422 | invalid_ai_input | 해시·프로필·문법·coverage·청크 수/결과 크기 한도 실패 |
| 503 | processing_unavailable | 로컬 토크나이저 준비 등 처리 기반 이용 불가 |
| 504 | processing_timeout | 청킹 처리 시간 초과 |

청킹 오류는 `error.code/message`와 필요한 경우 원문을 포함하지 않는 `error.details.reason`, 검증된 locator를 제공한다. 모두 result 없이 반환하고 X-Request-Id를 유지한다. 임베딩은 기존 400/401/422/502/504 code와 usage 규칙을 유지한다. source 원문·인증/GMS 키·provider 응답 본문을 오류에 노출하지 않는다.

내부 `document_limit_exceeded`는 입력 크기, 정규화 길이, AST·표·파서 출력 한도에 함께 사용된다. 오류 코드만으로 413을 결정하지 않고 입력 바이트 한도를 별도 확인해 413으로 분류한다. 정규화 길이·AST·표·파서 출력 한도는 422로 분류하며, 각각의 동작을 HTTP 테스트로 고정한다.

## BE 인계·적용 순서

1. 새 contract_version과 실제 프로필 설정을 확인한다. 공유 Bearer 인증을 구성한다.
2. BE의 전체 행 text 복원/중복 검사 대신 source_spans + excluded_ranges 검증을 연결하고, 정확한 원문 위치·context_refs를 저장/검색 결과에 전달한다. 기존 DB 컬럼에 담을 형식 또는 migration 선택은 BE가 담당한다.
3. 문서 임베딩에는 chunk.embedding_text, key에는 chunk.chunk_key를 사용하고, DOCUMENT/QUERY 모두 같은 전체 임베딩 프로필을 사용한다.
4. read timeout과 실패·취소·usage 저장 정책을 맞춘 뒤 worker/READY 교체·검색을 실제 DB에서 검증한다.

현재 f3362fe worker는 새 청킹 계약을 완전히 소비할 수 없다. AI 계약 테스트가 통과해도 BE 통합 완료로 표시하지 않는다. BE 티켓 배정·상태·연결을 임의 변경하지 않고 이 내용을 API 문서와 PR에 인계한다.

## 구성·검증 계획

- 신규 `schemas/document_chunks.py`, `services/retrieval/document_chunks.py`, `routers/document_chunks.py`에 외부 DTO·매핑·HTTP 책임을 나눈다.
- 기존 embedding_api/라우터는 선택적 확장만 하고, 공통 HTTP helper는 중복 제거에 꼭 필요한 경우에만 도입한다.
- 신규 라우터를 main에 등록하고 API 문서·공통 응답 스키마·요청/응답 예시·retrieval README에 #214를 반영한다.
- RED/GREEN으로 내부 인증, BE 입력, 4종 Markdown, BOM/CRLF/한글/emoji, 긴 행 하위 분할, 여러 span과 제외 범위의 원문 복원, key 결정성·문서/프로필 변화, 미지원 형식/해시/프로필/크기·청크 한도/실패 시 result 부재를 검증한다.
- 이벤트 barrier를 이용해 CPU 처리 중 health 응답, timeout·취소 후 결과 폐기, 유료 호출 0회를 검증한다. 실제 시간을 오래 기다리는 테스트는 피한다.
- 임베딩 legacy 정확한 응답 형식 회귀, 새 DOCUMENT/QUERY 전달·실제 프로필 대조, 단독 필드/null 거부, 정상·모델 오류·timeout 및 실패 usage 보존을 검증한다.
- BE 요청/응답 소비를 모사한 계약 fixture를 제공하고, 전체 AI pytest·Ruff check/format·diff 검사를 수행한다. 실제 Java/DB 통합이나 BE 담당자의 계약 승인을 이 결과로 대체하지 않는다.

## 완료 기준

합의 대상으로 제시할 명시적 계약과 예시, AI 라우터/DTO 구현, 로컬 단위·HTTP 회귀 검증 및 BE 후속 변경 목록이 준비되면 AI 개발 산출물은 완료다. BE 담당자의 외부 계약 확인 여부는 별도로 기록한다. #214 티켓은 컨벤션대로 리뷰 승인과 dev 병합 후 완료 처리한다.
