
> **현재 합의:** #197의 분석 접수행을 발행 트랜잭션에서 만들고 CDC가 직접 읽는 방향이다. v1과 기존 백필은 `NOT_EVALUATED`로 영향 분석을 자동 실행하지 않는다. 최초 관계 구축은 #167, 색인은 #223의 별도 경로다. 현재 접수 테이블·컬럼 및 consumer 연동은 미확정이므로 [OPERATIONS.md](OPERATIONS.md)의 템플릿은 실행 불가한 검토안이다. 아래 raw document 캡처 자료는 과거 실험이며 운영 대상이 아니다.

# 문서 CDC 원본 설정 검토 초안 (#225)

아래 자료는 과거 raw document 캡처 실험 보존용이며 현재 발행 접수 계약이 아니다. 이 디렉터리는 팀 리뷰용이다. **운영 배포 설정이 아니며 자동 실행하지 않는다.** `publication.review.sql`과 `connector.review.json`은 인프라·문서·관계 서비스 담당자의 계약 및 보안 검토 후 적용 여부를 결정한다. 기존 `infra/compose.yaml`, 운영 DB, 역할, replication slot, Kafka에 영향을 주지 않는다. 로컬 실행 검증은 별도 `infra/cdc-poc`에서 수행한다.

## 캡처 대상 제안

PostgreSQL 17의 `public.document`와 `public.document_version`에서 식별·상태 메타데이터만 수집하도록 각각 publication 컬럼과 connector allowlist를 명시한다. PK `id`는 UPDATE/DELETE에 필요한 replica identity다. `document.title`, `domain`, 사용자 식별자, `document_version.content_text`, `content_hash`, `storage_key`, `parse_error`는 선택하지 않는다. 토픽 자체의 프로젝트/문서 ID, 시간·상태는 민감할 수 있으므로 접근·보존 정책이 필요하다.

`document_version`에는 `project_id`가 없다. 소비자는 원본 DB의 `document_id → document.project_id`를 적절한 프로젝트 권한과 버전 일관성 아래 조회하거나, 담당자와 별도 이벤트 계약을 정의해야 한다. 순서상 문서와 버전은 서로 다른 테이블·토픽이므로 결합 순서가 보장되지 않는다.

같은 DRAFT 버전의 본문 수정도 UPDATE다. 단 `lock_version`은 모든 변경에서 증가하지 않으므로 유일 이벤트 ID나 변경 종류로 단독 사용하면 안 된다. `parse_status` 업데이트와 승인 `status` 업데이트도 포착될 수 있다. UPDATE 자체는 승인 또는 발행을 의미하지 않으며 기존 색인 worker를 다시 실행하는 신호로 사용하지 않는다. 삭제와 tombstone, 오래된 이벤트, 초기 스냅샷 `r`/이후 `c`, `u`, `d`의 중복 처리 및 최신 버전 판정은 #226·#227 계약에서 다룬다. 이 초안은 변경의 의미를 분류하지 않는다.

**운영 반영 전 필수 결정:** 기본 replica identity가 PK인 경우 DELETE 이벤트에는 삭제 전 `id`만 제공될 수 있다. 이후 원본 행이 사라지면 `document_id → project_id`를 조회할 수 없어 삭제된 문서 버전의 프로젝트를 식별·권한 검증·복구할 수 없다. 격리 PostgreSQL 17·Debezium 실험에서는 `document_version` DELETE의 `before.document_id`가 `null`이 아니라 `0`으로 나타나기도 했다. 이 값은 실제 문서 ID로 사용하면 안 되며, 표현은 디코더·설정에 따라 달라질 수 있다. DELETE를 전달할지, 불변 삭제 이력/outbox나 권한 있는 이전 매핑을 어떻게 보존할지 담당자와 합의하고 실제 삭제·재처리 테스트를 통과하기 전 이 템플릿을 운영에 적용하지 않는다. `REPLICA IDENTITY FULL`을 임의 설정하면 publication 컬럼 및 원문 노출, WAL 크기에 영향을 줄 수 있으므로 임의로 켜지 않는다.

## 권한과 보안 경계

