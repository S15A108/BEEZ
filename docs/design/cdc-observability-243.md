# 발행 후 CDC 분석 상태·운영 지표 (#243)

## 범위

`published_change_analysis`가 발행 후 분석 상태의 기준이고 `published_analysis_attempt`는 완료된 시도의 기록이다. 변경 제안 상세 CHG-A03의 `published_analysis`에 프로젝트 멤버 권한을 확인한 뒤 선택 리비전의 발행 baseline 상태를 추가한다. 발행 전 영향 분석 CHG-A05와 상태를 합치지 않고 새로운 중복 조회 API를 만들지 않는다. 프론트 #203·MCP #221의 실제 연동 및 계약 검토는 각 담당자와 확인한다.

`COMPLETED`는 접수 처리가 끝났다는 뜻이다. `evaluation_status=INSUFFICIENT_EVIDENCE`, `NOT_EVALUATED`, 누락된 결과 또는 `UNKNOWN`은 평가 완료·영향 0건이 아니다. 발행된 제안의 baseline이나 접수행을 확인할 수 없으면 `UNKNOWN`, DB 질의 자체의 실패는 503이다. `current_baseline`은 현재 baseline과 동일한지만 나타내며 전체 입력의 최신성을 보장하지 않는다. `RETRY_SCHEDULED`와 `RECOVERY_PENDING`은 백그라운드 처리 안내이고 사용자가 재처리할 수 있다는 약속이 아니다. 수동 재처리 API는 현재 없다.

## 지표 계약 제안

Micrometer 이름은 `beez.cdc.*`이고 Prometheus 형식에서는 `beez_cdc_*`로 변환된다. 상태별 숫자는 개별 인스턴스가 해당 DB의 **전체 보관 행**을 집계한 gauge다. 프로젝트 ID, baseline ID, 문서 ID, 원문, 예외 메시지나 식별자를 태그로 쓰지 않는다. 라벨은 고정된 `status`, `result` 값만 사용한다. 모델 처리 결과와 Kafka 수신 실패는 별개의 단계다.

| Micrometer 지표 | Prometheus 지표 | 유형 | 의미 |
| --- | --- | --- | --- |
| `beez.cdc.analysis.intakes{status}` | `beez_cdc_analysis_intakes{status}` | gauge | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `NOT_EVALUATED` 현재 접수행 수 |
| `beez.cdc.analysis.evaluations{result}` | `beez_cdc_analysis_evaluations{result}` | gauge | 완료 행의 `COMPLETED`, `INSUFFICIENT_EVIDENCE`, 평가값이 없거나 완료와 맞지 않는 `UNKNOWN` 수 |
| `beez.cdc.analysis.not.evaluated.expected` | `beez_cdc_analysis_not_evaluated_expected` | gauge | 최초 v1 및 `published_before_intake`로 명시된 과거 백필 |
| `beez.cdc.analysis.not.evaluated.requires.review` | `beez_cdc_analysis_not_evaluated_requires_review` | gauge | 그 외 미평가. 모두 장애라고 단정하지 않지만 후속 분석을 막을 수 있다 |
| `beez.cdc.analysis.blocked.projects` | `beez_cdc_analysis_blocked_projects` | gauge | `PENDING`이 있고 선행 baseline 누락·미완료 등으로 순서상 막힌 프로젝트 수. 워커와 같은 선행 검사 사용 |
| `beez.cdc.analysis.pending.retries` | `beez_cdc_analysis_pending_retries` | gauge | 다음 재시각이 예약됐거나 도래한 `PENDING` 수 |
| `beez.cdc.analysis.expired.leases` | `beez_cdc_analysis_expired_leases` | gauge | `RUNNING` 중 lease가 만료된 행 수 |
| `beez.cdc.analysis.started.retries` | `beez_cdc_analysis_started_retries` | gauge | 보관 중인 접수행의 `max(attempt - 1, 0)` 합계. 시작된 재시도 수 |
| `beez.cdc.analysis.attempt.records{result}` | `beez_cdc_analysis_attempt_records{result}` | gauge | 보관 중인 완료 시도 이력의 `COMPLETED`, `INSUFFICIENT_EVIDENCE`, `NOT_EVALUATED`, `FAILED` 건수 |
| `beez.cdc.analysis.recorded.retry.attempts` | `beez_cdc_analysis_recorded_retry_attempts` | gauge | 저장된 시도 이력 중 attempt > 1인 건수. lease 상실 등으로 기록되지 못한 시도는 제외 |
| `beez.cdc.analysis.oldest.pending.seconds` | `beez_cdc_analysis_oldest_pending_seconds` | gauge | 가장 오래된 현재 `PENDING`의 `requested_at`부터 DB 조회 시점까지의 초. 기존 실행·재시도 시간도 포함. 대기 행이 없으면 0 |
| `beez.cdc.analysis.snapshot.available` | `beez_cdc_analysis_snapshot_available` | gauge | 최근 DB 집계 성공 1, 실패 0. 실패 중 DB 기반 지표는 0 대신 NaN |
| `beez.cdc.analysis.snapshot.last.success.epoch.seconds` | `beez_cdc_analysis_snapshot_last_success_epoch_seconds` | gauge | 마지막 DB 집계 성공 시각(Unix 초). 성공한 적 없으면 NaN |
| `beez.cdc.analysis.consumer.enabled` | `beez_cdc_analysis_consumer_enabled` | gauge | consumer 설정의 켜짐 여부. 프로세스나 Kafka 연결 건강을 보장하지 않음 |
| `beez.cdc.kafka.receive.failures` | `beez_cdc_kafka_receive_failures_total` | counter | Kafka 레코드 처리 실패 **시도**. 같은 레코드의 반복 재시도도 각각 셈. broker 연결 실패 등 리스너 진입 전 오류는 별도 관측 필요 |

