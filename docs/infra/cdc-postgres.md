# dev PostgreSQL 논리 복제 준비 런북 (S15P31A108-293)

CDC(`published_change_analysis` → Debezium → Kafka → backend)를 위해 dev PostgreSQL을 논리 복제가 되는 상태로 만들고,
복제 전용 계정·publication·비밀 파일을 둔다. 결정과 근거는 [CDC 배포 계획](cdc-deploy-plan-279.md) D3(비밀 파일)·D4·D5가
정본이고, 이 문서는 **그 결정을 서버에서 실행하는 방법과 결과**를 적는다.

- **이 작업에서 만들지 않는 것**: 복제 슬롯 `beez_published_analysis`. 슬롯은 S15P31A108-295에서 커넥터를 등록할 때 생긴다.
- **Kafka·Connect 스택**은 S15P31A108-294, **커넥터·consumer**는 S15P31A108-295가 맡는다.

| 무엇 | 어디에 | 누가 바꾸나 |
|---|---|---|
| `wal_level=logical`, `max_slot_wal_keep_size=10GB` | `infra/compose.yaml` postgres `command` | PR 머지 → CD |
| 복제 계정 `beez_cdc` | DB 역할 (Flyway 아님) | 손으로 (아래 3) |
| publication `beez_published_analysis_cdc` | DB (Flyway 아님) | 손으로 (아래 3) |
| 비밀번호 파일 | 서버 `/opt/beez/.cdc-secrets/database.properties` | 손으로 (아래 2) |

2·3은 1(머지)과 순서가 상관없다. `wal_level=replica`에서도 계정과 publication은 만들어진다
(`"wal_level" is insufficient to publish logical changes` 경고만 나온다, 로컬에서 확인).

## 꼭 지킬 것

- **접수행(`published_change_analysis`)을 손으로 지우지 않는다.** consumer는 DELETE 이벤트를 건너뛰지 않고 재시도하므로
  (`AnalysisIntakeEventPolicy`), 한 줄만 지워도 그 파티션이 영구히 막힌다. 앱에는 삭제 경로가 없다(FK에 cascade 없음).
  데이터를 정리해야 하면 먼저 CDC를 아래 "되돌리기" 순서로 내린다.
- **`wal_level`은 슬롯을 지운 뒤에만 내린다.** 논리 복제 슬롯이 남은 채로 `replica`로 내리면 PostgreSQL이
  `logical replication slot "…" exists, but wal_level < logical`로 기동을 거부한다.
- **계정은 `REVOKE` 뒤에만 `DROP ROLE` 한다.** 컬럼 권한이 남아 있으면
  `role "beez_cdc" cannot be dropped because some objects depend on it`으로 거부된다(로컬에서 확인).
- **비밀번호는 화면·명령 이력·로그에 내지 않는다.** 서버에서 바로 만들어 파일에 쓰고, 파일에서 읽어 DB에 넘긴다(아래 2·3-3).
- **계약 확인은 DB를 직접 조회한다.** `infra/cdc/check_contract.py`는 문서 캡처용 검토안만 보고 접수 테이블은 보지 않는다.

## 0. 준비 (서버 bash, 셸을 새로 열 때마다)

아래 절의 명령은 이 두 가지를 쓴다. **셸을 새로 열면 다시 친다.** postgres가 CD로 다시 만들어져도 컨테이너 이름은 같다.

```bash
# 본 compose 프로젝트(beez)의 postgres 컨테이너
PG=$(docker ps --filter label=com.docker.compose.project=beez --filter label=com.docker.compose.service=postgres --format '{{.Names}}')
[ -n "$PG" ] && echo "postgres 컨테이너: $PG" || echo "postgres 컨테이너를 찾지 못함 — 아래 명령을 치지 않는다"

# 컨테이너 안에서 테이블 소유자(슈퍼유저, POSTGRES_USER)로 접속한다. 계정 이름·비밀번호를 명령에 적지 않는다.
# 컨테이너 안 소켓 접속은 pg_hba 의 trust 라 비밀번호가 필요 없다. 실패하면 다음 문장으로 넘어가지 않는다(ON_ERROR_STOP)
pgsql() { docker exec -i "$PG" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 "$@"' _ "$@"; }
```

## 1. `wal_level` 바꾸기 (PR 머지)

`infra/compose.yaml` postgres에 `command`를 넣었다. 이미지의 기본 명령이 `["postgres"]`라 첫 단어를 `postgres`로 두면
entrypoint(`docker-entrypoint.sh`)의 초기화·권한 처리를 그대로 탄다.

```yaml
command: ["postgres", "-c", "wal_level=logical", "-c", "max_slot_wal_keep_size=10GB"]
```

