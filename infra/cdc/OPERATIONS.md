# 발행 이벤트 CDC 배포 검토안 (#225)

이 구성은 **운영 배포 완료 상태가 아니다.** 별도 승인된 Kafka 브로커와 보안 설정을 전제로 Connect worker만 선택적으로 띄우는 초안이다. 실제 운영 DB 변경, Kafka 구축, 자격 증명·네트워크 정책 설정과 배포는 하지 않았다. `compose.yaml`은 기존 `infra/compose.yaml`을 수정하거나 자동 실행하지 않는다.

BE CDC consumer는 `beez.cdc.kafka-security.properties`를 통해 SSL 또는 SASL_SSL Kafka 속성을 받는다. 인증서·ACL·방화벽과 장애 복구 협의 및 실제 TLS 연결 검증을 완료한 뒤에만 배포를 판단한다. 보안 설정이 비어 있으면 로컬 PLAINTEXT로 동작하므로 운영에서 반드시 보안 속성을 주입한다. 이 Compose는 Kafka 자체를 설치하지 않는다. 팀이 Kafka를 직접 운영할지 별도 제공받을지와 브로커의 인증·암호화·영속 저장·운영 책임을 정해야 한다.

## 범위와 이벤트

#197이 발행 트랜잭션에서 분석 접수행을 만들고 Debezium이 이 행을 Kafka에 전달하는 방향이다. 초기 v1과 과거 발행 백필은 `NOT_EVALUATED`로 남기며 자동 영향 분석하지 않는다. 승인 변경 발행의 `PENDING`은 소비자가 원자적으로 선점해 처리한다. 상태 UPDATE가 새로운 작업으로 중복 실행되지 않도록 소비 계약을 정해야 한다. 재분석 요청으로 `NOT_EVALUATED`가 `PENDING`으로 바뀌는 경우를 어떻게 감지할지도 확정해야 한다.

초기 관계 구축은 #167, 색인 생성은 #223의 별도 경로다. 본 접수 소비가 이를 실행한다고 가정하지 않는다. #223이 같은 Kafka 이벤트를 별도 consumer group으로 읽을지는 담당자 간 미확정이다. `notification`과 `activity_event`, 원문·초안·파싱 상태 변경은 이 분석 접수 캡처 대상이 아니다.

**이 템플릿은 바로 등록하면 안 된다.** #197의 실제 테이블·컬럼, 식별자와 상태 전환, 저장·승인 트랜잭션, PENDING 재처리 및 장애 복구 계약이 아직 확정되지 않았다. 민감정보가 포함되지 않도록 캡처 컬럼을 검토한 뒤에만 placeholder를 바꾼다. 현재 BE consumer는 기본 비활성화된 기존 outbox 계약용 실험 구현이고 #197 접수행을 아직 처리할 수 없다. 명시적 legacy PoC 플래그 없이 enabled만 켜면 애플리케이션이 시작을 거부한다.

## 초기 적재와 스냅샷 경계

`snapshot.mode=initial`은 실제 캡처 대상 테이블에 존재하는 행의 스냅샷과 이후 WAL을 연결한다. #197의 기존 baseline 백필은 `NOT_EVALUATED`이므로 snapshot으로 읽더라도 자동 영향 분석을 만들지 않는다. 기존행 중 `PENDING`인 경우의 재처리, 백필과 신규 발행의 경계·중복·누락, 커넥터 checkpoint와 재시작은 계약 확정 후 격리 DB에서 검증한다. 이미 완료·미평가된 행의 snapshot은 신규 분석 지시가 아니다.

## 보안 설정 및 사전 협의