DB gauge는 애플리케이션 인스턴스마다 같은 DB 전체 값을 보므로 인스턴스별 `sum`을 하면 중복된다. 수집 대상을 하나로 정하거나 정상·최신 스냅샷만 대상으로 `max` 등으로 중복 제거한다. 캐시 시각이 서로 다른 인스턴스의 `max`는 잠시 오래된 값을 유지할 수 있다. 이벤트 counter는 프로세스별이며 재시작 시 초기화되므로 인스턴스별 `increase()`를 계산한 뒤 합산한다. 보관 중인 행이나 시도를 세는 gauge는 감소할 수 있으므로 `increase()`를 적용하지 않는다. `COMPLETED` 상태와 `INSUFFICIENT_EVIDENCE` 결과는 동시에 존재할 수 있다.

Kafka 실패 counter는 consumer를 켰을 때만 등록된다. 꺼져 있으면 값 0이 아니라 시계열이 없다. 대시보드의 결측을 0으로 처리하더라도 수집 대상의 `up`·시계열 누락과 연결 장애는 별도로 감지한다.

## 수집·운영 경계

지표가 실제 조회될 때 `JdbcClient`가 기존 접수·시도 테이블을 SQL로 집계한다. 모든 gauge가 매번 개별 쿼리를 날리지 않도록 인스턴스에서 스냅샷을 기본 15초 캐시한다. `beez.cdc.observability.cache-millis`로 조정하며 최소 1초, 최대 5분이다. 첫 조회 전에는 DB 집계를 실행하지 않는다. 집계 트랜잭션의 제한은 5초이며 연결 풀의 연결 획득 대기까지 제한한다는 뜻은 아니다. 전체 집계는 데이터가 커지면 비용이 증가할 수 있으므로 수집 주기와 인덱스는 부하를 측정해 정한다.

DB 집계 실패는 제한 없는 예외 텍스트를 로그에 남기지 않고 실패 상태를 내보낸다. Prometheus 수집 실패 또는 오래된 마지막 성공 시각도 #244 알림에서 별도로 감지해야 한다. consumer가 꺼져 있거나 Kafka가 아직 배포되지 않은 dev에서 오래된 PENDING을 장애로 단정하지 않는다. PENDING이 600초를 초과한 상태로 5분 지속될 때 알림을 주면 연속 대기 기준 약 15분 이후이며, RUNNING 전환 등으로 조건이 끊기면 더 늦어질 수 있다. 초기 기준은 실측과 장애 대응 시간에 따라 조정한다.

Prometheus registry 의존성, `/actuator/prometheus` 활성화·인증·내부망 수집 및 Grafana 대시보드는 팀원이 담당하는 #244의 선행 PR이다. 이 변경은 기존 Actuator 공개 범위나 보안 체인을 열지 않는다. 그 PR이 반영되기 전에는 Micrometer meter가 등록되어도 Prometheus HTTP 수집이 완료된 상태가 아니다. 설정이 반영된 뒤 실제 scrape·대시보드·알림과 #229 장애 복구 시험을 별도로 확인한다.

관리 포트를 분리하면 Actuator health도 이동하므로 현재 `infra/compose.yaml`의 8080 헬스체크와 Nginx의 `/actuator/health` 경로를 함께 유지하거나 변경하고 배포 경로를 검증해야 한다.

분석 상세 GET의 접근·권한 거부 로그와 발행 후 분석 워커의 권한 거부 로그는 프로젝트 ID를 마스킹한다. 그 밖의 공통 접근 로그에는 기존 정책을 적용하며, 서비스 전체의 감사·마스킹 정책은 별도 합의가 필요하다. MCP 패킷 프로젝트 불일치는 사유만 남기고 두 프로젝트 ID를 남기지 않는다.

Kafka 수신 실패 counter는 메시지가 리스너에 전달된 뒤 발생한 디코딩·접수 확인 오류를 센다. 같은 메시지의 반복 실패도 각각 증가한다. consumer lag, broker 미접속, Connect 장애, WAL·호스트 리소스는 #244의 별도 수집이 필요하다. Kafka가 접수 신호를 ACK한 것은 실제 분석 완료를 보장하지 않는다.