- `max_replication_slots`·`max_wal_senders`는 기본 10으로 충분해 바꾸지 않는다.
- 서버(`infra/compose.yaml`) 전용이다. 로컬 개발용 `backend/compose.yaml`은 `replica` 그대로라 거기서는 슬롯을 만들 수 없다.
- **머지하면 약 3분 뒤 CD가 postgres를 다시 만든다**(`deploy.sh`의 `up -d`). 새 SHA 이미지라 backend·ai도 함께 다시 뜨고,
  backend는 healthy까지 최대 60초가 걸린다. 그래서 팀에 **"머지 시각부터 10분"**을 공지하고 그 시각에 머지한 뒤
  Jenkins 배포가 끝날 때까지 지켜본다. 볼륨(`pgdata`)은 그대로다.

로컬 확인(11-05, 서버와 같은 pgvector digest, 별도 프로젝트 `beez293test`로 로컬 `beez_pgdata`는 건드리지 않음):

| 시나리오 | 결과 |
|---|---|
| `command` 없이 만든 볼륨(`replica`)에 표를 넣고, 새 정의로 같은 볼륨을 다시 띄움 | healthy, `wal_level`=`logical`, `max_slot_wal_keep_size`=`10GB`, 넣은 행 유지 |
| 빈 볼륨에서 새 정의로 처음 띄움 | 초기화 완료 후 healthy, 같은 두 값 |

배포 뒤 확인 (**서버 bash**, 0 준비 뒤):

```bash
pgsql -Atc "SHOW wal_level" -c "SHOW max_slot_wal_keep_size"
docker ps --filter label=com.docker.compose.project=beez --format '{{.Names}}\t{{.Status}}'
```

기대값: `logical`, `10GB`, backend `(healthy)`.

## 2. 비밀 파일 만들기 (서버)

Connect 컨테이너가 `uid=1001`로 돌며 이 파일을 읽는다(294가 디렉터리를 `/run/cdc-secrets`에 읽기 전용으로 붙임).
600이면 Connect가 읽지 못해 커넥터 등록이 실패하므로 `root:1001` `640`으로 둔다. 스택 폴더 `/opt/beez/cdc` 안에 두면
스택을 올릴 때 폴더째 바뀌어 사라지므로 **바깥** `/opt/beez/.cdc-secrets/`에 둔다(계획 D3).

**서버 bash**:

```bash
# 디렉터리: 없으면 만들고, 있으면 주인·권한만 맞춘다 (294가 먼저 만들었어도 같은 값)
sudo install -d -o root -g 1001 -m 750 /opt/beez/.cdc-secrets

# 비밀번호를 서버에서 만들어 바로 파일에 쓴다. 화면에 찍지 않는다.
# 파일이 이미 있으면 덮어쓰지 않는다. openssl 이 실패하거나 길이가 48자가 아니면 파일을 만들지 않고 실패로 끝난다
sudo sh -c 'set -e; umask 027; f=/opt/beez/.cdc-secrets/database.properties
test -e "$f" && { echo "이미 있음: 그대로 둔다"; exit 0; }
pw=$(openssl rand -hex 24); [ "${#pw}" -eq 48 ]
printf "password=%s\n" "$pw" > "$f"; echo "만듦"'
sudo chown root:1001 /opt/beez/.cdc-secrets/database.properties
sudo chmod 640 /opt/beez/.cdc-secrets/database.properties

# 내용 말고 주인·권한과 비밀번호 길이만 본다
sudo stat -c '%U:%g %a %n' /opt/beez/.cdc-secrets /opt/beez/.cdc-secrets/database.properties
sudo awk -F= '/^password=/{print length($2)}' /opt/beez/.cdc-secrets/database.properties
```

기대값: 디렉터리 `root:1001 2750`, 파일 `root:1001 640`, 마지막 줄 `48` 하나.

- 서버에는 gid 1001의 그룹 이름이 없어 `%G`로 찍으면 `UNKNOWN`이 나온다. 그래서 숫자(`%g`)로 본다.
- 디렉터리가 `750`이 아니라 `2750`(setgid)인 것은 부모 `/opt/beez`가 setgid(`drwxr-s---`)라 물려받은 것이다.
  새로 생기는 파일이 그룹 1001을 물려받을 뿐이라 그대로 둔다(11-05 서버 확인).
빈 값이거나 다른 숫자면 파일을 지우고(`sudo rm …/database.properties`) 이 절을 다시 한다.
비밀번호는 16진수 48자라 따옴표·역슬래시 문제가 없다.

## 3. 복제 계정·publication 만들기 (서버, 손으로)

### 3-1. 착수 전 확인 (**서버 bash**, 0 준비 뒤)

```bash
pgsql <<'SQL'
\d published_change_analysis
SELECT relreplident FROM pg_class WHERE oid = 'public.published_change_analysis'::regclass;
SELECT rolname FROM pg_roles WHERE rolname = 'beez_cdc';
SELECT pubname FROM pg_publication;
SELECT slot_name FROM pg_replication_slots;
SQL
```

