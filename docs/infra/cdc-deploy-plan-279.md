# dev 서버 CDC(Kafka·Debezium Connect) 배포 계획 — 제안 (S15P31A108-279)

> **상태: 제안, 승인 대기.** 이 문서가 머지되기 전에는 서버·DB·compose를 바꾸지 않는다.
> 승인 범위: 아래 [결정 제안](#3-결정-제안) D1~D8. 계약·consumer 관련(D3·D5·D6)은 양지훈님,
> PostgreSQL 재시작 시간(D4)은 팀 확인이 필요하다. 승인된 결정은 이 문서를 고쳐 "확정"으로 바꾸고
> 실행은 별도 PR(같은 티켓)로 한다.

## 1. 목적

발행 접수행 `published_change_analysis`의 변경을 Debezium → Kafka → backend consumer로 전달해
발행 후 영향 분석이 접수 즉시 깨어나게 한다. 계약은 [#227 연동 문서](../design/published-analysis-227.md),
운영 주의는 [CDC 운영 검토안](../../infra/cdc/OPERATIONS.md)이 정본이다. 이 문서는 그 검토안을
**dev 서버 한 대에 어떻게 올릴지**를 정한다.

이 작업이 끝나야 S15P31A108-261(Kafka·Connect·WAL 수집·알림)과 S15P31A108-229(CDC 통합·장애 복구
검증)를 시작할 수 있다.

## 2. 현재 상태 (11-05 확인)

| 항목 | 상태 | 근거 |
|---|---|---|
| 서버 컨테이너 | backend·ai·postgres·seaweedfs + 모니터링 5개. **Kafka·Connect 없음** | `docker ps` |
| backend consumer | 꺼짐(`beez_cdc_analysis_consumer_enabled` = 0) | 244 대시보드 |
| PostgreSQL | 17.11(pgvector 이미지), `wal_level=replica`, `max_replication_slots`=10, `max_wal_senders`=10, `max_slot_wal_keep_size`=-1(무제한), DB 11MB | `SHOW` 읽기 |
| postgres 설정 위치 | `infra/compose.yaml`에 `command` 없음(이미지 기본값) | compose |
| 서버 자원 | 메모리 15.4GB 중 사용 가능 13.4GB, 4코어, 디스크 296GB 여유 | `free`·`df` |
| backend CDC 설정 | `beez.cdc.intake.enabled`·`bootstrap-servers`·`group-id`·`topic` 모두 있어야 기동. 켜면 2초 주기 복구 스캔도 같이 켜짐 | `AnalysisIntakeKafkaConfiguration`, #227 문서 |
| backend env 전달 | `infra/compose.yaml` backend `environment`에 **나열한 변수만** 들어감. CDC 변수는 아직 없음 | compose |
| 저장소 CDC 자료 | `infra/cdc/compose.yaml`은 Connect만 있고 broker 없음. 커넥터·publication은 `intake.*.review.*` 검토안 | `infra/cdc/` |
| 검증된 조합 | PoC는 PostgreSQL 16 + `apache/kafka:3.9.1` + `debezium/connect:2.7.3.Final` | `infra/cdc-poc/compose.yaml` |
| 배포 스크립트 | `deploy.sh`가 본 compose를 `up -d --remove-orphans`로 올림 | `infra/deploy/deploy.sh` |

**알아 둘 성질** — Kafka 메시지는 "DB를 다시 보라"는 힌트일 뿐이고, 처리 대상은 DB 상태로 정한다.
메시지가 사라져도 2초 주기 복구 스캔이 오래된 PENDING을 찾는다. 그래서 CDC가 멈추면 **지연이 생기지
데이터가 사라지지는 않는다**(#227 문서 6번). 아래 제안은 이 성질을 근거로 dev에서 구성을 단순하게
가져간다.

## 3. 결정 제안

### D1. 배포 위치 — 별도 compose 프로젝트 `beez-cdc` (승인: 전진·팀)

- **제안**: `infra/cdc/` 아래 compose로 Kafka·Connect를 **본 스택과 다른 프로젝트** `beez-cdc`로 띄운다.
  서버 경로는 `/opt/beez/cdc`, 배포는 모니터링 스택처럼 손으로 한다. 본 스택 네트워크 `beez_default`에
  external로 붙어 `postgres`·`backend`와 이름으로 통신한다.
- **이유**: 본 compose에 넣으면 dev 머지 때마다 CD가 Kafka까지 다시 만든다. 다른 compose 파일을 같은
  프로젝트 이름(`beez`)으로 올리면 `deploy.sh`의 `--remove-orphans`가 **Kafka·Connect를 지운다.**
- **대안**: 본 compose에 profile로 추가. CD와 한 몸이 되어 위 위험이 생기므로 택하지 않는다.
- CD: `infra/cdc/**`를 `cd.yml` `paths-ignore`에 넣는다(모니터링과 같은 방식).

### D2. Kafka·Connect 구성 (승인: 전진)

| 항목 | 제안 |
|---|---|
| Kafka | `apache/kafka:3.9.1`(PoC에서 쓴 버전) 단일 broker, KRaft(controller 겸용), 데이터는 named volume |
| Connect | PostgreSQL **17을 공식 지원하는** Debezium Connect 버전으로 고정(PoC의 2.7.3은 16으로 검증. 착수 때 릴리스 노트로 버전 확정, digest 고정) |
| 메모리 | 힙 각 512MB, 컨테이너 상한 각 1GB(합계 2GB, 사용 가능 13.4GB 대비) |
| 토픽 | `beez.public.published_change_analysis`, 파티션 1, 복제 1, 보존 7일(`retention.ms`), 자동 생성 끔 |
| Connect 내부 토픽 | config·offset·status 모두 compact, 복제 1 |
| 외부 포트 | **없음**. 호스트에 publish 하지 않는다 |

- 단일 broker라 broker가 죽으면 전달이 멈춘다. 위 성질대로 복구 스캔이 받쳐 주므로 dev에서는 수용한다.

### D3. 보안 — dev는 Docker 내부망 PLAINTEXT (승인: **양지훈**, 검토안과 다름)

- **제안**: dev에서는 Kafka를 TLS·SASL 없이 Docker 내부망에서만 쓴다. Connect→PostgreSQL도 같은
  내부망이라 `sslmode=disable`로 둔다. 보안 경계는 244 결정 2번과 같게 **외부 인터넷 차단**이다.
- **검토안과 다른 점**: `OPERATIONS.md`는 운영에 SSL/SASL_SSL, `verify-full`을 요구한다. 이 제안은
  dev 서버 한정 예외이고, 운영 서버를 따로 만들 때 검토안 기준으로 다시 정한다. 문서에 예외로 남긴다.
- **줄이는 위험**
  - Connect REST(커넥터 설정 변경 가능)는 `listeners=http://127.0.0.1:8083`으로 **컨테이너 안에서만**
    열고, 관리는 `docker exec`로 한다. 같은 네트워크의 ai·backend 컨테이너에서 닿지 않는다.
  - 복제 계정은 전용(`beez_cdc`)으로 만들고 비밀번호는 서버 `/opt/beez/.env.cdc`(600)와 Connect
    file config provider로만 넘긴다. 저장소·로그·명령 이력에 남기지 않는다.
- **대안**: dev에도 TLS를 적용. 인증서 발급·갱신과 trust/key store 관리가 늘고, 얻는 것은 같은 호스트
  안 컨테이너 사이 도청 방지뿐이라 dev에서는 택하지 않는다.

### D4. PostgreSQL 설정과 재시작 (승인: 전진·**팀(재시작 시간)**)

- **제안**: `infra/compose.yaml` postgres에 `command`를 추가한다.
  - `wal_level=logical`(필수, **재시작 필요**)
  - `max_slot_wal_keep_size=10GB` — 슬롯이 멈춰도 WAL이 디스크를 다 먹지 않게 상한을 둔다. 넘으면
    슬롯이 무효화돼 재스냅샷이 필요하지만, 복구 스캔 덕분에 분석 누락은 없다
  - `max_replication_slots`·`max_wal_senders`는 지금 값(10)으로 충분해 바꾸지 않는다
- **주의**: compose의 postgres 정의가 바뀌면 그 PR이 dev에 머지될 때 **CD가 postgres를 다시 만든다**
  (`deploy.sh`의 `up -d`). 그래서 실행 PR의 **머지 시각 = 재시작 시각**이다. 머지 시각을 팀에 미리
  공지하고 그때 머지한다. 재시작은 수 초~수십 초이고 볼륨은 유지된다.
- **대안**: `ALTER SYSTEM`으로 서버에서만 바꾸고 손으로 재시작. 재시작 시각은 자유롭지만 설정이
  저장소 밖(볼륨 안 `postgresql.auto.conf`)에 남아 로컬·서버가 어긋난다. 택하지 않는다.

### D5. 복제 계정·publication·슬롯 (승인: **양지훈**)

- publication: 검토안 [`intake.publication.review.sql`](../../infra/cdc/intake.publication.review.sql) 그대로
  `beez_published_analysis_cdc`(컬럼 `id`, `status`만). 테이블 소유자로 **손으로** 만든다(Flyway 아님).
- 복제 계정 `beez_cdc`: `LOGIN REPLICATION`, 스냅샷용 `SELECT (id, status)`만 준다.
- 슬롯 이름 `beez_published_analysis`, `publication.autocreate.mode=disabled`.
- **DELETE 금지**: consumer는 DELETE 이벤트를 건너뛰지 않고 재시도하므로, 접수행을 손으로 지우면
  그 파티션이 영구히 막힌다. 앱에는 삭제 경로가 없다(FK에 cascade 없음). 런북에 "접수행 삭제 금지"를 적는다.
- 착수 전 `python infra/cdc/check_contract.py`로 컬럼 목록이 현재 스키마와 맞는지 확인한다.

### D6. 커넥터 설정 (승인: **양지훈**)

[`intake.connector.review.json`](../../infra/cdc/intake.connector.review.json)에서 자리표시자만 바꾼다.

| 키 | 값 |
|---|---|
| `database.hostname` / `dbname` | `postgres` / 서버 env의 `DB_NAME`(기본 `beez`) |
| `database.sslmode` | `disable` (D3) |
| `topic.prefix` | `beez` → 토픽 `beez.public.published_change_analysis` |
| `slot.name` | `beez_published_analysis` |
| `snapshot.mode` | `initial` 유지. 기존 행은 대부분 `NOT_EVALUATED`·`COMPLETED`라 consumer가 무시하고, id·status만 읽어 부담이 작다 |
| `tombstones.on.delete` | `true` 유지(consumer가 tombstone을 처리함) |
| 그 밖의 키 | 검토안 그대로 |

### D7. backend 켜기 (승인: **양지훈**)

- `infra/compose.yaml` backend `environment`에 아래를 **기본값 꺼짐**으로 추가하고, 켜는 값은 서버
  env 파일에만 둔다. 로컬·CI는 지금처럼 꺼진 채로 돈다.

  | env | 서버 값 |
  |---|---|
  | `BEEZ_CDC_INTAKE_ENABLED` | `true` (기본 `false`) |
  | `BEEZ_CDC_INTAKE_BOOTSTRAP_SERVERS` | `kafka:9092` |
  | `BEEZ_CDC_INTAKE_GROUP_ID` | `beez-published-analysis` |
  | `BEEZ_CDC_INTAKE_TOPIC` | `beez.public.published_change_analysis` |

- `beez.cdc.kafka-security.properties`는 비운다(D3). 비우면 PLAINTEXT로 동작하는 것이 현재 코드다.
- **켜는 순서**: Kafka·Connect·커넥터가 RUNNING인 것을 확인한 **뒤** 서버 env를 바꾸고 backend를
  재배포한다. 반대 순서면 consumer가 연결 실패를 계속 재시도한다.
- 확인할 것(양지훈님): Kafka가 잠시 죽어도 backend 기동·헬스체크(`/actuator/health`)가 실패하지 않는지.
  헬스가 Kafka에 묶이면 배포 롤백이 일어난다.

### D8. 일정 (승인: 팀)

- Sprint4는 11-09에 끝나고 11-10부터 통합 검증만 한다.
- **제안**: 이 계획은 이번 주에 승인받고, 실행(D4 재시작 포함)은 **229 통합 검증의 첫 단계**로 넣는다.
  229 담당(양지훈님)과 실행 날짜를 맞춘다.
- 대안: 11-09 전에 실행. 재시작 공지와 검증 시간을 생각하면 촉박하다.

## 4. 실행 순서 (승인 뒤, 별도 PR)

각 단계는 기존 인프라 작업 방식대로 한다. 명령마다 어느 셸에서 칠지 적어 주면 전진이 실행하고,
결과는 읽기 전용으로 검증해 런북에 남긴다.

| # | 단계 | 확인 | 되돌리기 |
|---|---|---|---|
| 1 | 실행 PR: `infra/cdc/` compose·Connect 설정 템플릿, `infra/compose.yaml`(D4 postgres `command`, D7 env 기본 꺼짐), `cd.yml` paths-ignore, 런북 | CI·리뷰 | PR revert |
| 2 | 공지한 시각에 머지 → CD가 postgres 재생성 | `SHOW wal_level` = `logical`, backend healthy | `command` 제거 PR + 재시작 |
| 3 | 복제 계정·publication 생성(D5) | `pg_publication_tables` | `DROP PUBLICATION`, `DROP ROLE` |
| 4 | `beez-cdc` 기동(D1·D2) | 컨테이너 healthy, `ss -tlnp`에 새 포트 없음 | `docker compose down`(볼륨 유지) |
| 5 | 커넥터 등록(D6) | connector·task RUNNING, 슬롯 `active`, 토픽 생성 | 커넥터 삭제 + **슬롯 삭제**(남기면 WAL이 쌓임) |
| 6 | 서버 env로 consumer 켜고 backend 재배포(D7) | `consumer_enabled`=1, Kafka 수신 실패 0 | env를 `false`로 되돌리고 재배포 |
| 7 | 변경 제안 1건 발행 | 접수행 PENDING → COMPLETED, 대시보드 `beez_cdc_*` | — |
| 8 | 재시작 시험: backend·Connect·Kafka 각각 | 유실·중복 없이 이어짐 | — |

7·8은 Jira 279 완료 조건이며, 229 장애 시험과 범위가 겹치므로 229와 함께 설계한다.

## 5. 위험과 한계

- **단일 서버·단일 broker**: 서버나 broker가 죽으면 CDC가 멈춘다. 이때 복구 스캔이 처리를 이어 가지만
  모니터링도 같은 서버라 알림이 가지 않는다(263 런북 한계와 같음).
- **WAL 보유**: 슬롯이 멈추면 WAL이 쌓인다. 10GB 상한(D4)으로 디스크는 지키지만, 넘으면 슬롯이
  무효화돼 커넥터를 다시 만들어야 한다. 슬롯·WAL 알림은 261에서 붙인다. 그 전까지는 런북의 수동 확인
  쿼리로 본다.
- **D3 예외**: 같은 호스트의 다른 컨테이너가 Kafka 토픽을 읽을 수 있다. 토픽에는 접수행 id·status만
  있고 프로젝트 ID·원문은 없다.
- **261 연결**: Connect REST를 컨테이너 안으로만 열면(D3) 261에서 Connect 상태를 수집할 방법(JMX
  exporter 등)을 따로 정해야 한다.

## 6. 범위 밖

- 운영(prod) 서버 CDC와 TLS/SASL 구성
- 문서 원본 캡처(`connector.review.json`, #225 과거 실험)
- #223 색인 경로의 Kafka 소비 여부
- 수동 재처리 API(244 결정 6번, 별도)

## 7. 승인 체크리스트

- [ ] D1 별도 compose 프로젝트 `beez-cdc`, 수동 배포 — 전진·팀
- [ ] D2 Kafka 3.9.1 단일 broker, Connect 버전은 PG17 지원으로 착수 때 확정 — 전진
- [ ] D3 dev 한정 PLAINTEXT·`sslmode=disable` 예외, Connect REST 컨테이너 안에서만 — **양지훈**
- [ ] D4 compose `command`로 `wal_level=logical`·`max_slot_wal_keep_size=10GB`, 머지 시각 = 재시작 시각 — 전진·**팀**
- [ ] D5 publication·복제 계정·슬롯 이름, 접수행 삭제 금지 — **양지훈**
- [ ] D6 커넥터 값(토픽 이름·`snapshot.mode=initial`) — **양지훈**
- [ ] D7 backend env 이름·기본 꺼짐·켜는 순서, Kafka 장애 시 헬스 영향 — **양지훈**
- [ ] D8 실행을 229 통합 검증 첫 단계로 — 팀·양지훈
