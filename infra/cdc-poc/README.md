# CDC 로컬 실험: 설계 발행 Outbox

이 폴더는 기존 서비스와 독립된 실험이다. 예시 PostgreSQL 트랜잭션에서
설계 발행과 outbox 이벤트를 같이 기록하고, Debezium이 커밋된 outbox 행만
읽어 Kafka로 전달하는지 확인한다. 기존 문서 색인 워커와 서비스 DB는 사용하지 않는다.

```text
예시 설계 발행 + outbox INSERT (같은 DB 트랜잭션)
  → PostgreSQL WAL → Debezium → Kafka 토픽
```

기본 smoke_test.py 실험은 원시 Debezium envelope를 그대로 전송한다. `op=c` 및
`after.payload`를 확인한다. 이 기본 실험은 EventRouter 변환이나 Spring consumer를 사용하지 않는다. 별도 실제 Spring 수신 테스트는 아래에 설명한다.
`DESIGN_PUBLISHED`의 필드는 논의 중인 예시이며 최초 발행 이후의 변경 발행(#200) 연동은 아직
구현되지 않았다. 테스트 테이블 `cdc_demo.publication_outbox`의 DDL은 운영
Outbox의 확정된 스키마가 아니다. 최초 발행·변경 발행의 이벤트 계약과 운영
토픽·파티션·키·consumer 등은 담당자 합의가 필요하다.

## 실행

Docker Desktop이 실행 중이고 Python 3가 설치되어 있어야 한다. 포트 18083을
다른 프로그램에서 사용하는지 확인하고, 이 폴더에서 PowerShell로 실행한다.

```powershell
docker compose config --quiet
docker compose up -d
python smoke_test.py
```

첫 실행에는 이미지를 내려받고 초기화하므로 시간이 걸릴 수 있다. 스모크 테스트가
Connect를 기다린 다음 `connector.json`을 등록하고, 다음을 확인한다.

- 같은 트랜잭션에 기록된 발행 예시와 outbox 이벤트 중 커밋된 이벤트가
  Kafka에 도착하고 주요 내용이 유지된다.
- 롤백한 outbox 이벤트는 지정된 관측 시간 동안 나타나지 않는다.

실행마다 임의 UUID를 사용하여 기존 이벤트와 구분한다. 반복 실행하면 예시
데이터가 쌓인다. 관측 시간 내 롤백 이벤트가 없다는 확인은 운영 환경에서의
정확히 한 번 전송이나 장시간 장애 복구를 증명하지 않는다.

## 직접 살펴보기

```powershell
docker compose ps
Invoke-RestMethod http://127.0.0.1:18083/connectors/beez-cdc-poc-outbox/status
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic beez_cdc.cdc_demo.publication_outbox --from-beginning --timeout-ms 6000
```

출력의 JSON은 Debezium envelope다. `after.payload`에 예시 발행 내용이
들어가며 `source`, `op`, `before`, `after`는 CDC 메타데이터다.
컨슈머 명령은 메시지가 더 없을 때 타임아웃하며 종료 코드가 0이 아닐 수 있다.

## 중지와 초기화

데이터를 남기고 중지:

```powershell
docker compose down
```

이 실험의 예시 DB, Kafka 로그와 커넥터 오프셋을 **모두 지우고** 처음부터
실행하려면 이 폴더에서만 다음 명령을 사용한다.

```powershell
docker compose down --volumes
```

Compose 프로젝트 이름은 `beez-cdc-poc`이며 별도 네트워크와 볼륨을 사용한다.
PostgreSQL은 호스트에 포트를 노출하지 않는다. Kafka 테스트 리스너는 `127.0.0.1:19092`에만, Connect 관리 API는
`127.0.0.1:18083`에만 노출하고 실험용 계정을 사용한다. 운영 보안·가용성
설정으로 사용해서는 안 된다.

## 운영 연결 전에 남은 일

- #200 발행 트랜잭션과 outbox DDL/발행 이벤트 계약 및 실패 정책 확정
- 기존 문서 색인 워커와 중복 실행 방지, 소비자 멱등성 및 원본 DB 기준 검증
- 이벤트 보관·재처리·순서·오프셋 복구 및 WAL 증가 감시
- 브로커 보안·운영 자원·모니터링, 최초 스냅샷 및 복구 시나리오 합의


## 초기 적재와 순서 주의

실험은 `snapshot.mode=no_data`로 설정되어 있으며 테스트가 복제 슬롯 활성화를
확인한 후에만 쓰기를 시작한다. 이 방식은 슬롯 생성 전에 이미 DB에 있던 이벤트를
가져오지 못한다. 운영에서 그대로 사용하면 이벤트 누락이 가능하며, 최초 적재 및
복구 방식을 합의해야 한다. `initial` 스냅샷으로 변경하면 기존 행은 `op=r`
이벤트로 재전송될 수 있으므로 이를 처리할 정책과 멱등성이 필요하다.

JSONB의 `after.payload`는 Debezium에서 JSON 문자열로 나올 수 있어 소비자가
한 번 더 JSON 디코딩해야 한다. 스모크 테스트는 문자열과 객체 두 형식을 모두
확인한다. 단일 파티션인 실험에서는 롤백 다음에 쓰인 정상 이벤트까지 관찰한 후
롤백 이벤트를 검사하지만, 이는 유한한 관측 시간의 검증이다.

기본 Kafka 키는 outbox의 event_id(UUID)다. 운영 토픽을 여러 파티션으로 구성하면
같은 프로젝트의 서로 다른 이벤트가 서로 다른 파티션에 배치될 수 있으므로
프로젝트별 순서가 보장되지 않는다. 프로젝트 순서가 필요하면 실제 라우팅 키와
파티션 전략을 별도로 설계해야 한다.

## 실제 Spring Kafka 수신기까지 시험

```powershell
python run_spring_e2e.py
```

별도의 임시 Debezium 커넥터·토픽·소비자 그룹을 만들고 실제 Spring Kafka 리스너를 포함한 Gradle 통합 테스트를 실행한다. Source 예제 DB에서 이벤트를 커밋하면 Debezium과 Kafka를 지나 리스너의 inbox에 최초 발행·변경 발행이 `PENDING`으로 저장되고, 각 이벤트가 `WAITING` 큐에 접수되는지 확인한다. 큐 접수는 실제 온톨로지 동기화가 완료됐다는 뜻이 아니다. 롤백 이벤트가 전달되지 않았는지는 같은 파티션에서 뒤따르는 정상 이벤트가 inbox에 기록될 때까지 확인한다. 임시 커넥터는 종료 시 삭제하며 POC 볼륨은 보존한다.

이 테스트는 격리된 **서로 다른 두 DB**를 사용한다. source DB와 Spring Testcontainers DB에 예시 project·baseline ID만 각각 생성하며, DB 복제 또는 실제 발행·관계 데이터의 일치성을 증명하지 않는다. 최초 발행 API부터의 전체 경로, 실제 CHANGE 발행 #200, 관계 조회·AI 분석, Kafka 재전달 및 offset 실패 복구는 검증 범위 밖이다. Testcontainers와 Docker Desktop, Java 21, Gradle 의존성이 필요하며 처음 실행에는 시간이 걸린다. 포트 18083과 19092는 로컬 전용이며 빈 포트여야 한다. 실행 중인 POC가 없다면 테스트 종료 시 POC 서비스만 중지한다.

2026-10-29 로컬 환경에서 Spring Kafka listener → DB inbox 사례가 통과했다.