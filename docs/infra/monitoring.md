# 모니터링 런북

dev 서버의 서버·PostgreSQL·백엔드 지표를 Prometheus로 모으고 Grafana로 보는
스택입니다. 결정 근거는 S15P31A108-244 [결정 (11-04)]이고, 이 문서는 수집 스택
(S15P31A108-263) 범위입니다. 대시보드·알림 규칙·Mattermost 연결은
S15P31A108-264, Kafka·Connect·WAL 수집은 S15P31A108-261에서 더합니다.

[서버 세팅](server-setup.md) · [CD](cd.md) 런북을 마쳐 본 스택(compose 프로젝트
`beez`)이 떠 있는 상태를 전제로 합니다.

## 구성

```
                 ┌─────────── monitoring 네트워크 ───────────┐
 SSH 터널 ─▶ grafana ─▶ prometheus ─▶ node-exporter          │
 (127.0.0.1)              │  │   └─▶ alertmanager            │
                          │  └──────▶ postgres-exporter ─┐   │
                 └────────┼──────────────────────────────┼───┘
                 ┌────────┼── beez_default (본 스택) ────┼───┐
                          └─▶ backend:8080              postgres:5432
                              /actuator/prometheus
```

| 항목 | 값 |
|---|---|
| compose 파일 | `infra/monitoring/compose.yaml` (프로젝트 이름 `beez-monitoring`) |
| 서버 위치 | `/opt/beez/monitoring/` — 저장소의 `infra/monitoring/`을 그대로 푼 것 |
| 서버 값 | `/opt/beez/.env.monitoring` — 비밀값 포함, 저장소에 없음 |
| 수집 주기 | 15초 (`prometheus/prometheus.yml`) |
| 보존 | 30일 또는 10GB 중 먼저 닿는 쪽 |
| 호스트 포트 | Grafana `127.0.0.1:3000`, Prometheus `127.0.0.1:9090`. 나머지는 열지 않음 |
| 배포 | 수동(아래 "올리기·업데이트"). Jenkins 배포 잡에는 연결하지 않음 |

| 서비스 | 이미지 | 하는 일 | 메모리 한도 |
|---|---|---|---|
| prometheus | `prom/prometheus:v3.15.1` | 수집·저장 | 1GiB |
| grafana | `grafana/grafana:13.2.5` | 화면 | 1GiB |
| alertmanager | `prom/alertmanager:v0.34.2` | 알림 전달. 지금은 수신처 없음 | 128MiB |
| node-exporter | `prom/node-exporter:v1.12.1` | 서버 CPU·메모리·디스크 | 128MiB |
| postgres-exporter | `prometheuscommunity/postgres-exporter:v0.20.1` | PostgreSQL 통계 | 128MiB |

- **본 스택과 프로젝트를 나눕니다.** `deploy.sh`는 `compose up --remove-orphans`로
  `beez` 프로젝트에 없는 컨테이너를 지웁니다. 같은 프로젝트에 넣으면 배포할 때마다
  모니터링이 지워집니다. 따로 두면 배포와 무관하게 계속 수집합니다.
- **본 스택 네트워크에는 수집에 필요한 둘만 붙입니다.** prometheus(→ backend)와
  postgres-exporter(→ postgres)만 `beez_default`에 붙고, grafana·alertmanager는
  `monitoring`에만 있습니다. backend·ai 컨테이너에서 Grafana에 닿지 않습니다.
  `beez_default`는 본 스택이 만드는 네트워크라 본 스택이 먼저 떠 있어야 합니다.
