# #129 문서 청킹·임베딩·검색 연동 계약안

관련: [S15P31A108-129](https://ssafy.atlassian.net/browse/S15P31A108-129) / PR #21 · 담당: 박상은 · **제안 v0.1, BE 합의 전**

Jira #129의 AI 범위는 청크·임베딩·검색 전략·근거 메타다. BE는 벡터 저장·검색 SQL·권한/승인 판정·상태를 담당하며 구체적 DB 구현 담당은 공동 합의한다. #129의 완료 조건에는 실제 BE 저장/SQL 검색의 프로젝트·허용 버전·승인본 필터와 실제 검색 입출력 예시 확인도 포함된다. AI 모듈 테스트만으로 티켓 전체를 완료 처리하지 않는다.

## 1. 현재 제공하는 AI 기능

- Markdown 4종의 원문 구조·위치를 보존하는 파서와 청커. 하위 source span, chunk key, 문서 버전·해시·파서/청커 버전·전체 청킹 프로필을 제공한다.
- GMS text-embedding-3-small / 1024차원 / cosine / identity-v1 프로필과 기존 `POST /v1/embeddings`.
- key 집합·차원·유한 숫자·영벡터·float32 저장 가능성 검증, 실제 호출별 usage와 부분 실패 처리.
- 초기 최대 600토큰·자동 overlap 0. 제목/표 헤더 보강은 별도 옵션이며 예산에 포함한다.

`PreparedIndex`는 Python 내부의 저장 준비 결과다. 공개 HTTP 응답이나 BE의 READY 발행 완료 증거가 아니다.

## 2. 제안 `POST /v1/document-chunks`

내부 Bearer 인증과 `X-Request-Id`는 AI-A04와 같다. 라우터·공통 API 변경은 BE와 DTO·오류표를 확정한 뒤 반영한다. 요청은 내부 `SourceInput` 필드에 request_key와 chunking_profile_id를 더한 평면 객체를 제안한다.

| 필드 | 규칙 |
| --- | --- |
| project_id, document_version_id | 십진 문자열 ID. BE가 현재 멤버십·문서 소속·원본 접근을 확인한 뒤 전달 |
| request_key | BE의 실행 상관관계 키. 키 자체가 권한·멱등성을 보장하지 않음 |
| kind, media_type | PLAN / REQUIREMENTS / API_SPEC / ERD + text/markdown |
| content | UTF-8 원문을 엄격하게 디코딩한 문자열. BOM·CRLF를 임의 제거하지 않음 |
| content_hash | 원본 bytes의 SHA-256. AI는 운반하고 BE가 원본과 대조 |
| text_hash | utf8-lf-v1 정규화 텍스트의 UTF-8 SHA-256. AI가 다시 계산해 대조 |
| normalization_version, parser_profile | utf8-lf-v1 / markdown-v1. 불일치 시 거부 |
| chunking_profile_id | 초기 structure-v1. 서버에 등록된 프로필 선택 |

DB document.type의 REQ_SPEC는 AI kind의 REQUIREMENTS로 변환한다. PLAN/API_SPEC/ERD는 같은 값이다.

응답 제안은 `{"result": ChunkBatch, "origin": "PARSER", "usage": []}`다. ChunkBatch의 manifest/chunks/excluded_ranges를 보존한다. `source_spans`는 원문 근거, `embedding_text`의 추가 제목/표 문맥은 보조 정보다. 파싱·청킹은 모델을 호출하지 않으며 HTTP 연결 시 동기 CPU 처리를 워커로 넘긴다.

입력 1 MiB/100,000자, 최대 1,024청크, 결과 8 MiB 한도를 유지한다. 해시·프로필·coverage·지원 형식 오류는 전체 실패다. HTTP 오류 매핑은 합의 후 추가하며 기존 AI-A04의 code를 바꾸지 않는다.

## 3. AI-A04와 검색 결과 계약

BE가 `texts[].key = chunk.chunk_key`, `texts[].text = chunk.embedding_text`로 호출한다. 응답 순서 대신 key 집합으로 연결하고 전체 임베딩 프로필을 대조한다. 부분 벡터로 성공을 표시하거나 벡터를 템플릿·0으로 대체하지 않는다.

AI의 기본 전체 예산은 검증을 포함해 90초다. 최신 dev의 BE 기본 read timeout은 20초여서, 임베딩 전용 읽기 예산 또는 작업 접수 방식을 함께 정해야 한다. 연결 종료가 공급자 처리·과금 취소를 보장하지 않는다.

검색 scope는 BE가 인증 후 확정한다. APPROVED는 현재 current_baseline_id의 허용 버전, DOCUMENT_REVIEW는 접근 검사를 마친 명시적 버전 목록이다. 프로젝트·권한·허용 버전·READY·전체 임베딩 프로필 조건 안에서 top-k를 구한다. 전역 top-k 후 필터링이나 과거 승인본 fallback을 사용하지 않는다.

검색 결과에는 문서 버전·원문 위치·원문 text·유사도·profile·scope를 포함하고, 오류·미준비·정상 0건을 구분한다. cosine 임계값은 실제 평가 전 고정하지 않는다. 결과를 #90 컨텍스트·#92 비비에 전달할 최종 DTO는 사용처 담당자와 확인한다.

## 4. #130과 완료 범위 구분

[S15P31A108-130](https://ssafy.atlassian.net/browse/S15P31A108-130)에서 실행 run/attempt/lease·SOURCE/INDEX 상태·전체 배치 발행·READY 교체·재색인 롤백·늦은 실행 차단과 실제 C/D 측정을 다룬다. 그 영역의 합성 시나리오·생성기·구현 계획도 #130 브랜치로 분리한다.

#129는 현재 AI 모듈/API 구현과 리뷰 수정까지 검증됐다. 실제 BE 저장·검색 연동, 검색 결과 예시·담당자 검토가 남아 있으므로 Jira 상태는 진행 중으로 유지한다.
