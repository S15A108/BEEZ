# 문서 청킹·색인·검색 AI↔BE 연동 계약안

작성: 2026-10-27 · 관련: S15P31A108-129 / PR #21 · **제안 v0.1, BE 합의 전**

이 문서는 AI가 전달할 데이터와 BE 통합 시험의 기대 결과를 정한다. 공개 API 확정, DB migration, BE 서비스 구현 또는 실제 PostgreSQL 검증의 완료를 뜻하지 않는다. 현재 AI 모듈은 `SourceDocument → ChunkBatch → PreparedIndex`와 AI-A04 임베딩 API까지 구현됐다.

근거: [RAG 계획 Task 4](../superpowers/plans/2026-10-26-document-rag-development.md), [AI Internal](../api/03-ai-internal.md), [현재 모듈 계약](../../ai/app/services/retrieval/README.md).

## 1. 연결 순서와 책임

1. BE가 호출자의 현재 멤버십, 대상 문서의 프로젝트·버전, 원본 접근 권한을 검사한다.
2. BE가 UTF-8 원본을 읽고 원본 bytes의 `content_hash`와 정규화 텍스트의 `text_hash`를 확인한다.
3. 제안 청킹 API가 원문 구조·위치·하위 범위를 보존한 청크를 반환한다. 모델은 호출하지 않는다.
4. BE가 청크 key와 `embedding_text`를 기존 AI-A04에 전달한다. AI는 GMS 임베딩과 실제 호출별 usage를 반환한다.
5. BE가 실행 소유권과 전체 결과를 검증한 뒤 한 트랜잭션으로 청크와 READY manifest를 발행한다.
6. 검색 때 BE가 현재 허용 버전과 활성 프로필을 확정하고, 그 집합 안에서 벡터 정렬·limit을 적용한다.

AI는 DB·원본 저장소·사용자 권한을 직접 조회하지 않는다. 프로젝트 ID는 상관관계 식별자이며, AI 응답을 사용자 접근 권한의 증명으로 사용하지 않는다.

## 2. 제안 `POST /v1/document-chunks`

내부 Bearer 인증과 `X-Request-Id`는 AI-A04와 같다. 아직 라우터를 공개하지 않는다.

요청은 기존 내부 `SourceInput` 필드에 `request_key`와 `chunking_profile_id`를 더한 평면 객체를 제안한다.

| 필드 | 제안 규칙 |
| --- | --- |
| project_id, document_version_id | API Common의 십진 문자열 ID. BE가 소속 확인 후 전달 |
| request_key | BE의 실행 상관관계 키. 실행 소유권·멱등성 자체를 보장하지 않음 |
| kind, media_type | PLAN / REQUIREMENTS / API_SPEC / ERD + text/markdown |
| content | 원본을 엄격하게 UTF-8 디코딩한 문자열. BOM·CRLF를 임의 제거한 원문으로 바꾸지 않음 |
| content_hash | 원본 파일 bytes의 SHA-256. AI는 bytes가 없으므로 그대로 운반하며 BE가 원본과 대조 |
| text_hash | utf8-lf-v1 정규화 결과의 UTF-8 SHA-256. AI가 다시 계산해 대조 |
| normalization_version, parser_profile | utf8-lf-v1 / markdown-v1. 불일치 시 거부 |
| chunking_profile_id | 초기 structure-v1. 임의 옵션 변경 대신 서버에 등록된 프로필을 선택 |

현재 DB의 `document.type=REQ_SPEC`는 AI의 `kind=REQUIREMENTS`에 대응한다. 나머지 PLAN/API_SPEC/ERD는 같은 값이다. BE 어댑터에서 명시적으로 변환한다.

응답 제안: `{"result": ChunkBatch, "origin": "PARSER", "usage": []}`. `ChunkBatch`에는 manifest, chunks, excluded_ranges가 있다. 각 chunk의 `source_spans`는 원문 근거이고, `embedding_text`의 선택적 제목/표 보강은 근거 범위로 저장하지 않는다. manifest의 실제 파서·청커 버전과 전체 프로필을 보존한다.

입력 1 MiB/100,000자, 초기 600토큰/1,024청크, 결과 8 MiB 한도를 유지한다. 형식·해시·프로필·coverage 오류는 전체 실패다. 오류 code와 HTTP 매핑은 합의 후 AI Internal에 추가하며 기존 AI-A04의 code를 바꾸지 않는다. 라우터로 연결할 때 동기 파싱·청킹은 이벤트 루프 밖의 워커에서 실행한다.

