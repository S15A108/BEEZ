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
| [CdcPendingDelay](#cdcpendingdelay) | 가장 오래된 PENDING > 600초 **그리고** consumer 켜짐 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcSnapshotFailing](#cdcsnapshotfailing) | DB 집계 실패, 또는 마지막 성공 120초 초과 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcExpiredLeases](#cdcexpiredleases) | lease 만료 RUNNING > 0 | 5분 | 4시간 | 앱 · 양지훈 |
| [CdcBlockedProjects](#cdcblockedprojects) | 순서상 막힌 프로젝트 > 0 | 10분 | 4시간 | 앱 · 양지훈 |
| [CdcFailedIntakes](#cdcfailedintakes) | FAILED > 0 | 바로 | **24시간** | 앱 · 양지훈 |
| [CdcNotEvaluatedRequiresReview](#cdcnotevaluatedrequiresreview) | 확인 필요 미평가 > 0 | 바로 | **24시간** | 앱 · 양지훈 |
| [CdcKafkaReceiveFailures](#cdckafkareceivefailures) | 최근 5분 Kafka 수신 실패 증가 **그리고** consumer 켜짐 | 바로 | 4시간 | 인프라 · 전진 |
| [CdcKafkaFailureMetricMissing](#cdckafkafailuremetricmissing) | consumer 켜짐인데 실패 지표 없음 | 5분 | 4시간 | 인프라 · 전진 |
| [BackendScrapeDown](#backendscrapedown) | `up{job="backend"} == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [MonitoringTargetDown](#monitoringtargetdown) | backend 외 수집 대상 `up == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [PostgresDown](#postgresdown) | `pg_up == 0` | 2분 | 4시간 | 인프라 · 전진 |
| [DiskSpaceLow](#diskspacelow) | 파일시스템 남은 공간 < 15% | 10분 | 4시간 | 인프라 · 전진 |

- **기준값은 초기값입니다.** 244 결정대로 실제 분석 소요 시간을 보고 조정합니다. 바꾸면
  규칙 파일과 이 표, 시험을 같이 고칩니다.
- **대응 시간**: (팀 결정 대기 — Mattermost 채널과 함께 정해 채웁니다)
- 2분(`BackendScrapeDown` 등)은 배포 때 backend 재시작으로 잠깐 끊기는 것을 거르는 값입니다.
  디스크 15%(약 46GB)는 244 결정에 값이 없어 정한 초기값입니다.
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
- consumer가 꺼진 환경(dev에 Kafka 없음)에서는 울리지 않습니다.

### CdcSnapshotFailing

backend가 지표용 DB 집계에 실패하거나, 집계 값이 120초 넘게 갱신되지 않은 상태가 5분
이어졌습니다. 이 동안 CDC 건수 지표가 비어 다른 CDC 알림은 울리지 않습니다.

- 볼 곳: `BEEZ CDC 분석`의 "DB 집계"·"마지막 집계 뒤", `BEEZ 서버·DB`의 PostgreSQL 연결·
  Hikari 대기. backend 로그(집계 실패는 예외 원문 없이 상태만 남깁니다).
- DB 자체 문제(연결 고갈·DB 다운)면 인프라(전진)와 같이 봅니다.

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
  리스너에 닿기 전 오류는 이 지표에 잡히지 않습니다(S15P31A108-261에서 lag·Connect로 봄).

### CdcKafkaFailureMetricMissing

consumer 설정은 켜졌는데 Kafka 수신 실패 counter가 5분 넘게 없습니다. counter는 리스너가
등록될 때 0으로 생기므로, 리스너가 뜨지 않았거나 지표 등록이 빠진 것입니다.

- 볼 곳: backend 기동 로그의 Kafka 리스너 등록, 배포된 설정(consumer 관련 env).

### BackendScrapeDown

Prometheus가 `backend:8080/actuator/prometheus`를 2분 넘게 긁지 못합니다. 이 동안 CDC
지표가 없어 CDC 알림은 울리지 않습니다.

- 볼 곳: Prometheus `targets` 화면의 오류 문구. `401`이면 보안 설정에서 경로가 닫힌 것
  ([monitoring.md](monitoring.md#구성)), 연결 실패면 backend 컨테이너 상태
  (`docker ps`, [운영 안내](operations.md)).

### MonitoringTargetDown

backend 외 수집 대상(prometheus·alertmanager·node·postgres exporter)이 2분 넘게 응답하지
않습니다.

- 볼 곳: `mon ps`([monitoring.md](monitoring.md#4-띄우기)의 `mon` 함수), 그 컨테이너 로그.
  메모리 한도에 닿아 재시작을 반복하는지 봅니다.
- alertmanager가 죽으면 이 알림도 보내지 못합니다(아래 한계).

### PostgresDown

postgres-exporter가 2분 넘게 DB에 접속하지 못합니다.

- 볼 곳: 본 스택 `postgres` 컨테이너 상태. DB는 정상인데 이것만 울리면 수집 계정
  `beez_monitor` 문제입니다. DB 볼륨을 새로 만들었으면
  [수집 계정 단계](monitoring.md#2-db-수집-계정)를 다시 돌립니다.

### DiskSpaceLow

파일시스템 남은 공간이 15% 아래로 10분 넘게 있습니다.

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
- **집계 실패 중에는 CDC 건수 알림이 풀립니다.** 대신 `CdcSnapshotFailing`이 웁니다.
- Kafka lag·Connect 상태·replication slot WAL 알림은 S15P31A108-261입니다.
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

264에서 확인한 범위는 아래 "검증 기록"입니다. 위 표의 CDC 시나리오는 Kafka·consumer가
있는 환경이 필요해 #229에서 확인합니다.

## 웹훅 넣기 (서버당 한 번)

Alertmanager는 웹훅 주소를 파일(`webhook_url_file`)로 읽습니다. 주소를 compose나 env에
두면 `docker inspect`·`compose config`에 찍혀서 파일로 둡니다.

1. Mattermost 채널 → 통합 → Incoming Webhook 추가. 채널을 고정하고 주소를 복사합니다.
   주소는 저장소·메신저에 붙이지 않습니다.
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

4. [올리기·업데이트](monitoring.md#올리기업데이트)로 반영합니다. 값이 빠지면
   `mon config --quiet`가 `ALERTMANAGER_WEBHOOK_URL_FILE is required`로 멈춥니다.

주소가 샜으면 Mattermost에서 그 웹훅을 지우고 새로 만든 뒤 2단계를 다시 하고
`mon restart alertmanager`를 합니다(파일은 기동 때 읽음).

## 규칙 바꿀 때

규칙·Alertmanager 설정을 고치면 PR 전에 로컬에서 돌립니다.

```bash
# 로컬 PC (Git Bash, 저장소 루트)
P=prom/prometheus:v3.15.1; A=prom/alertmanager:v0.34.2   # compose.yaml 과 같은 태그
cd infra/monitoring
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/prometheus:/p" -w /p/tests --entrypoint promtool $P check rules ../rules/beez.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/prometheus:/p" -w /p/tests --entrypoint promtool $P test rules beez_test.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/alertmanager:/etc/alertmanager:ro" --entrypoint amtool $A check-config /etc/alertmanager/alertmanager.yml
cd ../..
```

- `MSYS_NO_PATHCONV=1`: Git Bash가 `/p` 같은 컨테이너 경로를 Windows 경로로 바꾸지 않게 합니다.
- 시험은 244 결정의 계약만 봅니다(consumer 꺼짐에서 안 울림, 600초 + 5분, 집계 실패·갱신
  멈춤, Kafka 실패·지표 누락, FAILED 중복 제거). 각 시험 위에 깨지면 알게 되는 것을 적었습니다.
- CI에서는 돌지 않습니다(S15P31A108-264 범위 밖).

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

- CDC 건수 패널은 기록 규칙(`beez_cdc:*`)을 씁니다. 기록 규칙이 없는 지표는 같은 중복 제거
  식(`max(지표 and on(instance) beez_cdc_analysis_snapshot_available == 1)`)을 씁니다.
- `or vector(0)`은 "Kafka 수신 실패" 표시에만 씁니다(consumer가 꺼져 지표가 없을 때 0으로
  보이게). 알림 규칙에는 쓰지 않습니다.

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
