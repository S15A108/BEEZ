# #130 AI 색인 검증·검색 평가 및 BE 인계

관련: [S15P31A108-130](https://ssafy.atlassian.net/browse/S15P31A108-130) · 담당: 박상은 · **제안 v0.1, BE 합의 전**

#130의 담당 범위는 **AI 검증 도구·fixture·개발용 실제 GMS 평가·BE 인계 산출물**이다. 실제 BE 저장/검색 구현은 오서현 담당의 기존 `feature/backend-document-upload-S15P31A108-192` 브랜치에서 진행 중이다. 아래 저장/READY/검색 규칙은 기존 BE 구현과 대조할 인계 요구사항이며 #130 종료를 막는 공동 작업 조건이 아니다. [평가 기록](../experiments/document-rag-evaluation-S15P31A108-130.md)에 수치·실패·비용·제한을 남겼다.

청킹 DTO·AI-A04·검색 결과 규약은 [#129 계약안](document-chunking-search-contract-S15P31A108-129.md)을 따른다. 이 브랜치는 최신 dev에서 시작하고 미병합 PR #21의 AI 모듈을 의존성 merge로 연결했다. #21 병합 후 dev 기준으로 #130 변경만 남는지 확인하고 별도 PR을 생성한다.

## 1. 산출물 인계와 책임

- AI: 전체 청크·벡터·key·hash·profile을 검증한 PreparedIndex, 검증 시나리오·검색 전략·품질 평가 자료를 제공한다. DB에 직접 접속하지 않는다.
- 기존 BE 담당은 오서현이다. 확인한 #192 브랜치 `DocumentIndexWorker`와 V3 migration에 run/attempt/lease·SOURCE/INDEX·청크/벡터 저장·manifest 교체·벡터 SQL 검색이 존재한다. 구현 존재와 계약 호환/실제 통합 시험 완료는 구분한다. 기존 BE 티켓의 담당·범위·상태나 이슈 연결은 변경하지 않는다.
- #130 담당은 실행 가능한 입력 packet·검증기·개발용 측정 결과·실패 사례·인터페이스 제안을 인계하면 구현 책임을 마친다. BE의 구현 완료·별도 담당 배정·운영 품질 승인까지 기다리지 않는다. 인터페이스 자체의 추가 변경이 필요하면 별도 변경 티켓으로 추적한다.
- 기존 BE 구현과 연결할 때 AI 전체 90초/공급자 호출별20초와 BE 기본 read timeout20초 차이를 대조한다. 공개 DTO 자체의 변경은 별도 구현 범위에서 다루며 이 문서가 확정된 양쪽 계약을 대신하지 않는다.

### 확인된 계약 차이

2026-10-27 #192 `f3362fe`와 AI #129 `d34530f` 기준이다. BE는 `/v1/document-chunks`를 호출하지만 AI에는 해당 HTTP 라우터가 아직 없고 내부 청킹 모듈만 있다. BE의 청킹 입력은 `normalization_profile`, `md-structure-v1` 및 행 locator 기반 결과를 기대하며 AI 제안은 `normalization_version`/`parser_profile`, `structure-v1`, manifest와 source_spans 기반이다. BE 임베딩 요청의 `embedding_profile_id`/`purpose`는 현재 AI-A04 요청 스키마에서 허용하지 않고, AI 응답에도 그 필드가 없다. 따라서 현재 상태를 즉시 연결 가능한 완성 계약으로 표시하지 않는다. 상세 대조와 재현 근거는 PR 인계 본문에 기록한다. AI HTTP/API 구현 보완이 필요하면 해당 구현 작업에서 추적하며 #130의 평가 산출물 완료와 구분한다.

## 2. 전체 배치 발행 제안

`publish_index(run_id, attempt, lease_token, prepared_result)`는 BE 도메인 계약안이며 공개 REST 경로가 아니다.

BE가 트랜잭션에서 재확인할 값:

1. 현재 run/attempt/lease 소유권과 결과를 받을 수 있는 상태.
2. 실행의 프로젝트·문서·문서 버전과 반환 manifest의 동일성.
3. 원본 content_hash·정규화 text_hash, 파서/청커 버전, 전체 청킹·임베딩 프로필.
4. expected_chunk_keys와 청크·벡터의 정확한 key 집합, chunk_no 순서, source span/해시, 유한 숫자·차원·0 norm·float32 저장 가능성.

현재 document_chunk는 `(document_version_id, chunk_no)`가 유일하다. MVP 제안은 비공개 결과 준비 후 해당 버전의 청크 전량과 READY manifest를 한 트랜잭션으로 교체하는 방식이다. 일부 삽입 뒤 실패하면 전체 롤백한다. 행별 부분 upsert를 검색에 노출하지 않는다. 이전 attempt/lease와 취소된 실행은 현재 성공본을 바꾸지 않는다.

SOURCE 성공과 INDEX 성공을 분리한다. 같은 허용 버전·활성 임베딩 프로필의 기존 READY는 재색인 중이나 실패 후에도 사용할 수 있어야 한다. 새 승인으로 baseline에서 빠진 옛 버전은 기본 검색에서 즉시 제외한다. 승인 원문·설계 항목의 사람 수정을 색인 갱신이 덮지 않는다.

현재 Python index_key는 `{"source": ChunkManifest, "embedding": EmbeddingProfile}`을 재귀 key 정렬하고 전체 필드·배열 순서를 유지해 공백·말미 개행 없이 JSON으로 직렬화한 UTF-8 bytes의 SHA-256 소문자 hex다(`ensure_ascii=False`, `separators=(",", ":")`). BE와 최종 조립 위치·hash 규약을 합의하고 예시로 대조한다. 기존 V2 수정이나 미합의 migration 번호 발급은 이 자료의 범위가 아니다.

## 3. READY·승인·답변 검증

APPROVED는 현재 baseline의 정확한 허용 버전만 사용한다. 필요한 새 승인본이 미색인이면 준비 중이며 과거 baseline·초안으로 fallback하지 않는다. DOCUMENT_REVIEW는 BE가 접근 검사를 마친 명시적 버전에 한정한다.

프로젝트·현재 멤버십·허용 버전·READY·활성 프로필 조건을 정렬·top-k 전에 적용한다. 오류·미준비·정상 0건·프로필 불일치를 구분한다. 보조 목록 필터의 없는 ID 0건과 프로젝트·상세 대상 접근 거부는 구분한다. 실제 SQL은 작은 허용 집합의 정확 거리 정렬부터 검증하고 HNSW 최적화는 쿼리 계획·재현율 측정 후 결정한다.

baseline 내 필수 문서의 READY/profile과 모델 전환 정책을 BE와 고정한다. 새 모델 준비 전 활성 profile을 바꾸지 않는다. 비비 반환 직전 BE가 멤버십·scope·baseline을 재검사하고 변경 시 이번 답변을 폐기한다. 최종 검사 직후 변경까지 막는 최신성은 보장하지 않는다.

## 4. 합성 검증 packet

자료: `ai/tests/fixtures/retrieval/handoff.json`, `ai/scripts/generate_retrieval_handoff.py`, `ai/tests/test_retrieval_handoff.py`.

`ai/`에서 어휘 준비 후 실행한다. 생성기는 실제 파서·청커·PreparedIndex를 사용하지만 GMS·DB·네트워크를 호출하지 않는다.

```text
python -m scripts.prepare_tokenizer
python -m scripts.generate_retrieval_handoff --output <출력 경로>/retrieval-handoff.json
```

- 두 프로젝트와 문서 4개: 과거 승인본 10MB, 현재 승인본 30MB, 초안 50MB, 다른 프로젝트의 같은 내용.
- 1024차원 합성 벡터·usage. provider/profile_id와 metadata의 Jira 키를 fixture 전용으로 표시하며 운영 READY·과금 기록에 넣지 않는다.
- 현재 버전 202의 초기 published_seed는 이전 청킹 정책·문맥 보강·벡터로 만든 별도 PreparedIndex다. 새 후보와 key/벡터가 달라 교체 누락을 관찰할 수 있다. 같은 문서 버전·임베딩 프로필은 유지한다.
- 성공 시 후보 key, 거부·이전 실행·rollback 시 이전 READY key를 기대한다. 각 발행 case의 expected.ready_index_key를 대조한다. replay는 먼저 성공 발행한 뒤 같은 결과를 재전달한다. 각 case는 초기 seed부터 독립적으로 실행한다.
- 제외 대상 점수가 현재 승인본보다 높아 전역 top-k 후 필터링을 잡을 수 있다. 이는 합성 SQL 검증 데이터이며 실제 모델 검색 품질이 아니다.

26개 시나리오는 승인/검토 범위·비활성 멤버·프로필·준비 중·정상 0건·재색인 중 조회와, 누락/중복 key·잘못된 차원/모델·NaN/영벡터·hash 불일치·이전 attempt/lease·부분 AI 실패·저장 rollback·재전달을 포함한다.

Python 시험은 artifact/schema·근거·key·차원·재현성·case 참조를 검증한다. BE는 같은 자료로 PostgreSQL/pgvector 통합 시험을 작성한다. published_seed와 run.expected_source는 시험용 초기 상태이며 AI 응답이나 운영 권한·원본·발행 성공 증거가 아니다.

## 5. #130 완료 조건

- [x] 실제 AI artifact 형식과 source/key/hash/profile을 검증하는 합성 packet·BE 시나리오 26개 및 생성 명령 제공.
- [x] 별도 baseline oracle과 Event barrier를 사용하는 C 모의 실행 6개·검증기 제공. 실제 DB 시험과 구분.
- [x] Markdown 4종·12질문 × 고정/구조·보강 off/on ×3회 D 개발용 실제 GMS 실행, 144개 답변·usage·Recall/MRR·인용/보류·비용/지연·실패 및 잠정 설정 기록.
- [x] 실행·재개·한도·검증 명령과 코드/데이터 provenance 제공. 전체 AI 테스트 175개 및 Ruff 통과 기록.
- [x] 기존 BE 구현의 담당·근거·DTO/청킹 라우터 차이와 상태·저장/검색·timeout·실제 통합 검증 요구사항을 PR 인계 본문에 명시.
- [ ] #130 PR이 리뷰 조건을 충족해 dev에 병합됨. 이 조건을 충족하면 #130을 완료 처리한다.

실제 DB/운영 경로 시험, 운영 자료·정답 라벨 검토 및 품질/지연 합격선은 이후 통합 검증 사항이다. 기존 BE 작업의 책임을 새 티켓으로 이전하지 않으며 수행하지 않은 시험을 성공으로 표시하지 않는다. #130은 위 자체 완료 조건과 dev 병합 기준으로 닫을 수 있다.