- SQL은 publication이 아직 없을 때 수동 실행하는 예시이며, 기존 publication을 덮거나 삭제하지 않는다. 생성은 테이블 소유권과 DB `CREATE`가 필요한 관리 작업이다. 운영용 커넥터에는 자동 publication 생성을 허용하지 않는다.
- PostgreSQL의 **publication 컬럼 제한만으로 민감정보의 보안 경계를 보장할 수 없다.** 논리 복제 권한을 가진 주체가 다른 publication에 접근할 수 있다. 별도 제한된 DB 계정, 접근망, Kafka ACL·암호화, Connect API 인증·비밀 관리 및 서버 감사 정책을 함께 설계한다. DB 복제 권한은 높은 권한이며 무조건 안전한 최소 권한이라고 볼 수 없다.
- 초기 스냅샷의 JDBC `SELECT` 권한은 논리 복제 연결 권한과 별개다. 예시 connector의 명시적인 snapshot SELECT는 허용 컬럼만 조회한다. 실제 버전의 Debezium이 메타데이터 및 기타 동작에 추가 권한이 필요한지는 **격리 환경에서** 컬럼 단위 SELECT와 복제 권한 조합으로 확인해야 한다. 본 초안은 역할/비밀번호/GRANT를 자동으로 만들지 않는다.
- 예시의 `<...>`는 교체해야 할 자리표시자다. Kafka Connect의 비밀 config provider를 배포 환경에서 따로 설정하고 실제 비밀번호가 파일, Git, 커맨드 이력, 로그에 노출되지 않게 한다. `database.sslmode=verify-full`에는 인증서 검증이 가능한 호스트 이름과 CA 파일이 필요하다. Kafka Connect worker의 Kafka TLS/SASL, 내부 토픽 ACL·암호화와 REST API 보호도 별도 구성하며 이 JSON만으로 해결되지 않는다. `PLAINTEXT` 실험 설정을 운영에 사용하지 않는다.

## 초기 스냅샷과 복구

`snapshot.mode=initial`은 기존 행의 스냅샷 후 WAL 스트림으로 이어지는 기본 방향이다. 스냅샷 행과 변경이 경계에서 누락되지 않는지, 중단 후 Connect offset/replication slot·Kafka 내부 토픽이 유지되는지 실제 구성에서 검증해야 한다. 순서·중복, consumer의 DB commit과 offset 저장은 별개의 문제이고 Kafka의 정확히 한 번 처리를 보장하지 않는다.

운영 전 `wal_level=logical`, `max_replication_slots`, `max_wal_senders`, 디스크·WAL 보존 용량을 산정한다. `wal_level` 변경은 PostgreSQL 재시작이 필요하다. 슬롯이 멈추면 WAL이 쌓여 디스크가 소진될 수 있으므로 `pg_replication_slots`, WAL 보유량, Kafka/Connect 상태·소비 지연과 알림을 운영 담당자가 설정한다. 슬롯/offset/내부 토픽을 독립적으로 삭제·재생성하면 누락될 수 있다. 삭제가 아닌 백업, 장애 시 재스냅샷·대조·재처리 절차를 합의한다.

컬럼 리스트에 새 컬럼이 자동 추가되지 않는다. 스키마 마이그레이션이나 replica identity 변경, 새 토픽·파티션·커넥터/슬롯 변경 시 캡처 목록과 소비 계약을 함께 검토한다. Kafka Connect 메타데이터 토픽과 Kafka 보관 정책도 별도로 관리한다. 원본 DB의 identity sequence는 행 변경 이벤트가 아니므로 별도의 복제 대상이 아니다.

## 아직 검증할 항목

1. snapshot 시작 전·진행 중·완료 후의 문서 등록/동일 버전 수정/새 버전 생성과 rollback을 격리 DB에서 확인.
2. 문서 상태와 parse 상태의 구별, 프로젝트 연결·권한, 원문 비노출 및 재처리·순서 역전 검증.
3. 역할·publication·스냅샷 SELECT 권한, TLS/ACL, DB·Connect·Kafka 재시작 후 checkpoint와 유실·중복 테스트.
4. #200 발행 이벤트와 #197의 분석 접수를 중복하지 않는 서비스 계약, #223 기존 색인 worker와 책임 분리.

참고: [PostgreSQL 17 publication column lists](https://www.postgresql.org/docs/17/logical-replication-col-lists.html), [PostgreSQL replication security](https://www.postgresql.org/docs/17/logical-replication-security.html), [Debezium PostgreSQL connector](https://debezium.io/documentation/reference/2.7/connectors/postgresql.html).

로컬 정적 확인은 저장소 루트에서 `python infra/cdc/check_contract.py`로 실행한다. 이 검사는 컬럼 목록과 현재 DB 스키마의 일치를 확인하며 runtime, 접근 권한 또는 보안을 증명하지 않는다.