## 3. 기존 AI-A04 연결

- 요청 `texts[].key = chunk.chunk_key`, `texts[].text = chunk.embedding_text`다. 원문 `chunk.text`와 혼용하지 않는다.
- 응답 순서 대신 key 집합으로 연결한다. 누락·추가·중복 key, 잘못된 차원·프로필, NaN/Inf·영벡터·float32 저장 불가 값을 거부한다.
- 운영 프로필은 현재 GMS / text-embedding-3-small / 1024차원 / cosine / identity-v1이다. BE와 AI가 같은 전체 프로필을 설정해야 한다. DB의 차원만 보고 모델 일치를 판단하지 않는다.
- 요청 실패 시 받은 usage는 실제 호출 기록으로 처리하고, 부분 벡터로 READY를 만들지 않는다. 벡터를 템플릿이나 0으로 대체하지 않는다.
- AI 검증을 포함한 전체 예산은 기본 90초다. BE의 read timeout도 이를 고려해 별도로 맞춘다. 연결을 끊었다고 공급자 처리·과금이 취소됐다고 기록하지 않는다.

최신 dev의 BE 기본 read timeout은 20초라 AI의 전체 예산 90초와 다르다. 임베딩 전용 읽기 예산 또는 비동기 작업 접수 방식을 BE와 합의해야 한다. 이 계약안은 공통 타임아웃을 일괄 변경하지 않는다.

## 4. 발행 불변 조건

`publish_index(run_id, attempt, lease_token, prepared_result)`는 도메인 서비스 계약 제안이며 공개 REST 경로가 아니다.

발행 전 BE가 DB에서 재확인할 값:

- 현재 실행의 run_id·attempt·lease 소유권 및 아직 결과를 받을 수 있는 상태.
- 실행이 가리킨 프로젝트·문서·문서 버전과 반환 manifest의 동일성.
- 원본 content_hash·정규화 text_hash, 파서·청커 버전, 청킹 프로필·임베딩 프로필.
- manifest의 expected_chunk_keys와 실제 청크·벡터의 정확한 key 집합, chunk_no 순서, 원문 span·해시.

현재 Python 내부 `index_key`는 `{"source": ChunkManifest, "embedding": EmbeddingProfile}`을 재귀적으로 객체 key 정렬하고, 기본값을 포함한 전체 필드·배열 순서를 유지해 공백·말미 개행 없이 JSON으로 직렬화한 UTF-8 bytes의 SHA-256 소문자 hex다(`ensure_ascii=False`, `separators=(",", ":")`). BE가 같은 식별자를 만들 경우 이 규약과 packet의 예시를 대조한다. PreparedIndex는 두 HTTP 응답을 결합한 검증용 내부 결과이며 현재 공개 API의 응답 봉투가 아니다. 최종 조립 위치·hash 규약도 합의 후 고정한다.

현재 `document_chunk`는 `(document_version_id, chunk_no)`가 유일하다. 따라서 MVP는 해당 버전의 청크 전량 교체와 manifest READY 반영을 한 트랜잭션으로 묶는 안을 제안한다. 일부 삽입 뒤 실패하면 전체 롤백한다. 이전 성공본은 동일 허용 버전·활성 프로필이면 계속 검색할 수 있어야 한다. 늦게 도착한 이전 attempt/lease는 현재 성공본을 바꾸지 않는다.

실행·manifest·색인 준비 상태를 영속화할 위치와 migration은 BE 담당이 정한다. 기존 V2를 수정하거나 parse_status 하나를 색인 상태로 재해석하지 않는다. SOURCE 성공과 INDEX 성공은 별개다.

## 5. 검색 불변 조건

`search_evidence(scope, query_vector, embedding_profile, limit)` 역시 도메인 계약 제안이다. scope는 BE가 인증 후 만든 값이며 사용자·AI가 보낸 값만으로 권한을 부여하지 않는다.

| 모드 | 버전 집합 |
| --- | --- |
| APPROVED | 현재 project.current_baseline_id의 baseline_document에 속한 정확한 버전 |
| DOCUMENT_REVIEW | 해당 요청에서 BE가 접근 검사를 마친 명시적 문서 버전 목록 |

