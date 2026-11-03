# 발행 후 변경 영향 분석 연동 (#227)

기준: `dev` ae07c61의 #197 발행 접수와 #226 수신기. #225 원본 문서 캡처 PoC는 이 경로의 입력이 아니다. 최초 관계 구축(#167)과 문서 처리·색인(#223)은 각자의 기존 경로를 유지하고 이 소비자가 재실행하지 않는다.

## 처리 경로

1. 발행 트랜잭션이 `published_change_analysis`에 `(project_id, published_baseline_id)`별 접수행을 하나 만든다. 최초 v1과 도입 이전 백필은 자동 분석하지 않는다.
2. 활성화된 소비자는 Debezium JSON envelope의 `public.published_change_analysis` 이벤트를 해석한다. `PENDING`의 c/r/u는 **현재 DB 상태를 확인하라는 힌트**다. 종결 상태와 Kafka tombstone은 새 작업이 아니며, DELETE·잘못된 레코드는 건너뛰지 않는다. 기존 #197 행이 없거나 DB 조회가 실패하면 Kafka 수신에 실패한다.
3. 제한된 비동기 실행기는 DB 프로젝트 잠금 아래 앞선 발행을 확인하고 접수를 원자적으로 선점한다. 중복 수신, 순서 역전과 여러 인스턴스의 경쟁은 DB 상태로 처리한다. 버전 또는 접수 누락, `FAILED`, 사유를 확인할 수 없는 `NOT_EVALUATED`가 앞에 있으면 대기한다. v1과 명시적인 `published_before_intake`만 건너뛴다.
4. 발행한 사람의 현재 프로젝트 멤버 권한을 다시 확인한다. 입력은 불변 제출 리비전, **발행 전 baseline의 이전 버전**, 현재 열린 작업과 현재 관계 조회다. 발행 전 버전은 이후 `SUPERSEDED`여도 이전 baseline에서 승인됐던 이력으로만 전달하며 현재 승인 버전이라고 표현하지 않는다. AI 요청에 분석 시점과 이전 baseline임을 명시한다.
5. 분석 외부 호출 중 DB 트랜잭션을 유지하지 않는다. 완료 전 프로젝트 잠금 아래 현재 발행 baseline·입력 fingerprint와 권한을 재확인하고, 유효한 attempt/lease가 일치할 때만 결과와 attempt 이력을 같은 트랜잭션에 기록한다. 승인 전 `change_impact_run`은 변경하지 않는다.
6. Kafka 힌트가 유실되거나 프로세스가 종료돼도 주기적으로 오래된 PENDING, 재시각이 도래한 PENDING, 만료 RUNNING을 키셋으로 다시 찾는다. 외부 모델 호출은 중복될 수 있으며 정확히 한 번 호출을 보장하지 않는다.

## 결과 해석 및 제한

- 접수 `status=COMPLETED`는 처리가 끝났다는 뜻이다. **분석의 증거 수준은 반드시 `evaluation_status`를 함께 확인**한다. `INSUFFICIENT_EVIDENCE`는 후보가 비어도 영향 없음이 아니다. `explicit_result`, `ai_result`, `generation`, `usage`, `usage_status`, `input_fingerprint`는 발행 후 접수에 저장한다.
- 발행 시 변경 항목의 확정 관계가 `NEEDS_RECHECK`로 전환된다. 현재 관계 조회만으로 발행 전 관계 전체를 복원할 수 없으므로 발행 후 분석의 규칙·관계 범위는 `NOT_CONNECTED`이며 결과를 완전한 `COMPLETED` 평가로 주장하지 않는다. 모델이 충분하다고 답해도 전체 평가에는 `INSUFFICIENT_EVIDENCE`가 기록된다. 자동 관계 확정이나 작업 상태 변경은 없다.
- 문서 변경 전후 AI 입력 계약이나 사용 가능한 분석 대상이 없는 경우 모델을 호출하지 않고 `NOT_EVALUATED`와 사유를 기록한다. 이런 미평가는 v1/백필이 아니므로 다음 발행을 자동 통과시키지 않는다.
- 후속 발행이 먼저 이루어져 처리 대상 baseline이 현재 baseline이 아니면 과거와 현재의 관계·작업을 섞어 분석하지 않고 `baseline_changed` 실패로 보존한다. 입력 중 변경은 제한된 재시도 후 실패할 수 있다. 실패·미평가에 대한 사용자 재처리 API/운영 절차는 아직 없으며 담당자 합의가 필요하다.
- 발행자 멤버십이 취소되었거나 프로젝트가 보관된 경우 발행자를 가장해 접근하지 않고 `analysis_access_unavailable`로 중단한다. 별도 시스템 주체는 도입하지 않았다.
- 일시적 모델 연결·타임아웃은 최초 실행 후 1·5·30초 간격으로 최대 세 번 재시도하며 각 완료된 시도의 사용량을 `published_analysis_attempt`에 보존한다. lease를 잃은 이전 작업자의 결과와 사용량은 현재 상태를 덮어쓰지 못한다. 외부 호출 후 프로세스가 죽거나 lease가 만료되면 해당 호출의 사용량은 받지 못할 수 있다.

## 활성화와 검증 범위

기본값은 OFF다. 켜려면 `beez.cdc.intake.enabled=true`, `bootstrap-servers`, `group-id`, `topic`을 모두 지정한다. `beez.cdc.intake.recovery-poll-millis` 기본값은 2000이다. 이 설정은 복구 스캔도 함께 켜므로 소비자만 따로 켜서 PENDING을 읽고 ACK하는 구성은 지원하지 않는다. legacy outbox PoC와 동시 활성화는 금지한다.

[접수행 publication 검토안](../../infra/cdc/intake.publication.review.sql)과 [커넥터 검토안](../../infra/cdc/intake.connector.review.json)은 id·status만 캡처하는 수동 설정 예시다. Kafka key는 기본 PK(id)를 사용하며 프로젝트별 완료 순서는 DB가 보장한다. Debezium Connect와 PostgreSQL publication, Kafka 토픽·접근 권한은 이 코드가 만들거나 배포하지 않는다. JSON converter가 schema wrapper 유무와 관계없이 Debezium `source`, `op`, `after`를 보존하고 접수행 ID·status를 제공해야 한다. 운영 TLS/SASL, replication slot, 보관·DELETE, snapshot 경계, Connect 및 Kafka 장애 대응은 [운영 검토안](../../infra/cdc/OPERATIONS.md)에 따라 담당자가 별도로 검토해야 한다. #228 표시/API, #243/#244 모니터링 및 #229 장애 운영 검증은 이 변경에 포함되지 않는다.

Jira의 원본 문서 등록·초안 변경 캡처 표현과 기존 팀 합의의 **승인·발행 접수** 범위에는 차이가 있다. 이를 임의로 확장하지 않았으며 해당 범위, 시스템 주체, 과거 관계 복원, 문서 변경 분석 및 수동 복구 계약은 담당자 확인이 필요하다.
