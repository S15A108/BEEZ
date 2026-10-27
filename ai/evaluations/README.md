# #130 AI 평가 실행

운영 앱에서 import하지 않는 개발용 C/D 실험이다. 실제 파서·청커·GMS 임베딩을 재사용하며, BE/DB와 운영 비비 API는 연결하지 않는다. 현재 데이터는 agent가 작성한 가상 프로젝트의 Markdown 4종·질문 12개이며 독립 사람 검토와 운영 데이터 평가는 남아 있다.

## 실행

`ai/`에서 어휘 파일을 준비한 뒤 실행한다. 키는 Git에서 제외된 `ai/.env`에만 둔다. 기본 실행은 네트워크·모델 호출이 없다. 같은 출력 파일을 자동으로 덮어쓰지 않는다.

```text
python -m scripts.prepare_tokenizer
python -m evaluations.document_rag --output <새 경로>/dry.json
python -m evaluations.document_rag --live --output <새 경로>/live.json
python -m evaluations.summarize <경로>/live.json --output <경로>/summary.md
```

`--live`는 유료 GMS 호출을 수행한다. 고정/구조 경계 × 제목·표 헤더 보강 off/on, 청크 600토큰·overlap 0·top-12·문맥 6개/4,000토큰·출력 최대 512토큰, 각 질문 3회다. `gpt-6-luna`와 `text-embedding-3-small`/1024차원을 사용하며 모델을 자동 교체하지 않는다. 공급자 기본 샘플링을 기록하고 설정 간 동일하게 적용한다.

실제 호출 최대 200회, 공개 단가 추산 최대 500크레딧으로 제한한다. 실행 전 응답의 입력 UTF-8 byte 수+여유량과 최대 출력 비용을 예약한다. 실제 usage가 알려지면 추산을 정산하고, 미확정 사용량은 예약액을 유지한다. 계정 잔액 차감과 같은 값이라고 보장하지 않는다. 재시도는 없고 HTTP 장애는 중단·기록한다. JSON/출력 오류는 실패 행으로 남긴다. 단위 테스트는 모의 HTTP만 사용한다.

공유 임베딩 입력과 질의 벡터는 설정·반복 사이에 재사용한다. 결과에는 실제 공유 호출 비용과 설정별 논리적 색인 토큰을 따로 기록한다. 생성 지연에는 실제 공급자 응답 시간이 포함되지만 로컬 검색 지연은 DB/네트워크 지연이 아니다.

## 중단 후 재개

원시 응답·usage를 호출마다 체크포인트에 저장한다. Windows의 일시적 파일 교체 거부는 짧은 로컬 재시도로 처리한다. 같은 출력에 동시에 두 실행기를 실행하지 않는다. 프로세스가 종료됐음을 확인한 뒤 명시적으로 재개한다.

```text
python -m evaluations.document_rag --live --resume --output <기존 경로>/live.json
```

기존 생성 행은 성공/실패 여부에 관계없이 재호출하지 않는다. 원시 성공 응답이 저장된 행은 네트워크 없이 채점을 복구한다. 공유 임베딩 풀은 다시 생성하므로 추가 호출·비용도 기존 사용량에 합산한다. 원문·질문·청킹·프롬프트·한도·공개 단가가 바뀌면 재개를 거부한다. 이미 완료된 측정은 재개할 수 없다.

## 점수와 원문

정답은 정규화 원문에 고유하게 존재하는 quote와 Unicode offset으로 고정한다. Recall은 top-k 범위 합집합이 정답 범위를 완전히 포함한 비율이며, 중복 청크를 중복 정답으로 세지 않는다. MRR은 정답 원문과 겹치는 첫 결과의 역순위라 부분 근거도 관련 결과가 될 수 있다. 정답이 없는 질문은 검색 지표 분모에서 분리한다.

고정 기준선도 Unicode 경계·원문 offset을 유지하며, 원문 제목·표를 제거하지 않는다. 보강 on은 파서가 해석한 제목/표 헤더를 embedding_text에 추가하고 600토큰에 포함한다. 모든 생성 비교에는 동일하게 선택된 원문 text를 제공해 검색 보강과 원문 사실을 구분한다. 고정 기준선은 운영의 엔드포인트/표 경계 계약을 대체하는 구현이 아니다.

원문 인용 검사는 제공된 ID와 정확한 원문 조각을 검사한다. 정답 용어 일치는 의미 정확도의 대리지표이며 조건 누락·추가 주장·반대 의미를 완전히 검출하지 못한다. 원시 답변·실패·인용을 확인하고 사람이 의미를 따로 검토해야 한다. 문서의 악성 지시를 따랐는지도 별도 marker 검사로 기록하되 보편적 prompt-injection 저항성을 주장하지 않는다.

## C와 BE 인계

Windows의 기존 Proactor `socketpair()` 초기화가 간헐적 WinError 10014를 내는 환경에서는 다음처럼 **테스트 프로세스에만** Selector 정책을 적용해 전체 기능을 구분 검증할 수 있다. 저장소·운영 정책과 Linux CI 기본 설정은 바꾸지 않는다.

```text
python -c "import asyncio,pytest; asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy()); raise SystemExit(pytest.main(['-q','-o','asyncio_default_test_loop_scope=session']))"
```

`consistency.py`의 Event barrier가 검색 후/생성 후 최종 검사 전/최종 검사 후 승인 변경 순서를 제어한다. 검증기는 trace 안의 자체 기대값을 믿지 않고 별도 `SNAPSHOTS`에서 승인 버전과 정책값을 얻는다. BE trace를 검사할 때는 권위 있는 시험 fixture에서 별도로 만든 `snapshots`를 `validate_trace(trace, snapshots)`에 전달한다. trace가 주장한 허용 버전/정답을 그대로 oracle로 넣으면 안 된다.

이 실행의 모의 C 6개와 기존 handoff 26개는 실제 PostgreSQL의 트랜잭션·rollback·권한 격리·늦은 attempt 차단 검증을 대신하지 않는다. [BE 후속 #213](https://ssafy.atlassian.net/browse/S15P31A108-213) 담당이 저장/검색을 구현하고 동일 fixture의 SQL 결과·승인 경합 trace·검색→모델 입력→최종 답변/인용을 검증한다. 필요한 AI 인터페이스 변경은 별도 변경 티켓에서 추적한다. #130은 실행 가능한 도구·결과·인계 자료와 dev 병합을 기준으로 완료하며 BE 통합 대기를 종료 조건으로 두지 않는다.

실측 결과와 BE 후속 인계 범위는 [#130 평가 기록](../../docs/experiments/document-rag-evaluation-S15P31A108-130.md)에 있다.
