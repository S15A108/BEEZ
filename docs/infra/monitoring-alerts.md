# 모니터링 알림 런북

CDC 분석·서버·DB 알림을 Mattermost로 받고 대응하는 절차입니다. 기준은
S15P31A108-244 [결정 (11-04)] 4~6번이고, 이 문서는 S15P31A108-264 범위입니다.
스택 설치·업데이트·접속은 [모니터링 런북](monitoring.md)에 있습니다.

| 파일 | 내용 |
|---|---|
| `infra/monitoring/prometheus/rules/beez.yml` | 기록 규칙(중복 제거)·알림 규칙 |
| `infra/monitoring/prometheus/tests/beez_test.yml` | 규칙 시험(promtool) |
| `infra/monitoring/alertmanager/alertmanager.yml` | 묶음·재통지·Mattermost 수신처 |
| `infra/monitoring/alertmanager/templates/beez.tmpl` | 알림 문구 |
| `infra/monitoring/grafana/dashboards/*.json` | 대시보드 `BEEZ CDC 분석`·`BEEZ 서버·DB` |
| 서버 `/opt/beez/.alertmanager-webhook-url` | Mattermost 웹훅 주소(비밀값, 저장소에 없음) |

## 알림 한눈에 보기

| 알림 | 조건 | 지속 | 재통지 | 1차 대응 |
|---|---|---|---|---|
| [CdcPendingDelay](#cdcpendingdelay) | 가장 오래된 PENDING > 600초 **그리고** 복구 스캔 켜짐 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcSnapshotFailing](#cdcsnapshotfailing) | DB 집계 실패, 또는 마지막 성공 120초 초과 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcMetricsMissing](#cdcmetricsmissing) | backend 수집은 되는데(`up == 1`) CDC 필수 지표가 없음(인스턴스별) | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcExpiredLeases](#cdcexpiredleases) | lease 만료 RUNNING > 0 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcBlockedProjects](#cdcblockedprojects) | 순서상 막힌 프로젝트 > 0 | 10분 | 4시간 | 앱 · 양지훈 |
| [CdcFailedIntakes](#cdcfailedintakes) | FAILED > 0 | 바로 | **24시간** | 앱 · 양지훈 |
| [CdcNotEvaluatedRequiresReview](#cdcnotevaluatedrequiresreview) | 확인 필요 미평가 > 0 | 바로 | **24시간** | 앱 · 양지훈 |
| [CdcKafkaReceiveFailures](#cdckafkareceivefailures) | 최근 5분 Kafka 수신 실패 증가 **그리고** consumer 켜짐 | 바로 | 4시간 | 인프라 · 전진 |
| [CdcKafkaFailureMetricMissing](#cdckafkafailuremetricmissing) | consumer 켜진 인스턴스에 그 인스턴스의 실패 지표 없음 | 5분 | 4시간 | 인프라 · 전진 |
| [CdcConsumerDown](#cdcconsumerdown) | consumer 켜진 인스턴스가 Kafka에 붙어 있지 않음(`consumer_up` ≠ 1 또는 없음) | 5분 | 4시간 | 인프라 · 전진 |
| [CdcSlotInactive](#cdcslotinactive) | 논리 복제 슬롯 `slot_is_active == 0`(커넥터 등록 뒤에만 시계열 있음) | 10분 | 4시간 | 인프라 · 전진 |
| [CdcSlotWalRetained](#cdcslotwalretained) | 슬롯이 붙잡은 WAL > 1GiB | 5분 | 4시간 | 인프라 · 전진 |
| [CdcSlotMissing](#cdcslotmissing) | consumer 켜짐 **그리고** 기대 슬롯(`beez_published_analysis`) 지표 없음(exporter·수집기는 정상) | 10분 | 4시간 | 인프라 · 전진 |
| [CdcSlotCollectorFailing](#cdcslotcollectorfailing) | `replication_slots` 수집기 실패(exporter 는 DB 에 붙어 있음) | 5분 | 4시간 | 인프라 · 전진 |
| [CdcKafkaUnreachable](#cdckafkaunreachable) | consumer 켜짐 **그리고** kafka-exporter 수집 실패(`up{job="kafka"} == 0`, broker 다운이면 이렇게 보임) | 5분 | 4시간 | 인프라 · 전진 |
| [CdcConnectDown](#cdcconnectdown) | consumer 켜짐 **그리고** Connect 지표 수집 실패(`up{job="connect"} == 0`) | 5분 | 4시간 | 인프라 · 전진 |
| [CdcConnectorNotRunning](#cdcconnectornotrunning) | 커넥터나 task 상태가 RUNNING 아님(failed·paused·stopped 등) | 5분 | 4시간 | 인프라 · 전진 |
| [CdcConnectorMissing](#cdcconnectormissing) | consumer 켜짐 **그리고** Connect·Kafka는 수집되는데 커넥터 지표 없음 | 10분 | 4시간 | 인프라 · 전진 |
| [CdcConsumerLagStuck](#cdcconsumerlagstuck) | consumer가 Kafka에 붙어 있는데(`consumer_up` 1) 접수 group lag > 0 | 10분 | 4시간 | 인프라 · 전진 |
| [BackendScrapeDown](#backendscrapedown) | `up{job="backend"} == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [MonitoringTargetDown](#monitoringtargetdown) | backend·kafka·connect 외 수집 대상 `up == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [PostgresDown](#postgresdown) | `pg_up == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [DiskSpaceLow](#diskspacelow) | 루트(`/`) 남은 공간 < 15% | 10분 | 4시간 | 인프라 · 전진 |

- **기준값은 초기값입니다.** 244 결정대로 실제 분석 소요 시간을 보고 조정합니다. 바꾸면
  규칙 파일과 이 표, 시험을 같이 고칩니다.
- **받는 곳**: 팀 Mattermost 채널. [배포 실패 알림](operations.md#6-배포-실패-알림)과 같은 채널에
  웹훅만 따로 만듭니다(한쪽 주소가 새도 다른 쪽은 그대로).
- **대응 시간**: 평일 일과(09~18시) 중에 확인합니다. 1차 대응자가 알림 글 스레드에 "확인 중"을
  남깁니다. 야간·주말에 온 알림은 다음 일과에 봅니다. dev 서버 기준이며 운영 서비스를 열면 다시 정합니다.
- 2분(`BackendScrapeDown` 등)은 배포 때 backend 재시작으로 잠깐 끊기는 것을 거르는 값입니다.
  디스크 15%(약 46GB)는 244 결정에 값이 없어 정한 초기값입니다. 루트만 봅니다(아래 DiskSpaceLow).
- 1차 대응자는 알림 글에 찍힙니다. 담당이 바뀌면 `alertmanager/templates/beez.tmpl`의
  `beez.owner`, 이 표, 규칙 파일 머리말을 함께 고칩니다.
- **CDC 건수 값은 신선한 스냅샷만 씁니다.** 인스턴스별로 집계 성공이고 마지막 성공이 120초
  이내인 것(`beez_cdc:snapshot_fresh`)만 남겨 `max`로 합칩니다. 갱신이 멈춘 인스턴스의 옛 값이
  이미 해소된 건을 계속 알리지 않게 하려는 것입니다(PR #121 리뷰).
- **CDC 값이 없을 때의 거짓 "해소"를 막습니다.** 집계 실패·지표 누락·backend 다운으로 신선한
  스냅샷이 어디에도 없으면 CDC 건수 값이 사라지고, 그 값으로 거는 CDC 상태 알림(지연·lease·
  막힘·FAILED·미평가)이 풀려 "해소" 글이 갈 수 있습니다. 실제로 풀린 것이 아닙니다. 두 겹으로 막습니다.
  - 내부 알림 `CdcDataUnavailable`(신선한 스냅샷이 어디에도 없음)이 울리는 동안 Alertmanager가 위
    알림들을 **억제**합니다(`inhibit_rules`). 이 알림은 글을 보내지 않습니다. 인스턴스 하나만
    고장 나고 다른 쪽이 신선하면 값이 있으므로 억제하지 않습니다.
  - CDC 상태 알림은 조건이 풀린 뒤 **10분 더 유지**합니다(`keep_firing_for`). 억제 원인이
    Alertmanager에 먼저 닿게 하려는 것입니다. 그래서 **실제 해소 글도 최대 10분 늦게 옵니다.**
  - Kafka·인프라 알림은 억제하지 않습니다.
- 묶음: 같은 알림 이름은 한 글로 옵니다. 처음 발생 뒤 30초 모아서 보내고, 묶음에 새
  대상이 붙으면 5분 간격으로 다시 보냅니다. 해소되면 `✅ … 해소` 글이 옵니다.

### 받는 글

```
🚨 CdcFailedIntakes 발생                     ← 누르면 이 문서의 그 알림 절
1차 대응: 앱 · 양지훈
- 분석 FAILED 2건 남아 있음(자동 재처리 없음) · 11-05 10:12 시작
제목을 누르면 대응 절차(런북)가 열립니다. 일시 중지는 런북 "알림 일시 중지".
```

- 글에 들어가는 값은 알림 이름·담당·요약·수집 대상 이름(`backend:8080` 등)·시각뿐입니다.
  프로젝트 ID·문서 원문은 지표 라벨에 없어(244 결정 4) 글에도 없습니다.
- 시각은 한국 시각입니다(템플릿에서 `Asia/Seoul`로 바꿈). 단 `amtool` 출력은 UTC입니다.
- Alertmanager·Prometheus·Grafana 화면은 SSH 터널로만 열려 Mattermost에서 링크를 누를 수
  없습니다. 제목 링크는 GitHub의 이 문서로 갑니다.

## 대응 순서

244 결정 6번 범위입니다. **확인 → 원인 파악 → 담당 협의 → 필요하면 일시 중지**까지만
적습니다.

1. **확인** — [SSH 터널](monitoring.md#ssh-터널로-접속)을 열고 Grafana `BEEZ CDC 분석`(CDC 알림)
   또는 `BEEZ 서버·DB`(인프라 알림)에서 값과 시작 시각을 봅니다.
2. **원인 파악** — 아래 알림별 절의 "볼 곳"을 봅니다.
3. **담당 협의** — 1차 대응자가 Mattermost 알림 글의 스레드에 "확인 중"을 남기고, 다른
   쪽이 필요하면 그 스레드에서 부릅니다. 앱 처리 = 양지훈, 수집·서버·DB·Kafka 인프라 = 전진.
4. **일시 중지(선택)** — 원인을 알고 고칠 때까지 같은 글이 반복되면 아래
   "알림 일시 중지"로 멈춥니다. 일시 중지는 해결이 아닙니다.
5. **데이터 장애가 풀린 뒤 다시 확인** — `CdcSnapshotFailing`·`CdcMetricsMissing`·
   `BackendScrapeDown`의 해소 글을 받으면 `BEEZ CDC 분석`에서 FAILED·미평가·막힘 등을 다시
   봅니다. 장애 중에 실제로 풀린 CDC 상태 알림은 **해소 글이 오지 않습니다**(억제 중에 풀린
   알림은 Alertmanager가 글 없이 버림, 2026-11-05 실측). 채널에 "발생"만 남아 있어도 이미
   해소됐을 수 있습니다.

**하지 않는 것**: `FAILED`를 `PENDING`으로 바꾸거나 `attempt`를 초기화하는 등 DB 상태를
직접 바꾸지 않습니다. 워커는 attempt 4 이상을 선점하지 않고, 시도 이력이
`(intake_id, attempt)` 기본 키라 이력이 깨집니다. 일반 `NOT_EVALUATED`로 바꿔도 후속
분석의 선행 조건을 채우지 못합니다. 안전한 수동 재처리는 별도 백엔드 작업(수동 재처리 API,
티켓 미생성)입니다. 근거: [243 지표 계약 "복구 경계"](../design/cdc-observability-243.md#복구-경계).

## 알림별

### CdcPendingDelay

가장 오래된 `PENDING` 접수가 600초 넘게 처리되지 않은 상태가 5분 이어졌습니다.

- 볼 곳: `BEEZ CDC 분석`의 "가장 오래된 PENDING 경과", "재시도 예정 PENDING", "막힌
  프로젝트". backend 로그의 분석 워커 오류.
- 재시도 예정이 같이 늘었으면 AI 호출 실패로 재시도 중일 가능성, 막힌 프로젝트가 같이
  있으면 선행 접수 미완료로 순서 대기일 가능성이 큽니다.
- 복구 스캔이 꺼진 환경(`BEEZ_CDC_INTAKE_ENABLED=false`)에서는 울리지 않습니다. 처리는 스캔이 맡으므로
  consumer 설정이 아니라 스캔 설정(`beez_cdc_analysis_recovery_enabled`)을 봅니다(S15P31A108-298) — Kafka가
  죽어 스캔만 도는 동안에도 지연은 알립니다.

### CdcSnapshotFailing

backend가 지표용 DB 집계에 실패하거나, 집계 값이 120초 넘게 갱신되지 않은 상태가 5분
이어졌습니다. 이 동안 CDC 건수 값이 비어 CDC 상태 알림은 새로 울리지 않고, 이미 울리던 것은
억제된 채 최대 10분 유지되다 해소 글 없이 사라집니다(위 "거짓 해소를 막습니다"). 풀린 뒤
대응 순서 5번대로 다시 확인합니다.

- 볼 곳: `BEEZ CDC 분석`의 "DB 집계"·"마지막 집계 뒤", `BEEZ 서버·DB`의 PostgreSQL 연결·
  Hikari 대기. backend 로그(집계 실패는 예외 원문 없이 상태만 남깁니다).
- DB 자체 문제(연결 고갈·DB 다운)면 인프라(전진)와 같이 봅니다.

### CdcMetricsMissing

backend 지표는 수집되는데(`up == 1`) 그 인스턴스에 CDC 필수 지표(`snapshot_available`·
`last_success`·`consumer_enabled`·`recovery_enabled`·`intakes`) 중 하나라도 5분 넘게 없습니다. 이때는
`CdcSnapshotFailing`도 `BackendScrapeDown`도 울리지 않아 CDC 감시가 통째로 빠집니다.

- 볼 곳: 그 인스턴스에 배포된 커밋(CDC 지표가 없는 커밋으로 배포·롤백했는지), backend 기동
  로그의 지표 등록. Prometheus에서 `{__name__=~"beez_cdc.*", instance="<대상>"}` 조회.

### CdcExpiredLeases

`RUNNING`인데 lease가 만료된 접수가 5분 넘게 있습니다. 워커가 처리 중에 죽었거나 멈춘
것입니다.

- 볼 곳: backend 재시작 여부(`BackendScrapeDown`, 배포 시각), 워커 로그. 만료된 lease는
  워커가 다시 선점해 처리하는 것이 정상 흐름이라, 계속 남아 있으면 워커가 돌지 않는지 봅니다.

### CdcBlockedProjects

`PENDING`이 있지만 선행 baseline 누락·미완료로 순서상 처리할 수 없는 프로젝트가 10분 넘게
있습니다.

- 볼 곳: 같은 시각의 `CdcFailedIntakes`·`CdcNotEvaluatedRequiresReview`. 선행 접수가
  FAILED이거나 확인 필요 미평가면 자동으로 풀리지 않습니다(수동 재처리 API 없음).

### CdcFailedIntakes

자동 재처리되지 않는 `FAILED` 접수가 남아 있습니다. 처음 한 번 알리고, 남아 있으면
24시간마다 다시 알립니다.

- 볼 곳: "FAILED 남은 건", "시도 이력 결과별". 원인 확인은 권한이 있는 담당자가 DB를
  조회해서 합니다(조회만).
- 남아 있는 것을 알고 있고 처리 계획이 정해졌으면 24시간 재통지를 그대로 두거나 일시
  중지합니다.

### CdcNotEvaluatedRequiresReview

최초 v1이나 명시적 과거 백필이 아닌 `NOT_EVALUATED`가 남아 있습니다. 모두 장애는 아니지만
후속 분석을 막을 수 있습니다. 재통지는 24시간입니다.

- 볼 곳: "확인 필요 미평가", "막힌 프로젝트". "미평가(예상된 것)"는 장애가 아닙니다.

### CdcKafkaReceiveFailures

consumer가 켜진 상태에서 최근 5분 동안 Kafka 레코드 처리 실패(디코딩·접수 확인 오류)가
늘었습니다. 같은 레코드의 반복 실패도 각각 셉니다.

- 볼 곳: backend 로그의 리스너 오류, "Kafka 수신 실패(5분)". broker 연결 실패처럼
  리스너에 닿기 전 오류는 이 지표에 잡히지 않습니다. 그런 끊김은 `CdcConsumerDown`, 붙어 있는데 처리가 막힌 것은
  `CdcConsumerLagStuck`(S15P31A108-261)이 봅니다.

### CdcKafkaFailureMetricMissing

consumer 설정이 켜진 인스턴스에 그 인스턴스의 Kafka 수신 실패 counter가 5분 넘게 없습니다.
counter는 리스너가 등록될 때 0으로 생기므로, 리스너가 뜨지 않았거나 지표 등록이 빠진 것입니다.
인스턴스별로 대조하므로 backend가 여럿일 때 한쪽만 빠져도 잡습니다.

- 볼 곳: backend 기동 로그의 Kafka 리스너 등록, 배포된 설정(consumer 관련 env).

### CdcConsumerDown

consumer 설정이 켜진 인스턴스가 5분 넘게 Kafka에 붙어 있지 않습니다(S15P31A108-298).
`beez_cdc_kafka_consumer_up`은 리스너가 돌고, 파티션을 받았고, 최근 30초 안에 그룹 heartbeat를 보냈을 때만 1입니다.
이 동안에도 분석은 복구 스캔이 30초 늦게 처리하므로 `CdcPendingDelay`는 울리지 않습니다. 급하지 않지만 Kafka 경로가 멈춘 상태입니다.

- 볼 곳: `cdc ps`(Kafka·Connect가 떠 있는지), backend 로그의 "CDC intake Kafka consumer could not start"
  (Kafka 이름이 안 풀려 시작을 30초마다 재시도 중), Kafka 연결 경고. 지표가 아예 없으면 리스너 설정이 빠진 배포다.
- Kafka가 돌아오면 backend 재시작 없이 다시 붙습니다(최대 30초 + 재연결 대기 10초). 붙으면 저절로 풀립니다.
- 계획된 Kafka 점검이면 알림 일시 중지를 걸거나 consumer만 끕니다(`cdc-stack.md` "중지·내리기").

### CdcSlotInactive

PostgreSQL 논리 복제 슬롯을 10분 넘게 아무도 읽지 않습니다(S15P31A108-295, 계획 D6). 슬롯은 Debezium 커넥터가 쓰며,
읽히지 않는 동안 WAL이 쌓입니다. 분석은 복구 스캔이 이어 가므로 다른 CDC 알림은 울리지 않습니다.

- 볼 곳: [cdc-connector.md "상태 보기"](cdc-connector.md#상태-보기)의 커넥터·task 상태와 슬롯 쿼리, `cdc ps`.
  task가 `FAILED`면 그 절의 오류 문구를 보고 task를 재시작합니다. Connect·Kafka가 내려가 있으면 [cdc-stack.md](cdc-stack.md)대로 올립니다.
- 커넥터를 일부러 지웠다면 슬롯도 정리합니다(계획 §4 되돌리기 2·3번). 남겨 두면 다음 알림 `CdcSlotWalRetained`로 이어집니다.

### CdcSlotWalRetained

슬롯이 붙잡은 WAL(현재 WAL 위치 − 슬롯이 확인한 위치)이 1GiB를 5분 넘게 넘었습니다. dev의 WAL 생성량(1주 약 19MB)으로는
정상일 때 닿지 않으므로 슬롯이 오래 멈춘 것입니다. `max_slot_wal_keep_size`(10GB, 계획 D4)에 닿으면 슬롯이 무효화되고,
커넥터를 새 이름으로 다시 만들어야 합니다([cdc-connector.md "다시 만들기"](cdc-connector.md#다시-만들기)).

- 먼저 위 `CdcSlotInactive`와 같은 곳을 봅니다. 대개 함께 울립니다.
- 5GB를 넘으면 원인을 찾기 전이라도 계획 §4 되돌리기 1~3번(커넥터·슬롯 정리)을 합니다(계획 D6).

### CdcSlotMissing

consumer가 켜져 있는데(= CDC 켜짐) 기대 슬롯 `beez_published_analysis`의 지표가 10분 넘게 없습니다. 위 두 슬롯 알림은 시계열이
있어야 울기 때문에, 이 상태에서는 슬롯 감시가 조용히 빠져 있습니다(PR #166 리뷰). exporter가 DB에 붙어 있고 수집기도 성공할
때만 울리므로 원인은 슬롯 쪽입니다.

- 볼 곳: [cdc-connector.md "상태 보기"](cdc-connector.md#상태-보기)의 슬롯 쿼리. 0행이면 누가 슬롯을 지웠거나 커넥터가 없는 것이고,
  다른 이름의 슬롯만 있으면 커넥터 설정의 `slot.name`이 바뀐 것입니다(이 알림은 이름을 고정해서 봅니다).
- 일부러 내린 것이면 되돌리기 순서(consumer 먼저 끄기)를 지켰는지 봅니다. consumer를 끄면 이 알림은 풀립니다.
- 한계: consumer를 끈 점검 중에는 슬롯이 사라져도 울지 않습니다. 커넥터 자체가 지워진 것은 `CdcConnectorMissing`(S15P31A108-261)이
  같은 조건(consumer 켜짐)으로 봅니다.
- 슬롯 이름을 바꾸기로 했으면 규칙 파일의 `slot_name`도 함께 고칩니다.

### CdcSlotCollectorFailing

postgres-exporter의 `replication_slots` 수집기가 5분 넘게 실패합니다. 슬롯 시계열이 통째로 빠져 위 슬롯 알림이 모두 멈춘
상태입니다. consumer를 끈 점검 중에도 슬롯은 WAL을 붙잡으므로 consumer와 상관없이 울립니다. 이 수집기는 슬롯이 없어도 성공하므로
CDC 전에는 울지 않고, exporter가 DB에 못 붙을 때(`pg_up` 0)는 `PostgresDown`에 맡깁니다.

- 볼 곳: `mon logs postgres-exporter`의 오류 문구. 권한 문제면 수집 계정 `beez_monitor`의 `pg_monitor` 역할을
  ([monitoring.md 수집 계정](monitoring.md#2-db-수집-계정)), 그 밖이면 exporter 버전 변경을 의심합니다.
- 그동안은 [cdc-connector.md "상태 보기"](cdc-connector.md#상태-보기)로 슬롯을 손으로 봅니다.

### Kafka·Connect 알림 공통 (S15P31A108-261)

수집은 둘입니다. job `kafka`는 모니터링 스택의 kafka-exporter가 broker에 Kafka 프로토콜(`kafka:9092`)로 붙어 접수
토픽·group의 lag을 냅니다. job `connect`는 Connect JVM 안의 JMX exporter javaagent가 `connect:9404`에서 커넥터·task 상태와
Debezium 연결 여부를 냅니다(Connect REST는 그대로 컨테이너 안에만 있음, [cdc-stack.md "지표"](cdc-stack.md#지표-s15p31a108-261)).
대시보드는 `BEEZ CDC 분석`의 "Kafka·Connect·슬롯" 줄입니다.

- 이 장애들은 복구 스캔이 분석을 30초 늦게 이어 가므로 `CdcPendingDelay` 같은 앱 알림으로는 보이지 않습니다. 급하지 않지만 Kafka
  경로가 멈춘 상태이고, Connect가 멈추면 슬롯이 WAL을 쌓기 시작합니다.
- `CdcKafkaUnreachable`·`CdcConnectDown`·`CdcConnectorMissing`은 consumer 켜짐(= CDC 켜짐)일 때만 웁니다. CDC 스택을 계획해서
  내릴 때 consumer를 먼저 끄면([cdc-stack.md "중지·내리기"](cdc-stack.md#중지내리기) 1번) 울지 않고, 켠 채 내리면 `CdcConsumerDown`처럼
  5분 뒤 웁니다 — 그때는 일시 중지를 겁니다. 커넥터만 pause·stop하는 점검도 `CdcConnectorNotRunning`이 5분 뒤 울므로 마찬가지입니다.
- Kafka가 죽으면 `CdcConsumerDown`(5분)과 `CdcKafkaUnreachable`(5분)이 함께 옵니다. 원인은 broker 쪽부터 봅니다.

### CdcKafkaUnreachable

CDC가 켜졌는데 kafka-exporter를 5분 넘게 긁지 못합니다. broker가 죽으면 exporter는 마지막 메타데이터로 broker 수 1을 계속 내지만,
한 번 긁는 데 15~30초가 걸려 수집 시간 초과(10초)가 납니다(11-06 로컬 실측). 그래서 broker 다운과 exporter 다운이 같은 모양입니다.

- 볼 곳: `cdc ps`([cdc-stack.md](cdc-stack.md) `cdc` 함수)로 kafka가 healthy인지, `mon ps`로 kafka-exporter가 떠 있는지.
  둘 다 떠 있으면 `mon logs kafka-exporter`의 `Cannot get ...` 줄과 Prometheus `targets` 화면의 오류 문구를 봅니다.
- broker가 돌아오면 exporter·backend consumer·Connect 모두 재시작 없이 다시 붙습니다(298·295 실측).

### CdcConnectDown

CDC가 켜졌는데 Connect의 지표(`connect:9404`)를 5분 넘게 긁지 못합니다. Connect 프로세스가 죽었거나, javaagent가 지표를 내지
못하는 상태입니다. 이 동안 `CdcConnectorNotRunning`·`CdcConnectorMissing`은 지표가 없어 울지 않습니다.

- 볼 곳: `cdc ps`, `cdc logs connect`. 컨테이너가 `Created`에서 멈춰 있으면 javaagent jar가 서버에 없는 것입니다
  (`bind source path does not exist`, [cdc-stack.md "지표"](cdc-stack.md#지표-s15p31a108-261)의 jar 받기).
- 메모리 상한(1GiB)에 닿아 재시작을 반복하는지 대시보드 "Connect 메모리"로 봅니다.

### CdcConnectorNotRunning

커넥터나 task 상태가 5분 넘게 RUNNING이 아닙니다. 글에 커넥터 이름·task 번호·상태가 찍힙니다(같은 커넥터의 connector 줄과
task 줄은 한 글로 묶임).

- `failed`: [cdc-connector.md "등록 확인"](cdc-connector.md#4-등록-확인)의 오류 문구를 보고 task를 재시작합니다. 같은 오류로 다시
  실패하면 원인(DB 권한·publication·비밀 파일)부터 고칩니다.
- `paused`·`stopped`: 누가 일부러 멈춘 것입니다. 점검이면 일시 중지를 걸고, 끝나면 resume합니다. `stopped`는 되돌리기·다시 만들기
  도중의 상태입니다([cdc-connector.md "되돌리기"](cdc-connector.md#되돌리기)).
- 멈춘 동안 슬롯은 남아 있고 WAL을 붙잡습니다(paused는 슬롯이 활성으로 남음, 11-06 로컬 실측). 오래 둘 거면 `CdcSlotWalRetained`를 봅니다.

### CdcConnectorMissing

CDC가 켜졌고 Connect·Kafka 지표도 수집되는데, 커넥터 상태 지표가 10분 넘게 없습니다. 커넥터가 지워졌거나 등록에 실패한 것입니다.
슬롯은 남아 WAL을 붙잡으므로 `CdcSlotInactive`도 곧 웁니다. broker가 죽으면 worker가 커넥터를 내려 이 지표도 사라지지만(REST는 옛
RUNNING을 그대로 보여 줌), 그때는 원인인 `CdcKafkaUnreachable`만 울도록 Kafka 수집 정상을 조건에 넣었습니다(11-06 로컬 실측).

- 볼 곳: `crest /connectors`([cdc-connector.md "0. 준비"](cdc-connector.md#0-준비-서버-bash-셸을-새로-열-때마다)). `[]`이면
  [cdc-connector.md "다시 만들기"](cdc-connector.md#다시-만들기)대로 새 이름으로 등록하거나, 일부러 내린 것이면 되돌리기 순서
  (consumer 먼저 끄기)를 지켰는지 봅니다.

### CdcConsumerLagStuck

backend consumer는 Kafka에 붙어 있는데(`consumer_up` 1), 접수 group `beez-published-analysis`의 밀린 레코드가 10분 넘게 0으로
돌아오지 않습니다. 평소 레코드는 1초 안에 처리됩니다(295 실측 0.8초). 같은 레코드를 계속 재시도하며 막혀 있을 가능성이 큽니다
(예: 접수행 삭제 이벤트, [cdc-connector.md "꼭 지킬 것"](cdc-connector.md#꼭-지킬-것)).

- 볼 곳: 대시보드 "접수 lag", backend 로그의 리스너 오류, `CdcKafkaReceiveFailures`가 같이 울었는지. 처리 자체는 복구 스캔이
  이어 가지만 Kafka 경로는 그 레코드에서 멈춥니다. 앱 쪽 원인이면 양지훈님과 같이 봅니다.
- consumer가 끊긴 경우는 `CdcConsumerDown`이 맡고 이 알림은 울지 않습니다.

### BackendScrapeDown

Prometheus가 `backend:8080/actuator/prometheus`를 2분 넘게 긁지 못합니다. 이 동안 CDC
지표가 없어 CDC 알림은 울리지 않습니다.

- 볼 곳: Prometheus `targets` 화면의 오류 문구. `401`이면 보안 설정에서 경로가 닫힌 것
  ([monitoring.md](monitoring.md#구성)), 연결 실패면 backend 컨테이너 상태
  (`docker ps`, [운영 안내](operations.md)).

### MonitoringTargetDown

backend 외 수집 대상(prometheus·alertmanager·node·postgres exporter)이 2분 넘게 응답하지
않습니다. kafka·connect는 위 `CdcKafkaUnreachable`·`CdcConnectDown`이 CDC 켜짐 조건과 함께 맡아 여기서 뺐습니다(261).

- 볼 곳: `mon ps`([monitoring.md](monitoring.md#4-띄우기)의 `mon` 함수), 그 컨테이너 로그.
  메모리 한도에 닿아 재시작을 반복하는지 봅니다.
- alertmanager가 죽으면 이 알림도 보내지 못합니다(아래 한계).

### PostgresDown

postgres-exporter가 2분 넘게 DB에 접속하지 못합니다.

- 볼 곳: 본 스택 `postgres` 컨테이너 상태. DB는 정상인데 이것만 울리면 수집 계정
  `beez_monitor` 문제입니다. DB 볼륨을 새로 만들었으면
  [수집 계정 단계](monitoring.md#2-db-수집-계정)를 다시 돌립니다.

### DiskSpaceLow

루트(`/`) 남은 공간이 15% 아래로 10분 넘게 있습니다. DB·Docker 볼륨·Prometheus 데이터가 모두
`/`에 있습니다. `/boot`(약 900MB)는 같은 비율이면 커널 업데이트만으로 울려 알림에서 뺐고,
대시보드 "파일시스템 남은 비율"에서 봅니다.

- 볼 곳: `BEEZ 서버·DB` "파일시스템 남은 비율"·"DB 크기", 서버에서 `docker system df`,
  `sudo du -sh /var/lib/docker/volumes/*`. Prometheus 자체는 10GB 상한이 있습니다.

## 알림 일시 중지

Alertmanager는 호스트 포트를 열지 않아 컨테이너 안의 `amtool`로 겁니다.
서버에서 [monitoring.md](monitoring.md#4-띄우기)의 `mon` 함수를 정의한 셸에서 칩니다.

```bash
# 서버 (ssh beez)
am() { mon exec -T alertmanager amtool --alertmanager.url=http://127.0.0.1:9093 "$@"; }

am alert query                                   # 지금 울리는 알림
am silence add alertname=CdcFailedIntakes --duration=24h \
  --author=전진 --comment="FAILED 2건 원인 확인 중, 양지훈과 협의"   # 찍힌 ID 를 스레드에 남긴다
am silence query                                 # 걸린 일시 중지
am silence expire <ID>                           # 일찍 풀기
```

- 알림 이름 하나만 멈춥니다. `alertname=` 없이 걸면 전부 멈추니 쓰지 않습니다.
- **기간을 꼭 줍니다.** 끝나면 저절로 풀리고, 그때도 남아 있으면 다시 알립니다.
- 건 사람·이유를 `--author`·`--comment`에 남기고 Mattermost 알림 스레드에도 적습니다.
- 일시 중지는 alertmanager 볼륨에 남아 스택을 다시 띄워도 유지됩니다. 볼륨을 지우면
  사라집니다([백업 대상](monitoring.md#백업-대상)).

## 한계

- **지연 알림은 약 15분 뒤에 옵니다.** 600초 기준 + 5분 지속이고, 중간에 RUNNING으로
  바뀌어 조건이 끊기면 더 늦어집니다.
- **FAILED·확인 필요 미평가는 "남은 건수" gauge입니다.** 이미 남은 건이 있으면 새로 생긴
  건을 따로 알리지 않습니다(같은 알림이 계속 울리는 중이라). 24시간 재통지 사이에 건수가
  늘어도 글이 새로 오지 않습니다. 신규 건마다 알림이 필요해지면 이벤트 지표를 별도로
  정합니다(244 결정 4).
- **같은 서버라 서버가 죽으면 알림도 못 갑니다.** Prometheus·Alertmanager도 그 서버에
  있습니다. alertmanager 컨테이너만 죽어도 알림이 나가지 않습니다. 바깥에서 서버 생존을
  보는 일은 별도 모니터링 서버 검토 때 정합니다([monitoring.md](monitoring.md#같은-서버에-두는-이유)).
- **consumer가 꺼진 환경에서는 지연·Kafka 알림이 꺼집니다.** dev에는 Kafka가 없어
  consumer가 꺼져 있습니다. 켜는 배포(S15P31A108-261 이후)부터 이 두 알림이 의미를 가집니다.
- **CDC 데이터 장애 중에 실제로 해소된 CDC 상태 알림은 해소 글이 오지 않습니다.** 거짓
  "해소"(문제가 남았는데 해소로 보임)를 막는 대신 받아들인 쪽입니다. 틀려도 "이미 해소됐는데
  발생으로 남음"이라 덜 위험합니다. 장애 해소 글을 받으면 대시보드로 다시 확인합니다.
- **모니터링을 다시 띄우면(`--force-recreate`) 유지 중이던 알림 상태가 처음부터 다시 계산됩니다.**
  CDC 데이터 장애 중이면 `CdcDataUnavailable`이 바로 다시 울려 억제가 이어지므로 거짓 해소는
  가지 않지만, 장애 중에는 될 수 있으면 다시 띄우지 않습니다.
- Kafka·Connect 알림(261)은 consumer 켜짐을 조건으로 둡니다. consumer를 끈 채 Kafka·Connect가 죽은 것은 알리지 않고, 그
  동안 슬롯이 WAL을 쌓으면 `CdcSlotInactive`·`CdcSlotWalRetained`가 웁니다.
- broker 하나만 있어 broker 다운과 kafka-exporter 다운을 지표로 구분하지 못합니다(`CdcKafkaUnreachable` 글이 둘 다 뜻함).
- 웹훅 주소가 틀리거나 Mattermost가 응답하지 않으면 Alertmanager 로그
  (`mon logs alertmanager`)에 `Notify for alerts failed`가 남고 재시도합니다. 이를 따로
  알리는 경로는 없습니다.

## #229 장애 시험과의 관계

S15P31A108-229(CDC 통합·장애 복구 검증, 양지훈)가 Sprint5에 지연·오류·장애 복구
시나리오를 돌립니다. 이 문서는 그 시험을 대신하지 않고, 알림 쪽 확인 항목만 넘깁니다.

| #229 시나리오 | 이 문서에서 볼 것 |
|---|---|
| consumer·워커 중단 뒤 지연 | `CdcPendingDelay`가 약 15분 뒤 오는지, 재시작 뒤 해소 글이 오는지 |
| 분석 실패(재시도 소진) | `CdcFailedIntakes`가 오고 24시간 재통지가 걸리는지 |
| 선행 접수 미완료 | `CdcBlockedProjects`가 10분 뒤 오는지 |
| 워커 강제 종료 | `CdcExpiredLeases` |
| Kafka 수신 오류 | `CdcKafkaReceiveFailures`(consumer 켜진 환경에서만) |
| Kafka 중지(261) | `CdcConsumerDown`·`CdcKafkaUnreachable`이 5분 뒤 함께 오는지, 다시 띄우면 둘 다 해소 글이 오는지 |
| Connect 중지·커넥터 실패(261) | `CdcConnectDown`(Connect 중지), `CdcConnectorNotRunning`(task failed), 10분 넘으면 `CdcSlotInactive` |
| 커넥터 삭제(261) | `CdcConnectorMissing`·`CdcSlotInactive`(슬롯이 남는 경우) |
| 레코드 처리 막힘(261) | `CdcConsumerLagStuck`이 10분 뒤 오는지(consumer는 붙어 있음) |

264에서 확인한 범위는 아래 "검증 기록"입니다. 위 표의 CDC 시나리오는 Kafka·consumer가
있는 환경이 필요해 #229에서 확인합니다.

## 웹훅 넣기 (서버당 한 번)

Alertmanager는 웹훅 주소를 파일(`webhook_url_file`)로 읽습니다. 주소를 compose나 env에
두면 `docker inspect`·`compose config`에 찍혀서 파일로 둡니다.

1. Mattermost 팀 채널(배포 실패 알림과 같은 채널) → 통합 → Incoming Webhook 추가. 이름은
   `BEEZ 모니터링`, 채널을 고정하고 주소를 복사합니다. 배포 실패 알림의 웹훅을 같이 쓰지
   않습니다. 주소는 저장소·메신저에 붙이지 않습니다.
2. 서버에서 파일을 만듭니다(`webhook URL:`이 뜬 뒤 붙여 넣기).

   ```bash
   # 서버 (ssh beez)
   read -rsp "webhook URL: " U && echo && \
     if [[ "$U" =~ ^https://meeting\.ssafy\.com/hooks/[A-Za-z0-9]+$ ]]; then \
       sudo install -m 640 -o root -g 65534 /dev/null /opt/beez/.alertmanager-webhook-url && \
       printf '%s\n' "$U" | sudo tee /opt/beez/.alertmanager-webhook-url > /dev/null && echo saved; \
     else echo "형식이 다름 — 저장하지 않음"; fi; unset U
   ls -l /opt/beez/.alertmanager-webhook-url
   # -rw-r----- 1 root nogroup ... /opt/beez/.alertmanager-webhook-url
   ```

   - 그룹 `65534`(nogroup): Alertmanager 컨테이너가 `nobody`(65534)로 돕니다. compose의
     파일 secret은 바인드 마운트라 호스트 권한이 그대로 가서, 이 그룹에 읽기를 줘야
     읽힙니다. 다른 사용자는 읽지 못합니다.
3. env 파일에 경로를 더합니다.

   ```bash
   # 서버 (ssh beez)
   grep -q '^ALERTMANAGER_WEBHOOK_URL_FILE=' /opt/beez/.env.monitoring ||
     printf 'ALERTMANAGER_WEBHOOK_URL_FILE=/opt/beez/.alertmanager-webhook-url\n' >> /opt/beez/.env.monitoring
   ```

4. [올리기·업데이트](monitoring.md#올리기업데이트)로 반영합니다. 경로 값이 빠지면 compose가
   `ALERTMANAGER_WEBHOOK_URL_FILE is required`로, 파일이 없으면 `moncheck`가 멈춥니다.

### 주소 바꾸기

주소가 샜거나 웹훅을 바꿀 때는 Mattermost에서 옛 웹훅을 지우고 새로 만든 뒤, **있는 파일에
덮어씁니다.**

```bash
# 서버 (ssh beez). 2단계와 달리 install 없이 tee 로 덮어쓴다
read -rsp "webhook URL: " U && echo && \
  if [[ "$U" =~ ^https://meeting\.ssafy\.com/hooks/[A-Za-z0-9]+$ ]]; then \
    printf '%s\n' "$U" | sudo tee /opt/beez/.alertmanager-webhook-url > /dev/null && echo saved; \
  else echo "형식이 다름 — 저장하지 않음"; fi; unset U
```

- Alertmanager는 이 파일을 **알림을 보낼 때마다** 읽습니다(v0.34.2 `notify/mattermost`의
  `Notify`). 같은 파일에 덮어쓰면 재시작 없이 다음 알림부터 새 주소로 갑니다.
- 2단계 명령(`install`)을 다시 돌리면 파일을 지우고 새로 만듭니다. 그러면 실행 중인
  컨테이너의 바인드 마운트가 옛 파일(지워진 inode)을 계속 가리켜 옛 주소로 보냅니다. 그때는
  `mon up -d --force-recreate alertmanager`로 컨테이너를 다시 만듭니다.

## 규칙 바꿀 때

규칙·Alertmanager 설정을 고치면 PR 전에 로컬에서 돌립니다.

```bash
# 로컬 PC (Git Bash, 저장소 루트)
P=prom/prometheus:v3.15.1; A=prom/alertmanager:v0.34.2   # compose.yaml 과 같은 태그
cd infra/monitoring
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/prometheus:/etc/prometheus:ro" --entrypoint promtool $P check config /etc/prometheus/prometheus.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/prometheus:/etc/prometheus:ro" -w /etc/prometheus/tests --entrypoint promtool $P test rules beez_test.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/alertmanager:/etc/alertmanager:ro" --entrypoint amtool $A check-config /etc/alertmanager/alertmanager.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/alertmanager:/etc/alertmanager:ro" --entrypoint amtool $A config routes test --config.file=/etc/alertmanager/alertmanager.yml --verify.receivers=null alertname=CdcDataUnavailable
cd ../..
```

- `MSYS_NO_PATHCONV=1`: Git Bash가 `/etc/...` 같은 컨테이너 경로를 Windows 경로로 바꾸지 않게 합니다.
- `check config`는 prometheus.yml과 `rule_files`(`rules/*.yml`)가 가리키는 규칙 파일을 함께
  검사합니다. 서버 컨테이너와 같은 경로에 붙여야 `rule_files` 경로가 맞습니다.
- routes test는 `CdcDataUnavailable`이 `null`이 아닌 수신처로 가면 실패(종료 코드 1)합니다.
- 시험은 244 결정과 PR #121 리뷰의 계약만 봅니다(consumer 꺼짐에서 안 울림, 600초 + 5분,
  집계 실패·갱신 멈춤, 오래된 스냅샷 제외·한 인스턴스만 멈추면 억제하지 않음, 집계가 멈추면
  억제 원인이 FAILED 해소보다 먼저 생김, CDC 지표 전부·일부 누락, Kafka 실패·인스턴스별 지표
  누락, FAILED 중복 제거). 억제 자체(Alertmanager)는 promtool이 보지 못해 아래 기록처럼 실측했습니다. 각 시험 위에 깨지면 알게 되는 것을 적었습니다.
- `infra/monitoring/**`를 바꾼 PR에서는 CI `monitoring` 잡이 같은 검사를 돌리고, 실패하면
  `ci-gate`가 실패해 머지할 수 없습니다(S15P31A108-292). 잡은 위 네 명령에 더해, 이미지를
  `compose.yaml`에서 읽고(위 `P`·`A`처럼 손으로 맞추지 않음) `rules/`에 `*.yml`이 아닌
  파일·하위 디렉터리가 있으면 실패합니다(서버가 읽지 않는 규칙 파일). 또 alertmanager.yml에서
  `{{`가 든 값(제목·본문·링크)을 모두 뽑아 `amtool template render`로 실제로 그려 봅니다.
  `check-config`는 템플릿 파일을 읽기만 해서, 없는 템플릿 이름·함수 오타·`templates` 글롭에서
  빠진 파일을 통과시키고 서버에서는 알림을 보낼 때 실패합니다. 로컬 실행은 PR 전에
  빨리 보려는 용도이고, 결과를 PR 본문에 붙이지 않아도 됩니다.
- FAILED·확인 필요 미평가의 24시간 재통지는 routes test가 수신처만 보여 주어 CI에서 고정하지
  않습니다. 바꿀 때는 `config routes show`로 직접 확인합니다.

알림 문구까지 보려면 [로컬에서 검증하기](monitoring.md#로컬에서-검증하기)로 스택을 띄웁니다.
`compose.local.yaml`의 `webhook-catcher`가 가짜 Mattermost로 받아 본문을 로그에 찍습니다.
로컬에는 backend가 없어 2분 뒤 `BackendScrapeDown`이 저절로 옵니다.

```bash
# 로컬 PC (Git Bash, infra/monitoring)
docker compose -f compose.yaml -f compose.local.yaml --env-file local.env logs --no-log-prefix webhook-catcher
```

## 대시보드 바꾸기

대시보드는 파일 프로비저닝이라 화면에서 고쳐도 저장되지 않습니다. 화면에서 고친 뒤
대시보드 설정 → JSON Model(또는 Export → JSON)을 복사해 `grafana/dashboards/<uid>.json`을
바꾸고 PR로 올립니다. 데이터 소스는 uid `prometheus`로 둡니다.

- 파일이 정본입니다(`disableDeletion: false`). JSON을 지우거나 `uid`를 바꾸면 Grafana에서도
  옛 대시보드가 지워집니다. 화면에서 준 별표·버전 기록은 그 대시보드와 함께 사라집니다.
- 올리기를 두 번 연달아 하고 그 사이에 `--force-recreate`를 빼먹으면, 두 번째 올리기가 지우는
  `monitoring.old`가 실행 중인 grafana가 붙인 폴더라 대시보드가 잠시 지워집니다. 다시 만들면
  파일에서 돌아옵니다. 올리기 뒤에는 항상 다시 띄우기까지 합니다.

- CDC 건수 패널은 기록 규칙(`beez_cdc:*`)을 씁니다. 기록 규칙이 없는 지표는 같은 중복 제거
  식(`max(지표 and on(instance) beez_cdc:snapshot_fresh)`)을 씁니다. "DB 집계" 칸도 이 신선도
  기준입니다.
- `or vector(0)`은 표시에만 씁니다. "Kafka 수신 실패"는 consumer가 꺼져 지표가 없을 때 0으로,
  "DB 집계"는 신선한 스냅샷이 없을 때 실패로 보이게 합니다. 알림 규칙에는 쓰지 않습니다.

## 검증 기록

### 2026-11-04 로컬

| 항목 | 결과 |
|---|---|
| `promtool check rules` | 19개 규칙 통과 |
| `promtool test rules` | 6개 시험 통과. 규칙에서 consumer 조건을 뺀 사본으로 돌리면 "consumer 꺼짐" 시험이 실패하는 것 확인 |
| `amtool check-config` | 통과(수신처 1·템플릿 1). FAILED·확인 필요 미평가가 24시간 경로로 가는 것 `config routes show`로 확인 |
| 전달 | 로컬 스택(backend 없음)에서 `BackendScrapeDown`이 2분 뒤 발생 → 가짜 Mattermost(`webhook-catcher`)가 위 "받는 글" 형식의 JSON을 받음. 웹훅 주소는 secret 파일에서 읽힘 |
| 문구 | `amtool alert add`로 넣은 `CdcFailedIntakes`가 담당 "앱 · 양지훈", 한국 시각, 런북 링크 `#cdcfailedintakes`로 옴 |
| 일시 중지 | "알림 일시 중지"의 `silence add`·`query`·`expire`가 그대로 동작. 걸린 동안 `alert query`에서 빠짐 |
| 대시보드 | 두 대시보드가 `BEEZ` 폴더에 프로비저닝됨. 패널 식 57개를 서버 Prometheus에 조회만 해 오류 없음(기록 규칙 패널은 서버 반영 전이라 빈 값, 같은 식을 직접 조회해 값 확인) |
| 리뷰 반영 | 기록 규칙을 알림과 한 그룹(`beez-cdc`)으로 합친 뒤, 같은 변형 시험에서 지연 알림 문구가 더는 갈리지 않음(그룹을 나눴을 때는 17m/16m으로 갈림). 웹훅 파일이 없으면 compose가 경고만 내고 띄우며 Docker가 빈 디렉터리를 만들어 붙이는 것 확인 → `moncheck` 추가, 정상·없음·디렉터리·빈 파일·키 없음·키 중복 6가지 시험 |

### 2026-11-04 서버

커밋 `a666cb0`을 [올리기·업데이트](monitoring.md#올리기업데이트)로 올렸습니다(웹훅 파일·env 키를 먼저 만듦).

| 항목 | 결과 |
|---|---|
| 적재 | 규칙 19개 health ok, 수집 대상 5개 UP, Prometheus가 alertmanager를 알림 전달 대상으로 잡음 |
| 웹훅 파일 | `640 root:nogroup`, 컨테이너 안 `nobody`(65534)가 읽음 |
| consumer 꺼짐 | `beez_cdc:consumer_enabled:max` 0, Kafka 실패 지표 없음(`absent` = 1)인데 `CdcKafkaFailureMetricMissing`·`CdcPendingDelay` 비활성 — consumer 조건이 막음 |
| 실제 수신 | `mon stop node-exporter`(17:39) → `MonitoringTargetDown` pending 17:39:22, firing 17:41:22 → 팀 채널에 발생 글. `mon start`(17:42) → 해소 글. Mattermost 전송 2건, 실패 0 |
| 리뷰 반영 재배포 | 커밋 `f386d84`를 올려 `moncheck` 통과 뒤 다시 띄움. 규칙 그룹 `beez-cdc`(15개)·`beez-infra-alerts`(4개) 모두 health ok, 울리는 알림 없음, 수집 대상 5개 UP, DiskSpaceLow 식이 `/`만 봄, `beez_cdc:*` 기록 계속(consumer 0, 상태 5개), 대시보드 프로비저닝 오류 없음 |
| PR #121 리뷰 반영 | 리뷰 지적 3건(갱신이 멈춘 인스턴스의 옛 값이 `max`에 남음, `up=1`인데 CDC 지표가 전부 빠지면 무알림, 인스턴스별 Kafka counter 누락)을 먼저 promtool로 재현 → 신선도 기록 규칙 `beez_cdc:snapshot_fresh`, `CdcMetricsMissing`, 인스턴스별 `unless`, CDC 상태 알림 `keep_firing_for: 10m`. 시험 10개 통과, 각 수정을 되돌린 사본에서 대응 시험이 실패하는 것 확인 |
| 억제(inhibit) | 로컬 Alertmanager v0.34.2로 실측. ① FAILED 발생 → `CdcDataUnavailable` → (억제 중) FAILED 풀림 → 다시 발생: 처음 "발생" 한 번만 옴(거짓 해소·중복 발생 없음). ② (억제 중) 실제로 풀린 뒤 억제 원인 해소: 해소 글이 오지 않음(한계로 기록). 실제 설정에서 `CdcFailedIntakes`는 suppressed, `CdcKafkaReceiveFailures`는 그대로, `CdcDataUnavailable`은 `null` 경로. 시험 10개 통과, 수정 7가지를 하나씩 되돌린 사본에서 모두 대응 시험이 실패 |
| inhibit 반영 재배포 | 커밋 `15f5687`을 올림. 규칙 그룹 `beez-cdc` 18개·`beez-infra-alerts` 4개 health ok, 수집 대상 5개 UP, `beez_cdc:snapshot_fresh`가 `backend:8080`을 신선으로 잡아 `CdcDataUnavailable` 비활성, Alertmanager에 억제 규칙 적재, 울리거나 억제된 알림 없음 |

### 2026-11-06 로컬 (S15P31A108-261)

Docker Desktop에 `beez_default`를 만들고 서버와 같은 pgvector(`wal_level=logical`)·`infra/cdc/dev/compose.yaml`(javaagent 포함)·
모니터링 스택(`compose.local.yaml`, 가짜 Mattermost)을 띄웠습니다. 커넥터는 `intake-connector.json` 그대로 등록했습니다.

| 항목 | 결과 |
|---|---|
| promtool | 규칙 33개, 시험 32개 통과. 새 조건 다섯 가지(CDC 켜짐 조건, Kafka 수집 정상 조건, MonitoringTargetDown 제외, lag 의 consumer_up 조건 등)를 하나씩 뺀 사본에서 대응 시험이 실패 |
| 수집 | job `kafka`·`connect` UP. 새 대시보드 패널 식 18개 모두 조회 성공(로컬 postgres-exporter는 수집 계정이 없어 슬롯 값만 빔) |
| Connect 지표 | `kafka_connect_connector_status`·`task_status`(status 라벨), task 수, `debezium_streaming_connected` 1. 에이전트 지표 120줄 |
| kafka-exporter | 필터 없으면 `__consumer_offsets` 50파티션 등으로 수백 줄 → 접수 토픽·group 필터. lag·offset 정상, 메모리 약 8MiB |
| broker 중지 | exporter가 마지막 메타데이터로 `kafka_brokers` 1을 계속 냄, 한 번 긁는 데 15~30초 → Prometheus `context deadline exceeded`, `up{job="kafka"}` 0(중지 22초 뒤). 이때 Connect worker가 커넥터를 내려 커넥터 지표가 사라짐(REST는 RUNNING) → `CdcConnectorMissing`에 Kafka 수집 정상 조건 추가. 다시 띄우면 13초 뒤 커넥터·task running |
| 실제 전달 | 커넥터 pause(14:11) → `CdcConnectorNotRunning` pending 14:11:46, 가짜 Mattermost에 "🚨 CdcConnectorNotRunning 발생 (2건)"(커넥터 줄·task 0 줄, 담당 인프라 · 전진, 런북 링크 `#cdcconnectornotrunning`) 14:17 → resume(14:17:43) → "✅ 해소" 14:22(묶음 간격 5분) |
| pause 중 슬롯 | `active = t` 그대로 |