1. 운영 담당자가 호스트별 접근이 제한된 Kafka TLS 리스너·인증·ACL과 보존 정책을 마련한다. worker의 세 내부 토픽(config는 compact 1 partition, offset/status는 compact)과 발행 토픽의 생성·복제 계수, 파티션·보존, Connect와 BE의 최소 권한을 별도로 정한다. 단일 브로커는 브로커 장애에 내결함성이 없다.
2. 운영 담당자는 외부 Docker 네트워크를 만들고 Connect REST 8083에 관리 담당자만 접근할 수 있도록 네트워크 격리와 인증 가능한 관리 경로를 구성한다. Compose의 외부 포트 미노출만으로 같은 Docker 네트워크 내 접근이 차단되지는 않는다. REST는 커넥터 설정 변경이 가능하므로 무단 접근을 막는다.
3. `CDC_CONNECT_IMAGE`에 검토한 이미지 digest, `CDC_PRIVATE_NETWORK`에 기존 제한망, `CDC_CONNECT_SECRETS_DIR`에 호스트 절대 경로를 설정한다. 해당 디렉터리는 적절한 OS 접근 권한으로 보호하고 Git에 넣지 않는다. Connect 컨테이너 사용자(기본 이미지 UID 1001)가 읽을 수 있어야 한다. `worker.properties.template`을 참고해 그 안에 `connect-worker.properties`, DB 인증용 `database.properties`, PostgreSQL CA와 Kafka trust/key 자료를 제공한다. 템플릿은 인증 방식에 맞는 설정이 완성된 파일이 아니다. 비밀값을 Git, 명령 이력, 로그에 노출하지 않는다. Connect 설정의 저장/조회 권한과 Kafka 내부 토픽에 대한 ACL도 제한한다.
4. PostgreSQL은 인증서 호스트명이 일치하는 TLS `verify-full`, 별도 복제 계정과 네트워크 제한을 사용한다. 사전에 확정된 #197 접수 테이블만 포함하는 publication과 슬롯 이름을 합의한다. `publication.autocreate.mode=disabled`이므로 커넥터가 publication을 만들지 않는다. 논리 복제 권한은 강력한 권한이며 컬럼 allowlist는 독립적인 보안 경계가 아니다. 초기 snapshot에는 별도 SELECT 권한이 필요하므로 실제 사용 DB/커넥터 조합에서 권한을 검증한다.
5. BE consumer의 `beez.cdc.kafka-security.properties`에 Kafka 표준 키 `security.protocol`, `ssl.truststore.location`과 인증 방식에 필요한 속성을 외부 설정으로 주입한다. SASL_SSL이면 `sasl.mechanism`, `sasl.jaas.config`가 필요하다. 민감한 설정은 비밀 저장소를 사용하고 로그와 Git에 남기지 않는다. 자동 커밋과 오프셋 정책은 이 보안 설정으로 변경할 수 없다. 실제 인증서·브로커 연결 테스트는 아직 하지 않았다.
6. #197 스키마·consumer 계약과 키/상태 변경 정책이 확정된 후에만 `publication.connector.template.json`의 자리표시자를 교체하고 커넥터의 file config provider와 DB 비밀번호 참조를 검증한다. `topic.prefix`·실제 토픽명, JSON converter envelope·키, BE consumer의 토픽과 일치 여부를 검증한다. 특히 `message.key.columns`의 project_id 라우팅과 컬럼 권한·replica identity, 다중 파티션 및 파티션 수 변경 시 순서를 실제 구성에서 검증한다. 같은 키의 전달 순서는 비동기 작업 완료 순서를 보장하지 않는다. 이 템플릿은 REST에 바로 제출할 수 없다. 제한된 관리 경로에서만 등록한다.

구성 형식만 검증: `docker compose -f infra/cdc/compose.yaml --profile cdc config --quiet`. 필수 환경 변수/경로는 위 사전 협의 후 주입한다. 이 명령은 DB·브로커 연결, 자격 증명, 인증서, 실제 안전성을 확인하지 않는다. 실제 실행은 인프라 담당자와 승인된 운영 절차를 따른다.

## WAL, 상태 및 복구

- DB 담당자는 `SHOW wal_level;` (`logical` 필요), `SHOW max_replication_slots;`, `SHOW max_wal_senders;`, `SHOW max_slot_wal_keep_size;`와 실제 디스크 여유를 확인한다. `wal_level` 변경은 PostgreSQL 재시작이 필요하다. 다른 복제 작업을 포함해 슬롯/송신기 여유를 산정한다. 무제한 WAL 보유는 디스크 고갈을 일으킬 수 있고, 보유 한도를 초과하면 슬롯이 무효화되어 재동기화가 필요할 수 있다. 수치와 알림 임계값은 부하·복구 목표를 측정해 합의한다.
- 예시 조회 (권한 필요): `SELECT slot_name, active, restart_lsn, confirmed_flush_lsn, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal FROM pg_replication_slots WHERE slot_name = '<slot>';`. `restart_lsn`이 null이면 계산 결과도 null이다. DB 디스크 사용률, 보유 WAL 크기·증가 속도, 슬롯 비활성 지속 시간에 알림을 건다. 대시보드나 알림이 이 파일로 자동 생성되지는 않는다.
- 접근이 제한된 관리 경로에서 Connect `GET /connectors/<name>/status`의 connector/task FAILED 상태를 감시하고, Kafka consumer group 도구로 topic/partition별 lag, broker 디스크/가용성 및 Connect 내부 토픽 상태를 점검한다. BE 처리 상태·실패 이벤트도 따로 조회/알림한다. 지연의 정상 기준과 알림 수신자는 운영 담당자가 정한다.
- 현재 BE에는 Kafka 수신/DB 실패 시 offset을 건너뛰지 않고 1초 간격으로 계속 재시도하는 transport 처리가 있다. 사후 영향 분석 업무 처리의 제안된 재시도(1초·5초·30초 및 최종 실패 상태)는 별개다. 이 둘을 합쳐 3회만 재시도한다고 설명하지 않는다. 잘못된 이벤트가 한 파티션을 막을 수 있으므로 경보·진단·안전한 복구 방안을 합의한다.
- 장애 시 slot, Connect offset, 내부 토픽을 독립적으로 삭제·초기화하지 않는다. 재처리 범위, 최초 baseline 대조, 중복·누락 확인과 복구 권한을 담당자와 합의하고 격리 환경에서 테스트한다. 프로젝트별 발행 순서와 이전 이벤트 재수신 순서도 검증한다. DLQ로 넘긴 실패 뒤 후속 버전이 먼저 완료되지 않도록 복구 정책을 정한다.

## 이 초안에서 확인하지 못한 것

운영 TLS/SASL, DB 권한·인증서, 실제 발행 트랜잭션, bootstrap 경계, Kafka 재시작·장기 장애 복구, 담당자의 관계 구축/영향 분석 전체 연결과 부하/자원 사용량은 실제 배포 구성에서 검증하지 않았다. 로컬 격리 테스트의 PLAINTEXT 브로커는 테스트에만 쓰며 운영 예시가 아니다.