기대값: `id`·`status` 컬럼과 PK `id`, `relreplident` = `d`(default), 나머지 세 조회는 0행.
하나라도 다르면 멈추고 계획 D5를 다시 본다.

### 3-2. 계정·publication (**서버 bash**, 내용은 psql)

publication 문장은 검토안 [`intake.publication.review.sql`](../../infra/cdc/intake.publication.review.sql) 그대로다.

```bash
pgsql <<'SQL'
CREATE ROLE beez_cdc WITH LOGIN REPLICATION;
GRANT SELECT (id, status) ON public.published_change_analysis TO beez_cdc;
CREATE PUBLICATION beez_published_analysis_cdc
    FOR TABLE public.published_change_analysis (id, status)
    WITH (publish = 'insert, update, delete');
SQL
```

- `REPLICATION`은 WAL을 읽는 권한이고, `SELECT (id, status)`는 Debezium 초기 스냅샷용이다. 다른 컬럼과 다른 표는 주지 않는다.
- 이 조합으로 초기 스냅샷이 도는 것은 `infra/cdc-poc/document-capture`에서 확인했다.

### 3-3. 비밀번호를 파일에서 역할로 넘기기 (**서버 bash**, 0 준비 뒤)

비밀번호는 셸 변수와 psql 표준 입력으로만 지나간다.

- 명령줄(`ps`)과 셸 이력에는 변수 이름만 남는다.
- psql 이력(`~/.psql_history`)에는 남지 않는다. 표준 입력이 파이프라 psql이 대화형으로 돌지 않기 때문이다.
  대화형 psql에서 같은 문장을 치면 이력에 남으므로 그렇게 하지 않는다.
- 같은 세션에서 `log_statement = 'none'`(성공한 문장)과 `log_min_error_statement = panic`(실패한 문장)을 걸어
  서버 로그에 문장(비밀번호 포함)이 남지 않게 한다. 슈퍼유저 세션이라 걸 수 있다.
- 비밀번호가 48자가 아니면 보내지 않는다. 빈 값을 보내면 PostgreSQL은 NOTICE만 내고 비밀번호를 지운 채 성공으로 끝나기 때문이다.

```bash
pw=$(sudo sed -n 's/^password=//p' /opt/beez/.cdc-secrets/database.properties)
if [ "${#pw}" -ne 48 ]; then
  echo "비밀번호 파일 이상(길이 ${#pw}) — 2를 다시 본다"
else
  { echo "SET log_statement = 'none';"
    echo 'SET log_min_error_statement = panic;'
    printf '\\set pw %s\n' "$pw"
    echo "ALTER ROLE beez_cdc PASSWORD :'pw';"
  } | pgsql -q
  echo "exit=$?"
fi
unset pw
```

기대값: `exit=0` 한 줄. 비밀번호를 바꿀 때는 파일을 지우고 2·3-3을 다시 하고, 커넥터가 있으면 재시작한다.

## 4. 검증 (서버, 읽기 전용)

### 4-1. publication 컬럼 (**서버 bash**, 0 준비 뒤)

```bash
pgsql -Atc "SELECT attnames FROM pg_publication_tables WHERE pubname = 'beez_published_analysis_cdc'"
```

기대값: `{id,status}`.

### 4-2. `beez_cdc`로 망을 거쳐 접속 (**서버 bash**, 0 준비 뒤)

Connect와 같은 조건(uid·gid 1001, 비밀 파일 디렉터리를 읽기 전용으로 붙임, `beez_default` 망으로 `postgres:5432`)으로 접속한다.
Connect 이미지의 `kafka` 사용자는 `uid=1001 gid=1001`이다. **`--user 1001`처럼 그룹을 빼면 안 된다.** postgres 이미지에는
uid 1001 사용자가 없어 그룹이 0(root)이 되고, 그룹 1001에만 열린 파일(640)을 읽지 못해 검증이 거짓으로 실패한다
(11-05 서버에서 겪음: `sed: can't read …: Permission denied`).
컨테이너 안 `127.0.0.1` 접속은 `pg_hba.conf`에서 trust라 비밀번호를 검사하지 않으므로 검증이 되지 않는다.
클라이언트는 서버에 이미 있는 postgres 이미지를 쓰고, DB 이름은 postgres 컨테이너의 `POSTGRES_DB`에서 받는다.