- **backend는 컨테이너 이름으로 긁습니다.** 호스트 포트(8081)나 Nginx를 거치지
  않습니다. 지표 경로는 S15P31A108-262(#109)가 8080에 인증 없이 열고, 외부는
  Nginx가 `/actuator/`를 막습니다. backend가 `401`로 DOWN이면 보안 설정에서 이
  경로가 닫힌 것입니다(262 배포 전 상태).
- **메모리 한도를 둡니다.** 같은 서버의 DB·backend가 모니터링 때문에 OOM에 걸리지
  않게 합니다. 로컬 실측(빈 상태)은 grafana 약 250MiB, prometheus 약 45MiB,
  나머지 각 15MiB 이하였습니다. 한도에 닿으면 그 컨테이너만 재시작됩니다.
- **node-exporter는 호스트 네트워크를 쓰지 않습니다.** 호스트 네트워크면 9100이
  호스트에 열립니다. 대신 호스트 NIC 트래픽 지표는 없습니다(컨테이너 자기
  인터페이스만 보임). CPU·메모리·디스크는 호스트 값입니다(`/`를 읽기 전용으로
  붙이고 pid 네임스페이스 공유).

### 같은 서버에 두는 이유

244 작업 내용 4번 "별도 모니터링 서버 가능 여부"에 대한 지금의 답입니다. dev
서버는 4 vCPU·RAM 15GiB에 사용 약 1GiB·가용 13GiB, 디스크 309GB 중 10GB를
씁니다(2026-11-04). 스택의 메모리 한도 합이 약 2.4GiB이고 실제 사용은 그보다
훨씬 적어 본 서비스와 자원을 다투지 않습니다. 서버를 하나 더 받는 2서버 구조는
보류 중이라 dev 하나만 먼저 연결했고, 같은 서버면 Docker 네트워크 안에서 바로
긁어 수집 포트를 밖에 열 필요가 없습니다. 한계는 **서버 자체가 죽으면
모니터링도 같이 죽어 그 사실을 알리지 못한다**는 점입니다. 서버 생존을 바깥에서
확인하는 일은 별도 모니터링 서버를 검토할 때 함께 정합니다.

## 접근 경계

- Grafana·Prometheus는 `127.0.0.1`에만 열고 SSH 터널로만 봅니다. grafana
  서브도메인은 DNS·인증서가 필요해 보류했습니다(263 본문).
- `0.0.0.0`으로 열면 Docker가 iptables에 직접 규칙을 넣어 ufw를 우회합니다
  ([서버 세팅](server-setup.md#주의-docker-포트-공개는-ufw를-우회한다)). 포트를
  더할 때도 `127.0.0.1:`을 붙입니다.
- ufw·호스트 Nginx는 바꾸지 않았습니다.
- Grafana는 관리자 계정 하나만 씁니다. 가입·익명 접근을 끄고 사용 통계·업데이트
  확인도 끕니다.
- 지표 라벨에 프로젝트 ID·문서 원문을 넣지 않습니다(244 결정 4). 수집 설정에서
  라벨을 더하지 않고, postgres-exporter는 쿼리 원문을 라벨로 내는
  `stat_statements` 수집기를 켜지 않습니다. 확인 방법은 아래 "확인" 5.

## 보존 기간과 디스크

**30일 또는 10GB 중 먼저 닿는 쪽**에서 오래된 블록부터 지웁니다
(`prometheus/prometheus.yml`의 `storage.tsdb.retention`). v3.15부터
`--storage.tsdb.retention.*` 실행 플래그는 deprecated라 설정 파일에 둡니다.

| 근거 | 값 |
|---|---|
| 시계열 수 | 실측 4,816(2026-11-04 서버, 그중 backend 226). 요청 경로·테이블이 늘면 증가하므로 여유를 두어 약 6,000으로 계산 |
| 하루 샘플 | 15초 주기 → 시계열당 5,760 |
| 압축 뒤 크기 | 샘플당 약 1~2바이트 → 6,000 기준 하루 약 35~70MB, 30일 약 1~2GB |
| 디스크 | 309GB 중 10GB 사용(2026-11-04) → 상한 10GB는 디스크의 약 3% |

- **30일**: 244 알림 기준은 "실제 분석 소요 시간을 보고 조정"하기로 한 초기값입니다.
  조정하려면 몇 주 치 추세가 필요해 한 달을 둡니다.
- **10GB**: 예상치의 약 5배입니다. 라벨 실수로 시계열이 폭증해도 디스크를 이
  이상 먹지 않게 막는 안전장치이고, 평소에는 30일이 먼저 닿습니다. 상한은 블록
  기준이라 지워지지 않는 WAL만큼(수백 MB) 더 쓸 수 있습니다.
- 실제 값은 하루 이상 돈 뒤 "확인" 3으로 보고 예상과 크게 다르면 이 표를 고칩니다.

## 처음 올리기 (서버당 한 번)

### 1. env 파일

```bash
# 서버 (ssh beez)
( set -C; umask 027
  printf '%s\n' \
    '# BEEZ 모니터링 환경값 (S15P31A108-263). 비밀값 포함: 저장소·메신저에 올리지 않는다' \
    "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)" \
    "MONITOR_DB_PASSWORD=$(openssl rand -hex 24)" > /opt/beez/.env.monitoring ) && ls -l /opt/beez/.env.monitoring
# -rw-r----- 1 ubuntu beez-deploy ... /opt/beez/.env.monitoring
```

- 본 스택의 `.env.dev`와 나눕니다. 모니터링 값은 배포 잡(jenkins)이 읽을 필요가
  없고, 본 스택 env 파일을 다시 만들지 않는다는 규칙([cd.md](cd.md#2-env-파일))도
  건드리지 않습니다.
- `set -C`로 덮어쓰기를 막습니다. 이미 있으면 "cannot overwrite existing file"로
  멈춥니다. `MONITOR_DB_PASSWORD`를 바꿀 때는 아래 "비밀번호 바꾸기"를 따릅니다.
- `umask 027`로 `640`이 되고 그룹은 `/opt/beez`의 setgid로 `beez-deploy`입니다.
- 키는 둘입니다. `GRAFANA_ADMIN_USER`(기본 `admin`)·`MONITOR_DB_USER`(기본
  `beez_monitor`)·`DB_NAME`(기본 `beez`)은 기본값을 쓸 때 적지 않습니다.

### 2. DB 수집 계정

postgres-exporter 전용 계정 `beez_monitor`를 만듭니다. 권한은 `pg_monitor`
하나입니다. 통계 뷰(`pg_stat_*`)와 설정은 읽지만 테이블 데이터(문서 원문 등)는
읽지 못하고, 기본 트랜잭션도 읽기 전용입니다. SQL은
`infra/monitoring/postgres/monitor-role.sql`입니다(3단계로 서버에 올라간 뒤 실행).

```bash
# 서버 (ssh beez). 3단계 "파일 올리기"를 먼저 한 뒤
MONITOR_DB_PASSWORD=$(sed -n 's/^MONITOR_DB_PASSWORD=//p' /opt/beez/.env.monitoring) \
  docker exec -i -e MONITOR_DB_PASSWORD beez-postgres-1 \
  psql -U "$(sed -n 's/^DB_USERNAME=//p' /opt/beez/.env.dev)" -d beez -f - \
  < /opt/beez/monitoring/postgres/monitor-role.sql
#    rolname    |  member_of   | table_grants
# --------------+--------------+--------------
#  beez_monitor | {pg_monitor} |            0
```

- 비밀번호는 명령 인자가 아니라 환경변수로 넘기고 psql이 `\getenv`로 읽습니다.
  인자로 넘기면 컨테이너 프로세스 목록에 보입니다. `-e MONITOR_DB_PASSWORD`처럼
  값 없이 쓰면 docker가 지금 셸의 값을 넘깁니다.
- 접속은 본 스택 DB 관리자(`.env.dev`의 `DB_USERNAME`)로 컨테이너 안 소켓을
  씁니다.
- 다시 돌려도 됩니다. 계정이 있으면 비밀번호만 env 파일 값으로 맞춥니다.
- 값이 없거나 16자 미만이면 계정을 만들지 않고 오류(종료 코드 3)로 멈춥니다.
- **DB 볼륨(`beez_pgdata`)을 새로 만들면 계정도 사라집니다.** 이 단계를 다시
  돌립니다. 그 전까지 `pg_up`이 0입니다.

### 3. 파일 올리기

아래 "올리기·업데이트"의 명령을 그대로 씁니다. 처음에는 기존 폴더가 없어 옮기는
단계를 건너뜁니다.

### 4. 띄우기

```bash
# 서버 (ssh beez)
mon() { docker compose -f /opt/beez/monitoring/compose.yaml --env-file /opt/beez/.env.monitoring "$@"; }
mon config --quiet && mon up -d && mon ps --format '{{.Service}}\t{{.Status}}\t{{.Ports}}'
```

- `mon`은 이 셸에서만 쓰는 함수입니다. 아래 명령들도 이 함수를 씁니다.
  새 셸에서는 이 줄을 다시 붙여 넣습니다.
- `config --quiet`가 env 파일 필수값을 먼저 검사합니다. 빠지면
  `GRAFANA_ADMIN_PASSWORD is required`처럼 이름만 찍고 멈춥니다.
- 이미지는 digest로 고정돼 있어 처음 한 번만 받습니다.

## 확인

`ssh beez`로 들어가 위 `mon` 함수를 정의한 셸에서 칩니다(5번 마지막 줄만 로컬 PC).

```bash
# 서버 (ssh beez)
# 1) 수집 대상. 다섯 job(alertmanager·backend·node·postgres·prometheus) 모두 up
curl -s 127.0.0.1:9090/api/v1/targets | python3 -c '
import sys, json
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    print(t["labels"]["job"].ljust(13), t["health"].ljust(5), t["lastError"][:70])'

# 2) 열린 포트. 3000·9090 이 127.0.0.1 에만 있어야 한다(0.0.0.0·[::] 이면 외부 노출)
sudo ss -ltnp | grep -E ':(3000|9090|9093|9100|9187)\b'

# 3) 보존 설정과 디스크 사용량(하루 이상 돈 뒤 "보존 기간과 디스크" 예상과 비교)
curl -s 127.0.0.1:9090/api/v1/status/runtimeinfo | python3 -c 'import sys,json; print("보존", json.load(sys.stdin)["data"]["storageRetention"])'   # 30d or 10GiB
curl -s 127.0.0.1:9090/api/v1/query --data-urlencode 'query=prometheus_tsdb_head_series' | python3 -c 'import sys,json; print("시계열", json.load(sys.stdin)["data"]["result"][0]["value"][1])'
sudo du -sh /var/lib/docker/volumes/beez-monitoring_prometheus-data

# 4) 재기동 뒤 데이터 유지. 내리기 전 시각의 값이 다시 띄운 뒤에도 조회되면 통과
T0=$(date +%s); mon down && mon up -d && sleep 20
curl -s 127.0.0.1:9090/api/v1/query --data-urlencode 'query=count_over_time(pg_up[10m])' --data-urlencode "time=$T0"

# 5) 라벨에 프로젝트 ID·원문이 없는지. 라벨 이름마다 값 개수와 앞의 몇 개를 찍는다
for n in $(curl -s 127.0.0.1:9090/api/v1/labels | python3 -c 'import sys,json; print(" ".join(json.load(sys.stdin)["data"]))'); do
  [ "$n" = __name__ ] && continue
  curl -s "127.0.0.1:9090/api/v1/label/$n/values" | python3 -c "import sys,json; v=json.load(sys.stdin)['data']; print('$n', len(v), v[:5])"
done | cut -c1-160
```

- 5)에서 볼 것: UUID·긴 숫자 ID가 값으로 깔린 라벨, 문장처럼 보이는 값, 값 개수가
  계속 늘어나는 라벨. 특히 backend의 `uri`는 템플릿 경로(`/api/projects/{projectId}`
  꼴)여야 하고 실제 ID가 박힌 경로가 보이면 안 됩니다. backend만 보려면 `labels`·
  `label/<이름>/values` 요청에 `-G --data-urlencode 'match[]={job="backend"}'`를 붙입니다.
  backend에 지표나 API를 더한 PR이 배포되면 한 번 돌립니다.

외부에서 닿지 않는지는 로컬 PC에서 봅니다.

```bash
# 로컬 PC (Git Bash). 전부 000(연결 실패·시간 초과)이어야 한다
for p in 3000 9090 9093 9100 9187; do
  printf '%s ' $p; curl -s -m 5 -o /dev/null -w '%{http_code}\n' "http://k15a108.p.ssafy.io:$p/"
done
```

## SSH 터널로 접속

```bash
# 로컬 PC (Git Bash 또는 PowerShell). 연결을 열어 둔 채로 브라우저를 쓴다. 끝내려면 Ctrl+C
ssh -N -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 beez
```

- `-L 3000:127.0.0.1:3000`은 로컬 PC의 3000을 서버의 `127.0.0.1:3000`으로
  잇습니다. `-N`은 셸을 열지 않고 터널만 유지합니다.
- 브라우저에서 Grafana는 <http://localhost:3000>, Prometheus는
  <http://localhost:9090/targets>입니다.
- 로컬 PC에서 3000을 이미 쓰고 있으면(프론트 개발 서버 등)
  `-L 13000:127.0.0.1:3000`처럼 앞의 숫자만 바꾸고 `localhost:13000`으로 엽니다.
- Grafana 로그인은 `admin`과 env 파일의 비밀번호입니다. 서버에서
  `sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' /opt/beez/.env.monitoring`으로 봅니다.
  채팅·메신저에 붙여 넣지 않습니다.

## 올리기·업데이트

설정이나 이미지 버전을 바꿀 때는 PR로 `infra/monitoring/`을 고쳐 dev에 넣은 뒤
아래를 돌립니다. 서버에서 파일을 직접 고치지 않습니다(다음 올리기에 덮입니다).

```bash
# 로컬 PC (Git Bash, 저장소 루트). REV 는 올릴 커밋. PR 검증 중이면 그 브랜치의 커밋(HEAD 등)
REV=origin/dev
git fetch -q origin && SHA=$(git rev-parse "$REV") && echo "$SHA" &&
git archive "$SHA" infra/monitoring | ssh beez "set -e; cd /opt/beez
  rm -rf monitoring.new && mkdir monitoring.new
  tar -x -C monitoring.new --strip-components=2
  rm -f monitoring.new/local.env monitoring.new/compose.local.yaml
  echo $SHA > monitoring.new/REVISION
  rm -rf monitoring.old; if [ -d monitoring ]; then mv monitoring monitoring.old; fi
  mv monitoring.new monitoring && ls monitoring && cat monitoring/REVISION"
```

- `git archive <SHA> infra/monitoring`은 그 커밋의 `infra/monitoring/`만 묶습니다.
  로컬 작업 트리의 고치다 만 파일이 섞이지 않습니다.
- 새 폴더에 다 푼 뒤 이름을 바꿉니다. 직전 것은 `monitoring.old`로 하나 남겨
  되돌릴 때 씁니다(`REVISION`에 커밋이 적혀 있음).
- 로컬 검증용 `local.env`·`compose.local.yaml`은 서버에 두지 않습니다.

그다음 서버에서 다시 띄웁니다.

```bash
# 서버 (ssh beez). mon 함수는 "처음 올리기" 4단계
mon config --quiet && mon up -d --force-recreate && mon ps --format '{{.Service}}\t{{.Status}}'
```

- `--force-recreate`를 붙입니다. 설정 파일은 바인드 마운트라 내용만 바뀌면
  compose가 변경을 알아채지 못해 컨테이너를 그대로 둡니다. 다시 만들어야 새
  폴더의 파일을 붙입니다. 수집이 수 초 비는 것 말고 데이터는 볼륨에 남습니다.
- 이미지 버전을 올릴 때는 compose 파일의 태그와 digest를 함께 바꿉니다. digest는
  Docker Hub 태그 화면이나
  `docker buildx imagetools inspect prom/prometheus:<태그>`의 `Digest:`(멀티
  아키텍처 인덱스)를 씁니다.
- 되돌리기: 서버에서 `cd /opt/beez && mv monitoring monitoring.bad && mv
  monitoring.old monitoring` 뒤 위 명령으로 다시 띄웁니다. 또는 직전 커밋으로
  올리기를 다시 합니다.

## 중지·삭제

```bash
# 서버 (ssh beez)
mon stop                 # 멈추기만. 컨테이너·데이터 그대로, mon start 로 다시
mon down                 # 컨테이너·monitoring 네트워크 삭제. 볼륨(데이터)은 남음
# mon down -v            # 볼륨까지 삭제 — 수집 이력·Grafana 설정이 사라진다. 스택을 영구히 없앨 때만
```

- 본 스택 배포(`deploy.sh`)는 모니터링을 건드리지 않습니다. 본 스택을 `down`하면
  `beez_default`가 사라지려다 모니터링 컨테이너가 붙어 있어 실패합니다
  ("has active endpoints"). 그때는 모니터링을 먼저 `mon down`합니다.
- 서버 재부팅 뒤에는 `restart: unless-stopped`로 Docker가 다시 띄웁니다.
  `mon stop`으로 멈춘 것은 다시 띄우지 않습니다.

## 백업 대상

| 대상 | 내용 | 백업 | 잃었을 때 |
|---|---|---|---|
| `infra/monitoring/` | 설정 전부 | 저장소가 정본 | 다시 올리기 |
| `/opt/beez/.env.monitoring` | Grafana 관리자·DB 수집 계정 비밀번호 | 하지 않음 | 새로 만들고 2단계 SQL 다시 실행, Grafana 비밀번호 재설정 |
| `beez-monitoring_prometheus-data` | 수집 이력(최대 30일) | 하지 않음 | 그 시점부터 다시 쌓임. 서비스 영향 없음 |
| `beez-monitoring_grafana-data` | 로그인 정보·화면에서 만든 것 | 하지 않음 | 데이터 소스는 파일로 다시 생김. 대시보드는 264에서 파일로 저장소에 둘 예정 |
| `beez-monitoring_alertmanager-data` | 알림 일시 중지·통지 이력 | 하지 않음 | 일시 중지를 다시 건다 |

- 정본은 저장소와 env 파일이고, 볼륨은 다시 쌓이는 값이라 따로 백업하지
  않습니다. 대시보드를 화면에서만 만들고 저장소에 넣지 않으면 grafana-data와 함께
  사라집니다(264에서 파일로 관리).

## 비밀번호 바꾸기

### Grafana 관리자

`GRAFANA_ADMIN_PASSWORD`는 **grafana-data 볼륨이 처음 만들어질 때만** 쓰입니다.
env 파일만 바꾸면 Grafana에는 반영되지 않습니다.

```bash
# 서버 (ssh beez). 새 값을 만들어 Grafana 와 env 파일에 같이 넣는다
K=$(openssl rand -hex 16)
printf '%s' "$K" | mon exec -T grafana grafana cli admin reset-admin-password --password-from-stdin &&
( umask 027; { grep -v '^GRAFANA_ADMIN_PASSWORD=' /opt/beez/.env.monitoring; printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$K"; } > /opt/beez/.env.monitoring.new ) &&
mv /opt/beez/.env.monitoring.new /opt/beez/.env.monitoring && unset K && echo saved
```

### DB 수집 계정

```bash
# 서버 (ssh beez). env 파일 값을 바꾼 뒤 2단계 SQL 을 다시 돌려 DB 를 맞추고 exporter 를 다시 만든다
K=$(openssl rand -hex 24)
( umask 027; { grep -v '^MONITOR_DB_PASSWORD=' /opt/beez/.env.monitoring; printf 'MONITOR_DB_PASSWORD=%s\n' "$K"; } > /opt/beez/.env.monitoring.new ) &&
mv /opt/beez/.env.monitoring.new /opt/beez/.env.monitoring && unset K && echo saved
# → "처음 올리기" 2단계 명령을 그대로 다시 실행
mon up -d postgres-exporter   # env 값이 바뀌어 이 컨테이너만 다시 만들어진다
```

## 로컬에서 검증하기

PR을 올리기 전에 로컬 Docker로 같은 구성을 띄워 봅니다.

```bash
# 로컬 PC (Git Bash, 저장소 루트)
docker compose -f infra/compose.yaml --env-file infra/local.env up -d --wait postgres   # beez_default 생성
MONITOR_DB_PASSWORD=$(sed -n 's/^MONITOR_DB_PASSWORD=//p' infra/monitoring/local.env) \
  docker exec -i -e MONITOR_DB_PASSWORD beez-postgres-1 psql -U beez -d beez -f - < infra/monitoring/postgres/monitor-role.sql
cd infra/monitoring && docker compose -f compose.yaml -f compose.local.yaml --env-file local.env up -d
# http://localhost:9090/targets , http://localhost:3000 (admin / local.env 값)
docker compose -f compose.yaml -f compose.local.yaml --env-file local.env down -v && cd ../..
docker compose -f infra/compose.yaml --env-file infra/local.env down
```

- `compose.local.yaml`은 node-exporter의 루트 마운트에서 `rslave`만 뺍니다.
  Docker Desktop(WSL2)은 `rslave`로 붙이지 못해 "not a shared or slave mount"로
  뜨지 않습니다. 서버(Ubuntu)는 그대로 씁니다.
- 로컬에는 backend를 띄우지 않았으므로 backend 대상이 DOWN이고, 파일시스템
  지표도 WSL 마운트가 많이 보입니다. 서버에서는 `/`와 부트 파티션 정도만 남습니다.
- 2026-11-04 로컬 결과: 대상 4개 UP(backend 제외), 재기동 뒤 이전 값 조회,
  Grafana 데이터 소스 정상, 계정 SQL 재실행·빈 값 거부, `beez_monitor`의 테이블
  조회 `permission denied` 확인.

## 2026-11-04 서버 결과

커밋 `6925db7`을 올려 처음 띄웠습니다. backend 줄은 #109 배포(`6be547d`) 뒤에 확인했습니다.

| 항목 | 결과 |
|---|---|
| 수집 대상 | 다섯 job 모두 UP, `pg_up` 1. backend는 #109 배포 전 401 → 배포 뒤 UP(수집 13ms) |
| backend 지표 | 시계열 226. `beez_cdc_*` 23개 수집, `beez_cdc_analysis_snapshot_available` 1 |
| 포트 | 서버에서 `127.0.0.1:3000`·`127.0.0.1:9090`만 열림. 로컬 PC에서 3000·9090·9093·9100·9187 모두 연결 실패 |
| 보존 | `30d or 10GiB` |
| 파일시스템 | `/`(ext4)·`/boot`·`/boot/efi`만 수집 |
| 시계열 | 4,816 (backend 226 포함) |
| 메모리 | grafana 263MiB, prometheus 42MiB, 나머지 각 15MiB 이하 |
| 라벨 | UUID·긴 숫자 ID·원문 없음. node·postgres는 테이블·DB·롤 이름과 EC2 인스턴스 ID·EBS 볼륨 ID·OS 버전 같은 서버 정보(외부 비공개라 수용). backend는 `uri`가 템플릿 경로 4개, CDC는 고정 `status`·`result` 값, 그 밖에 클래스 이름·JVM·보안 필터 이름 |

기동 로그에 남지만 무시해도 되는 줄:

- grafana `Skipping migration: Already executed, but not recorded` (warn): 새 DB를
  만들 때 Grafana가 남기는 줄입니다.
- node-exporter `Failed to open directory, disabling udev device properties`: 디스크
  지표에 udev 부가 라벨(모델명 등)만 빠집니다. 디스크 I/O 값은 그대로 수집됩니다.
- postgres-exporter `Error loading config ... postgres_exporter.yml`: 여러 DB를
  인증별로 긁을 때 쓰는 선택 설정 파일입니다. 이 스택은 env로 접속해 필요 없습니다.

## 남은 일

| 할 일 | 어디서 |
|---|---|
| 대시보드·알림 규칙·Mattermost 수신처·운영 런북 | S15P31A108-264 |
| Kafka lag·Connect 상태·replication slot WAL 수집 | S15P31A108-261 |
| 배포 자동화(Jenkins 잡 연결) | 미정. 변경이 잦아지면 별도 티켓 |
| 별도 모니터링 서버·서버 생존 외부 확인 | 244 작업 내용 4번, 2서버 구조 결정 때 |