프로젝트·현재 멤버십·허용 버전·READY manifest·활성 프로필 조건을 **정렬·top-k 전에** 적용한다. 전역 top-k 후 애플리케이션 필터링으로 대신하지 않는다. 작은 MVP에서는 허용 집합의 정확 거리 정렬을 먼저 검증하고 HNSW 사용 여부는 실제 쿼리 계획·재현율로 정한다.

APPROVED에서 필요한 현재 버전의 색인이 없으면 준비 중이다. 과거 baseline·초안·다른 프로젝트로 넓혀 결과를 채우지 않는다. 기존 READY가 있는 동일 버전·프로필의 재색인 중에는 그 성공본을 사용할 수 있다.

정상 0건, 색인 미준비, 검색 실패, 프로필 불일치는 서로 다른 도메인 결과로 구분한다. 최종 HTTP code·DTO는 합의 대상이다. 보조 목록 필터로 넣은 없는 ID는 0건이라는 공통 정책과, 프로젝트·상세 대상의 접근 거부를 구분한다.

비비 응답 연결 시에는 별도로 최종 반환 직전 멤버십·scope·baseline을 재검사한다. 이 검색 계약만으로 답변 수신 시점의 권한·최신성을 보장하지 않는다.

## 6. 검증 packet

원본 fixture 정의: `ai/tests/fixtures/retrieval/handoff.json`.

`ai/`에서 다음을 실행한다. 모델·DB·네트워크 호출은 하지 않으며 공개 토크나이저 어휘는 사전에 준비해야 한다.

```text
python -m scripts.prepare_tokenizer
python -m scripts.generate_retrieval_handoff --output <출력 경로>/retrieval-handoff.json
```

생성 packet은 실제 파서·청커·PreparedIndex 경로를 통과한 데이터와 제안 청킹/임베딩 요청·응답 예시를 담는다. 모든 문서·계정·벡터·usage는 합성 fixture다. provider/profile_id를 fixture 전용으로 구분하며 운영 READY나 과금 기록에 넣지 않는다. 실행마다 달라지는 모델 호출 키는 재현 가능한 fixture 전용 키로 치환한다.

문서별 `published_seed`는 BE 시험의 초기 READY 데이터를 설정하기 위한 합성 상태다. AI 응답이나 발행 완료 증거가 아니다. `run.expected_source`는 시험에서 DB 측 원본·버전과 대조할 입력이며, 생성 packet 자체를 운영 권한·원본의 진실로 신뢰하지 않는다.

발행 대상 202의 초기 READY는 이전 청킹 정책·문맥 보강으로 생성한 별도 PreparedIndex다. 새 후보와 index_key·chunk key·벡터 값이 다르고, 임베딩 프로필은 같다. 성공하면 후보 key, 거부·이전 실행·롤백이면 이전 READY key가 남아야 한다. 각 발행 시나리오의 `expected.ready_index_key`를 확인한다. `publish-replay`는 먼저 성공 발행한 뒤 같은 결과를 다시 발행하는 순서라 후보 key를 유지한다. 시나리오는 서로 독립적으로 초기 seed를 다시 적재하고 mutation을 적용한다.

두 프로젝트, 과거 승인본 10MB·현재 승인본 30MB·초안 50MB, 비활성 멤버를 포함한다. 제외돼야 할 후보의 코사인 점수를 현재 승인본보다 높게 만들어, 필터를 top-k 뒤에 적용하는 구현을 잡을 수 있게 한다. 별도 시나리오는 누락 벡터·다른 프로필·이전 attempt·lease·저장 롤백과 READY 유지의 기대 결과를 제시한다.

Python 검사는 packet의 데이터·근거·key·차원·시나리오 참조 무결성만 검증한다. BE 담당은 같은 데이터로 PostgreSQL/pgvector 통합 테스트를 구현해야 한다. 그 결과 전에는 권한 차단·원자적 교체·검색 통합을 완료로 표시하지 않는다.

## 7. BE 합의 항목

1. 문서 원본 디코딩·종류 매핑과 청킹 요청/응답의 최종 DTO·오류표.
2. 청킹 호출·AI-A04 호출·실행 저장·발행·검색의 BE 담당과 트랜잭션 경계.
3. 실행 attempt/lease, READY manifest, 활성 프로필의 영속 구조와 새 migration.
4. 두 검색 모드의 허용 버전 집합·준비 중 응답·BE 타임아웃.
5. 이 packet을 사용하는 실제 DB 시험과 모델 기반 검색 품질 평가의 담당·완료 기준.
