# dev CDC 커넥터 등록과 backend consumer 켜기 런북 (S15P31A108-295)

발행 접수행 `published_change_analysis`의 변경을 Debezium 커넥터로 Kafka 토픽에 싣고, backend가 그 토픽을 받아
분석을 바로 깨우게 한다. 결정과 근거는 [CDC 배포 계획](cdc-deploy-plan-279.md) D6(커넥터)·D7(backend)이 정본이고,
이 문서는 **그 결정을 서버에서 실행하는 방법과 결과**를 적는다.

- 앞 단계: PostgreSQL 논리 복제·계정·publication·비밀 파일은 [cdc-postgres.md](cdc-postgres.md)(293),
  Kafka·Connect 스택과 접수 토픽은 [cdc-stack.md](cdc-stack.md)(294), 스캔·consumer 분리와 자동 재연결은 S15P31A108-298(PR #164).
- 이 문서가 만드는 것: 커넥터 `beez-published-analysis-1`과 그 복제 슬롯 `beez_published_analysis`, 슬롯 알림 네 개,
  서버 env의 CDC 켜기 두 줄.

| 무엇 | 어디에 | 누가 바꾸나 |
|---|---|---|
| 커넥터 설정 | `infra/cdc/dev/intake-connector.json` → 서버 `/opt/beez/cdc/intake-connector.json` | PR → [cdc-stack.md "올리기·업데이트"](cdc-stack.md#올리기업데이트) → 손으로 등록(아래 3) |
| 커넥터 상태·offset | Connect 내부 토픽(`beez-cdc-connect-*`) | REST(`docker exec`)로만 |
| 복제 슬롯 | PostgreSQL. 커넥터가 처음 붙을 때 만든다 | 커넥터. 지우는 것만 손으로(아래 "되돌리기") |
| 슬롯 알림 `CdcSlotInactive`·`CdcSlotWalRetained`·`CdcSlotMissing`·`CdcSlotCollectorFailing` | `infra/monitoring/prometheus/rules/beez.yml` | PR → [monitoring.md "올리기·업데이트"](monitoring.md#올리기업데이트) |
| 분석 켜기 `BEEZ_CDC_INTAKE_ENABLED`·`BEEZ_CDC_INTAKE_CONSUMER_ENABLED` | 서버 `/opt/beez/.env.dev` (compose 전달은 298이 넣음, 기본 꺼짐) | 손으로(아래 6) → 같은 SHA 재배포 |

## 꼭 지킬 것

- **접수행을 손으로 지우지 않는다.** publication은 DELETE도 내보내고(D5), consumer는 DELETE 이벤트를 건너뛰지 않고
  1초마다 영원히 재시도한다(`AnalysisIntakeEventPolicy`). 한 줄만 지워도 파티션이 막힌다. 막혀도 분석은 복구 스캔이
  이어 가지만 Kafka 경로가 멈춘다([cdc-postgres.md "꼭 지킬 것"](cdc-postgres.md#꼭-지킬-것)).
- **커넥터를 지우기 전에 offset부터 지운다.** 커넥터를 지워도 offset은 Connect 내부 토픽에 남고, 지운 뒤에는 offset API를
  쓸 수 없다. 다시 만들 때는 새 이름을 쓴다(아래 "다시 만들기", 계획 D6).
- **슬롯이 남은 채로 Connect를 오래 멈추지 않는다.** 슬롯이 WAL을 붙잡는다. 오래 내릴 거면 아래 "되돌리기"로 커넥터·슬롯까지 정리한다.
- **비밀번호는 파일로만 넘긴다.** 커넥터 설정에는 `${file:/run/cdc-secrets/database.properties:password}` 자리표시자만 있고,
  Connect가 등록할 때 읽는다. REST 조회 응답에도 자리표시자 그대로 나온다(로컬 실측).
- **문서의 명령 블록을 서버 셸로 파이프하지 않는다.** 한 블록씩 붙여 넣는다.

## 0. 준비 (서버 bash, 셸을 새로 열 때마다)

```bash
# 서버 (ssh beez). 293·294 런북과 같은 함수. 셸을 새로 열면 다시 친다
PG=$(docker ps --filter label=com.docker.compose.project=beez --filter label=com.docker.compose.service=postgres --format '{{.Names}}')
[ -n "$PG" ] && echo "postgres 컨테이너: $PG" || echo "postgres 컨테이너를 찾지 못함 — 아래 명령을 치지 않는다"
pgsql() { docker exec -i "$PG" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 "$@"' _ "$@"; }
cdc() { docker compose -f /opt/beez/cdc/compose.yaml "$@"; }
kt() { docker exec -e KAFKA_HEAP_OPTS=-Xmx128m beez-cdc-kafka-1 /opt/kafka/bin/"$@"; }
# Connect REST 는 컨테이너 안 127.0.0.1 에만 열려 있다(계획 D3). 경로 뒤에 curl 옵션을 더 줄 수 있다.
# -i 를 주지 않는다 — 주면 함께 붙여 넣은 다음 줄을 입력으로 삼킨다. 파일을 보내는 등록(3)만 따로 -i 를 쓴다
crest() { local p=$1; shift; docker exec beez-cdc-connect-1 curl -sS "$@" "http://127.0.0.1:8083$p"; echo; }
# 분석 켜기 env 두 줄을 항상 함께 쓴다: cdcenv <복구 스캔 true|false> <consumer true|false>.
# 명령을 손으로 고치다 한 줄만 남기면 consumer 만 켜진 env 가 되어 backend 가 기동을 거부하므로(298) 그 조합은 쓰기 전에 막는다.
# 기존 두 줄을 뺀 새 파일에 두 줄을 덧붙여 바꾼다(umask 027 + /opt/beez setgid 로 640·beez-deploy 유지).
# 원본을 읽지 못하면 바꾸지 않는다: grep -v 는 0(남은 줄 있음)·1(남은 줄 없음)이 정상이고 2 이상이 읽기 오류다.
# 오류를 묶음 안에서 흘려보내면 뒤의 echo 가 성공해 원본이 두 줄짜리 파일로 바뀐다(PR #166 리뷰)
# .new 는 지우고 새로 만든다 — 남은 .new 에 쓰면 umask 가 안 먹어 그 파일의 옛 권한이 env 파일로 넘어간다.
# 바꿔 넣기(mv)에 실패해도 .new 를 남기지 않는다(S15P31A108-321)
cdcenv() {
  case "$1 $2" in "true true"|"true false"|"false false") ;;
    *) echo "쓰지 않음: '$1 $2' — 가능한 값은 'true true'(켜기)·'true false'(Kafka 경로만 끔)·'false false'(끄기)"; return 1;; esac
  local f=/opt/beez/.env.dev
  [ -f "$f" ] && [ -r "$f" ] || { echo "$f 를 읽을 수 없음 — 바꾸지 않는다"; return 1; }
  ( umask 027
    rm -f "$f.new" && : > "$f.new" || { echo "$f.new 를 새로 만들 수 없음 — 원본 그대로"; exit 1; }
    grep -Ev '^BEEZ_CDC_INTAKE_(ENABLED|CONSUMER_ENABLED)=' "$f" > "$f.new"; rc=$?
    if [ "$rc" -gt 1 ]; then rm -f "$f.new"; echo "$f 읽기 실패(grep $rc) — 원본 그대로"; exit 1; fi
    { echo "BEEZ_CDC_INTAKE_ENABLED=$1"; echo "BEEZ_CDC_INTAKE_CONSUMER_ENABLED=$2"; } >> "$f.new" \
      || { rm -f "$f.new"; echo "새 파일 쓰기 실패 — 원본 그대로"; exit 1; }
    mv "$f.new" "$f" || { rm -f "$f.new"; echo "바꿔 넣기 실패 — 원본 그대로"; exit 1; } ) \
    && grep '^BEEZ_CDC_INTAKE_' "$f" && ls -l "$f"
}
```

- `pgsql`은 `docker exec -i`라 뒤에 붙여 넣은 줄까지 입력으로 삼킨다. 한 줄짜리 조회는 `</dev/null`을 붙인다.
- `cdcenv`로 env를 바꾼 뒤에는 6의 같은 SHA 재배포를 해야 컨테이너에 반영된다.

## 1. 사전 점검 (읽기 전용)

```bash
# 서버. 1) 슬롯이 아직 없어야 한다(0행)
pgsql -c "SELECT slot_name, plugin, active FROM pg_replication_slots" </dev/null
# 2) publication 컬럼 = {id,status}
pgsql -Atc "SELECT attnames FROM pg_publication_tables WHERE pubname = 'beez_published_analysis_cdc'" </dev/null
# 3) 스택 healthy, 커넥터 없음({})
cdc ps --format '{{.Service}}\t{{.Status}}'; crest '/connectors?expand=status'
# 4) 접수 토픽(파티션 1)
kt kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic beez.public.published_change_analysis
# 5) Connect 가 비밀 파일을 읽을 수 있음(내용은 출력하지 않음)
docker exec beez-cdc-connect-1 test -r /run/cdc-secrets/database.properties && echo "읽힘"
```

하나라도 다르면 멈춘다. 1)에 슬롯이 있으면 전에 등록한 흔적이므로 아래 "다시 만들기"를 먼저 본다.

## 2. 커넥터 설정 올리기

[cdc-stack.md "올리기·업데이트"](cdc-stack.md#올리기업데이트)의 명령을 그대로 쓴다. 머지 전에 등록할 때는 `REV`에 이 PR 브랜치의
커밋을 준다. 그랬다면 머지 뒤 `REV=origin/dev`로 한 번 더 올려 서버 폴더의 `REVISION`을 dev 커밋으로 맞춘다.
compose 파일이 그대로면 그다음 `cdc up -d --wait`는 아무것도 다시 만들지 않는다.

**브랜치 커밋을 올리기 전에** 그 커밋이 서버 폴더에 지금 올라가 있는 커밋을 포함하는지 본다. 올리기는 폴더를 통째로 바꾸므로,
브랜치가 dev보다 뒤처져 있으면 그 사이 다른 PR이 서버에 올린 설정이 조용히 사라진다. `포함 안 됨`이면 브랜치를 dev와 동기화한 뒤
다시 확인한다. 5의 모니터링 올리기도 같은 함수로 확인한다(`revcheck monitoring`).

```bash
# 로컬 PC (Git Bash, 저장소 루트). 셸마다 함수를 붙여 넣는다. REV 는 바로 뒤 올리기 명령에 줄 값과 같게
revcheck() {  # 사용: revcheck <cdc|monitoring>   (REV 변수를 먼저 정한다)
  [ -n "$REV" ] || { echo "REV 를 먼저 정한다"; return 1; }
  # ssh 가 실패하면 빈 값이 되므로 "파일 없음"(NONE)과 구분한다
  local cur; cur=$(ssh beez "if [ -f /opt/beez/$1/REVISION ]; then cat /opt/beez/$1/REVISION; else echo NONE; fi") \
    && [ -n "$cur" ] || { echo "서버에서 REVISION 을 읽지 못함(ssh 실패) — 올리지 않는다"; return 1; }
  git fetch -q origin || return 1
  if [ "$cur" = NONE ]; then echo "서버에 REVISION 없음(처음 올리기) — 그대로 올려도 됨"
  elif ! git cat-file -e "$cur^{commit}" 2>/dev/null; then echo "서버 커밋 $cur 을 로컬에서 못 찾음 — 확인 불가, 올리지 않는다"; return 1
  elif git merge-base --is-ancestor "$cur" "$REV"; then echo "포함됨($cur) — 올려도 됨"
  else echo "포함 안 됨 — $REV 에 서버 커밋 $cur 이 없다. 브랜치를 dev 와 동기화한 뒤 다시"; return 1; fi
}
REV=origin/dev   # 머지 전이면 이 PR 브랜치 커밋
revcheck cdc
```

```bash
# 서버. 올라온 설정 확인 — 커넥터 이름은 파일에 없고 아래 3의 경로에 있다. 비밀번호 줄이 자리표시자인지 본다
cat /opt/beez/cdc/REVISION; grep -E '"(slot.name|topic.prefix|database.password)"' /opt/beez/cdc/intake-connector.json
```

설정 값은 계획 D6 그대로다(`database.sslmode=disable`, `topic.prefix=beez`, `slot.name=beez_published_analysis`,
`snapshot.mode=initial`, 스냅샷 SELECT override, `tombstones.on.delete=true`, heartbeat 없음). DELETE를 커넥터에서 거르는
`skipped.operations`는 넣지 않았다(D6 승인 범위 밖, PR에서 따로 제안).

## 3. 등록

```bash
# 서버. PUT …/config 는 없으면 만들고 있으면 같은 값으로 덮는다(두 번 쳐도 안전). 응답에 비밀번호 원문은 없다
docker exec -i beez-cdc-connect-1 curl -sS -X PUT -H 'Content-Type: application/json' --data-binary @- \
  http://127.0.0.1:8083/connectors/beez-published-analysis-1/config < /opt/beez/cdc/intake-connector.json; echo
```

## 4. 등록 확인

```bash
# 서버. 10~20초 뒤. 1) connector·task 모두 RUNNING
crest /connectors/beez-published-analysis-1/status
# 2) 슬롯이 생기고 active = t, retained 는 수 MB 이하
pgsql -c "SELECT slot_name, plugin, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots" </dev/null
# 3) 초기 스냅샷 끝남(기존 접수행이 있으면 그 수만큼 op=r 메시지가 토픽에 감)
# 대소문자를 가리고 ERROR 앞뒤에 공백을 둔다 — -i 면 설정 덤프의 errors.* 줄까지 잡힌다(11-05 서버)
cdc logs connect --since 5m 2>&1 | grep -E "Snapshot (ended|completed)| ERROR " | tail -5
```

- task가 `FAILED`면 `crest /connectors/beez-published-analysis-1/status`의 `trace` 첫 줄을 본다.
  `password authentication failed`면 293 3-3, `permission denied for table`면 293 3-2의 `GRANT`,
  `publication … does not exist`면 293 4-1을 다시 본다. 고친 뒤 `crest /connectors/beez-published-analysis-1/tasks/0/restart -X POST`.
- **접수 토픽에 시험 메시지를 쓰지 않는다.** consumer가 모르는 형식을 받으면 재시도로 파티션이 막힌다(계획 D3).

## 5. 슬롯 알림 올리기 (6의 선행 조건)

**consumer를 켜기 전에 슬롯 알림이 서버 Prometheus에 들어가 있어야 한다**(PR #166 리뷰). 켠 뒤에는 복구 스캔이 분석을 이어
가므로 Connect가 멈추거나 슬롯이 사라져도 다른 알림이 울지 않는다.

- [monitoring.md "올리기·업데이트"](monitoring.md#올리기업데이트)대로 모니터링 스택을 올린다. 머지 전이면 `REV`에 이 PR 브랜치
  커밋을 주고, 머지 뒤 `REV=origin/dev`로 한 번 더 올려 `REVISION`을 맞춘다(2와 같은 방식).
  올리기 전에 2의 `revcheck monitoring`이 `포함됨`인지 본다(모니터링 폴더는 다른 PR도 올리므로 뒤처진 브랜치로 덮으면 그 규칙이 사라진다).
- 확인(읽기 전용): 다시 띄우고 **30초쯤 뒤**에 아래 명령을 친다. 슬롯 알림 네 개가 모두 보이고 health 가 `ok` 면 들어간 것이다.
  바로 치면 첫 평가 전이라 `unknown unknown`이 나온다(11-06 서버).

```bash
# 서버. Prometheus 는 호스트 127.0.0.1:9090 에만 열려 있다(monitoring.md "확인"). 슬롯 규칙의 이름·상태·건강만 뽑는다
curl -s 127.0.0.1:9090/api/v1/rules | python3 -c '
import sys, json
for g in json.load(sys.stdin)["data"]["groups"]:
    for r in g["rules"]:
        if r["name"].startswith("CdcSlot"): print(g["name"], r["name"], r.get("state"), r["health"])'
cat /opt/beez/monitoring/REVISION
```

- 알림 네 개: `CdcSlotInactive`(10분 비활성)·`CdcSlotWalRetained`(WAL 1GiB)·`CdcSlotMissing`(consumer가 켜졌는데 슬롯 지표 없음)·
  `CdcSlotCollectorFailing`(수집기 실패, consumer와 상관없이). 대응은 [monitoring-alerts.md](monitoring-alerts.md#cdcslotinactive).
- 알림을 올리기 전에 consumer를 켜야 하는 사정이 있으면, 올릴 때까지 아래 "상태 보기"를 하루 두 번(오전·오후) 하고
  실행 기록에 그 사실을 적는다.

## 6. consumer 켜기

**5가 확인되고, 4가 RUNNING·`active = t`인 뒤에만 한다**(계획 D7 "켜는 순서"). 켜면 복구 스캔도 같이 켜져, 그동안 쌓인 PENDING을 한꺼번에
집어 AI를 부른다. 그래서 켜기 직전 접수 상태를 적어 둔다.

```bash
# 서버. 1) 켜기 직전 접수 상태 — 결과를 아래 "실행 기록"에 적는다
pgsql -c "SELECT status, count(*) FROM published_change_analysis GROUP BY status ORDER BY status" </dev/null
# 2) env 두 줄(0 준비의 cdcenv). 스캔·consumer 모두 켬
cdcenv true true
sudo -u jenkins test -r /opt/beez/.env.dev && echo "jenkins can read"
# 3) 지금 떠 있는 SHA — 재배포에 쓴다
docker inspect -f '{{.Config.Image}}' beez-backend-1
```

- 2)의 `ls -l`이 `-rw-r----- … ubuntu beez-deploy`여야 jenkins가 읽는다([cd.md "env 파일"](cd.md#2-env-파일)).
- 나머지 셋(`BOOTSTRAP_SERVERS`·`GROUP_ID`·`TOPIC`)은 compose 기본값이 계획 D7 값이라 env 파일에 쓰지 않는다.

이어서 3)의 SHA로 [cd.md "손으로 배포·롤백"](cd.md#손으로-배포롤백)을 돌린다. `deploy.sh`는 env 파일을 읽어
`compose up`을 하므로 env가 바뀐 backend만 다시 만든다. 그 전에 dev에 머지가 들어오면 Jenkins 배포가 같은 일을 한다.

```bash
# 서버. 재배포 뒤 1~2분. 1) backend healthy
docker compose -p beez ps --format '{{.Service}}\t{{.Status}}'
# 2) 스캔·consumer 켜짐, Kafka 에 붙음(모두 1), 수신 실패 0
docker exec beez-backend-1 curl -s http://127.0.0.1:8080/actuator/prometheus \
  | grep -E '^beez_cdc_(analysis_recovery_enabled|analysis_consumer_enabled|kafka_consumer_up|kafka_receive_failures_total)'
# 3) consumer 그룹이 파티션을 받음(LAG 0)
kt kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group beez-published-analysis
```

`kafka_consumer_up`이 1이 되기까지 최대 1분쯤 걸린다(298 실측). 1)이 healthy가 아니면 env를 되돌리고(아래 "되돌리기" 1)
같은 SHA로 다시 배포한다.

## 7. 발행 1건 확인

dev 화면에서 시작된 프로젝트의 변경 제안 하나를 승인·발행한다(계획 §4 8단계).

- 사람이 화면에서 내는 제안은 문서 변경이다: 공용 문서함 → 문서 열기 → **수정 제안하기**(변경 제안 창에는 새 제안 버튼이 없다,
  설계 항목 변경은 에이전트·회의 경로). 승인할 때 영향 분석을 돌리지 않았으면 생략 사유를 적는다.
- 문서 변경 제안의 발행 후 분석은 AI 없이 `NOT_EVALUATED`(`document_change_unavailable`)로 닫힌다. 판정은 상태가 아니라
  `wake_seconds`로 한다(`RUNNING`으로 집을 때 `started_at`이 찍힌다).
- 프로젝트가 아직 시작 전이면(기준 v1 없음) 설계 초안이 있어야 시작할 수 있다. 초안 생성(`design-drafts`)은 AI 스위치
  `DESIGN_DRAFTS_ENABLED`와 후보 채택 화면이 필요하고, 없으면 기획 분석 창의 **항목 추가**로 DRAFT를 만든다(11-06 서버).

그다음:

```bash
# 서버. 최근 접수행. wake_seconds 가 30 미만이면 Kafka 경로로 깨어난 것(복구 스캔은 30초 지난 것만 집음)
pgsql -c "SELECT id, status, round(extract(epoch FROM started_at - requested_at)::numeric, 1) AS wake_seconds, finished_at - requested_at AS total FROM published_change_analysis ORDER BY id DESC LIMIT 3" </dev/null
```

최종 `status`는 `COMPLETED`(또는 근거 부족이면 `NOT_EVALUATED`)여야 한다. `total`에는 AI 시간이 더해지므로 기준으로 쓰지 않는다.
대시보드 `BEEZ CDC 분석`의 접수·평가 패널도 함께 본다.

## 8. 재시작 확인

backend·Connect·Kafka를 하나씩 재시작하고, 각각 뒤에 4의 1)·2)와 6의 2)를 다시 본다. 로컬에서는 Connect 재시작 뒤 약 10초에
RUNNING으로 돌아왔고 스냅샷을 다시 하지 않았다. Kafka를 멈춘 채 backend를 재시작하는 시험((가)·(나), 계획 §4 9단계)은
S15P31A108-229 장애 시험에서 함께 한다.

```bash
# 서버. 하나씩, 사이에 위 확인
docker restart beez-backend-1
docker restart beez-cdc-connect-1
docker restart beez-cdc-kafka-1   # 60초 정리 대기가 있다(stop_grace_period). 그동안 분석은 복구 스캔이 30초 지연으로 처리
```

## 상태 보기

261부터는 커넥터·task 상태를 Prometheus가 수집하고 알린다([cdc-stack.md "지표"](cdc-stack.md#지표-s15p31a108-261),
대시보드 `BEEZ CDC 분석` "Kafka·Connect·슬롯"). 알림을 받았거나 배포·재시작 뒤에는 아래로 직접 본다.

```bash
# 서버 (0 준비 뒤)
crest /connectors/beez-published-analysis-1/status
pgsql -c "SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots" </dev/null
```

- task `FAILED` → 4의 대응. 슬롯 `retained` > 1GB → 원인 확인, > 5GB → 아래 "되돌리기" 2·3번(계획 D6).
- 슬롯 비활성·WAL은 알림도 운다(`CdcSlotInactive`·`CdcSlotWalRetained`). Connect·task 상태는 261의 `CdcConnectDown`·
  `CdcConnectorNotRunning`·`CdcConnectorMissing`이 운다.

## 다시 만들기

설정을 바꾸는 것만이면 3의 `PUT …/config`로 덮는다(offset·슬롯 유지). 슬롯을 지웠거나 슬롯이 무효화돼
(`pg_replication_slots.wal_status = 'lost'`) 처음부터 다시 읽어야 할 때만 아래를 한다. 옛 offset이 새 슬롯에 옛 LSN으로
붙으려 하기 때문이다(계획 D6).

- **기본: 새 이름.** 아래 "되돌리기" 2·3번으로 옛 커넥터·슬롯을 정리하고, 3의 경로 이름 끝 번호를 올려(`…-2`) 등록한다.
  이 문서와 [monitoring-alerts.md](monitoring-alerts.md)의 이름도 함께 고친다.
- 같은 이름이 꼭 필요하면 "되돌리기" 2번의 `stop → offsets 삭제 → 커넥터 삭제` 순서를 지킨다.

## 되돌리기

계획 §4 되돌리기 표의 1~3번이다. **순서대로** 한다.

| 순서 | 되돌리는 것 | 방법 |
|---|---|---|
| 1 | consumer·스캔 (6) | `cdcenv false false`(0 준비) → 같은 SHA 재배포. 이때부터 분석이 멈춘다. Kafka 경로만 끄려면 `cdcenv true false`(분석은 스캔으로 계속) |
| 2 | 커넥터 (3) | 아래 명령. 지우기 전에 stop·offset 삭제 |
| 3 | 슬롯 (3) | 커넥터가 없어 `active = f`인 것을 본 뒤 삭제 |

```bash
# 서버. 2) stop(204) → offset 확인 → 삭제(200, "reset successfully") → [] 확인 → 커넥터 삭제(204)
crest /connectors/beez-published-analysis-1/stop -X PUT -w '%{http_code}'
crest /connectors/beez-published-analysis-1/offsets
crest /connectors/beez-published-analysis-1/offsets -X DELETE
crest /connectors/beez-published-analysis-1/offsets
crest /connectors/beez-published-analysis-1 -X DELETE -w '%{http_code}'
# 3) active = f 확인 뒤 삭제, 0행 확인
pgsql -c "SELECT slot_name, active FROM pg_replication_slots" </dev/null
pgsql -c "SELECT pg_drop_replication_slot('beez_published_analysis')" </dev/null
pgsql -c "SELECT count(*) FROM pg_replication_slots" </dev/null
```

- offset API는 커넥터가 STOPPED여야 쓸 수 있다(pause로는 안 됨). 이미지 안 Kafka Connect 3.7.0이 지원한다.
- 4번 이후(Kafka·Connect, publication·계정, `wal_level`)는 [cdc-stack.md "되돌리기"](cdc-stack.md#되돌리기)와
  [cdc-postgres.md "5. 되돌리기"](cdc-postgres.md#5-되돌리기)를 이어서 한다.

## 로컬 리허설 기록 (11-05, Docker Desktop)

계획 §4 5단계. 서버와 같은 pgvector digest(`wal_level=logical`), `infra/cdc/dev/compose.yaml` 그대로, 로컬 `beez_default` 망,
293 런북 3-2·3-3 명령 그대로(48자 비밀번호 파일), backend는 dev HEAD 이미지(prod 프로필, 두 env `true`).

| 확인 | 결과 |
|---|---|
| 1차(298 전, 3a5c9b2d): consumer 켜고 `kafka` 이름 없음 | **Spring 기동 실패**(`No resolvable bootstrap urls` ← `KafkaListenerEndpointRegistry.start`), 게다가 MCP sweeper 비데몬 스레드로 JVM이 남아 재시작 정책이 돌지 않음 → 298로 해결(PR #164) |
| 등록 | `PUT …/config` → connector·task RUNNING, 슬롯 `pgoutput` `active = t`. 조회 응답의 password는 자리표시자 |
| 이벤트 | 등록 전 행 `op=r`, 이후 INSERT `c`·UPDATE `u`. `after`에 `id`·`status`만 |
| backend 수신 | `consumer_up` 1, 그룹 offset 3/3(LAG 0), 수신 실패 0 |
| Connect 재시작 | 약 10초 뒤 RUNNING, 재스냅샷 없이 새 `c`만 이어짐 |
| 되돌리기 2·3 | stop 204 → offsets(LSN 보임) → DELETE 200 → `[]` → 커넥터 DELETE 204 → 슬롯 `active = f` → drop → 0행 |
| 슬롯 지표(postgres-exporter v0.20.1, `pg_monitor` 계정) | `pg_replication_slots_slot_is_active`, `pg_replication_slots_pg_wal_lsn_diff`(슬롯 생성 뒤 20MB 쓰자 약 21.5MB) |

- 시험 행은 FK 때문에 `SET session_replication_role = replica` 세션에서 `NOT_EVALUATED`로 넣었다. **서버에서는 쓰지 않는다.**
- Git Bash에서 `docker exec … /opt/kafka/bin/…`는 경로가 `C:/Program Files/Git/opt/…`로 바뀐다 → `MSYS_NO_PATHCONV=1`.

## 서버 실행 기록

| 날짜 | 단계 | 결과 |
|---|---|---|
| 11-05 | 1 사전 점검 (전진, 머지 전) | 슬롯 0행, publication `{id,status}`, kafka·connect healthy, 커넥터 `{}`, 토픽 파티션 1·복제 1·`retention.ms=604800000`, 비밀 파일 `읽힘` |
| 11-05 | 2 설정 올리기 (브랜치 커밋 `1bb781bd`) | `/opt/beez/cdc` = `REVISION`·`compose.yaml`·`intake-connector.json`, password 줄은 자리표시자. `cdc up -d --wait` 재생성 없음(Up 3 hours 유지) |
| 11-05 17:41 KST | 3 등록 · 4 확인 | `PUT …/config` 응답의 password 자리표시자. connector·task `RUNNING`, 슬롯 `beez_published_analysis` `pgoutput` `active = t`, retained 152 bytes. `Snapshot ended … status=COMPLETED`, `totalEventCount=0`(접수행 0) |
| 11-05 | 5 슬롯 알림 — **6 전에 하지 않음** | 서버 모니터링은 298 반영본(REVISION `629ab6e0`, `CdcConsumerDown` 포함)이고 이 PR의 슬롯 알림은 없는 채로 6을 진행했다(머지 전 켜기를 택함). 그 사이 감시는 "상태 보기" 수동 확인뿐이었다. 리뷰 지적으로 5를 6의 선행 조건으로 바꿈 |
| 11-05 17:43~17:46 KST | 6 consumer 켜기 | 켜기 직전 접수행 0행. env 두 줄 `true`, `.env.dev` `-rw-r----- ubuntu beez-deploy`·jenkins 읽기 OK. 같은 SHA `9727f475` 손 배포 → backend만 재생성(healthy 22.6초, ai·postgres·seaweedfs 유지), Nginx 확인 OK. `recovery_enabled` 1·`consumer_enabled` 1·`kafka_consumer_up` 1·`kafka_receive_failures_total` 0 |
| 11-06 12:42 KST | 7 앞단계: 프로젝트 1 시작 | 설계 초안 생성(`design-drafts`, S15P31A108-232)은 dev에서 AI 스위치 꺼짐·채택 화면 없음 → 기획 분석 "항목 추가"(REQUIREMENT 1개)로 초안을 만들고 프로젝트 시작. 배포 `bbc75d74`(새 이미지로 backend 재생성 뒤에도 `consumer_up` 1). 접수행 id 1 `NOT_EVALUATED`(change_proposal_id 없음) → 그룹 offset 1/1·LAG 0, 수신 실패 0 = **dev 첫 CDC 이벤트 수신** |
| 11-06 | 7 발행 1건 | 공용 문서함 "수정 제안하기"로 문서 변경 제안 1건 → 영향 분석 생략 사유로 승인(기준 v2). 접수행 id 2(제안 1) **`wake_seconds` 0.8**(Kafka 경로), `NOT_EVALUATED`·`document_change_unavailable`(문서 변경 제안은 AI 없이 닫는 설계), 0.87초에 종료. 그룹 offset 4/4·LAG 0, 수신 실패 0 |
| 11-06 | 8 재시작 | backend: 그 사이 dev 배포로 두 번 재생성 → 매번 `consumer_up` 1로 스스로 회복(consumer ID 바뀜). Connect: 20초 안에 connector·task RUNNING, 슬롯 `t`. Kafka: 1분 뒤 healthy, connector RUNNING, `consumer_up` 1, offset 4/4 그대로(중복·누락 없음), 수신 실패 0 |
| 11-06 13:00 KST | 5 슬롯 알림 (리뷰 반영 뒤) | 브랜치 커밋 `e3f5c121`으로 모니터링 올리기, `mon up -d --force-recreate` 5개 Up. 30초 뒤 `beez-cdc` 그룹에 `CdcSlotInactive`·`CdcSlotWalRetained`·`CdcSlotMissing`·`CdcSlotCollectorFailing` 모두 `inactive ok`. 입력 지표: `slot_is_active` 1, `pg_wal_lsn_diff` 142776, 수집기 성공 1, `beez_cdc:consumer_enabled:max` 1. 머지 뒤 `origin/dev`로 다시 올려 `REVISION` 맞춤 예정 |
| 11-06 13:20 KST | 5 슬롯 알림 (코드 리뷰 반영 뒤) | `revcheck monitoring` → `포함됨(e3f5c121)`, 브랜치 커밋 `b2a4ca43`으로 올리고 `--force-recreate` 5개 Up. 30초 뒤 슬롯 알림 네 개 `inactive ok`, `CdcSlotCollectorFailing` 식 = `pg_scrape_collector_success{collector="replication_slots"} == 0 and on () pg_up == 1`(consumer 조건 없음). 머지 뒤 `origin/dev`로 다시 올려 `REVISION` 맞춤 예정 → 11-06 13:44 KST `/opt/beez/cdc`·`/opt/beez/monitoring`을 dev 머지 커밋 `a83e2244`로 다시 올려 맞춤. 내용이 같아 컨테이너는 다시 만들지 않음 |
