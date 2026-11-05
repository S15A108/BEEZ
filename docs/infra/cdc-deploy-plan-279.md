# dev 서버 CDC(Kafka·Debezium Connect) 배포 계획 — 제안 (S15P31A108-279)

> **상태: 제안, 승인 대기.** 이 문서가 머지되기 전에는 서버·DB·compose를 바꾸지 않는다.
> 승인 범위는 아래 [결정 제안](#3-결정-제안) D1~D8이다. 계약·consumer 관련(D3·D5·D6·D7)은 양지훈님,
> PostgreSQL 재시작 시각(D4)과 일정(D8)은 팀이 승인한다. 승인된 결정은 이 문서에서 "확정"으로 바꾼다.
>
> **티켓 구성** — 279는 이 계획과 승인만 맡는다. 실행은 별도 작업 티켓 세 개로 나눴고, 티켓마다 PR 하나다.
>
> | 티켓 | 범위 | 결정 | 선행 |
> |---|---|---|---|
> | S15P31A108-293 | PostgreSQL 논리 복제 준비(`wal_level`·복제 계정·publication) | D4·D5 | 279 |
> | S15P31A108-294 | Kafka·Connect 스택 `beez-cdc` 배포(토픽 생성 포함) | D1·D2·D3 | 279 |
> | S15P31A108-295 | 커넥터 등록과 backend consumer 켜기, 발행 1건·재시작 확인 | D6·D7 | 293·294 |
>
> 293과 294는 서로 기다리지 않는다. 295가 끝나야 S15P31A108-261(Kafka·Connect·WAL 수집·알림)과
> S15P31A108-229(CDC 통합·장애 복구 검증)를 시작할 수 있다.

## 1. 목적

발행 접수행 `published_change_analysis`의 변경을 Debezium → Kafka → backend consumer로 전달한다.
계약은 [#227 연동 문서](../design/published-analysis-227.md), 운영 주의는 [CDC 운영 검토안](../../infra/cdc/OPERATIONS.md)이
정본이다. 이 문서는 그 검토안을 **dev 서버 한 대에 어떻게 올릴지**를 정한다.

**지금 dev에서는 발행 후 영향 분석이 아예 돌지 않는다.** 접수행 INSERT는 설정과 상관없이 들어가지만
(`PublishedAnalysisRepository.accept`), 이를 처리하는 복구 스캔(`PublishedAnalysisDispatcher`)은
`beez.cdc.intake.enabled=true`일 때만 생긴다. 켜는 설정은 Kafka 접속 정보를 함께 요구하므로
(`AnalysisIntakeKafkaConfiguration`) Kafka 없이 스캔만 켤 수는 없다. 따라서 이 배포는 지연을 줄이는 일이
아니라 **dev에서 분석 기능을 처음 켜는 일**이다. 켠 뒤 Kafka가 앞당기는 시간은 복구 스캔 주기(2초) 이내다.

## 2. 현재 상태 (11-05 확인)

| 항목 | 상태 | 근거 |
|---|---|---|
| 서버 컨테이너 | backend·ai·postgres·seaweedfs + 모니터링 5개. **Kafka·Connect 없음** | `docker ps` |
| backend consumer | 꺼짐(`beez_cdc_analysis_consumer_enabled` = 0) | 244 대시보드 |
| 접수 테이블 | `published_change_analysis` **0행** | `SELECT status, count(*)` |
| PostgreSQL | 17.11(pgvector 이미지), `wal_level=replica`, `max_replication_slots`=10, `max_wal_senders`=10, `max_slot_wal_keep_size`=-1(무제한), DB 11MB | `SHOW` 읽기 |
| WAL 생성량 | 10-29 이후 약 19MB | `pg_stat_wal` |
| DB 계정 | 앱 계정 `beez`가 superuser이고 테이블 소유자 | `pg_roles` |
| postgres 설정 위치 | `infra/compose.yaml`에 `command` 없음(이미지 기본값) | compose |
| 서버 자원 | 메모리 15.4GB 중 사용 가능 13.4GB, 4코어, 디스크 296GB 여유 | `free`·`df` |
| Docker 네트워크 | `beez_default`에 본 스택 4개와 prometheus·postgres-exporter가 붙어 있음 | `docker inspect` |
| backend CDC 설정 | `beez.cdc.intake.enabled`·`bootstrap-servers`·`group-id`·`topic` 모두 있어야 기동. 켜면 2초 주기 복구 스캔도 같이 켜짐 | `AnalysisIntakeKafkaConfiguration`, `PublishedAnalysisDispatcher` |
| backend env 전달 | `infra/compose.yaml` backend `environment`에 **나열한 변수만** 들어감. CDC 변수는 아직 없음 | compose |
| 저장소 CDC 자료 | `infra/cdc/compose.yaml`은 운영용 검토안(Connect만, TLS 전제, 전용 망)이고 broker는 없음. 커넥터·publication은 `intake.*.review.*` 검토안 | `infra/cdc/` |
| 검증된 조합 | **서버와 같은 pgvector digest(PG17)** + `apache/kafka:3.9.1` + `debezium/connect:2.7.3.Final` (`cdc-poc/consumer-recovery`·`snapshot-boundary`). `postgres:17.6`에서도 같은 2.7.3으로 컬럼 단위 SELECT 권한만 준 초기 스냅샷까지 확인(`cdc-poc/document-capture`) | `infra/cdc-poc/*/compose.yaml` |
| 배포 스크립트 | `deploy.sh`가 본 compose를 `up -d --remove-orphans --wait`로 올림. 헬스 실패 시 로그를 남기고 멈출 뿐 자동 롤백은 없음 | `infra/deploy/deploy.sh` |

**알아 둘 성질** — Kafka 메시지는 "DB를 다시 보라"는 힌트일 뿐이고, 처리 대상은 DB 상태로 정한다.
consumer를 켠 뒤에는 메시지가 사라져도 2초 주기 복구 스캔이 오래된 PENDING을 찾는다. 그래서 **켠 뒤** CDC가
멈추면 지연이 생기지 데이터가 사라지지는 않는다(#227 문서 6번). 아래 제안은 이 성질을 근거로 dev 구성을
단순하게 가져간다.

## 3. 결정 제안

### D1. 배포 위치 — 별도 compose 프로젝트 `beez-cdc` (승인: 전진·팀) → 294

- **제안**: `infra/cdc/` 아래에 **dev용 compose 파일을 새로** 두고, Kafka·Connect를 본 스택과 다른 프로젝트
  `beez-cdc`로 띄운다. 기존 `infra/cdc/compose.yaml`(운영 검토안)은 고치지 않는다. 서버 경로는 `/opt/beez/cdc`이고,
  배포는 모니터링 스택처럼 손으로 한다. 본 스택 네트워크 `beez_default`에 external로 붙어 `postgres`·`backend`와
  이름으로 통신한다. 모니터링 스택이 이미 같은 방식으로 붙어 있다.
- **이유**: 본 compose에 넣으면 dev 머지 때마다 CD가 Kafka까지 다시 만든다. 다른 compose 파일을 같은 프로젝트
  이름(`beez`)으로 올리면 `deploy.sh`의 `--remove-orphans`가 Kafka·Connect를 지운다. 이 옵션은 같은 프로젝트
  라벨의 컨테이너만 지우므로 `beez-cdc`는 지우지 않는다.
- **대안**: 본 compose에 profile로 추가. CD와 한 몸이 되어 위 위험이 생기므로 택하지 않는다.
- **CD**: `infra/cdc/**`를 `cd.yml` `paths-ignore`에 넣는다(모니터링과 같은 방식).
- **주의**: `beez-cdc`가 `beez_default`에 붙어 있는 동안 본 스택을 `docker compose down`하면 네트워크가 지워지지
  않는다. 본 스택을 내릴 일이 있으면 `beez-cdc`부터 내린다.

### D2. Kafka·Connect 구성 (승인: 전진) → 294

| 항목 | 제안 |
|---|---|
| Kafka | `apache/kafka:3.9.1` 단일 broker, KRaft(controller 겸용), 데이터는 named volume |
| 브로커 내부 토픽 | `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`·`KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`·`KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` 모두 1. env를 주면 Kafka 기본값(3)이 적용돼 단일 broker에서 consumer group이 붙지 못한다(PoC도 1로 줌) |
| 토픽 자동 생성 | 끔. 접수 토픽은 294에서 손으로 만든다(아래) |
| 접수 토픽 | `beez.public.published_change_analysis`, 파티션 1, 복제 1, `retention.ms` 7일 |
| Connect 이미지 | **`debezium/connect:2.7.3.Final`, digest 고정** (아래 선택지 참고) |
| Connect 구성 | distributed 모드, **worker 1개**. config·offset·status 토픽은 compact, 복제 1 |
| 메모리 | 힙 각 512MB, 컨테이너 상한 각 1GB(합계 2GB, 사용 가능 13.4GB 대비) |
| 외부 포트 | **없음**. 호스트에 publish 하지 않는다 |

- **Connect 버전 선택지**
  - **(제안) 2.7.3 유지**: 서버와 같은 pgvector PG17 이미지에서 PoC 여러 개가 이 버전으로 돌았다. 단점은
    Debezium 2.7의 공식 지원표에 PG17이 없다는 것이다. 문서에 한계로 적는다.
  - 3.x로 올림: 공식 지원은 받지만 검증되지 않은 조합이 되고, 이미지 저장소가 Docker Hub에서 `quay.io/debezium`으로
    바뀐다. 294 안에서 PoC를 다시 돌려야 한다.
- **worker 1개 전제**: REST를 컨테이너 안 127.0.0.1에만 여는 것(D3)은 worker가 하나라 leader로 요청을 넘길 일이
  없어서 성립한다. worker를 늘리면 `rest.advertised.*`와 REST 노출을 다시 정해야 한다.
- 단일 broker라 broker가 죽으면 전달이 멈춘다. 위 성질대로 복구 스캔이 받쳐 주므로 dev에서는 수용한다.

### D3. 보안 — dev는 Docker 내부망 PLAINTEXT (승인: **양지훈**, 검토안과 다름) → 294

- **제안**: dev에서는 Kafka를 TLS·SASL 없이 Docker 내부망에서만 쓴다. Connect→PostgreSQL도 같은 내부망이라
  `sslmode=disable`로 둔다. 보안 경계는 244 결정 2번과 같게 **외부 인터넷 차단**이다.
- **검토안과 다른 점**: `OPERATIONS.md`는 운영에 SSL/SASL_SSL과 `verify-full`을 요구한다. 이 제안은 dev 서버 한정
  예외이고, 운영 서버를 따로 만들 때 검토안 기준으로 다시 정한다. 문서에 예외로 남긴다.
- **줄이는 위험**
  - Connect REST(커넥터 설정 변경 가능)는 `listeners=http://127.0.0.1:8083`으로 **컨테이너 안에서만** 열고,
    관리는 `docker exec`로 한다.
  - 복제 계정은 전용(`beez_cdc`)으로 만들고, 비밀번호는 서버 `/opt/beez/.env.cdc`(600)와 Connect file config
    provider로만 넘긴다. 저장소·로그·명령 이력에 남기지 않는다.
- **남는 위험 (수용 여부를 함께 승인한다)**: Kafka는 `beez_default`에 붙은 모든 컨테이너(ai·backend·seaweedfs·postgres,
  모니터링의 prometheus·postgres-exporter)가 **읽고 쓸 수 있다.**
  - Connect 설정 토픽에 직접 쓰면 REST를 막은 효과를 우회해 커넥터를 바꿀 수 있다. 복제 권한은 publication과
    상관없이 WAL 전체를 읽을 수 있는 강한 권한이다(검토안 4번). 그래서 컨테이너 하나가 뚫리면 DB 변경 내용 전체가
    새어 나갈 수 있다.
  - 접수 토픽에 없는 id를 넣으면 consumer는 그 레코드를 건너뛰지 않고 1초 간격으로 끝없이 재시도하므로
    (`AnalysisIntakeKafkaConfiguration`의 `FixedBackOff(1000, Long.MAX_VALUE)`) 파티션이 막힌다. 처리는 복구 스캔이
    이어 가므로 분석이 사라지지는 않는다.
  - dev 데이터는 실사용자 데이터가 아니고, 이 경로는 같은 호스트의 컨테이너가 먼저 뚫려야 열린다. 그래서 dev에서는
    수용을 제안한다.
- **대안**
  - dev에도 TLS 적용: 인증서 발급·갱신과 trust/key store 관리가 늘어난다. 얻는 것은 같은 호스트 안 컨테이너 사이
    도청 방지뿐이라 택하지 않는다.
  - Kafka·Connect를 전용 망에 두고 backend만 그 망에도 붙이기: 위 노출이 backend 하나로 줄어든다. 대신 본 compose가
    `beez-cdc`의 망을 external로 요구하게 돼, 망이 없으면 CD가 실패한다. 운영 서버를 만들 때 다시 본다.

### D4. PostgreSQL 설정과 재시작 (승인: 전진·**팀(재시작 시각)**) → 293

- **제안**: `infra/compose.yaml` postgres에 `command`를 추가한다.
  - `wal_level=logical`(필수, **재시작 필요**)
  - `max_slot_wal_keep_size=10GB` — 슬롯이 멈춰도 WAL이 디스크를 다 먹지 않게 상한을 둔다. 지금 WAL 생성량
    (1주에 약 19MB)으로는 닿을 일이 거의 없고, 디스크 여유(296GB)에 비해 작다. 넘으면 슬롯이 무효화돼
    재스냅샷이 필요하지만, 복구 스캔 덕분에 분석 누락은 없다.
  - `max_replication_slots`·`max_wal_senders`는 지금 값(10)으로 충분해 바꾸지 않는다.
- **주의**: compose의 postgres 정의가 바뀌면 그 PR이 dev에 머지될 때 **CD가 postgres를 다시 만든다**
  (`deploy.sh`의 `up -d`). 그래서 293 PR의 **머지 시각 = 재시작 시각**이다. 머지 시각을 팀에 미리 공지하고 그때
  머지한다. 재시작은 수 초~수십 초이고 볼륨은 유지된다.
- **되돌리기 전제**: 논리 복제 슬롯이 남아 있는 채로 `wal_level`을 `replica`로 내리면 PostgreSQL이
  `logical replication slot "…" exists, but wal_level < logical`로 **기동을 거부한다.** `command`를 되돌리는 PR은
  슬롯을 지운 뒤에만 머지한다(§4 되돌리기 순서).
- **대안**: `ALTER SYSTEM`으로 서버에서만 바꾸고 손으로 재시작. 재시작 시각은 자유롭지만 설정이 저장소 밖(볼륨 안
  `postgresql.auto.conf`)에 남아 로컬과 서버가 어긋난다. 택하지 않는다.

### D5. 복제 계정·publication·슬롯 (승인: **양지훈**) → 293

- publication: 검토안 [`intake.publication.review.sql`](../../infra/cdc/intake.publication.review.sql) 그대로
  `beez_published_analysis_cdc`(컬럼 `id`, `status`만). 테이블 소유자 계정으로 **손으로** 만든다(Flyway 아님).
- 복제 계정 `beez_cdc`: `LOGIN REPLICATION`과 초기 스냅샷용 `SELECT (id, status)`만 준다. 이 권한 조합으로
  초기 스냅샷이 도는 것은 `cdc-poc/document-capture`에서 확인했다.
- 슬롯 이름은 `beez_published_analysis`, `publication.autocreate.mode=disabled`. 슬롯은 293이 아니라 295에서
  커넥터를 등록할 때 생긴다.
- **DELETE 금지**: consumer는 DELETE 이벤트를 건너뛰지 않고 재시도하므로(`AnalysisIntakeEventPolicy`), 접수행을
  손으로 지우면 그 파티션이 영구히 막힌다. 앱에는 삭제 경로가 없다(FK에 cascade 없음). 런북에 "접수행 삭제 금지"를
  적는다.
- 착수 전 `python infra/cdc/check_contract.py`로 컬럼 목록이 현재 스키마와 맞는지 확인한다.

### D6. 커넥터 설정 (승인: **양지훈**) → 295

[`intake.connector.review.json`](../../infra/cdc/intake.connector.review.json)에서 자리표시자만 바꾼다.

| 키 | 값 |
|---|---|
| `database.hostname` / `dbname` | `postgres` / 서버 env의 `DB_NAME`(기본 `beez`) |
| `database.sslmode` | `disable` (D3). `database.sslrootcert`는 뺀다 |
| `topic.prefix` | `beez` → 토픽 `beez.public.published_change_analysis`(294에서 미리 만든 토픽) |
| `slot.name` | `beez_published_analysis` |
| `snapshot.mode` | `initial` 유지. 지금 접수 테이블은 0행이라 스냅샷 부담이 없다 |
| `tombstones.on.delete` | `true` 유지(consumer가 tombstone을 처리함) |
| 그 밖의 키 | 검토안 그대로 |

- **커넥터를 다시 만들 때**: distributed 모드에서는 커넥터를 지워도 offset 토픽에 마지막 LSN이 남는다. 같은 이름으로
  다시 등록하면 새 슬롯에 옛 LSN으로 이어 붙으려 한다. 슬롯을 지웠거나 슬롯이 무효화된 뒤에는 **새 커넥터 이름으로
  등록하거나**, 커넥터를 멈춘 뒤 `DELETE /connectors/{name}/offsets`(Kafka 3.6 이상)로 offset을 지우고 등록한다.

### D7. backend 켜기 (승인: **양지훈**) → 295

- `infra/compose.yaml` backend `environment`에 아래를 **기본값 꺼짐**으로 추가하고, 켜는 값은 서버 env 파일에만
  둔다. 로컬·CI는 지금처럼 꺼진 채로 돈다. 환경변수 이름은 Spring이 `beez.cdc.intake.bootstrap-servers` 같은
  속성으로 바꿔 읽는다(`@Value`에도 적용).

  | env | 서버 값 |
  |---|---|
  | `BEEZ_CDC_INTAKE_ENABLED` | `true` (기본 `false`) |
  | `BEEZ_CDC_INTAKE_BOOTSTRAP_SERVERS` | `kafka:9092` |
  | `BEEZ_CDC_INTAKE_GROUP_ID` | `beez-published-analysis` |
  | `BEEZ_CDC_INTAKE_TOPIC` | `beez.public.published_change_analysis` |

- `beez.cdc.kafka-security.properties`는 비운다(D3). 비우면 PLAINTEXT로 동작하는 것이 현재 코드다
  (`CdcKafkaSecurityProperties`).
- **켜는 순서**: 커넥터·task RUNNING과 슬롯 `active`를 확인한 **뒤** 서버 env를 바꾸고 backend를 재배포한다.
  반대 순서면 consumer가 연결 실패를 계속 재시도한다.
- **켜는 순간 밀린 분석이 한꺼번에 돈다**: 켜기 전까지 쌓인 PENDING 접수행은 켜자마자 복구 스캔이 집어 AI를
  호출한다. 켜기 직전에 PENDING 개수를 확인하고 결과에 적는다(11-05 기준 0행).
- **헬스체크 영향 없음**: backend 헬스 설정에 Kafka 항목이 없고(`application.yml` `management.health`), spring-kafka
  기본값 `missingTopicsFatal=false`라 broker가 없어도 기동한다. Kafka가 죽어도 `/actuator/health`는 DB 상태만
  따른다. 295에서 Kafka를 멈춘 상태로 backend를 재시작해 실제로 확인한다.

### D8. 일정 (승인: 팀·양지훈)

- Sprint4는 11-09에 끝나고 11-10부터 통합 검증만 한다.
- **제안**: 이 계획은 이번 주에 승인받고, 실행(293·294·295)은 **229 통합 검증의 첫 단계**로 넣는다. 229 담당
  (양지훈님)과 실행 날짜를 맞춘다. 293과 294는 동시에 진행할 수 있다.
- **판단할 것**: §1대로 295가 끝나기 전까지 dev에서는 발행 후 영향 분석이 돌지 않는다. 그 기능에 기대는 다른 통합
  검증 항목이 있으면 그 항목은 295 뒤로 미뤄야 한다. 팀이 함께 확인한다.
- 대안: 11-09 전에 실행. 재시작 공지와 검증 시간을 생각하면 촉박하다.

## 4. 실행 순서 (승인 뒤, 작업 티켓별 PR)

각 단계는 기존 인프라 작업 방식대로 한다. 명령마다 어느 셸에서 칠지 적어 주면 전진이 실행하고, 결과는 읽기
전용으로 검증해 런북에 남긴다.

| # | 티켓 | 단계 | 확인 |
|---|---|---|---|
| 1 | 293 | PR: `infra/compose.yaml` postgres `command`(D4), 런북. 공지한 시각에 머지 → CD가 postgres 재생성 | `SHOW wal_level` = `logical`, backend healthy |
| 2 | 293 | 복제 계정·publication 생성(D5) | `pg_publication_tables`, `beez_cdc`로 다른 컬럼 SELECT 거부 |
| 3 | 294 | PR: dev용 compose·Connect 설정 템플릿, `cd.yml` paths-ignore, 런북. `beez-cdc` 기동(D1~D3) | 컨테이너 healthy, `ss -tlnp`에 새 포트 없음, backend·ai에서 Connect REST 안 닿음 |
| 4 | 294 | 접수 토픽 생성(D2) | `kafka-topics --describe` |
| 5 | 295 | PR: backend env 기본 꺼짐(D7). 커넥터 등록(D6) | connector·task RUNNING, 슬롯 `active` |
| 6 | 295 | PENDING 개수 확인 → 서버 env로 consumer 켜고 backend 재배포(D7) | `consumer_enabled`=1, Kafka 수신 실패 0 |
| 7 | 295 | 변경 제안 1건 발행 | 접수행 PENDING → COMPLETED, 대시보드 `beez_cdc_*` |
| 8 | 295 | 재시작 시험: backend·Connect·Kafka 각각, Kafka를 멈춘 채 backend 재시작 | 유실 없이 이어짐, backend healthy |

1~2(293)와 3~4(294)는 순서가 없다. 7·8은 229 장애 시험과 범위가 겹치므로 229와 함께 설계한다.

**되돌리기는 반드시 역순으로 한다.** 앞 단계를 먼저 되돌리면 다음 문제가 생긴다.

- 슬롯이 남은 채로 `wal_level`을 내리면 PostgreSQL이 기동하지 않는다.
- 슬롯이 남은 채로 Connect만 멈추면 WAL이 쌓인다.

| 순서 | 되돌리는 것 | 방법 |
|---|---|---|
| 1 | consumer (6) | 서버 env를 `false`로 되돌리고 backend 재배포 |
| 2 | 커넥터 (5) | 커넥터 삭제. 다시 만들 계획이면 offset도 지운다(D6) |
| 3 | 슬롯 (5) | `SELECT pg_drop_replication_slot('beez_published_analysis')`, `pg_replication_slots`에서 사라졌는지 확인 |
| 4 | Kafka·Connect (3·4) | `docker compose -p beez-cdc down`(볼륨 유지) |
| 5 | publication·계정 (2) | `DROP PUBLICATION beez_published_analysis_cdc`, `DROP ROLE beez_cdc` |
| 6 | `wal_level` (1) | 슬롯이 0개인 것을 확인한 뒤 `command` 제거 PR을 공지한 시각에 머지 |

## 5. 위험과 한계

- **단일 서버·단일 broker**: 서버나 broker가 죽으면 CDC가 멈춘다. 이때 복구 스캔이 처리를 이어 가지만, 모니터링도
  같은 서버라 알림이 가지 않는다(263 런북 한계와 같음).
- **WAL 보유**: 슬롯이 멈추면 WAL이 쌓인다. 10GB 상한(D4)으로 디스크는 지키지만, 넘으면 슬롯이 무효화돼 커넥터를
  새 이름(또는 offset 삭제)으로 다시 만들어야 한다(D6). 슬롯·WAL 알림은 261에서 붙인다. 그 전까지는 런북의 수동
  확인 쿼리로 본다.
- **D3 예외**: 같은 망의 컨테이너가 Kafka를 읽고 쓸 수 있다(D3 "남는 위험"). 토픽에는 접수행 id·status만 있지만,
  Connect 설정 토픽을 통해 복제 권한이 노출될 수 있다.
- **Connect 버전**: 2.7.3은 PG17 공식 지원 범위 밖이다(D2). 서버와 같은 이미지에서 PoC로 확인한 것이 근거다.
- **261 연결**: Connect REST를 컨테이너 안으로만 열면(D3) 261에서 Connect 상태를 수집할 방법(JMX exporter 등)을
  따로 정해야 한다.

## 6. 범위 밖

- 운영(prod) 서버 CDC와 TLS/SASL 구성
- 문서 원본 캡처(`connector.review.json`, #225 과거 실험)
- #223 색인 경로의 Kafka 소비 여부
- 수동 재처리 API(244 결정 6번, 별도)
- 앱 DB 계정 `beez`가 superuser인 문제(이 계획과 별개로 다룬다)

## 7. 승인 체크리스트

- [ ] D1 별도 compose 프로젝트 `beez-cdc`, dev용 compose 파일 신설, 수동 배포 — 전진·팀
- [ ] D2 Kafka 3.9.1 단일 broker(내부 토픽 복제 수 1, 토픽 수동 생성), Connect 2.7.3 유지 또는 3.x 재검증 — 전진
- [ ] D3 dev 한정 PLAINTEXT·`sslmode=disable` 예외, Connect REST 컨테이너 안에서만, **같은 망 컨테이너의 Kafka 쓰기 노출 수용** — **양지훈**
- [ ] D4 compose `command`로 `wal_level=logical`·`max_slot_wal_keep_size=10GB`, 293 머지 시각 = 재시작 시각 — 전진·**팀**
- [ ] D5 publication·복제 계정·슬롯 이름, 접수행 삭제 금지 — **양지훈**
- [ ] D6 커넥터 값(토픽 이름·`snapshot.mode=initial`), 재생성 때 offset 처리 — **양지훈**
- [ ] D7 backend env 이름·기본 꺼짐·켜는 순서, 밀린 PENDING 처리 — **양지훈**
- [ ] D8 실행을 229 통합 검증 첫 단계로, 295 전까지 분석이 돌지 않는 영향 확인 — 팀·양지훈
