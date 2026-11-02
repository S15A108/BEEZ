# 설계 관계와 변경 영향 분석 구현 메모 (#188)

이 문서는 현재 브랜치의 구현과 아직 연결되지 않은 부분을 구분한다. #195 변경 제안, #167 관계 관리, #197 결과 저장·조회와 팀 승인 절차의 계약을 변경하거나 담당자 합의를 대신하지 않는다.

## 현재 구현한 흐름

1. `ImpactContextQuery`는 프로젝트 멤버의 권한을 확인하고 `PROPOSED` 또는 `IN_REVIEW` 상태의 변경 제안, 현재 제출된 불변 리비전의 스냅샷·번호·해시, 기준 설계와 관련된 열린 작업의 현재 계획을 읽는다. 현재 기준선과 제출 기준이 다르거나 스냅샷이 유효하지 않으면 분석하지 않는다. 문서 전용 제안과 설계·문서 혼합 제안은 스냅샷에 문서 변경이 있음을 보존하되 현재 AI-A03 경로에서는 미평가로 남기며, 영향 0건을 뜻하지 않는다.
2. `ImpactRelationReader`는 #167 `DesignDependencyQueryService`를 이용해 변경 항목을 가리키는 역방향 1단계의 `CONFIRMED` 관계만 읽는다. `CANDIDATE`·`NEEDS_RECHECK`는 확정 관계로 취급하지 않는다. 현재 baseline의 양 끝 설계 버전과 `relation_set_revision`을 스냅샷에 기록하고, 확인 이벤트 ID를 임의로 만들지 않는다.
3. 변경 내용 키워드로 같은 프로젝트의 승인 설계와 열린 작업을 각각 최대 20건 추가 탐색한다. 명시 관계가 없는 항목도 AI 입력 후보로 넣되 관계를 자동 확정하지 않는다. 검색 상태와 잘림을 기록하며 벡터 검색이나 전체 후보의 완전성을 뜻하지 않는다.
4. #197의 `ExplicitImpactCalculator`가 변경 항목 직접 참조와 변경 항목을 가리키는 확정 관계의 from 항목 참조 작업에서 규칙 후보를 계산한다. 키워드 전용 작업과 완료·취소 작업은 제외하고 미배정 담당자는 생략한다. `ImpactContextQuery`는 `CONNECTED`를 반환한다. 과거 #188의 별도 관계 저장 서비스와 전용 V8 초안은 복원하지 않는다.
5. `ImpactAnalysisCoordinator`는 가능한 경우 AI-A03에 변경 정보·승인 설계·확정 관계·열린 작업을 전달하고 AI가 반환한 ID와 출처를 입력과 대조한다. `CONNECTED`이고 AI 근거 상태가 `SUFFICIENT`이면 `ANALYZED`, 그 외는 `INSUFFICIENT_EVIDENCE`다. #197은 이를 각각 `COMPLETED`·`INSUFFICIENT_EVIDENCE`로 저장하고 호출 전 미평가는 `NOT_EVALUATED`로 구분한다. `rule_candidates_not_connected` 오류는 더 이상 생성하지 않는다.
6. #195의 불변 제출 리비전은 변경 항목의 `schema_version`과 변경 내용을 보존한다. #188은 실제 스냅샷의 값이 확인된 `CREATE`·`UPDATE`를 AI 요청에 전달한다. 과거 제안 등에서 버전이나 내용이 없으면 각각 `change_schema_version_unavailable` 또는 `change_content_unavailable`로 모델 호출 없이 미평가 처리한다. 문서 전용·혼합 변경은 `document_change_unavailable`로 미평가 처리한다. 승인 설계 후보와 관련 작업이 모두 없어 영향 대상을 특정할 수 없는 경우도 모델을 호출하지 않고 `analysis_candidates_unavailable`로 미평가 처리한다.
7. AI 호출 후 `requireUnchanged`는 제안·기준 설계·작업·관계 스냅샷을 다시 읽어 영향 입력의 변경(`ImpactFingerprint` 기준: lock_version·갱신 시각·검증 상태 같은 작업 변동은 제외)을 확인한다. 이 검사는 결과 저장과 같은 트랜잭션이 아니며, #197의 원자적 저장·최신성 보장을 의미하지 않는다. 호출별 사용량은 수신된 범위에서만 반환하고 통신이 끊겨 수신하지 못하면 `UNKNOWN`이다.
8. AI의 `POST /v1/impact`는 공통 LLM 클라이언트를 사용한다. `evidence_status`, `validation_id` 등 AI-A03 확장 필드와 결과 저장 형식은 제안이며 #197 및 관련 담당자와 합의 전이다.

## 연결해야 할 작업

