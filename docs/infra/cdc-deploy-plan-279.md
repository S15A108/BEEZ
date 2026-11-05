# dev 서버 CDC(Kafka·Debezium Connect) 배포 계획 — 제안 (S15P31A108-279)

> **상태: 승인됨(PR #147, 11-05 양지훈님 승인 뒤 머지). 팀 승인 몫(D1·D4·D8)의 기록은 §7을 따른다.** 실행 결과는 티켓별 런북(293 `cdc-postgres.md`, 294 `cdc-stack.md`)에 적는다.
> 승인 범위는 아래 [결정 제안](#3-결정-제안) D1~D8이다. 계약·consumer 관련(D3·D5·D6·D7)은 양지훈님,
> PostgreSQL 재시작 시각(D4)과 일정(D8)은 팀이 승인한다. 승인된 결정은 이 문서에서 "확정"으로 바꾼다.
>
> **티켓 구성** — 279는 이 계획과 승인만 맡는다. 실행은 별도 작업 티켓 세 개로 나눴고, 티켓마다 PR 하나다.
>
> | 티켓 | 범위 | 결정 | 선행 |
> |---|---|---|---|
> | S15P31A108-293 | PostgreSQL 논리 복제 준비(`wal_level`·복제 계정·publication·비밀 파일) | D4·D5·D3 일부 | 279 |
> | S15P31A108-294 | Kafka·Connect 스택 `beez-cdc` 배포(토픽 생성 포함) | D1·D2·D3 | 279 |
> | S15P31A108-298 | backend: 복구 스캔과 Kafka consumer 기동 분리·자동 재연결(Kafka 없어도 기동) | D7 | 295 리허설에서 재현될 때 |
> | S15P31A108-295 | 커넥터 등록과 backend consumer 켜기, 슬롯 알림, 발행 1건·재시작 확인 | D6·D7 | 293·294(·298) |
>
> 293과 294는 서로 기다리지 않는다. 298은 295의 로컬 리허설에서 "Kafka가 없으면 backend가 뜨지 않는다"가 재현될 때
> 진행하고, 끝나야 295에서 consumer를 켠다(리뷰 반영, D7). 295가 끝나야 S15P31A108-261(Kafka·Connect·WAL 수집·알림)과
> S15P31A108-229(CDC 통합·장애 복구 검증)를 시작할 수 있다.

## 1. 목적

발행 접수행 `published_change_analysis`의 변경을 Debezium → Kafka → backend consumer로 전달한다.
계약은 [#227 연동 문서](../design/published-analysis-227.md), 운영 주의는 [CDC 운영 검토안](../../infra/cdc/OPERATIONS.md)이
정본이다. 이 문서는 그 검토안을 **dev 서버 한 대에 어떻게 올릴지**를 정한다.

**지금 dev에서는 발행 후 영향 분석이 아예 돌지 않는다.** 접수행 INSERT는 설정과 상관없이 들어가지만
(`PublishedAnalysisRepository.accept`), 이를 처리하는 복구 스캔(`PublishedAnalysisDispatcher`)은
`beez.cdc.intake.enabled=true`일 때만 생긴다. 켜는 설정은 Kafka 접속 정보(bootstrap·group·topic)도 비어 있지
않아야 한다(`AnalysisIntakeKafkaConfiguration`). 따라서 이 배포는 지연을 줄이는 일이 아니라 **dev에서 분석 기능을
처음 켜는 일**이다.

- **Kafka가 줄여 주는 시간은 약 30초다.** 복구 스캔은 2초마다 돌지만, 요청된 지 30초가 지난 PENDING만 집는다
  (`PublishedAnalysisRepository.findCandidates`의 `requested_at <= now - 30초`). 그래서 Kafka가 없거나 멈추면
  새 발행은 약 30~32초 뒤에 처리되고, Kafka가 있으면 바로 깨어난다.
- **검토했지만 택하지 않은 우회**: 이름은 풀리지만 broker가 없는 bootstrap 주소를 넣고 `enabled=true`로 켜면
  복구 스캔만 돌릴 수 있다(이름이 아예 안 풀리는 주소면 기동이 실패한다 — D7). 하지만 consumer가 연결 실패 경고를 계속 남기고, `beez_cdc_analysis_consumer_enabled`가
  1이 돼 대시보드가 CDC가 켜진 것처럼 보인다. 코드가 의도한 구성이 아니므로(#227 문서 "소비자만 따로 켜는 구성은
  지원하지 않는다"의 반대 경우) dev에서도 쓰지 않는다. 일정이 밀려 분석을 먼저 돌려야 하면 D8에서 다시 정한다.

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
| 검증된 조합 | **서버와 같은 pgvector digest(PG17)** + `apache/kafka:3.9.1` + `debezium/connect:2.7.3.Final` (`cdc-poc/consumer-recovery`·`snapshot-boundary`, 둘 다 `snapshot.mode=no_data`). `postgres:17.6`에서 같은 2.7.3으로 컬럼 단위 SELECT 권한만 준 초기 스냅샷까지 확인(`cdc-poc/document-capture`, snapshot override 없음). **실제로 쓸 조합(pgvector 이미지 + 접수 테이블 publication + snapshot override + `SELECT (id, status)`)은 함께 돌려 본 적이 없다** → 295에서 먼저 로컬 리허설 | `infra/cdc-poc/*/compose.yaml` |
| Connect 이미지 | `debezium/connect:2.7.3.Final`은 `uid=1001(kafka)`로 돌고, 안에 든 Kafka Connect는 3.7.0 | 로컬 `docker run … id` |
| 배포 스크립트 | `deploy.sh`가 본 compose를 `up -d --remove-orphans --wait`로 올림. 헬스 실패 시 로그를 남기고 멈출 뿐 자동 롤백은 없음 | `infra/deploy/deploy.sh` |

**알아 둘 성질** — Kafka 메시지는 "DB를 다시 보라"는 힌트일 뿐이고, 처리 대상은 DB 상태로 정한다.
consumer를 켠 뒤에는 메시지가 사라져도 복구 스캔이 30초 넘은 PENDING을 찾는다. 그래서 **켠 뒤** CDC가
멈추면 약 30초의 지연이 생기지 데이터가 사라지지는 않는다(#227 문서 6번). 아래 제안은 이 성질을 근거로 dev 구성을
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
- **주의 — 내리는 순서**: consumer를 켠 뒤에는 Kafka 컨테이너가 없으면 backend가 기동하지 못한다(D7). 그래서
  **`beez-cdc`를 내리거나 Kafka를 멈추기 전에는 반드시 consumer부터 끈다**(§4 되돌리기 1번). consumer가 켜진 채
  Kafka가 내려가 있는 동안 dev 머지가 들어오면, CD가 새로 만든 backend가 뜨지 못해 dev가 멈춘다.
  - 본 스택을 `docker compose down`할 일이 있으면 consumer 끄기 → `beez-cdc` 내리기 → 본 스택 내리기 순서로 한다.
    `beez-cdc`가 `beez_default`에 붙어 있으면 본 스택을 내려도 네트워크가 지워지지 않는다.
  - 서버 재부팅 때는 두 스택이 동시에 뜬다. backend가 Kafka보다 먼저 뜨면 기동에 실패하지만
    `restart: unless-stopped`로 다시 시도해 Kafka가 뜬 뒤 정상이 된다. 재부팅 뒤에는 backend가 healthy인지 본다.

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

### D3. 보안 — dev는 Docker 내부망 PLAINTEXT (승인: **양지훈**, 검토안과 다름) → 294(비밀 파일 작성은 293)

- **제안**: dev에서는 Kafka를 TLS·SASL 없이 Docker 내부망에서만 쓴다. Connect→PostgreSQL도 같은 내부망이라
  `sslmode=disable`로 둔다. 보안 경계는 244 결정 2번과 같게 **외부 인터넷 차단**이다.
- **검토안과 다른 점**: `OPERATIONS.md`는 운영에 SSL/SASL_SSL과 `verify-full`을 요구한다. 이 제안은 dev 서버 한정
  예외이고, 운영 서버를 따로 만들 때 검토안 기준으로 다시 정한다. 문서에 예외로 남긴다.
- **줄이는 위험**
  - Connect REST(커넥터 설정 변경 가능)는 `listeners=http://127.0.0.1:8083`으로 **컨테이너 안에서만** 열고,
    관리는 `docker exec`로 한다.
  - 복제 계정은 전용(`beez_cdc`)으로 만들고, 비밀번호는 서버 파일 하나와 Connect file config provider로만 넘긴다.
    저장소·로그·명령 이력에 남기지 않는다. 파일 배치는 아래 "비밀 파일"을 따른다.
- **비밀 파일**: 바인드 마운트는 호스트 파일 권한을 그대로 쓰는데, Connect 컨테이너는 `uid=1001(kafka)`로 돈다.
  600으로 두면 Connect가 읽지 못해 커넥터 등록이 실패한다(모니터링의 Mattermost 웹훅 파일과 같은 함정,
  `infra/monitoring/compose.yaml`의 secrets 주석).
  - 위치는 **스택 폴더 밖** `/opt/beez/.cdc-secrets/`이다. 스택 폴더 `/opt/beez/cdc`는 모니터링처럼 올릴 때마다
    통째로 바뀌므로(`docs/infra/monitoring.md` "올리기·업데이트"의 `mv … .old`), 그 안에 두면 다음 올리기에서
    비밀 파일이 밀려나고 지워진다. 모니터링 웹훅 파일도 같은 이유로 `/opt/beez/` 바로 아래에 있다.
  - 디렉터리는 `root:1001` `750`, 파일 `database.properties`(`password=…` 한 줄)는 `root:1001` `640`으로 둔다.
    컨테이너에는 디렉터리를 읽기 전용으로 붙인다(`/run/cdc-secrets`).
  - 파일은 **293**에서 복제 계정을 만들 때 비밀번호와 함께 쓴다(디렉터리가 없으면 293이 위 권한으로 만든다).
    294는 디렉터리 마운트와 provider 설정만 한다. provider는 두 줄이 다 있어야 생긴다:
    `CONNECT_CONFIG_PROVIDERS=file`, `CONNECT_CONFIG_PROVIDERS_FILE_CLASS=org.apache.kafka.common.config.provider.FileConfigProvider`
    (검토안 `infra/cdc/worker.properties.template`과 같다). provider는 커넥터를 등록할 때(295) 파일을 읽으므로,
    293과 294 중 어느 쪽이 먼저 끝나도 된다. 294를 먼저 올리면 디렉터리가 없어 Docker가 root 소유 빈 디렉터리를
    만들 수 있으므로, 294가 디렉터리부터 위 권한으로 만든다.
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
  (`deploy.sh`의 `up -d`). 재시작은 머지 때 바로가 아니라, CD가 이미지를 만들고 Jenkins가 배포하는 **머지 약
  3분 뒤**에 일어난다. 새 SHA 이미지라 backend·ai도 함께 다시 뜨고, backend는 헬스가 healthy가 될 때까지 최대
  60초(`start_period`)가 걸린다. 그래서 **공지 창은 "머지 시각부터 10분"**으로 잡고, 그 시각에 머지한 뒤 배포가 끝날
  때까지 지켜본다. 볼륨은 유지된다.
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
- **계약 확인은 DB를 직접 조회한다.** `infra/cdc/check_contract.py`는 문서 캡처용 검토안(`connector.review.json`·
  `publication.review.sql`)만 검사하고 접수 테이블은 보지 않는다. 그래서 돌려도 이 계획을 확인한 것이 아니다.
  - 착수 전: `\d published_change_analysis`에 `id`·`status`가 있고 replica identity가 default(PK `id`)인지 본다.
  - 생성 후: `SELECT attnames FROM pg_publication_tables WHERE pubname = 'beez_published_analysis_cdc'`가 `{id,status}`인지 본다.

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
| `heartbeat.interval.ms` | **넣지 않는다**(아래) |
| 그 밖의 키 | 검토안 그대로(`snapshot.select.statement.overrides` 포함) |

- **heartbeat를 쓰지 않는 이유**: 접수 테이블은 거의 바뀌지 않는다. PG15 이상 pgoutput은 빈 트랜잭션을 보내지
  않으므로, 다른 테이블에 쓰기가 있는 동안 슬롯의 보유 WAL이 그만큼 늘어난다. 하지만 dev WAL 생성량은 1주에 약
  19MB라 10GB 상한(D4)에 닿지 않는다. heartbeat를 켜면 heartbeat 토픽(`__debezium-heartbeat.beez`)을 손으로 더
  만들어야 하고(D2 자동 생성 끔), 슬롯을 확실히 앞당기려면 DB에 쓰는 `heartbeat.action.query`와 그 테이블을
  publication에 넣어야 한다. 복제 계정을 읽기 전용·컬럼 2개로 묶어 둔 D5와 부딪히므로 dev에서는 쓰지 않는다.
  대신 WAL 알림은 "증가 추세"가 아니라 **보유량 절댓값과 슬롯 비활성 지속 시간**으로 건다(아래).
- **슬롯·Connect 감시 (리뷰 반영)**: 복구 스캔이 처리를 이어 가므로 Connect가 멈춰도 분석 지연 알림은 울리지 않는다.
  그래서 Connect 장애와 WAL 누적은 따로 감시한다.
  - **슬롯·WAL — 295에서 켠다**: 기존 postgres-exporter의 `replication_slots` 수집기가 서버에서 이미 돌고 있다
    (`pg_scrape_collector_success{collector="replication_slots"}` = 1). 그래서 Connect REST 없이 수집된다. 295에서
    모니터링 규칙에 알림 두 개를 더한다: 슬롯이 10분 넘게 비활성, 보유 WAL 1GB 초과.
  - **Connect·task 상태 — 261에서 켠다**: Connect JMX 지표를 Prometheus 형식으로 내보내 connector·task 실패 수와
    Debezium 연결 여부를 수집한다. debezium 이미지에서 되는지는 261에서 확인한다. 수집 자체가 실패하면 기존
    `MonitoringTargetDown`처럼 `up == 0`이나 지표 누락으로 알린다.
  - **261 전까지 수동 확인**: 전진이 평일 오전 1회와 배포·재시작 뒤에 커넥터 상태(`docker exec`로 REST 조회)와 슬롯
    쿼리를 본다. task FAILED면 즉시 원인을 보고 재시작이나 재등록한다. WAL이 1GB를 넘으면 원인을 확인하고,
    5GB를 넘으면 §4 되돌리기 1~3단계를 실행한다.
- **커넥터를 다시 만들 때**: distributed 모드에서는 커넥터를 지워도 offset 토픽에 마지막 LSN이 남는다. 같은 이름으로
  다시 등록하면 새 슬롯에 옛 LSN으로 이어 붙으려 한다. 슬롯을 지웠거나 슬롯이 무효화된 뒤에는 둘 중 하나로 한다.
  - **새 커넥터 이름으로 등록한다**(기본). 옛 offset은 쓰이지 않고 남는다.
  - 같은 이름을 써야 하면, 커넥터를 **지우기 전에** `PUT /connectors/{name}/stop`으로 STOPPED 상태로 만들고
    `DELETE /connectors/{name}/offsets`로 offset을 지운 뒤 커넥터를 지운다. offset API는 커넥터가 STOPPED 상태로
    남아 있어야 쓸 수 있다(pause로는 안 된다). 이미지에 든 Kafka Connect 3.7.0이 이 API(3.6 이상)를 지원한다.

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
  Kafka 컨테이너가 없을 때 켜면 backend가 뜨지 못하고(아래 "헬스체크와 기동 영향"), 커넥터가 아직 없으면 토픽에
  아무것도 오지 않아 연결 확인이 안 된다.
- **켜는 순간 밀린 분석이 한꺼번에 돈다**: 켜기 전까지 쌓인 PENDING 접수행은 켜자마자 복구 스캔이 집어 AI를
  호출한다. 켜기 직전에 PENDING 개수를 확인하고 결과에 적는다(11-05 기준 0행).
- **헬스체크와 기동 영향** — Kafka가 어떻게 죽었는지에 따라 다르다.
  - **Kafka 컨테이너는 있고 broker만 응답하지 않을 때**: backend 헬스 설정에 Kafka 항목이 없고
    (`application.yml` `management.health`), spring-kafka 기본값 `missingTopicsFatal=false`라 기동한다.
    `/actuator/health`는 DB 상태만 따른다. 이미 떠 있는 backend는 Kafka가 멈추고 다시 떠도 계속 healthy다.
  - **Kafka 컨테이너가 멈췄거나 없을 때**: Docker 내장 DNS에서 `kafka` 이름이 빠진다. Kafka 클라이언트는 consumer를
    만들 때 bootstrap 주소 이름이 하나도 안 풀리면 `No resolvable bootstrap urls`로 실패한다(재점검에서
    kafka-clients로 실측). 그래서 이때 **새로 뜨는 backend는 기동에 실패한다**(Spring 기동까지 이어지는 것은 295에서
    확인). 이 때문에 D1 "내리는 순서"를 지킨다.
- **선행 조건 — Kafka와 무관하게 기동 (S15P31A108-298)**: "consumer부터 끈다"는 계획된 중지만 막는다. Kafka가
  예기치 않게 죽은 상태에서 backend 재시작이나 배포가 겹치면 backend 전체가 뜨지 못한다. 그래서 295 로컬 리허설에서
  Spring 기동 실패가 재현되면, consumer를 켜기 전에 298을 끝낸다.
  - 지금은 consumer와 복구 스캔이 같은 `beez.cdc.intake.enabled`에 묶여 있어 consumer만 끄면 스캔도 멈춘다.
    298은 둘의 기동을 나눈다. 단 "consumer만 켜고 스캔은 끈 구성"은 #227 규칙대로 계속 막는다.
  - 리스너는 `autoStartup=false`로 두고 주기적으로 시작을 다시 시도한다. 시작 실패는 앱 기동을 막지 않는다.
  - consumer가 실제로 돌고 있는지 지표를 두고, 켜져 있는데 5분 넘게 멈춰 있으면 알린다. 복구 스캔이 30초 안에
    처리를 이어 가므로 기존 `CdcPendingDelay`로는 consumer 장애가 보이지 않기 때문이다.
  - 298이 끝나면 위 두 번째 경우도 "기동하고 복구 스캔으로 처리, Kafka가 돌아오면 자동 연결"로 바뀐다. 295 재시작
    시험(§4 9단계)은 이 기준으로 확인한다.

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
| 1 | 293 | PR: `infra/compose.yaml` postgres `command`(D4), 런북. 공지한 시각에 머지 → 약 3분 뒤 CD가 postgres 재생성 | `SHOW wal_level` = `logical`, backend healthy |
| 2 | 293 | 복제 계정·publication 생성, 비밀 파일 배치(D3·D5) | `pg_publication_tables.attnames` = `{id,status}`, `beez_cdc`로 다른 컬럼 SELECT 거부, `/opt/beez/.cdc-secrets/database.properties` `root:1001 640` |
| 3 | 294 | PR: dev용 compose·Connect 설정 템플릿, `cd.yml` paths-ignore, 런북. `beez-cdc` 기동(D1~D3) | 컨테이너 healthy, `ss -tlnp`에 새 포트 없음, backend·ai에서 Connect REST 안 닿음, 293이 끝났으면 `docker exec <connect> test -r /run/cdc-secrets/database.properties`(내용은 출력하지 않음) |
| 4 | 294 | 접수 토픽 생성(D2) | `kafka-topics --describe` |
| 5 | 295 | **로컬 리허설**: 서버와 같은 pgvector digest + `debezium/connect:2.7.3.Final`로 이 계획의 publication·계정 권한·커넥터 설정(override 포함)·file config provider를 그대로 돌림. 파일 권한 함정은 Windows 바인드 마운트로 재현되지 않으므로 서버 3단계에서 따로 본다. 이어서 이름이 안 풀리는 bootstrap 주소로 consumer를 켜고 backend를 띄워 Spring 기동 실패가 재현되는지 본다 | 초기 스냅샷 `r`과 이후 `c`/`u`가 id·status만 담아 도착, provider로 비밀번호를 읽어 접속. 기동 실패가 재현되면 298을 끝낸 뒤 7단계로 |
| 6 | 295 | PR: backend env 기본 꺼짐(D7), 슬롯 알림 두 개(D6). 서버에 커넥터 등록(D6) | connector·task RUNNING, 슬롯 `active`, 슬롯 알림 규칙 시험 통과 |
| 7 | 295 | (재현됐다면 298 완료 확인) PENDING 개수 확인 → 서버 env로 consumer 켜고 backend 재배포(D7) | `consumer_enabled`=1, Kafka 수신 실패 0 |
| 8 | 295 | 변경 제안 1건 발행 | `started_at - requested_at` < 30초(Kafka 경로로 깨어남. COMPLETED까지는 AI 시간이 더해지므로 기준으로 쓰지 않음), 최종 COMPLETED, 대시보드 `beez_cdc_*` |
| 9 | 295 | 재시작 시험: backend·Connect·Kafka 각각 재시작. 이어서 (가) Kafka 컨테이너는 둔 채 broker만 응답하지 않게 하고(`docker pause`, 이름은 계속 풀림) backend 재시작, (나) Kafka 컨테이너를 멈춘 채 backend 재시작 | 유실 없이 이어짐. (가)·(나) 모두 backend healthy, 그동안 PENDING은 복구 스캔으로 처리, Kafka를 다시 올리면 재시작 없이 consumer가 연결됨(298 반영 기준. 298 없이 진행한 경우 (나)는 기동 실패를 확인하고 Kafka를 올리면 스스로 healthy로 돌아오는지 본다) |

1~2(293)와 3~4(294)는 순서가 없다. 5는 293·294와 상관없이 언제든 할 수 있지만, 실패하면 6 이후를 멈추고
D2·D5·D6을 다시 정한다. 8·9는 229 장애 시험과 범위가 겹치므로 229와 함께 설계한다.

**되돌리기는 반드시 역순으로 한다.** 앞 단계를 먼저 되돌리면 다음 문제가 생긴다.

- consumer가 켜진 채로 Kafka를 내리면 그 뒤 새로 뜨는 backend가 기동하지 못한다(D7).
- 슬롯이 남은 채로 `wal_level`을 내리면 PostgreSQL이 기동하지 않는다.
- 슬롯이 남은 채로 Connect만 멈추면 WAL이 쌓인다.
- 커넥터를 지운 뒤에는 offset을 지울 수 없다(D6).
- 권한이 남은 계정은 `DROP ROLE`이 거부된다.

| 순서 | 되돌리는 것 | 방법 |
|---|---|---|
| 1 | consumer (7) | 서버 env를 `false`로 되돌리고 backend 재배포. 이때부터 분석이 다시 멈춘다(§1) |
| 2 | 커넥터 (6) | 같은 이름을 다시 쓸 계획이면 `PUT …/stop` → `DELETE …/offsets` → `DELETE /connectors/{name}`. 아니면 바로 삭제(다음엔 새 이름으로 등록) |
| 3 | 슬롯 (6) | 커넥터가 없어 슬롯이 `active = false`인 것을 본 뒤 `SELECT pg_drop_replication_slot('beez_published_analysis')`, `pg_replication_slots`에서 사라졌는지 확인 |
| 4 | Kafka·Connect (3·4) | `docker compose -p beez-cdc down`(볼륨 유지) |
| 5 | publication·계정·비밀 파일 (2) | `DROP PUBLICATION beez_published_analysis_cdc` → `REVOKE SELECT (id, status) ON published_change_analysis FROM beez_cdc` → `DROP ROLE beez_cdc` → `/opt/beez/.cdc-secrets/database.properties` 삭제 |
| 6 | `wal_level` (1) | `pg_replication_slots`가 0행인 것을 확인한 뒤 `command` 제거 PR을 공지한 시각에 머지 |

`infra/cdc/OPERATIONS.md`는 "slot, Connect offset, 내부 토픽을 독립적으로 삭제·초기화하지 않는다"고 한다. 이 표는
그 원칙을 따라 셋을 **정해진 순서로 함께** 정리하는 절차이고, dev 서버에서는 이 표가 검토안의 해당 문장을 대신한다.
운영 서버에서는 검토안대로 담당자 합의와 격리 환경 시험을 먼저 한다.

## 5. 위험과 한계

- **backend가 Kafka 컨테이너에 기동을 기댄다**: consumer를 켠 뒤에는 Kafka 컨테이너가 없으면 backend가 새로 뜨지
  못한다(D7). 계획된 중지는 consumer부터 끄는 순서(D1)로 막지만, 예기치 않은 Kafka 장애에 backend 재시작·배포가
  겹치면 막지 못한다. 이 결합은 S15P31A108-298(backend)에서 푼다(D7 선행 조건). 295 리허설에서 기동 실패가 재현되면 298 전까지는
  consumer를 켜지 않는다.
- **단일 서버·단일 broker**: 서버나 broker가 죽으면 CDC가 멈춘다. 이때 복구 스캔이 처리를 이어 가지만, 모니터링도
  같은 서버라 알림이 가지 않는다(263 런북 한계와 같음).
- **WAL 보유**: 슬롯이 멈추면 WAL이 쌓인다. 접수 테이블이 조용한 동안에도 다른 테이블 쓰기만큼 보유량이 늘어난다
  (D6 heartbeat). 10GB 상한(D4)으로 디스크는 지키지만, 넘으면 슬롯이 무효화돼 커넥터를 새 이름(또는 offset 삭제)으로
  다시 만들어야 한다(D6). 슬롯·WAL 알림은 295에서 postgres-exporter 지표로 켠다(D6 "슬롯·Connect 감시").
- **검증 공백**: 실제로 쓸 조합은 295의 로컬 리허설(§4 5단계)이 처음이다.
- **D3 예외**: 같은 망의 컨테이너가 Kafka를 읽고 쓸 수 있다(D3 "남는 위험"). 토픽에는 접수행 id·status만 있지만,
  Connect 설정 토픽을 통해 복제 권한이 노출될 수 있다.
- **Connect 버전**: 2.7.3은 PG17 공식 지원 범위 밖이다(D2). 서버와 같은 이미지에서 PoC로 확인한 것이 근거다.
- **261 연결**: Connect REST를 컨테이너 안으로만 열었으므로(D3) Connect·task 상태는 261에서 JMX 지표로 수집한다.
  그 전까지는 D6의 수동 확인으로 본다.

## 6. 범위 밖

- 운영(prod) 서버 CDC와 TLS/SASL 구성
- 문서 원본 캡처(`connector.review.json`, #225 과거 실험)
- #223 색인 경로의 Kafka 소비 여부
- 수동 재처리 API(244 결정 6번, 별도)
- 앱 DB 계정 `beez`가 superuser인 문제(이 계획과 별개로 다룬다)

## 7. 승인 체크리스트

- [ ] D1 별도 compose 프로젝트 `beez-cdc`, dev용 compose 파일 신설, 수동 배포 — 전진·팀 (팀 승인 기록 없음)
- [x] D2 Kafka 3.9.1 단일 broker(내부 토픽 복제 수 1, 토픽 수동 생성), Connect 2.7.3 유지 또는 3.x 재검증 — 전진 (PR #147 승인, 11-05)
- [x] D3 dev 한정 PLAINTEXT·`sslmode=disable` 예외, Connect REST 컨테이너 안에서만, **같은 망 컨테이너의 Kafka 쓰기 노출 수용**, 비밀 파일 `/opt/beez/.cdc-secrets/` `root:1001 640`(293이 작성) — **양지훈** (PR #147 승인, 11-05)
- [x] D1·D7 CDC를 내릴 때 consumer부터 끄는 규칙, Kafka 없을 때 기동 실패가 재현되면 S15P31A108-298(스캔·consumer 분리·자동 재연결)을 consumer 켜기 전에 끝냄 — 전진·**양지훈** (PR #147 승인, 11-05)
- [x] D6 슬롯 알림 두 개(비활성 10분, WAL 1GB)는 295에서, Connect 상태 수집은 261에서, 그 전까지 수동 확인(평일 오전·배포 뒤) — 전진·**양지훈** (PR #147 승인, 11-05)
- [ ] D4 compose `command`로 `wal_level=logical`·`max_slot_wal_keep_size=10GB`, 293 머지 시각부터 10분을 재시작 공지 창으로 — 전진·**팀** (팀 승인 기록 없음, 실행: 293 PR #155 11-05 14:47 KST 머지)
- [x] D5 publication·복제 계정·슬롯 이름, 접수행 삭제 금지, 계약 확인은 DB 직접 조회 — **양지훈** (PR #147 승인, 11-05)
- [x] D6 커넥터 값(토픽 이름·`snapshot.mode=initial`), heartbeat 미사용, 재생성은 새 이름 기본 — **양지훈** (PR #147 승인, 11-05)
- [x] D7 backend env 이름·기본 꺼짐·켜는 순서, 밀린 PENDING 처리 — **양지훈** (PR #147 승인, 11-05)
- [ ] D8 실행을 229 통합 검증 첫 단계로, 295 전까지 분석이 돌지 않는 영향 확인(스캔만 켜는 우회는 쓰지 않음) — 팀·양지훈 (팀 승인 기록 없음)
