# 원문 청킹·임베딩 준비 (#129)

`prepare_document_index`는 #127의 `SourceDocument`를 구조별로 나누고, 주입한 임베딩 공급자로 벡터를 생성·검증하여 `PreparedIndex`를 반환한다. #214는 청킹 HTTP API 및 AI-A04 임베딩 프로필·용도 확장을 제공한다. 벡터 DB 저장·검색은 BE 통합이 필요하다. `PreparedIndex`는 내부 타입이며 BE 통합 완료를 뜻하지 않는다.

## 입력과 처리 흐름

```text
SourceDocument
  → build_chunks(document, chunking_profile, token_counter)
  → embed_texts(texts, embedding_profile, request_key, provider, token_counter)
  → PreparedIndex(manifest, chunks, vectors, excluded_ranges, usage)
  → BE의 실행·버전 검증 및 원자적 색인 발행
```

`EmbeddingProvider`는 한 번의 실제 API 호출을 구현한다. 운영 `EmbeddingTokenCounter`는 `tiktoken==0.14.0`의 `cl100k_base`를 사용한다. `<|endoftext|>` 같은 문자열도 일반 원문으로 세며 잘못된 Unicode는 거부한다. 테스트용 byte/문자 카운터는 운영 토크나이저가 아니다. 파싱·청킹 단계에서는 생성형 LLM을 호출하지 않는다.

## 선택한 모델과 GMS 연결

2026-10-26 기준 사용자 선택은 **비비 기본 `gpt-6-luna`, 복잡한 분석 보조 `gpt-6.1-sol`**이다. 설정에 기록했으며 답변 생성·자동 모델 전환은 #129에 구현하지 않는다.

임베딩은 **`text-embedding-3-small`, 1024차원, cosine, identity-v1**을 초기 AI 프로필로 선택했다. GMS의 공개 모델 목록과 가이드에서 활성 상태·호출 규격을 확인하고 실제 1024차원 반환도 검증했다. 1024차원은 현재 저장소에 맞춘 초기값이며 BE와 프로필을 고정한 후 색인을 저장해야 한다. 모델·차원·토크나이저·문맥 정책을 바꾸면 새 색인으로 취급한다. 벡터 차원 축소 자체가 입력 토큰 비용을 줄이지는 않는다.

- 호출: `POST https://gms.ssafy.io/gmsapi/api.openai.com/v1/embeddings`
- 인증: `Authorization: Bearer {GMS_KEY}`. 호출 주소는 코드에서 고정하며 리다이렉트·환경 프록시를 사용하지 않는다.
- 요청: 모델, 문자열 배열 `input`, `dimensions`, `encoding_format=float`.
- 응답의 `data[].index`를 입력 key로 매핑한다. 모델명·개수·중복 index·차원·숫자·사용량을 검증하고 원본 응답/키를 오류 메시지에 넣지 않는다. 응답 크기는 8 MiB로 제한한다.
- GMS 가이드의 small 입력 단가는 0.0002크레딧/토큰이다. 화면의 `0.001 Credit`은 예상 평균 호출 사용량이다. 실제 사용량은 공급자의 `usage`를 기록하며 API 응답에 임의 USD 비용을 붙이지 않는다. 운영 단가는 바뀔 수 있다.

