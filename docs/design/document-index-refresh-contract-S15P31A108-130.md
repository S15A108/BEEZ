# #130 색인 갱신·READY 발행·검증 계약안

관련: [S15P31A108-130](https://ssafy.atlassian.net/browse/S15P31A108-130) · 담당: 박상은 · **제안 v0.1, BE 합의 전**

#130은 색인 갱신·복구와 실제 검색/답변 C/D 평가를 담당한다. 계약안·합성 fixture에 더해 AI 독립 C 모의 실행과 실제 GMS D 개발용 측정을 수행했다. [평가 기록](../experiments/document-rag-evaluation-S15P31A108-130.md)에 수치·실패·비용을 남겼다. 실제 BE/DB 시험과 독립 사람 검토는 남아 있으며 문서·스크립트만으로 티켓을 완료 처리하지 않는다.

청킹 DTO·AI-A04·검색 결과 규약은 [#129 계약안](document-chunking-search-contract-S15P31A108-129.md)을 따른다. 이 브랜치는 최신 dev에서 시작하고 미병합 PR #21의 AI 모듈을 의존성 merge로 연결했다. #21 병합 후 dev 기준으로 #130 변경만 남는지 확인하고 별도 PR을 생성한다.

## 1. 역할과 선행 합의

- AI: 전체 청크·벡터·key·hash·profile을 검증한 PreparedIndex, 검증 시나리오·검색 전략·품질 평가 자료를 제공한다. DB에 직접 접속하지 않는다.
- BE: 원본·버전·멤버십·승인 범위를 확인하고 실행·SOURCE/INDEX 상태·벡터 저장·발행 트랜잭션·검색 SQL을 구현한다. 구체적인 DB 담당과 새 migration/manifest 구조는 공동 합의한다.
- #130 담당자는 BE와 실제 rollback·이전 실행 차단·현재 승인본·검색/답변 C/D 결과를 확인하고 기록한다. BE가 구현한다는 이유로 #130의 통합 검증 완료 조건을 제외하지 않는다.
- 문서 업로드·발행/버전 변경은 #100과 연결한다. AI 전체 90초와 현재 BE 기본 read timeout 20초 차이를 고려해 호출 예산 또는 작업 접수 방식을 합의한다.

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

## 5. 남은 완료 조건

- 실제 BE/DB: 전체 배치 발행, 중복 제거, 이전 실행 차단, rollback 뒤 READY 유지, 프로젝트/권한/승인본 격리.
- 평가 C: 승인·검색·모델 응답·최종 검증 barrier로 버전 경합 제어, 최종 검사 뒤 변경은 검사 시점 snapshot으로 기록.
- 평가 D: Markdown 4종의 질문/정답 근거, 고정/구조 청킹 × 문맥 보강 on/off를 동일 모델·예산으로 비교. Recall@k·MRR·답변/인용·보류·색인/질의 비용·재시도·지연 기록.
- 실제 실행 조건·수치·실패·채택 설정을 평가 문서에 남기고 팀이 확인. 품질·지연 합격선은 측정 후 합의.

현재 AI 독립 완료 범위는 계약안·합성 fixture·C 모의 검증기·D 개발용 실제 GMS 비교 측정이다. 실제 DB/운영 경로 시험·팀의 라벨/의미 검토가 남아 있으며 Jira #130은 진행 중으로 관리한다.