- **#195 변경 제안:** 현재 입력은 불변 제출 리비전·`snapshot_hash`와 실제 `schema_version`을 사용한다. 제안 리비전과 스냅샷 해시는 AI 요청에 붙이는 내부 추적 정보이며 공개·저장 계약은 아니다. 변경 항목의 0부터 시작하는 순번도 해당 리비전 안에서만 의미가 있다. 재제출 시 projection의 행 ID가 재생성되므로 결과 저장에 사용할 식별 기준은 #197과 정한다. 문서 전용·혼합 변경의 입력·분석 방식도 별도로 협의한다.
- **관계 및 규칙 후보:** #167을 관계의 유일한 저장·조회 원천으로 사용한다. #197이 동일 snapshot에서 규칙 후보를 별도로 계산·저장하며 AI 실패가 규칙 후보를 지우지 않는다.
- **#197 결과 저장:** `ImpactRunService`가 접수 후 유한 인메모리 실행기에서 coordinator를 호출하고 프로젝트 잠금·입력 fingerprint 재검사·미만료 RUNNING 조건부 저장을 수행한다. V18에 규칙/AI 후보·generation·사용량을 분리 저장한다. 호출 중 기준·제안·작업·관계 변경은 STALE로 닫고 현재 결과로 취급하지 않는다. 재시작 시 유실된 작업은 lease 만료 후 조회/재요청으로 정리한다.
- **검색 및 원문:** 현재 제한된 키워드 검색만 제공한다. 동의어·표현 차이, 결과 상한 등으로 관련 항목을 누락할 수 있고, 승인 원문 벡터 검색이나 문서와 설계·작업의 의미 연결도 아직 없다. 검색 실패·인덱스 미준비를 영향 없음으로 간주하지 않는다.
- **호출 및 CDC:** #197이 CHG-A05 조회·A06 수동 재분석과 발행별 접수행·claim·복구 조회 포트를 제공한다. 사용자 화면·승인 게이트·발행 후 AI 실행 및 반복 복구 스캔은 후속 작업이다. #227 연결 전 변경 발행 접수행은 PENDING으로 남으며 CDC transport를 이 변경에서 활성화하지 않는다.

## 검증과 한계

- 출처 위치는 AI의 Pydantic 모델과 같은 공백 기준으로 **요청 조립 시에만** 앞뒤를 정규화하며 저장 원본은 바꾸지 않는다. 응답의 문서 ID·버전·위치는 기존처럼 요청과 정확하게 대조한다. 일반 공백·NBSP·NEL과 정규화 대상이 아닌 제어 문자를 실제 FastAPI HTTP 왕복으로 검증했고, 다른 위치는 거부하는 단위 테스트도 확인했다. 이 테스트는 DB 조회와 모델을 대역으로 사용한다.
- 위 HTTP 왕복 테스트는 AI 가상환경을 준비한 뒤 저장소 루트에서 `ai/.venv/Scripts/python.exe backend/src/test/resources/run_impact_source_http_test.py`(Windows) 또는 `ai/.venv/bin/python backend/src/test/resources/run_impact_source_http_test.py`로 실행한다. 임시 로컬 서버·무작위 토큰·모의 LLM을 사용하고 Docker나 외부 LLM은 필요 없다.
- 격리 PostgreSQL(Testcontainers)과 별도 FastAPI 프로세스 간 실제 HTTP 시험에서 영향 분석 성공·모델 타임아웃 응답 및 사용량을 확인했다. 모델과 타임아웃은 테스트 제공자이며 청킹·임베딩 회귀에는 테스트 토큰 계산기·임베딩 제공자를 사용한다. 데이터는 가상이며 운영 설정은 변경하지 않는다. 테스트는 opt-in으로, 실행 방법은 [AI 모듈 README](../../ai/app/services/change_impact/README.md#be와-ai의-http-연동-시험)를 참고한다.
- 최종 테스트 설정의 별도 GMS `gpt-6-luna` 실제 호출 1회에서 AI 후보 1건, AI 근거 상태 `INSUFFICIENT`, 사용량 입력 878·출력 724 토큰을 확인했다. 규칙 후보 미연결로 BE 최종 상태는 `INSUFFICIENT_EVIDENCE`였다. 단일 가상 사례로 품질·반복 안정성을 보장하지 않는다. 이전 서버 기동 실패는 원인을 확인하지 못했고 재현되지 않았다.
- 공통 `AiClient`는 Uvicorn httptools의 h2c 업그레이드·chunked 요청에서 발생한 400을 피하도록 HTTP/1.1을 사용한다. 공개 BE API가 없어 이 HTTP 시험은 Spring 서비스를 직접 호출해 시작하므로 사용자용 BE HTTP API를 검증하지 않으며, #197 결과·사용량 DB 저장이나 #227 CDC 연결도 검증하지 않는다.

- 최신 `dev` 병합 후 AI 영향 분석 내부 API·서비스·평가 도구의 모의 공급자 테스트 83개와 Ruff 검사가 통과했다. 모델 호출 없이 수행한 회귀 테스트다.
- 관계 중복을 정리한 이전 단계에서는 백엔드 영향 분석 단위 테스트 13개와 PostgreSQL 기반 #188 입력 조회·#167 관계 서비스 통합 테스트 28개가 통과했다. #195 리비전 입력을 연결한 후의 회귀 테스트 결과는 PR에서 별도로 확인한다. 어느 테스트도 #197의 실제 결과 저장·원자성이나 전체 사용자 흐름을 증명하지 않는다.
- 실제 모델의 가상 사례는 [초기 평가](../../ai/evaluations/change_impact/results-2026-10-28.md)에서 필드 변경 출력 오류와 문구 변경 과잉 후보가 나왔다. [보완 후 평가](../../ai/evaluations/change_impact/results-2026-10-28-postfix.md)의 단일 실행에서는 네 사례의 형식과 기대 근거 상태가 맞았으나 독립·반복 평가가 필요하다.