출처: [GMS small 가이드](https://gms.ssafy.io/web/public/guide/text-embedding-3-small), [공개 모델·단가 목록](https://gms.ssafy.io/api/v1/common-codes/models), [OpenAI 임베딩 규격](https://developers.openai.com/api/reference/resources/embeddings/methods/create).

## 로컬 실행과 내부 API

`ai/.env.example`을 참고해 Git에서 제외되는 `ai/.env`에 `GMS_KEY`를 설정한다. HTTP 호출에는 BE와 공유한 **별도 `AI_INTERNAL_TOKEN`**도 필요하다. 키를 채팅·소스·로그에 넣지 않는다. 설정을 바꾸면 서버를 다시 시작한다. `GMS_EMBEDDING_DIMENSION`은 1~1536 범위에서 설정할 수 있지만 한 배포에서는 고정한다. 요청의 model/dimension이 배포 프로필과 다르면 422로 거부한다.

`ai/`에서 다음을 실행한다. 공개 어휘 파일은 **준비 명령에서만** 내려받으며 모델 비용은 들지 않는다. 서버는 네트워크로 어휘 파일을 받거나 손상된 캐시를 자동 복구하지 않는다. 기동 시 로컬 어휘의 SHA-256을 검증하고 토크나이저를 미리 구성한다. 파일이 없거나 손상됐으면 요청을 받기 전에 기동이 실패한다.

```text
python -m pip install -r requirements.txt
python -m scripts.prepare_tokenizer
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

배포 이미지 빌드 단계에서 `TIKTOKEN_CACHE_DIR`을 지속 경로로 지정하고 준비 명령을 실행한다. 예를 들어 `/opt/beez/tokenizer-cache`를 쓴다면 최종 이미지에도 그 디렉터리와 같은 환경변수를 포함하고 런타임 사용자에게 읽기 권한을 준다. 런타임은 이 캐시에 쓰지 않아도 된다. 다단계 빌드는 캐시 디렉터리도 최종 이미지로 복사해야 한다. CI도 같은 준비 명령을 실행하지만 CI의 임시 캐시가 운영 이미지로 전달되는 것은 아니다.

`TIKTOKEN_CACHE_DIR`은 `.env`의 모델 설정이 아니라 준비 명령과 서버 프로세스 양쪽에 전달하는 환경변수다. 지정하지 않으면 tiktoken의 기본 임시 캐시 경로를 사용한다. `TIKTOKEN_CACHE_DIR=""`로 캐시를 끄는 설정은 런타임에서 거부한다. 배포·로컬 실행 모두 준비 명령과 서버의 경로·사용자가 달라지지 않게 한다.

`POST /v1/embeddings`는 기존 [AI-A04 계약](../../../../docs/api/03-ai-internal.md#ai-a04)의 project_id/request_key/texts와 선택 model/dimension을 받는다. 추가로 embedding_profile_id와 DOCUMENT/QUERY purpose를 함께 전달할 수 있으며 서버 실제 프로필과 대조한다. 둘 다 생략한 기존 호출의 응답 형식과 document 기본 동작은 유지한다. `result`, `origin=MODEL`, `model`, 실제 호출별 `usage`를 반환한다.

`POST /v1/document-chunks`는 모델 호출 없이 문서별 청크와 정확한 source_spans, 제외 범위, manifest를 반환한다. 상세 요청·오류와 BE 변경 사항은 [청킹 HTTP 계약](../../../../docs/api/03-ai-internal.md#document-chunks)을 따른다.

내부 인증 누락/불일치는 401, 필드 형식 오류는 400, 중복 key·빈 텍스트·토큰 한도·프로필 위반은 422, 공급자 오류/출력 검증 실패는 502, 타임아웃은 504다. 실패 시 `result`를 반환하지 않고 실제 호출이 있었으면 선택적 usage만 보존한다. 외부 API에서는 모르는 토큰 필드를 생략한다. `X-Request-Id`를 전달하거나 생성한다. 프로젝트 멤버십·원문 버전 권한은 BE가 검증한다. AI 내부 토큰은 사용자/에이전트 토큰을 대신 받는 경로가 아니다. 배포 프록시에서도 본문 크기·동시 요청 수를 제한해야 한다.

`embed_texts`가 취소되면 표준 `asyncio.CancelledError`를 그대로 전파하고 `embedding_usage` 속성에 완료된 호출 기록을 보존한다. 공급자 호출 중 취소된 호출은 토큰 미확인 실패로 남기며, 호출 전 검증이나 재시도 대기 중 취소는 새 사용량을 만들지 않는다. 외부 `asyncio.timeout()` 또는 `wait_for()`가 변환한 `TimeoutError`에서는 `__cause__.embedding_usage`를 확인할 수 있다. 프로세스 종료나 네트워크 단절로 응답이 유실된 경우 BE까지 사용량 전달을 보장하지는 않는다.

## 청킹과 근거 계약

- 먼저 원문 해시, 블록 위치·해시, 제목·표 헤더 참조와 전체 행 범위를 검증한다. 원문 행은 블록 또는 명시적 제외 범위에 속해야 한다.
- 제목 경로·표·목록 전체/인용 컨테이너 경계를 유지한다. 같은 목록의 형제 항목은 토큰 예산 안에서 묶고, 항목별 원문 위치는 `source_spans`에 유지한다. 중첩 목록은 부모 항목 경계를 유지하며, 서로 다른 목록·인용·엔드포인트의 내용은 합치지 않는다. 목록 전체 메타데이터가 없는 이전 파서 결과는 기존 항목별 경계를 유지한다.
- 한 블록이 토큰 한도를 넘으면 줄 경계를 우선하여 나누고, 필요하면 Unicode 코드 포인트 경계로 나눈다. 긴 표 행도 하위 범위로 나눌 수 있으며 헤더 참조를 유지한다. 각 조각을 실제 토크나이저로 다시 검사한다.
- `source_spans`의 offset은 `normalized_text` 전체에 대한 **0-based Unicode 코드 포인트, 끝 제외** 범위다. UTF-8 byte나 JavaScript UTF-16 offset이 아니다. 행 locator는 #127과 같은 **1-based, 마지막 행 포함**이다. `resolve_span`으로 정확한 원문 조각을 복원한다.
- `text`는 원문 조각의 결합이다. `embedding_text`에는 선택적으로 제목·표 헤더 문맥을 붙일 수 있다. 추가 문맥은 `context_refs`로 기록하며 새로운 근거 범위로 중복 계산하지 않는다.
- 문맥 보강 플래그는 기본적으로 꺼져 있다. 제목과 표 헤더를 각각 켤 수 있고, 추가 문맥도 토큰 예산에 포함한다. 문맥만으로 예산이 소진되면 조용히 생략하지 않고 실패한다.
- 병합 전 모든 블록의 **하위 문자 범위까지** 누락·중복 없이 한 번씩 포함됐는지 확인한다. 자동 overlap은 없다. 이는 원문 보존 검사이며 검색 재현율을 보장하는 평가는 아니다.
- `chunk_no`는 0부터 시작한다. 청크 키와 manifest는 프로젝트·문서 버전·원문/텍스트 해시·파서/청커 버전·프로필·위치를 반영한다. 같은 입력과 설정이면 같은 키를 만든다.
- 목록 메타데이터와 묶음 정책 변경으로 파서·청커 버전은 `1.1.0`이다. 이전 버전의 청크 키를 재사용하지 않으며 기존 색인의 전환·재색인은 BE 연결 시 별도로 다룬다. 임베딩 모델·차원·전처리 프로필은 유지한다.

초기 청킹 한도는 청크당 600토큰, 문서당 1,024청크, 직렬화 청킹 결과 8 MiB다. 모델 한도를 초과하는 프로필이나 과도한 토큰 계산도 거부한다. 수치는 품질·비용 평가로 조정할 내부 기본값이다. 한도 초과 시 일부 청크만 성공으로 반환하지 않는다.

## 임베딩과 사용량 계약

- 모델·공급자·차원·토크나이저·전처리 버전·거리 함수·저장 정밀도를 `EmbeddingProfile`로 고정한다. 문서와 검색어는 같은 임베딩 공간을 사용해야 한다. 현재 전처리는 입력을 그대로 전달하는 `identity-v1`만 지원한다.
- 외부 호출 전에 전체 입력의 중복 키·빈 내용·토큰 수·배치 수 한도를 확인한다. 이 검증은 요청마다 한 번의 워커 스레드 작업으로 실행해 이벤트 루프를 점유하지 않는다. 요청당 2,000,000자 한도는 유지한다. 기본 배치는 최대 64개 입력/60,000토큰이며 실제 공급자 제한에 맞춰 설정해야 한다.
- 반환 배열 순서에 의존하지 않는다. 누락·중복·알 수 없는 키, 다른 프로필, 잘못된 차원, NaN/Inf, 영벡터를 거부한다. float32 저장 시 overflow나 전체 0이 되는 벡터도 거부한다.
- 순차 배치 처리 중 하나라도 실패하면 부분 벡터와 발행 가능한 manifest를 반환하지 않는다. `EmbeddingError.usage`에는 앞선 실제 호출 기록을 남긴다.
- 기본 재시도는 일시적 공급자 오류에 최대 2회 시도다. 호출별 20초/검증을 포함한 전체 90초 예산을 적용한다. 검증 중 타임아웃·취소가 발생하면 공급자를 호출하지 않는다. 이미 실행 중인 순수 검증 스레드는 계산을 마칠 수 있지만 결과를 버린다. 프록시의 요청 크기·동시성 제한은 계속 필요하다. 타임아웃은 기본 재시도하지 않는다. 어댑터 내부 SDK 재시도는 끄고, 성공·실패 모두 실제 호출별 사용량을 돌려줘야 한다.
- 타임아웃 등으로 사용량을 알 수 없으면 `tokens_known=false`, 토큰 수는 `null`이다. 0으로 간주하지 않는다. 실제 호출 키는 실행마다 달라 동일 요청 재전달 시 별도 과금된 호출을 잘못 합치지 않는다.
- 취소·프로세스 종료 후 공급자의 처리 완료 여부는 이 모듈만으로 알 수 없다. 영속 실행 기록과 미확정 사용량 정리는 BE 연결 시 필요하다. 정확히 한 번 호출·과금된다고 보장하지 않는다.

`index_key`는 청크 manifest와 전체 임베딩 프로필을 함께 반영한다. 차원이 같아도 모델이 바뀌면 다른 색인이다. `PreparedIndex`는 **저장 준비 결과**이며 READY나 승인 상태가 아니다. BE는 프로젝트 권한·문서 버전·실행 ID를 확인하고 전체 청크/벡터 저장 성공 후 색인을 발행해야 한다. 승인 baseline, 재실행 충돌, 검색 시 사용할 색인 세대의 선택은 별도 BE/검색 계약이다.

## 남은 통합 작업

#129의 DTO·역할·검색 결과 계약안은 [청킹·검색 연동 계약안](../../../../docs/design/document-chunking-search-contract-S15P31A108-129.md)에 있다. AI 구현과 실제 BE/DB 연동 완료를 구분한다. run/attempt·READY 교체·롤백과 실제 DB 검증은 BE 구현 범위다.

1. BE와 내부 인증 설정 및 임베딩 프로필을 고정하고 실제 AI-A04 호출을 연결한다.
2. BE의 전체 행 검증을 source_spans/excluded_ranges 검증으로 변경하고 원문 offset 저장·검색과 원자적 색인 발행을 통합한다.
3. 실제 문서의 검색 품질·비용을 측정한 뒤 청크 크기와 문맥 보강 정책을 정한다. 변경되지 않은 임베딩 입력의 재사용은 프로젝트 범위·입력 해시·전체 모델 프로필을 함께 검증하도록 별도로 연결한다.

## 검증

#130의 재색인·READY·rollback 합성 fixture는 [별도 계약안](../../../../docs/design/document-index-refresh-contract-S15P31A108-130.md)과 `scripts.generate_retrieval_handoff`에서 제공한다. #129의 실행 모듈에 의존하며 실제 BE/DB 통합 C/D 시험은 아직 남아 있다.

#130의 AI 독립 평가 실행기는 [evaluations 안내](../../../evaluations/README.md)에 있다. 고정/구조 청킹과 문맥 보강 비교, 모의 승인 경합, 제한된 실제 GMS 측정 및 결과 요약을 지원한다. [평가 기록](../../../../docs/experiments/document-rag-evaluation-S15P31A108-130.md)에 수치·실패·인계를 남겼다. #130은 AI 산출물·인계 및 dev 병합 후 완료한다. BE 저장/검색·실행 상태는 오서현 담당의 기존 #192 브랜치에 구현돼 있다. 실제 API 호환·DB 통합 검증 완료를 주장하지 않는다.

`ai/`에서 실행한다.

```text
python -m pytest -q
python -m ruff check .
python -m ruff format --check .
```

`test_chunking.py`, `test_embeddings.py`, `test_document_pipeline_contract.py`는 원문 범위 보존, 긴 표/문맥 예산, 엔드포인트 경계, Unicode 위치, 배치 누락·중복·차원 오류, 재시도·사용량, 마지막 배치 실패 시 전체 미반영을 검증한다. `test_gms_embeddings.py`와 `test_embeddings_api.py`는 실제 토크나이저·모의 GMS HTTP 응답·내부 인증·기존 API 계약을 검증한다. pytest에서는 유료 모델을 호출하지 않는다.

2026-10-27 리뷰 반영 후 Windows / Python 3.12.12에서 기본 `python -m pytest -q`와 세션 루프 옵션 실행 모두 150개가 통과했다. 이전 환경에서 표준 `socket.socketpair()` 생성 자체의 간헐적 WinError 10014를 재현한 이력이 있어, 같은 환경 문제가 발생하면 `-o asyncio_default_test_loop_scope=session`으로 확인할 수 있다. Linux CI는 기본 pytest 설정을 유지한다.

실제 연결 확인은 별도 명령 `python -m scripts.smoke_gms_embeddings`로 실행한다. 합성 문장 2개, 최대 입력 1,000토큰, API 호출 1회, 재시도 없음으로 제한했다. 2026-10-26 실행 결과: `text-embedding-3-small`, 1024차원 벡터 2개, 입력 38토큰/출력 0토큰, 공개 입력 단가 기준 예상 0.0076크레딧. 계정 잔액 차감 대조나 한국어 검색 품질 평가를 수행한 결과는 아니다.