```bash
docker run --rm --network beez_default --user 1001:1001 -v /opt/beez/.cdc-secrets:/run/cdc-secrets:ro \
  -e PGHOST=postgres -e PGUSER=beez_cdc -e PGDATABASE="$(docker exec "$PG" printenv POSTGRES_DB)" \
  "$(docker inspect -f '{{.Config.Image}}' "$PG")" sh -c '
PGPASSWORD=$(sed -n "s/^password=//p" /run/cdc-secrets/database.properties); export PGPASSWORD
echo "[1] id,status";  psql -X -Atc "SELECT count(*) FROM (SELECT id, status FROM published_change_analysis) t"
echo "[2] 다른 컬럼";  psql -X -Atc "SELECT project_id FROM published_change_analysis LIMIT 1" 2>&1
echo "[3] 복제 접속";  psql "replication=database" -X -Atc "IDENTIFY_SYSTEM" 2>&1'
```

기대값: [1] 숫자(행 수), [2] `permission denied for table published_change_analysis`, [3] `시스템ID|타임라인|LSN|DB이름`.
[1]이 `password authentication failed`면 3-3이 안 된 것이고, `Permission denied`(파일)면 2의 주인·권한을 다시 본다.

## 5. 되돌리기

CDC 전체를 내릴 때는 [계획 §4 되돌리기](cdc-deploy-plan-279.md#4-실행-순서-승인-뒤-작업-티켓별-pr) 표를 **1번부터 역순으로** 한다
(consumer → 커넥터 → 슬롯 → Kafka·Connect → 이 문서의 계정 → `wal_level`). 이 문서가 맡는 것은 마지막 두 단계다.

### 5-1. publication·계정·비밀 파일 (계획 5번)

앞 단계(커넥터·슬롯)가 끝났는지 먼저 본다 (**서버 bash**, 0 준비 뒤):

```bash
pgsql -Atc "SELECT count(*) FROM pg_replication_slots"
```

`0`일 때만 진행한다 (**서버 bash**, 내용은 psql):

```bash
pgsql <<'SQL'
DROP PUBLICATION beez_published_analysis_cdc;
REVOKE SELECT (id, status) ON public.published_change_analysis FROM beez_cdc;
DROP ROLE beez_cdc;
SQL
sudo rm /opt/beez/.cdc-secrets/database.properties
```

순서를 바꾸면 `DROP ROLE`이 컬럼 권한 때문에 거부된다(로컬에서 확인). 디렉터리는 294의 마운트가 남아 있으면 지우지 않는다.

### 5-2. `wal_level` (계획 6번)

`pg_replication_slots`가 0행인 것을 다시 확인한 뒤, `infra/compose.yaml`의 `command`를 지우는 PR을 공지한 시각에 머지한다.
머지 약 3분 뒤 CD가 postgres를 `replica`로 다시 만든다.

## 실행 기록

| 날짜 | 단계 | 결과 |
|---|---|---|
| 11-05 | 로컬 확인 (1 시나리오 2개, 0·2·3·4·5-1 블록을 문구 그대로 실행) | 위 기대값과 같음. 2는 `sudo` 대신 시험 폴더 경로로 실행 |
| 11-05 | 서버 사전 확인 (머지 전, 읽기 전용) | `wal_level`=`replica`, DB `beez`, 접수 테이블 `id`·`status`·PK `(id)`·replica identity `d`, 접수행 0, `beez_cdc`·publication·슬롯 0, `log_statement`=`none`, `/opt/beez/.cdc-secrets` 없음 |
| 11-05 | 서버 2 비밀 파일 (전진) | `만듦`, 디렉터리 `0:1001 2750`(setgid는 `/opt/beez`에서 물려받음), 파일 `0:1001 640`, 길이 `48` |
| 11-05 | 서버 3-2·3-3 계정·publication·비밀번호 (전진, 머지 전) | `CREATE ROLE`·`GRANT`·`CREATE PUBLICATION`(`wal_level` 경고는 머지 전이라 정상), 3-3 `exit=0` |
| 11-05 | 서버 4 검증 (읽기 전용) | 4-1 `{id,status}`. `beez_cdc` = LOGIN·REPLICATION·슈퍼유저 아님·비밀번호 있음, 권한은 `id:SELECT,status:SELECT`뿐. 4-2(`1001:1001`) [1] `0`(접수행 0) [2] `permission denied` [3] `IDENTIFY_SYSTEM` 성공(`beez`). 다른 uid(1002)는 파일 읽기 거부. 서버 로그에 `ALTER ROLE` 문장 0건. 슬롯 0 |
| 11-05 | 1 머지 뒤 확인 (PR #155 14:47 KST 머지, `1d22b6f1`) | CD 성공, Jenkins 배포로 backend·프론트 같은 SHA. `wal_level`=`logical`, `max_slot_wal_keep_size`=`10GB`, postgres 기동 명령이 compose와 같음. postgres·ai·backend healthy, `/actuator/health` 200 UP, postgres 로그 FATAL·PANIC 0건. 재시작 뒤 publication `{id,status}`·`beez_cdc` 유지, 슬롯 0 |
