# 운영 안내 (dev 서버)

dev 서버(`dev.beez.ai.kr`)에 문제가 생겼을 때 팀원 누구나 상태·로그를 보고, 재시작하고,
이전 버전으로 되돌릴 수 있게 정리한 문서입니다. (S15P31A108-74)

자세한 구성과 배경은 [CD 런북](cd.md)에 있고, 이 문서는 그중 운영에 필요한 절차만 모아 링크합니다.
서버 접속(SSH 키 등록)은 [서버 세팅 런북](server-setup.md) 6단계를 따릅니다. 접속 계정은 모두 `ubuntu`입니다.

로컬 PC 명령은 저장소가 `/c/git/BEEZ`(Git Bash 경로)에 있고, GitHub `S15A108/BEEZ`를 가리키는 원격 이름이
`origin`이라고 가정합니다. 다르면 두 값을 바꿔 칩니다(`git -C <저장소 경로> remote -v`로 원격 이름 확인).
[CD 런북](cd.md)의 로컬 명령도 같은 가정입니다.

## 상황별로 먼저 할 일

| 상황 | 먼저 할 일 | 자세히 |
|---|---|---|
| 팀 채널에 배포 실패 알림이 옴 | 알림의 콘솔 출력 링크를 열어 끝부분 확인 | [6. 배포 실패 알림](#6-배포-실패-알림) |
| 알림을 받았는데 Jenkins 계정이 없음 | 서버에서 빌드 로그 파일을 봄 | [Jenkins 계정이 없을 때](#jenkins-계정이-없을-때) |
| 화면·API가 안 됨 | 상태부터 확인 | [1. 상태 보기](#1-상태-보기) |
| 방금 배포된 버전에서 기능이 망가짐 | DB에 적용된 마이그레이션 중 되돌릴 버전보다 새 것이 예전 backend의 읽기·쓰기를 막지 않는지 보고, 팀에 알린 뒤 직전 배포 SHA로 되돌림 | [4. 이전 버전으로 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) |
| 컨테이너 하나가 응답이 없음 | 그 컨테이너만 재시작 | [3. 재시작](#3-재시작) |
| 서버 env 값을 바꿈 | 같은 SHA로 다시 배포 | [3. 재시작](#env-값을-바꿨을-때) |
| 검증 결과가 `STALE`·`ERROR`가 됨 | 배포 문제가 아닙니다 | [5. 배포 복구와 검증 롤백](#5-배포-복구와-검증업무-롤백) |
| 문서 원본 다운로드가 안 됨·데이터를 잃음 | `beez-check-live dev`로 원본 대조 | [8. 백업·복원](#8-백업복원) |
| PC 매일 확인(`pull-backup.sh`)이 종료 코드 1·경고를 냄 | 서버에서 `tail -50 /var/backups/beez/backup.log` | [8. 매일 확인](#매일-확인-pc-git-bash) |

## 1. 상태 보기

로컬 PC(Git Bash):

```bash
curl -s https://dev.beez.ai.kr/api/ping                                          # {"ok":true,...,"started_at":"..."}
curl -s -o /dev/null -w '%{http_code}\n' https://dev.beez.ai.kr/actuator/health  # 200
```

- `started_at`은 지금 떠 있는 backend 프로세스의 기동 시각입니다. 배포·재시작 전후로 값이 바뀌었는지
  보면 실제로 교체됐는지 알 수 있습니다.
- `/api/...`가 HTML로 오면 backend가 아니라 프론트 `index.html`이 응답한 것입니다([cd.md 4. Nginx 프록시](cd.md#4-nginx-프록시)).

서버(`ssh beez`):

```bash
docker compose -p beez ps --format '{{.Service}}\t{{.Image}}\t{{.Status}}'   # 서비스별 이미지 SHA·healthy 여부
readlink /var/www/beez/current                                              # 프론트 릴리스 SHA
```

- 정상이면 backend·ai·postgres가 `(healthy)`, seaweedfs가 `Up`입니다. backend·ai 이미지 태그와 프론트 릴리스가
  같은 SHA여야 합니다(세 이미지는 항상 같은 SHA로 함께 올라감).
- `-p beez`는 compose 프로젝트 이름입니다. 서버에서는 compose 파일 없이 이 이름으로 상태·로그·재시작을 봅니다.

배포 기록: `https://jenkins.beez.ai.kr` → `beez-deploy`. 빌드 이름이 `#번호 짧은SHA dev`이고, 빨간 빌드가 실패입니다.
로그인 계정은 Jenkins 관리자 계정(`beez-admin`)뿐입니다. 계정이 없으면 서버에서 같은 기록을 봅니다([Jenkins 계정이 없을 때](#jenkins-계정이-없을-때)).

## 2. 로그 보기

| 대상 | 어디서 | 명령 |
|---|---|---|
| backend·ai·postgres·seaweedfs | 서버 | `docker compose -p beez logs --tail 200 -f backend` (서비스 이름만 바꿈, `-f`는 계속 보기, `Ctrl+C`로 끝) |
| 특정 시각 이후 | 서버 | `docker compose -p beez logs --since 30m backend` |
| 배포 과정 | Jenkins | 빌드 → Console Output. 헬스체크가 실패하면 backend·ai 로그 80줄도 여기에 찍힙니다. 계정이 없으면 [서버의 빌드 로그 파일](#jenkins-계정이-없을-때) |
| Nginx | 서버 | `tail -f /var/log/nginx/access.log` · `/var/log/nginx/error.log` (`ubuntu`는 `adm` 그룹이라 sudo 없이 읽음) |
| Jenkins 서비스 | 서버 | `sudo journalctl -u jenkins --since "-30 min"` |

- backend 요청 로그는 `com.beez.access` 줄입니다(`GET /api/ping -> 200 (5 ms)`). 대괄호 안 값이 요청 ID입니다.
- **컨테이너가 다시 만들어지면 이전 컨테이너의 로그는 사라집니다**(docker 로그는 컨테이너별). 배포가 실패했을 때의
  로그는 Jenkins 콘솔 출력에 남아 있습니다. 컨테이너 로그는 서비스마다 10MB×3개까지만 남습니다([서버 세팅 4단계](server-setup.md#4-docker)).
- 로그를 메신저에 붙일 때는 값이 섞이지 않았는지 먼저 봅니다. 앱 로그에 env 값이 찍히는 경우는 막고 있지 않습니다.

### Jenkins 계정이 없을 때

Jenkins는 빌드마다 콘솔 출력과 파라미터를 서버 파일로 남깁니다(최근 50회). `ubuntu`는 `/var/lib/jenkins`를
직접 못 읽으므로 `sudo`로 읽기만 합니다. 서버(`ssh beez`):

```bash
B=/var/lib/jenkins/jobs/beez-deploy/builds
sudo grep -E '^last(Failed|Successful)Build ' $B/permalinks        # 마지막 실패·성공 빌드 번호
sudo sed 's/\x1b\[8m[^\x1b]*\x1b\[0m//g' $B/<번호>/log | tail -n 80   # 콘솔 출력 끝 80줄 (알림의 #번호)
```

- `sed`는 Jenkins가 로그에 끼워 넣는 링크용 표시(`ESC[8mha:…`)를 지웁니다. 지우지 않으면 줄마다 긴 문자열이 붙습니다.
- 최근 빌드의 번호·결과·배포 SHA(40자)를 한 번에 봅니다. [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구)에서
  되돌릴 SHA를 고를 때 Jenkins 빌드 목록 대신 씁니다.

  ```bash
  sudo bash -c 'cd /var/lib/jenkins/jobs/beez-deploy/builds && for n in $(ls -d [0-9]* | sort -n | tail -n 10); do
    printf "%s\t%s\t%s\n" "$n" "$(sed -n "s/^  <result>\(.*\)<\/result>/\1/p" "$n/build.xml")" \
      "$(grep -A2 "<name>GIT_SHA</name>" "$n/build.xml" | sed -n "s/.*<value>\(.*\)<\/value>.*/\1/p")"
  done'
  # 48  SUCCESS  19b7e2aad400f095b080ebfe16fa128eafe95cb9
  # 49  SUCCESS  6be547dc9e6075fceabb3078e197673576e17d62   (결과가 비어 있으면 아직 도는 중)
  ```

- 다시 배포해야 하는데 Jenkins 화면을 쓸 수 없으면 [손으로 배포·롤백](cd.md#손으로-배포롤백)을 씁니다.
  손 배포는 dev 본줄기 검사를 거치지 않으므로, 넣을 SHA가 위 목록에서 `SUCCESS`였던 것인지 확인하고 씁니다.

## 3. 재시작

### 컨테이너 하나

서버:

```bash
flock -E 75 -w 300 /var/www/beez/.deploy.lock docker compose -p beez restart backend \
  || { [ $? -eq 75 ] && echo "배포가 5분 넘게 진행 중이라 재시작하지 않음. 배포가 끝난 뒤 다시 실행"; }
docker compose -p beez ps backend          # (healthy)가 될 때까지 다시 실행. 첫 healthy까지 1분 안팎
```

- `flock ... .deploy.lock`은 배포 스크립트와 같은 잠금을 잡습니다. 배포가 도는 중이면 끝날 때까지(최대 5분)
  기다렸다가 재시작하므로, 배포 도중 컨테이너를 건드리지 않습니다.
- 5분 안에 잠금을 못 잡으면 `flock`은 아무 문구 없이 끝납니다. `-E 75`로 그 경우만 종료 코드를 75로 바꿔 둘째 줄의
  안내를 찍습니다. 안내가 뜨면 재시작은 일어나지 않은 것이니 [배포 기록](#1-상태-보기)에서 도는 배포가 끝났는지 보고
  다시 실행합니다.
- `restart`는 **같은 컨테이너를 같은 이미지·같은 env 값으로** 다시 켭니다. 버전도 설정도 바뀌지 않습니다.
- 웹 세션은 PostgreSQL에 있어 backend를 재시작해도 로그인이 유지됩니다(S15P31A108-242). 재시작하는 동안은
  API가 잠시 실패하므로 dev 서버를 같이 쓰는 팀 채널에 먼저 알립니다.
- postgres를 재시작했으면 이어서 backend의 health를 봅니다. `DOWN`이면 backend도 재시작합니다.

### env 값을 바꿨을 때

`restart`로는 반영되지 않습니다. Jenkins에서 **지금 떠 있는 SHA로 다시 배포**합니다([화면에서 배포·롤백](cd.md#화면에서-배포롤백)).
compose가 설정이 바뀐 서비스만 다시 만들고, 나머지는 그대로 둡니다. 지금 떠 있는 것이 손으로 올린 작업 브랜치
커밋이면 Jenkins가 본줄기 검사에서 거부하므로 [손으로 배포·롤백](cd.md#손으로-배포롤백)으로 같은 SHA를 다시 올립니다.

### 하지 않는 것

- 서버에서 `docker compose up`을 직접 치지 않습니다. 서버에는 compose 파일이 없어서
  (`no configuration file provided`, 2026-11-04 확인) 배포 스크립트만 올바른 SHA·env 파일로 띄울 수 있습니다.
- `docker compose -p beez down`은 컨테이너를 지우고, **`down -v`는 DB·문서 원본 볼륨까지 지웁니다.**
  백업·복원은 [8. 백업·복원](#8-백업복원).

### 호스트 서비스

| 대상 | 명령(서버) | 비고 |
|---|---|---|
| Nginx | `sudo nginx -t && sudo systemctl reload nginx` | 설정 검사가 통과할 때만 반영. 재시작이 아니라 reload라 연결이 끊기지 않음 |
| Jenkins | `sudo systemctl restart jenkins` | 줄 선 배포가 없을 때. 켜질 때 JCasC로 설정을 다시 적용합니다([Jenkins 런북](jenkins.md)) |
| 서버 재부팅 | `sudo reboot` | 컨테이너는 `restart: unless-stopped`라 Docker와 함께 다시 뜹니다. 손으로 `stop`한 컨테이너는 뜨지 않음 |

## 4. 이전 버전으로 되돌리기 (배포 복구)

헬스체크가 실패해도 **자동으로 되돌리지 않습니다**(2026-10-30 결정). DB 마이그레이션은 되돌아가지 않아서,
원인이 마이그레이션이면 예전 backend로 돌려도 새 스키마와 맞지 않는 경우가 많고, 자동 롤백 자체가 실패하는
경로가 하나 더 생기기 때문입니다. 사람이 보고 되돌릴지, 고친 커밋을 새로 배포할지 정합니다.

**먼저 서버가 바뀌었는지 봅니다.** 배포가 컨테이너를 교체하기 전에 멈췄으면 서버는 직전 버전 그대로라 되돌릴
것이 없습니다. Verify 실패, 배포 전 점검 실패, 다른 배포 대기 초과, 프론트 dist 꺼내기·이미지 받기 실패가
그렇습니다. 원인만 고쳐 같은 SHA나 고친 커밋을 배포합니다(대기 초과는 [잠금 대기로 실패했을 때](#잠금-대기로-실패했을-때)를 먼저 확인). 어느 실패가 어디까지 바꿨는지는
[알림을 받았을 때](#알림을-받았을-때) 표에 있습니다. 아래 절차는 backend·ai가 이미 바뀐 경우(헬스체크 실패,
Nginx 경유 확인 실패, 배포는 성공했는데 기능이 망가짐)에 씁니다.

1. **되돌릴 SHA를 찾습니다.** 문제가 된 버전 **전에** 실제로 떠 있던 SHA입니다. Jenkins에는 40자 SHA를 넣습니다.
   - 배포가 실패했으면: 실패한 빌드 콘솔 앞부분의 `직전 backend 이미지: ...:<SHA>`. 그 SHA가 바로 앞의
     **실패한** 빌드의 것이면(연달아 실패해 backend가 이미 그 버전으로 바뀐 경우) 그보다 앞의 성공 빌드를 고릅니다.
   - 배포는 성공했는데 기능이 망가졌으면: 지금 떠 있는 SHA([1. 상태 보기](#1-상태-보기))를 확인하고, Jenkins
     빌드 목록에서 **그와 다른 SHA로 성공한 가장 최근 빌드**를 고릅니다. 마지막 성공 빌드는 지금 떠 있는 바로 그
     버전이라, 그 SHA를 넣으면 같은 SHA 재배포가 되어 아무것도 바뀌지 않습니다.
   - 빌드 이름에는 짧은 SHA만 보입니다. 그 빌드를 열어 **Parameters**의 `GIT_SHA`(또는 콘솔 앞부분의
     `배포 SHA`)에서 40자를 복사합니다. Jenkins 계정이 없으면 [서버의 빌드 목록](#jenkins-계정이-없을-때)에서 봅니다.
   - `git log`의 dev 이력에서 고르지 않습니다. 문서만 바뀐 머지 커밋은 CD가 이미지를 만들지 않아(`cd.yml`
     `paths-ignore`) 그 SHA로는 배포가 실패합니다. Jenkins 빌드 목록의 SHA는 실제로 배포됐던 것이라 이미지가 있습니다.
2. **예전 backend와 맞지 않는 DB 마이그레이션이 DB에 들어가 있는지 봅니다.** 되돌려도 DB 스키마·데이터는 그대로라
   ([롤백의 한계](cd.md#롤백의-한계)), 예전 backend가 읽거나 쓰던 방식이 새 스키마에서 막히면 되돌린 버전도 동작하지 않습니다.
   **지금 떠 있는 SHA가 아니라 DB의 Flyway 적용 이력을 기준으로** 봅니다. 앞서 새 버전이 마이그레이션을 적용한 뒤
   예전 이미지로 되돌린 적이 있으면, 지금 이미지보다 DB 스키마가 더 새것일 수 있기 때문입니다.

   서버(`ssh beez`)에서 최근 적용 이력을 봅니다(조회만 함). `success`가 `f`인 행이 있으면 마이그레이션이 중간에 실패한
   것이라, 어느 backend도 Flyway 검증에서 기동하지 못합니다. 되돌리기 전에 그 버전의 스키마 상태부터 확인합니다.

   ```bash
   docker compose -p beez exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
     "select version, description, checksum, success, installed_on from flyway_schema_history order by installed_rank desc limit 10"'
   ```

   로컬 PC(Git Bash, `python` 필요)에서 되돌릴 SHA의 마이그레이션 파일과 DB 적용 이력을 **버전마다 checksum·설명·유형으로**
   대조합니다(Flyway 검증이 셋 다 봅니다). DB 이력은 `ssh beez`로 조회만 합니다.

   ```bash
   OLD=<되돌릴 SHA>; DIR=backend/src/main/resources/db/migration; JDIR=backend/src/main/java/db/migration
   g() { git -C /c/git/BEEZ "$@"; }; T=$(mktemp -d)
   # Flyway checksum = BOM 과 줄바꿈 문자(\r·\n)를 뺀 UTF-8 내용의 CRC32 를 부호 있는 32비트로 (2026-11-06 서버 V10·V13·V37 과 일치 확인)
   sum='import sys,zlib; t=sys.stdin.buffer.read().decode("utf-8").lstrip(chr(0xfeff)); c=zlib.crc32(t.replace("\r","").replace("\n","").encode()); print(c-(1<<32) if c>=1<<31 else c)'
   if ! python -c "$sum" < /dev/null > /dev/null; then echo "python 을 실행하지 못함 — 중단"
   elif ! g fetch -q origin || ! g cat-file -e "$OLD^{commit}" 2> /dev/null; then echo "fetch 실패 또는 없는 SHA — 중단"
   elif ! ssh beez "docker compose -p beez exec -T postgres sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -AtF \"|\" -c \"select version, checksum, success, type, description from flyway_schema_history where version is not null order by installed_rank\"'" > "$T/db" || [ ! -s "$T/db" ]; then
     echo "DB 이력을 받지 못함 — 중단"
   else
     g ls-tree -r --name-only "$OLD" -- "$DIR/" "$JDIR/" | grep -E '/V[0-9][0-9_]*__' | while read -r f; do
       v=${f##*/V}; v=${v%%__*}; v=${v//_/.}   # V1_1__ 은 DB 에 1.1 로 기록된다
       s=${f#*__}; s=${s%.*}; s=${s//_/ }       # 설명 = 파일 이름의 __ 뒤에서 확장자를 빼고 _ 를 공백으로
       case $f in *.sql) echo "$v|$(g show "$OLD:$f" | python -c "$sum")|SQL|$s" ;; *) echo "$v||JDBC|$s" ;; esac   # Java 마이그레이션은 checksum 이 없다(DB 에도 빈 값)
     done > "$T/old"
     # Flyway 기본 검증(outOfOrder 꺼짐, *:future 만 무시)으로 예전 backend 가 어떻게 되는지 번호마다 가른다.
     # 번호의 높낮이는 수로 비교하므로 정수 번호(V1, V2 …)만 맞다. 점 버전(V1_10 과 V1_9)은 순서가 틀릴 수 있다
     awk -F'|' 'NR == FNR { o[$1] = $2; ot[$1] = $3; od[$1] = $4; if ($1 + 0 > om) om = $1 + 0; next }
          $4 != "SQL" && $4 != "JDBC" { next }   # 스키마 생성·baseline 같은 Flyway 기록용 행은 마이그레이션이 아니다
          { d[$1] = 1; if ($1 + 0 > dm) dm = $1 + 0 }
          $3 != "t" { print "V" $1 ": 실패한 마이그레이션 — 어느 backend도 기동 못 함. 되돌리기 전에 스키마 상태부터 확인"; next }
          !($1 in o) && $1 + 0 > om { print "V" $1 ": 되돌릴 SHA가 모르는 상위 번호 — 판단 대상 (DB checksum " $2 ")"; next }
          !($1 in o) { print "V" $1 ": DB에만 있는데 되돌릴 SHA의 최신 번호보다 낮음 — 되돌리면 예전 backend 기동 실패"; next }
          { m = "" }
          o[$1] != $2 { m = m " checksum(DB " $2 ", 되돌릴 SHA " o[$1] ")" }
          ot[$1] != $4 { m = m " 유형(DB " $4 ", 되돌릴 SHA " ot[$1] ")" }
          od[$1] != $5 { m = m " 설명(DB \"" $5 "\", 되돌릴 SHA \"" od[$1] "\")" }
          m != "" { print "V" $1 ": 기록 다름 —" m " — 되돌리면 예전 backend 기동 실패" }
          END { for (v in o) if (!(v in d)) print "V" v ": 되돌릴 SHA에만 있고 DB에 없음 — " \
                  (v + 0 < dm ? "되돌리면 예전 backend 기동 실패" : "되돌리는 순간 이 마이그레이션이 실행됨. 판단 대상과 같이 읽음") }' \
       "$T/old" "$T/db" | sort -n -k1.2
     echo "대조 끝 (DB $(grep -cE '^([^|]*\|){3}(SQL|JDBC)\|' "$T/db")개, 되돌릴 SHA $(wc -l < "$T/old")개)"
   fi; rm -rf "$T"
   ```

   - **`대조 끝` 줄만 나오면** DB에 들어간 마이그레이션이 모두 되돌릴 SHA의 파일과 같습니다. 스키마 걱정 없이 되돌릴 수 있습니다.
   - 마이그레이션은 SQL(`$DIR`)과 Java(`$JDIR`, 예: `V21__backfill_llm_usage.java`)가 같은 번호 체계를 씁니다. Java는
     checksum이 없어 번호·설명·유형만 대조하므로, 같은 번호의 Java 내용이 바뀐 것은 이 대조로 알 수 없습니다. 판단 대상이 Java면
     그 파일을 직접 읽습니다(아래 의심 줄 grep·판 찾기는 SQL만 봅니다).
   - **`기동 실패`가 하나라도 있으면 되돌리지 않습니다.** 예전 backend의 Flyway가 기본 검증(설정에서 바꾸지 않음)에서
     멈춥니다. 경우는 셋입니다. 되돌릴 SHA도 아는 번호인데 DB에 다른 판이 들어감(`기록 다름` — checksum·설명·유형 중
     하나라도 다름. 작업 브랜치에서 같은 번호 파일의 내용이나 이름을 고쳐 손 배포 등), DB에만 있는 번호가 되돌릴 SHA의
     최신 번호보다 낮음(Flyway가 "적용됐는데 로컬에 없음"으로 봄), 되돌릴 SHA에만 있는 번호가 DB 최신 번호보다 낮음(`outOfOrder`가 꺼져 있어 적용하지 않고 멈춤).
     앞의 둘은 그 번호를 아는 다음 dev 배포도 막을 수 있으니 팀 채널에 알려 정리합니다.
   - **`실패한 마이그레이션`이 있으면** 지금 backend도 다시 켜지지 않습니다. 되돌리기보다 그 버전의 스키마 상태 확인이 먼저입니다.
   - **`되돌리는 순간 이 마이그레이션이 실행됨`은** 되돌릴 SHA에만 있는 번호가 DB 최신보다 높은 경우입니다. 되돌리면서
     스키마를 바꾸므로 되돌릴 SHA의 그 파일(`git show "$OLD:<경로>"`)을 판단 대상과 같은 기준으로 읽습니다.
   - **`판단 대상`은 되돌릴 SHA가 모르는, DB 최신 쪽 상위 번호입니다.** 예전 backend의 Flyway는 이런 상위 번호만 경고하고
     넘어가므로(`*:future`), 그 내용이 예전 backend와 맞는지가 기준입니다. 먼저 아래 "실제 적용된 판 찾기"로 DB에 들어간 판의 파일을
     찾아 **처음부터 끝까지** 읽습니다. 아래 의심 줄 grep은 후보를 좁힐 뿐 판정이 아닙니다.
     기준은 "예전 backend가 하던 읽기·쓰기가 이 마이그레이션 뒤에도 통하는가"입니다. 아래가 하나라도 있으면 되돌리지 않고
     **고친 커밋을 배포**합니다.
     - 예전 코드가 쓰는 칼럼·테이블을 지우거나 이름·타입을 바꿈
     - 예전 코드가 넣지 않는 칼럼이 실제로 기본값 없는 `NOT NULL`이 됨. `ADD COLUMN … NOT NULL DEFAULT 1` 뒤에
       `DROP DEFAULT`를 하는 경우(V10·V12)도 결과는 같습니다
     - 기존 테이블에 새 `UNIQUE`(인덱스 포함, 예: V13)·외래 키·`CHECK`(허용 값 목록 바꾸기 포함)·트리거(예: V10·V17의
       수정·삭제 금지)를 걸어 예전 코드의 INSERT·UPDATE·DELETE를 거절할 수 있음
     - `UPDATE`·`DELETE`·`INSERT`로 예전 코드가 읽는 데이터의 값이나 의미를 바꿈

     예전 코드가 건드리지 않는 새 테이블, NULL을 허용하거나 기본값이 남는 칼럼, UNIQUE가 아닌 인덱스만이면 되돌려도 됩니다
     (엔티티 검증 `ddl-auto: validate`는 남는 칼럼을 문제 삼지 않습니다). 판단이 애매하면 되돌리지 않고 고친 커밋을 배포합니다.

   의심 줄 grep (로컬 PC, dev에 있는 파일 기준. 판단 대상이 dev 판일 때 씁니다):

   ```bash
   git -C /c/git/BEEZ diff --name-status "$OLD" origin/dev -- "$DIR"
   git -C /c/git/BEEZ diff "$OLD" origin/dev -- "$DIR" \
     | grep -inE '^\+ *(update|delete|insert) |^\+.*(drop |rename |alter (column )?[^ ]+ (set data )?type |not null|primary key|unique|references|foreign key|check *\(|trigger)'
   ```

   **실제 적용된 판 찾기.** 판단 대상 번호마다 dev 판과, 저장소 이력(브랜치·원격)에 있던 그 번호 파일의 모든 판의
   checksum을 찍어, 대조 결과의 `DB checksum`과 같은 줄을 찾습니다. 위 대조 블록을 실행한 같은 셸에서 이어 칩니다(`sum`·`g`·`DIR`).

   ```bash
   V=<번호>; F=${V//./_}   # 대조 결과의 번호 그대로. 점 버전 1.1 은 파일 이름 V1_1__ 로 찾는다
   f=$(g ls-tree -r --name-only origin/dev -- "$DIR/" | grep "/V${F}__"); [ -n "$f" ] && echo "dev 판: $(g show "origin/dev:$f" | python -c "$sum") ${f##*/}"
   g log --branches --remotes --format=%h -- ":(glob)$DIR/**/V${F}__*" | while read -r c; do
     g ls-tree -r --name-only "$c" -- "$DIR/" | grep "/V${F}__" | while read -r f; do
       echo "$(g show "$c:$f" | python -c "$sum") $c ${f##*/}"
     done
   done | sort -u -k1,1
   # 출력: dev 판: <checksum> <파일 이름> / 그 아래 <checksum> <그 판이 있던 커밋> <파일 이름>
   ```

   - `DB checksum`이 `dev 판`과 같으면 dev 파일로(위 의심 줄 grep 포함) 판단합니다.
   - 다르면 같은 checksum의 줄에 찍힌 커밋의 파일을 읽고 판단합니다. dev 파일로 판단하지 않습니다.
   - 같은 줄이 없으면(push하지 않은 브랜치를 손 배포 등) 손 배포한 사람에게 그 파일을 받아 같은 방식으로 checksum을
     맞춰 본 뒤 판단합니다. **확인하지 못하면 되돌리지 않습니다.**
3. **팀 채널에 알립니다.** "dev 서버 되돌림: `<지금 SHA 7자>` → `<되돌릴 SHA 7자>`, 원인 PR #번호, 그동안 dev 머지 보류".
   되돌린 뒤 누가 dev에 머지하면 그 머지가 자동 배포되면서 문제 커밋까지 다시 올라갑니다.
4. **Jenkins로 배포합니다.** [화면에서 배포·롤백](cd.md#화면에서-배포롤백) 그대로 예전 SHA를 넣습니다.
   Jenkins가 안 되면 [손으로 배포·롤백](cd.md#손으로-배포롤백)을 씁니다.
5. **확인합니다.** [1. 상태 보기](#1-상태-보기)에서 이미지 태그·프론트 릴리스·`started_at`이 바뀌었는지 봅니다.
6. **원인 PR을 revert하거나 고친 PR을 머지합니다.** 되돌린 상태는 임시입니다. 그 머지가 배포되면 팀 채널에
   머지 보류를 풉니다.

**되돌릴 수 없는 경우**는 [롤백의 한계](cd.md#롤백의-한계)에 있습니다. Jenkins로는 dev 본줄기(PR 머지 커밋)만
되돌릴 수 있고, DB 스키마는 되돌리지 않습니다(위 2번).

- 예전 SHA의 이미지·프론트 릴리스가 서버에서 정리됐어도(이미지 7일, 릴리스 최근 5개) GHCR에서 다시 받아 꺼냅니다.
- 웹 세션은 DB에 있어 되돌려도 로그인이 유지됩니다. 단 -242(`af6b7ac`) 이전 SHA의 backend는 세션을 메모리에 두므로,
  그 SHA로 되돌릴 때와 다시 올릴 때 웹 사용자가 로그아웃됩니다.

### 2026-11-04 시연

dev 최신 `5bf2381`(-245)에서 직전 PR 머지 커밋 `28afe4b`(-176)로 되돌린 뒤 다시 최신으로 올렸습니다. 둘은 `infra/`만
달라 DB 마이그레이션 차이가 없습니다. Jenkins 화면(`beez-admin`)에서 했습니다.

| | #43 되돌리기 → `28afe4b` | #44 복귀 → `5bf2381` |
|---|---|---|
| 콘솔의 직전 버전 | `직전 backend 이미지: …:5bf2381…` | `직전 backend 이미지: …:28afe4b…` |
| 다시 만든 컨테이너 | backend·ai (postgres·seaweedfs 유지) | backend·ai |
| 프론트 | 릴리스가 남아 있어 꺼내기 건너뜀 → `current` 전환 | 같음 |
| backend `started_at` | 03:22:48Z → 03:53:21Z | → 03:57:46Z |
| 걸린 시간 | 21초 | 22초 |
| 결과 | `완료`, `Finished: SUCCESS`, 모두 healthy | 같음, 외부 `/actuator/health` 200 |

- 배포 스크립트도 **그 커밋의 것**이 돕니다. `28afe4b`는 -245 이전이라 "배포 전 점검"·"Nginx 경유 확인" 줄이 없었습니다.
  예전 SHA로 되돌리면 그 뒤에 추가된 배포 점검도 함께 빠집니다.

## 5. 배포 복구와 검증(업무) 롤백

이름이 비슷하지만 다른 일입니다. 배포를 되돌려도 검증·작업 데이터는 그대로이고, 검증 롤백은 배포와 상관없이
앱 안에서 일어납니다.

| | 배포 복구 (이 문서) | 검증(업무) 롤백 |
|---|---|---|
| 되돌리는 것 | 서버에 떠 있는 **코드 버전**(이미지 SHA·프론트 릴리스) | 검증 결과가 **작업 상태에 반영되는 것** |
| 하는 방법 | 사람이 Jenkins에 예전 SHA를 넣어 다시 배포 | 앱이 트랜잭션 안에서 자동으로 처리. 검증을 `STALE`·`ERROR`로 표시하고 작업 상태 규칙으로 되돌림 |
| DB | 스키마·데이터를 되돌리지 않음 | 행을 지우지 않음(불변 행 + 현재 포인터) |
| 사람이 할 일 | 원인 PR revert·수정 | 재시도 또는 새 검증 요청 |
| 담당·문서 | 전진 · [CD 런북](cd.md), 이 문서 | 변준성 · [11 검증 롤백](../design/11-validation-rollback.md) |

- 배포 중 backend가 교체되면서 AI 호출 중이던 검증이 끊기면, 그 검증은 검증 롤백 쪽 규칙(리스 만료 →
  `ERROR`, [11 검증 롤백](../design/11-validation-rollback.md) R3)으로 정리됩니다. 배포를 되돌릴 일이 아닙니다.

## 6. 배포 실패 알림

Actions의 초록불은 "Jenkins가 배포 요청을 접수했다"까지입니다([cd.md Actions → Jenkins 호출](cd.md#actions--jenkins-호출)).
그래서 Jenkins `beez-deploy`가 실패·중단·시간 초과로 끝나면 **팀 Mattermost 채널**에 알림을 보냅니다.
성공은 보내지 않습니다(dev 머지마다 배포라 시끄러움).

```
🚨 beez-deploy #43 실패 (dev, `5bf2381`, 단계 Deploy) · 콘솔 출력 · 복구 안내
```

- "콘솔 출력"은 그 빌드의 Console Output, "복구 안내"는 GitHub의 이 절로 가는 링크입니다.

- 들어가는 값은 빌드 번호·환경·짧은 SHA·실패 단계·콘솔 링크뿐입니다. 콘솔 로그는 넣지 않습니다(실패 때
  찍히는 앱 로그에 값이 섞일 수 있음). 콘솔 링크는 Jenkins 로그인이 필요합니다.
- 중단(Aborted)도 같은 문구로 옵니다. Jenkins 빌드 결과는 `ABORTED`로 남습니다.

### 알림을 받았을 때

콘솔 출력 끝부분의 문구로 어디서 멈췄는지 봅니다(Jenkins 계정이 없으면 [서버의 빌드 로그](#jenkins-계정이-없을-때)).
배포 스크립트는 `env 파일 확인 → 잠금 → 배포 전 점검 → 프론트 dist 꺼내기 → 이미지 받기 → 컨테이너 교체·헬스체크
→ Nginx 경유 확인 → 프론트 전환` 순서로 돌고([cd.md 배포 스크립트](cd.md#배포-스크립트)), 단계마다 `[deploy 시각] …`
줄을 찍습니다. 서버 상태는 이 순서로 정해집니다. **컨테이너 교체 전에 멈췄으면 되돌릴 것이 없습니다.**

| 단계 · 문구 | 서버 상태 | 할 일 |
|---|---|---|
| Verify · `GIT_SHA 는 40자…`, `dev 본줄기(PR 머지 커밋)가 아니라…`, `git fetch` 오류 | 바뀐 것 없음(배포 스크립트 실행 전) | 보낸 SHA 확인. Actions가 보낸 것이면 dev 머지 커밋인지 확인. GitHub 연결 오류면 같은 SHA로 다시 배포 |
| Deploy · `env 파일을 읽을 수 없음` | 바뀐 것 없음 | [서버 준비 2. env 파일](cd.md#2-env-파일)의 권한 확인 |
| Deploy · `다른 배포가 5분 넘게 진행 중` | 이 빌드는 바꾼 것 없음. 잠금을 쥔 작업이 서버를 바꿨을 수 있음 | [잠금 대기로 실패했을 때](#잠금-대기로-실패했을-때) 확인 뒤에만 다시 배포 |
| Deploy · `배포 전 점검 실패` | 바뀐 것 없음 | [배포 전에 점검하기](env.md#배포-전에-점검하기)의 문구 표 |
| Deploy · 마지막 줄이 `프론트 dist 꺼내기`·`이미지 받기`이고 그 뒤 docker 오류(`manifest unknown`·`denied`·연결 오류 등) | 컨테이너·프론트 `current`는 그대로. 꺼낸 릴리스 폴더·받은 이미지만 남을 수 있음(다음 배포가 다시 쓰거나 정리) | GHCR에 그 SHA 이미지가 있는지 봅니다. 그 커밋의 Actions CD `image` 잡이 성공했는지, 문서만 바뀐 커밋(`paths-ignore`)이 아닌지, 패키지가 공개인지([새 패키지를 공개로 만들 때](cd.md#새-패키지를-공개로-만들-때)). GHCR 일시 장애면 같은 SHA로 다시 배포 |
| Deploy · `헬스체크 실패` | **backend·ai는 새 버전**(DB 마이그레이션이 이미 돌았을 수 있음), 프론트는 예전 | 콘솔의 backend·ai 로그로 원인 확인 → [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) 또는 고친 커밋 배포 |
| Deploy · `Nginx 경유 확인 실패` | **backend·ai는 새 버전**, 프론트는 예전 | 위에 찍힌 `Nginx 경유=… / backend 직접=…` 줄로 원인을 가릅니다 → [Nginx 경유 확인이 실패했을 때](#nginx-경유-확인이-실패했을-때) |
| 중단(`Aborted by …`)·시간 초과(잡 전체 15분) | 끊긴 단계에 따라 다름 → 아래 표 | 콘솔의 마지막 `[deploy …]` 줄로 단계를 찾고 `docker compose -p beez ps`로 이미지 태그 확인 |

중단·시간 초과는 어느 단계에서든 일어나므로 콘솔의 마지막 `[deploy …]` 줄과 `compose ps`로 판단합니다.
배포 스크립트는 중단 신호를 받으면 꺼내기용 컨테이너·임시 폴더를 지우고 끝나지만, 이미 시작한 컨테이너 교체를
되돌리지는 않습니다.

| 마지막 `[deploy …]` 줄 | 서버 상태 | 할 일 |
|---|---|---|
| 없음(Verify에서 끊겼거나, 앞 배포의 잠금을 기다리던 중), `배포 전 점검`, `배포 SHA`·`직전 …`, `프론트 dist 꺼내기`·`프론트 릴리스가 이미 있어…`, `이미지 받기` | 이 빌드는 바꾼 것 없음 | 같은 SHA로 다시 배포. 잠금을 기다리던 중이었으면 [잠금 대기로 실패했을 때](#잠금-대기로-실패했을-때) 확인 뒤에만 |
| `컨테이너 교체 + 헬스체크 대기` | 서비스마다 다름. `compose ps`의 이미지 태그가 새 SHA인 서비스만 바뀜 | 다시 배포해 끝내거나 [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) |
| `Nginx 경유 확인` | backend·ai는 새 버전, 프론트는 대개 예전 | `readlink /var/www/beez/current`로 프론트도 확인(전환 직후·기록 전에 끊겼으면 새 버전). 그 뒤 [Nginx 경유 확인이 실패했을 때](#nginx-경유-확인이-실패했을-때)와 같음 |
| `프론트 전환: …` 이후(`오래된 릴리스 삭제` 등) | 모두 새 버전. 정리 중에 끊김 | 할 일 없음. 다음 배포가 정리 |

#### 잠금 대기로 실패했을 때

이 빌드는 서버를 바꾸지 않았지만, 그동안 잠금을 쥐고 있던 작업은 서버를 바꿨을 수 있습니다. Jenkins 잡은 동시에 돌지
않으므로 잠금을 쥔 쪽은 손 배포(`deploy.sh` 직접 실행)·[3. 재시작](#3-재시작)·[복원](#db까지-되돌릴-때-전체-복원)입니다.
기다리던 SHA를 그대로 다시 배포하면 그 작업이 올린 더 최신 버전이나 되돌린 상태를 덮어쓸 수 있습니다.

1. 앞 작업이 끝났는지 보고, 지금 떠 있는 SHA를 확인합니다([1. 상태 보기](#1-상태-보기)의 backend 이미지 태그).
2. 그 작업이 무엇이었는지 팀 채널에서 확인합니다. **되돌리기·복원이었으면 다시 배포하지 않습니다.** 되돌린 사람과
   언제 다시 올릴지 정합니다.
3. 두 SHA의 선후를 양쪽 방향으로 봅니다. 로컬 PC(Git Bash):

   ```bash
   R=<다시 배포하려던 SHA>; N=<지금 SHA>; g() { git -C /c/git/BEEZ "$@"; }
   if ! g fetch -q origin || ! R=$(g rev-parse -q --verify "$R^{commit}") || ! N=$(g rev-parse -q --verify "$N^{commit}"); then
     echo "비교 불가(fetch 실패 또는 로컬에 없는 SHA) — 중단"
   elif ! g rev-list --first-parent origin/dev | grep -qx "$N"; then
     echo "지금 것은 dev 본줄기(PR 머지 커밋)가 아님(작업 브랜치 손 배포 등) — 중단"
   elif g merge-base --is-ancestor "$R" "$N"; then echo "지금 것이 같거나 새것 — 다시 배포하지 않음"
   elif g merge-base --is-ancestor "$N" "$R"; then echo "지금 것이 더 오래됨 — 다시 배포 가능"
   else echo "선후를 가릴 수 없음 — 중단"; fi
   ```

   - 지금 SHA가 dev 본줄기(Jenkins Verify와 같은 `--first-parent` 검사)에 있는지 먼저 봅니다. 최신 dev에서 딴 작업 브랜치를
     손으로 올렸으면 그 커밋은 재시도 SHA의 자손이라, 선후만 보면 "같거나 새것"으로 나와 본줄기가 아닌 버전이 계속 떠 있게
     됩니다. `본줄기가 아님`이 나오면 배포한 사람과 언제 dev로 돌릴지 정합니다.
   - `merge-base --is-ancestor`는 "조상 아님"과 "비교 실패"를 모두 성공이 아닌 값으로 끝내므로, 한 방향만 보면 없는 SHA도
     "더 오래됨"처럼 보입니다. 그래서 SHA가 있는지 먼저 보고 양쪽 방향을 다 봅니다. 둘 다 본줄기라 마지막 줄은 보통 나오지 않습니다.
   - `비교 불가`면 네트워크를 확인하고, 지금 SHA가 아직 push하지 않은 로컬 브랜치의 것인지 배포한 사람에게 묻습니다.
4. **`다시 배포 가능`이 나왔고 2번의 되돌리기·복원도 아닐 때만** 같은 SHA로 다시 배포합니다.

#### Nginx 경유 확인이 실패했을 때

이 확인은 같은 `/api/ping`을 Nginx를 거쳐 한 번, backend 컨테이너에 직접 한 번 부르고 두 응답의 `started_at`을 비교합니다
([Nginx 반영 확인](env.md#nginx-반영-확인)). 헬스체크(`/actuator/health`)는 이미 통과했지만, 둘 중 어느 쪽이 실패했는지에
따라 원인과 복구가 다릅니다. 콘솔의 `Nginx 경유=… / backend 직접=…` 줄을 봅니다.

| 콘솔 문구 | 원인 | 할 일 |
|---|---|---|
| `nginx 확인 실패`이고 `backend 직접=` 쪽이 `응답 없음`·`JSON 아님` | 새 backend가 `/api/ping`에 제대로 답하지 못함(API 회귀·기동 직후 오류 등) | backend 로그로 원인 확인 → [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) 또는 고친 커밋 배포. 되돌리기가 복구 방법이 됩니다 |
| `nginx 확인 실패`이고 `backend 직접=`은 시각, `Nginx 경유=`만 `응답 없음`·`JSON 아님` | Nginx·인증서 쪽(curl 종료 코드 7 연결 거부, 28 시간 초과, 35·60 TLS. HTML이면 `/api/` 프록시 설정 없음) | Nginx·인증서를 고친 뒤 같은 SHA로 다시 배포. 되돌려도 고쳐지지 않습니다 |
| `nginx 다름` (둘 다 시각인데 다름) | Nginx가 다른 backend로 보냄. 대개 upstream 포트를 바꾸고 reload 안 함 | `sudo nginx -t && sudo systemctl reload nginx` 뒤 같은 SHA로 다시 배포 |
| `backend 컨테이너 포트가 N 개`·`docker 조회 실패` | 비교 전 단계 실패 | `docker compose -p beez ps`로 backend 컨테이너 수·포트 확인 |

### 구조

```
beez-deploy 고정 파이프라인(JCasC) ── 실패·중단·시간 초과 ──▶ /etc/jenkins/deploy-notify.sh ──curl──▶ Mattermost 웹훅
                                                              주소: /etc/jenkins/deploy-notify.curl
```

- 파이프라인 전체를 `try/catch`로 감싸고, catch에서 알린 뒤 원래 오류를 다시 던집니다. 알림이 실패해도
  빌드 결과는 바뀌지 않습니다. 스크립트는 항상 exit 0이고, 알림 단계 자체가 중단돼도(알림 중에 중단을 누름 등)
  파이프라인이 그 오류를 삼킨 뒤 원래 오류를 던집니다. 이때 빌드 결과는 Jenkins가 `ABORTED`로 둡니다.
- 알림을 보내지 못하면 콘솔에 `HTTP 상태`와 `curl 종료 코드`가 찍힙니다(6 = 이름 해석 실패, 7 = 연결 실패,
  28 = 시간 초과, 60 = 인증서 오류). 웹훅 주소는 찍지 않습니다.
- **알림 스크립트는 배포할 커밋이 아니라 서버 파일입니다.** Verify 단계 실패는 배포할 커밋을 꺼내기 전이라
  커밋 안의 스크립트를 부를 수 없습니다. 배포 잡의 검사를 커밋 밖에 고정한 것과 같은 이유입니다([cd.md](cd.md#검사를-고정-파이프라인에-두는-이유)).
- 웹훅 주소는 curl 설정 파일(`url = "..."`)에 두고 `curl -K`로 읽습니다. 주소가 명령줄·환경변수에 없어
  프로세스 목록과 Jenkins 콘솔(`sh`의 `+` 명령 출력)에 찍히지 않습니다.
- 플러그인을 늘리지 않았습니다(Mattermost 플러그인·Credentials 바인딩 없음). jenkins 사용자는 어차피 자기
  자격 증명을 풀 수 있어 Credentials에 둬도 보호 수준이 같고, 파일 하나가 설치·교체가 단순합니다.
- **신뢰 경계:** 배포할 커밋의 `deploy.sh`는 jenkins 사용자로 돕니다. jenkins는 docker 그룹이라 사실상 root
  권한입니다. 그래서 이 서버에서 믿는 것은 **"리뷰를 거친 dev 본줄기 머지 커밋만 Jenkins로 실행된다"** 한 가지입니다.
  고정 파이프라인이 실행 전에 SHA 형식과 dev 본줄기 여부를 검사하고, 그 검사는 배포할 커밋 밖(JCasC)에 있습니다
  ([검사를 고정 파이프라인에 두는 이유](cd.md#검사를-고정-파이프라인에-두는-이유)). 이 경계의 전제는 저장소 규칙 세트
  `main-dev-review-required`(dev·main에 PR·승인 1건 필수, 예외 계정 없음, 2026-11-04 확인)입니다. 이 규칙이 풀리면
  리뷰 없는 커밋도 본줄기에 들어와 경계가 무너집니다.
- **받아들인 위험:** 위 경계 안에서, 배포되는 커밋의 `deploy.sh`도 jenkins로 돌아서 웹훅 파일을 읽을 수 있습니다.
  Jenkins로 배포되는 커밋은 리뷰를 거친 PR 머지 커밋뿐입니다(고정 파이프라인의 본줄기 검사). 리뷰 전 브랜치를 올리는
  [손 배포](cd.md#손으로-배포롤백)는 sudo가 있는 `ubuntu` 사용자가 하므로, 그 사람은 원래 이 파일을 읽을 수
  있습니다. 주소가 새도 할 수 있는 일은 그 채널에 글을 올리는 것까지입니다(웹훅을 채널에 고정). 새면 아래
  "주소가 샜으면" 대로 바꿉니다.
- Actions가 Jenkins 결과를 기다리게 하지 않은 이유는 [cd.md Actions → Jenkins 호출](cd.md#actions--jenkins-호출)
  (Nginx 인증 요청 제한).

| 서버 파일 | 권한 | 정본 |
|---|---|---|
| `/etc/jenkins/deploy-notify.sh` | `755 root:root` | 저장소 `infra/jenkins/deploy-notify.sh` |
| `/etc/jenkins/deploy-notify.curl` | `640 root:jenkins` | 저장소에 없음(웹훅 주소 = 비밀값) |

스크립트를 고치면 [`infra/jenkins/test_deploy_notify.py`](../../infra/jenkins/test_deploy_notify.py)를 돌립니다
(`python infra/jenkins/test_deploy_notify.py`, bash 필요, CI에는 없음). 가짜 curl로 메시지 JSON, 웹훅 주소가 출력·명령줄에
없는지, 설정이 없거나 비었거나 curl이 실패해도 exit 0인지, 빈 값이 `?`가 되는지를 봅니다. 서버에 반영하는 것은
아래 설치 2번입니다.

### 설치·웹훅 교체 (서버당 한 번)

1. Mattermost 팀 채널 → 통합 → Incoming Webhook 추가. 채널을 고정하고 주소를 복사합니다. 주소는 저장소·메신저에
   붙이지 않습니다.
2. 스크립트를 서버에 넣습니다.

   ```bash
   # 로컬 PC(Git Bash) — 반영할 커밋을 고정해 보낸다
   C=<반영할 커밋>
   git -C /c/git/BEEZ fetch -q origin
   git -C /c/git/BEEZ show "$C:infra/jenkins/deploy-notify.sh" | ssh beez 'cat > /tmp/deploy-notify.sh'
   # 서버
   test -s /tmp/deploy-notify.sh && sudo install -m 755 -o root -g root /tmp/deploy-notify.sh /etc/jenkins/ && rm /tmp/deploy-notify.sh
   ```

   - 로컬 명령은 저장소 `/c/git/BEEZ`·원격 `origin`을 가정합니다([맨 위](#운영-안내-dev-서버) 참고).
   - 넣은 파일이 그 커밋과 같은지 sha256으로 봅니다. 두 값이 같아야 합니다. 서버 파일이 지금 dev와 같은지
     볼 때도 `C=origin/dev`로 같은 명령을 씁니다(2026-11-04 `9104f0e…`로 같음 확인).

     ```bash
     # 로컬 PC(Git Bash)
     git -C /c/git/BEEZ show "$C:infra/jenkins/deploy-notify.sh" | sha256sum
     ssh beez sha256sum /etc/jenkins/deploy-notify.sh
     ```

     `git show`는 저장소에 든 내용(줄끝 LF) 그대로 내보내므로 Windows의 줄끝 설정과 상관없이 비교됩니다.

3. 웹훅 주소를 넣습니다(서버 프롬프트에서, `webhook URL:` 안내가 뜬 뒤 붙여 넣기).

   ```bash
   read -rsp "webhook URL: " U && echo && \
     if [[ "$U" =~ ^https://meeting\.ssafy\.com/hooks/[A-Za-z0-9]+$ ]]; then \
       sudo install -m 640 -o root -g jenkins /dev/null /etc/jenkins/deploy-notify.curl && \
       printf 'url = "%s"\n' "$U" | sudo tee /etc/jenkins/deploy-notify.curl > /dev/null && echo "saved"; \
     else echo "형식이 예상과 다름. 넣지 않음"; fi; unset U
   ```

   - 빈 파일을 `640 root:jenkins`로 먼저 만든 뒤 내용을 씁니다. `tee`는 있는 파일의 권한을 그대로 둡니다.
   - `printf`는 셸 내장 명령이라 주소가 프로세스 목록에 드러나지 않습니다.

4. 메시지가 오는지 jenkins 권한으로 보냅니다. 서버 상태는 바뀌지 않습니다.

   ```bash
   cd /tmp && sudo -u jenkins env BUILD_NUMBER=test DEPLOY_ENV=dev FAILED_STAGE=test bash /etc/jenkins/deploy-notify.sh
   # 배포 실패 알림 보냄
   ```

5. 파이프라인(`casc/jenkins.yaml`)을 바꿨으면 [잡·플러그인·권한을 바꿀 때](cd.md#잡플러그인권한을-바꿀-때-서버-반영)대로
   반영하고, dev에 없는 커밋(`1531d05fe926f94270db1cac84bb922902a74f62`)으로 Build해 **거부 + 알림**이 오는지 봅니다.

주소가 샜으면 Mattermost에서 그 웹훅을 지우고 새로 만든 뒤 3번을 다시 합니다.

### 2026-11-04 설치·확인

채널은 `A108_github 알림`(비공개)입니다. 파이프라인과 스크립트는 이 문서와 같은 PR의 커밋 `833b0e1`로 처음 반영하고, 리뷰 반영 뒤
`f1823a5`로 다시 반영했습니다(알림 넣기 전 설정은 `/var/backups/jenkins/jenkins.yaml.before-74-notify`).

| 확인 | 결과 |
|---|---|
| 서버 파일 | `deploy-notify.sh` 755 root(저장소와 sha256 같음), `deploy-notify.curl` 640 root:jenkins, `www-data`는 못 읽음 |
| 4번 시험 메시지 | `배포 실패 알림 보냄`, 채널 수신 |
| Jenkins 재시작 | `fully up`, SEVERE·ConfiguratorException·unresolved 0건 |
| #45 `GIT_SHA=1531d05…`(본줄기 아님) | `dev 본줄기(PR 머지 커밋)가 아니라 배포하지 않는다` → `배포 실패 알림 보냄` → FAILURE, 컨테이너·프론트 그대로(`5bf2381`) |
| 콘솔에 웹훅 주소 | 0회 |
| #46 자동 배포 `8b6590d`(-243, `github-actions`) | 새 파이프라인으로 SUCCESS, 알림 없음(성공 경로 영향 없음) |
| #47 재반영(`f1823a5`) 뒤 같은 시험 | 거부 → 알림에 콘솔·복구 안내 링크, 채널 수신 |

배포 전 로컬 Jenkins(같은 버전·플러그인)에서는 SHA 형식 오류·본줄기 아님·Deploy 단계 실패·중단(ABORTED)
네 경우 모두 알림이 가고 원래 결과가 유지되는 것을 확인했습니다.

## 7. 서비스별 실행 정보

환경변수의 이름·필수 여부·빠졌을 때의 문구는 [환경변수와 비밀값](env.md#2-변수-목록)이 정본입니다.
여기서는 어디서 어떻게 도는지만 적습니다(`infra/compose.yaml`, 프로젝트 이름 `beez`).

| 서비스 | 이미지 | 서버에서 닿는 곳 | 헬스체크 | 데이터 | 환경변수 |
|---|---|---|---|---|---|
| backend | `ghcr.io/s15a108/beez-backend:<SHA>` | `127.0.0.1:8081` ← 호스트 Nginx(`/api`·`/mcp`·`/actuator/health`) | `/actuator/health` | 없음(웹 세션은 postgres) | [backend](env.md#backend-spring) |
| ai | `ghcr.io/s15a108/beez-ai:<SHA>` | 열지 않음. backend가 `ai:8000` | `/health` | 없음 | [ai](env.md#ai-fastapi) |
| postgres | `pgvector/pgvector@sha256:…`(digest 고정) | 열지 않음 | `pg_isready` | 볼륨 `beez_pgdata` | [서버 env 파일](env.md#2-변수-목록) |
| seaweedfs | `chrislusf/seaweedfs:4.51@sha256:…` | 열지 않음. backend가 `seaweedfs:8333` | 없음 | 볼륨 `beez_document-originals` | [SeaweedFS](seaweedfs.md) |
| frontend | `beez-frontend-dist:<SHA>`에서 꺼낸 정적 파일 | 호스트 Nginx가 `/var/www/beez/current` 서빙 | 없음 | 릴리스 최근 5개 | [frontend](env.md#frontend-vite) |
| Jenkins | apt 패키지(systemd) | `127.0.0.1:8080` ← `jenkins.beez.ai.kr` | — | `/var/lib/jenkins` | [Jenkins 런북](jenkins.md) |

## 8. 백업·복원

DB(PostgreSQL)와 문서 원본(SeaweedFS)을 매일 새벽 서버에 백업하고, PC가 그 백업을 당겨 와 서버 밖에 둡니다.
구성·순서의 이유·키는 [SeaweedFS 백업·복원](seaweedfs.md#백업복원-s15p31a108-211)에 있습니다. (S15P31A108-211)

| 무엇 | 어디 | 언제 |
|---|---|---|
| `beez-backup dev` (= `infra/backup/backup.sh`) | 서버 `ubuntu` crontab | 매일 04:17 KST. pg_dump 먼저 → 원본 복사 |
| 서버 백업 | `/var/backups/beez` (`db/` 덤프 최근 14개, `objects/` 원본 전부, `LAST_OK` 마지막 성공 덤프 이름, `backup.log`) | |
| 서버 밖 백업 | PC `~/beez-backup/dev` (`infra/backup/pull-backup.sh`) | 출근해서 한 번 |
| 운영 원본 대조 `beez-check-live dev` (= `infra/backup/check-live.sh`) | 서버 | 배포·복원 뒤. 읽기만 함 |
| 운영 복원 `beez-restore dev objects`·`beez-restore dev all` (= `infra/backup/restore.sh`) | 서버 | 원본이 없어졌거나 DB 를 되돌릴 때. `--yes` 없이는 할 일만 보여 줌 |

### 매일 확인 (PC Git Bash)

```bash
# 로컬 PC(Git Bash). 스크립트는 지금 체크아웃한 브랜치가 아니라 origin/dev 것을 임시 폴더에 꺼내 쓴다
d=$(mktemp -d) && git -C /c/git/BEEZ fetch -q origin && \
  git -C /c/git/BEEZ archive origin/dev infra/backup infra/compose.yaml | tar -x -C "$d" && \
  "$d/infra/backup/pull-backup.sh" --check; r=$?; rm -rf "$d"; echo "exit $r"
# [pull-backup ...] 새 파일 N개 ... / 마지막 성공 백업: beez-<UTC 시각>.dump (몇시간 전) / [restore-check ...] 통과 / exit 0
```

- origin/dev 것을 쓰는 이유: 체크아웃이 다른 브랜치면 스크립트가 없거나 옛것이고, 복원 시험이 쓰는 postgres·SeaweedFS
  이미지 digest(`infra/compose.yaml`에서 읽음)가 서버에 배포된 것과 달라질 수 있습니다.

- 새 덤프와 원본만 받습니다. PC의 파일은 지우지도 덮어쓰지도 않습니다.
- 서버 백업이 끝까지 성공하면 `LAST_OK`에 그 덤프 이름을 적습니다. 원본 복사·확인이 실패한 날도 덤프 파일은 생기므로, 신선도와 복원 시험은 덤프가 아니라 `LAST_OK`를 기준으로 합니다.
- 받다가 끊기면 받은 것을 버리고 PC 폴더는 그대로 둡니다. 다시 돌리면 처음부터 받습니다.
- 서버 백업이 도는 중에 받아도 됩니다. 받은 `LAST_OK`가 가리키는 덤프와 원본은 이미 다 있고, 백업 중에 생긴 것은 다음에 받습니다. 받기는 서버 백업을 막지 않습니다. 서버가 `LAST_OK`의 덤프를 이미 정리해 받지 못했으면 PC의 `LAST_OK`는 그대로 두고 경고합니다. 스크립트끼리의 잠금 규칙은 [잠금 원칙](seaweedfs.md#스크립트)에 있습니다.
- `--check`는 `LAST_OK`의 덤프를 임시 컨테이너에 복원하고, 문서 버전 원본의 SHA-256을 `content_hash`와 대조합니다(Docker Desktop 필요, 1분 안팎).
- **종료 코드 1, "마지막 성공 백업이 36시간보다 오래됨"·"끝까지 성공한 서버 백업이 없음"**: 서버 백업이 멈췄거나 매일 실패하는 것입니다. 서버에서 `tail -50 /var/backups/beez/backup.log`를 봅니다.
- 백업하던 몇 초 사이에 첫 제출이 있으면 그날 서버 백업이 "덤프가 가리키는 문서 버전 원본 N개가 백업에 없다"로 실패하고 `LAST_OK`가 하루 전 그대로입니다([예외](seaweedfs.md#순서와-일관성)). 다음 날 백업이 성공하면 됩니다. 이틀 연속이면 원인을 찾습니다.

### 설치 (서버당 한 번)

1. 백업 폴더를 만들고 스크립트를 넣습니다. 스크립트는 고정된 서버 파일로 둡니다(배포 체크아웃이 바뀌어도 그대로).

   ```bash
   # 로컬 PC(Git Bash) — 반영할 커밋을 고정해 보낸다
   C=<반영할 커밋>
   git -C /c/git/BEEZ fetch -q origin
   for f in backup check-live restore; do git -C /c/git/BEEZ show "$C:infra/backup/$f.sh" | ssh beez "cat > /tmp/beez-$f"; done
   # 서버
   sudo install -d -m 700 -o ubuntu -g ubuntu /var/backups/beez && \
   for f in backup check-live restore; do test -s /tmp/beez-$f && sudo install -m 755 -o root -g root /tmp/beez-$f /usr/local/sbin/ && rm /tmp/beez-$f; done; ls -l /usr/local/sbin/beez-*
   ```

2. 한 번 손으로 돌려 봅니다(서버). `완료: 덤프 …, 원본 N개`가 나오면 됩니다.

   ```bash
   beez-backup dev 2>&1 | tee -a /var/backups/beez/backup.log
   ```

3. 매일 돌게 등록합니다(서버). 이미 있으면 다시 넣지 않습니다.

   ```bash
   crontab -l 2> /dev/null | grep -q beez-backup || \
     ( crontab -l 2> /dev/null; echo '17 4 * * * /usr/local/sbin/beez-backup dev >> /var/backups/beez/backup.log 2>&1' ) | crontab -
   crontab -l
   ```

   - 서버 시간대가 KST라 04:17 KST에 돕니다. 정각을 피해 다른 예약 작업과 겹치지 않게 했습니다.
   - `backup.log`는 하루 몇 줄이라 따로 회전하지 않습니다.

스크립트를 고쳤으면 1번만 다시 합니다.

**`beez-backup`·`beez-restore`는 sudo 없이 `ubuntu`로 실행합니다.** root로 실행하면 잠금 파일·덤프가 root 소유가 되어 다음 날부터 예약 백업이 실패하므로, 두 스크립트는 root로 실행하면 바로 멈춥니다(`ubuntu`는 docker 그룹이라 sudo가 필요 없습니다).

### 원본 몇 개가 없어졌을 때

`beez-check-live dev`가 `없음`을 찍으면, 서버 백업에서 bucket으로 되살립니다. 이미 있는 원본은 건너뛰고 없는 것만 올라갑니다.

```bash
# 서버
beez-restore dev objects          # 할 일만 보여 준다(원본 백업 폴더·개수)
beez-restore dev objects --yes    # 올린다
beez-check-live dev               # 일치 N개, 문제 0개
```

- bucket에 써야 해서 관리(bootstrap) 키를 씁니다. 키 값은 `-e 이름`으로만 넘겨 명령줄·`docker inspect`에 나오지 않습니다.
- `--immutable --checksum`: bucket의 객체를 지우지 않고, 같은 key인데 내용이 다르면 덮어쓰지 않고 실패합니다.
- 백업에는 정리 워커가 지운 옛 작업본 원본도 남아 있어 함께 올라갑니다. DB가 참조하지 않는 파일이라 동작에는 영향이 없습니다.
- 서버 백업에도 없으면 PC 백업을 서버로 보낸 뒤 같은 명령을 씁니다. PC Git Bash에서
  `tar -C ~/beez-backup/dev -cf - objects | ssh beez 'tar -C /var/backups/beez -xf - --skip-old-files'`를 칩니다.

### DB까지 되돌릴 때 (전체 복원)

DB를 덤프 시점으로 되돌립니다. **지금 DB는 지워지고, 덤프 뒤에 생긴 데이터는 모두 사라집니다.** 팀 채널에 알리고 진행합니다.

```bash
# 서버. 서버가 새로 만들어졌으면 먼저 PC 백업을 보낸다(PC Git Bash):
#   tar -C ~/beez-backup/dev -cf - db objects LAST_OK | ssh beez 'mkdir -p /var/backups/beez && tar -C /var/backups/beez -xf - --skip-old-files'
beez-restore dev all              # 할 일과 사전 검증 결과만 보여 준다(읽기만 함). 덤프 기본값은 LAST_OK 의 것(끝까지 성공한 마지막 백업)
beez-restore dev all --yes        # 다른 시점이면: beez-restore dev all /var/backups/beez/db/beez-<시각>.dump --yes
beez-check-live dev               # 일치 N개, 문제 0개
```

`all`이 하는 일(`infra/backup/restore.sh`):

1. 백업 잠금(`backup.sh`와 같은 파일), 이어서 배포 잠금(`deploy.sh`와 같은 파일)을 잡고 끝날 때까지 쥡니다. 백업이 30분, 배포가 5분 넘게 돌면 아무것도 바꾸지 않고 멈춥니다. 복원 중에는 예약 백업도 자동 배포도 기다립니다(`objects`는 백업 잠금만 잡습니다).
2. **사전 검증**: 아무것도 바꾸기 전에 덤프가 가리키는 문서 버전 원본(`storage_key`)이 백업 폴더나 지금 bucket에 다 있는지 봅니다. 하나라도 없으면 backend·DB·bucket을 건드리지 않고 멈춥니다. 원본 폴더가 없다는 것만으로는 "문서 0건"인지 "원본이 빠진 백업"인지 알 수 없기 때문입니다. bucket에 이미 있는 원본은 백업 폴더에 없어도 됩니다(DB만 되돌리는 경우). 백업 폴더의 원본과 bucket의 같은 key가 내용이 다른지도 미리 봅니다(`rclone copy --dry-run --immutable`). 다르면 실제 올리기가 DB를 지운 뒤에 실패해 서비스가 멈춘 채 남기 때문입니다. 이 검증은 읽기만 하므로 `--yes` 없는 미리보기에서도 돕니다 — 팀에 머지 중단을 알리기 전에 미리보기로 복원 가능 여부부터 봅니다.
   - 받아들인 한계: 검증과 backend 멈춤 사이 몇 초 동안, 정리 워커가 "덤프는 가리키지만 지금 DB는 참조하지 않는" 원본을 bucket에서 지울 수 있습니다(bucket에만 있는 옛 key일 때). 막으려면 backend를 먼저 멈춰야 해서 "실패하면 아무것도 바꾸지 않음"을 잃습니다. 드문 조건이라 받아들였습니다. 복원 뒤 `beez-check-live dev`가 이 경우를 `없음`으로 잡으면 PC 사본에서 그 원본을 찾아 `beez-restore dev objects --yes`로 올립니다.
3. backend를 멈춥니다(웹 사용자는 로그아웃).
4. **DB를 지우고 빈 DB로 다시 만든 뒤** 덤프를 복원합니다. `pg_restore --clean`으로 덮어쓰지 않는 이유가 있습니다. 그렇게 하면 덤프 뒤 마이그레이션이 만든 테이블이 남고 Flyway 이력만 덤프 시점으로 돌아가, backend를 켤 때 Flyway가 같은 마이그레이션을 다시 돌리다 `already exists`로 멈춥니다.
5. 원본을 올리고(`objects`와 같음) backend를 켜서 `healthy`가 될 때까지 기다립니다. 켜지면 Flyway가 덤프 뒤의 마이그레이션을 적용합니다.

- 붙여 넣는 명령 묶음이 아니라 스크립트입니다. 중간에 하나라도 실패하면 그 자리에서 멈춥니다.
- **실패하면 backend를 켜지 않고 멈춥니다.** 비었거나 반쯤 복원된 DB로 켜면 Flyway가 빈 스키마를 새로 만들어, 데이터가 없는 정상 서비스처럼 보이기 때문입니다. 원인을 고쳐 같은 명령을 다시 돌립니다(DB를 다시 지우고 처음부터 합니다).
  - 스크립트가 끝나면 배포 잠금도 풀립니다. 실패한 채 두는 동안 dev에 머지가 일어나면 자동 배포가 backend를 그 DB로 켭니다. 복원 전에 팀 채널에 "복원 끝날 때까지 dev 머지 멈춤"을 알리고, 실패하면 다시 돌릴 때까지 그 상태를 유지합니다.
- 접속 중인 세션(모니터링 exporter 등)은 `DROP DATABASE … WITH (FORCE)`가 끊습니다.
- 2026-11-05 로컬 확인: 실제 backend 이미지(Flyway)로 띄운 DB를 덤프한 뒤 "나중 마이그레이션"(테이블 + Flyway 이력 한 줄)을 더하고 `all --yes`로 되돌렸습니다. 나중 테이블과 이력이 사라지고 backend가 `healthy`로 켜졌고, `check-live`도 일치했습니다. 원본 2개를 지운 bucket은 `objects --yes`로 되살렸습니다. 운영 서버 기록은 아래 [2026-11-05 서버 적용·확인](#2026-11-05-서버-적용확인)에 있습니다.

### 2026-11-05 서버 적용·확인

PR #144(머지 커밋 `fdb18439`)를 dev 서버에 적용하고 확인했습니다. S15P31A108-211.

| 확인 | 결과 |
|---|---|
| 배포 | seaweedfs가 `beez_storage`에만 붙어 healthy, backend는 default+storage, ai·postgres는 default만. `docker inspect`에 비밀값 0건 |
| 내부망 | 적용 전 ai → filer(`seaweedfs:8888`) 200(무인증) → 적용 뒤 이름 해석 실패 |
| 설치 | `/usr/local/sbin/beez-{backup,check-live,restore}` 755 root(머지 커밋 파일과 sha256 같음), `/var/backups/beez` 700 ubuntu, crontab `17 4 * * *` |
| 첫 백업(문서 0건) | `원본 0개`, `objects/beez-documents` 빈 폴더 생성, `LAST_OK` 기록. PC 받기 `--check` 통과 |
| 화면 업로드 | 시험 계정으로 필수 4종 업로드 → 검토 → 승인. 문서 분석 4건 `COMPLETED` |
| 원본 해시 | `beez-check-live dev` 일치 4, 문제 0 |
| backend 재생성 뒤 | 다른 PR 배포로 backend 재생성 → 일치 4 |
| seaweedfs 재생성 뒤 | `docker rm -f beez-seaweedfs-1` → 같은 SHA 재배포 → 새 컨테이너(같은 volume)에서 일치 4 |
| 백업(문서 4건)·복원 | `beez-backup dev` 원본 4개·덤프의 문서 버전 4개 확인 → PC 받기 `--check`로 임시 복원 → storage_key 다운로드 해시 일치 4 |

- seaweedfs를 지우고 다시 만드는 사이에 백업을 돌렸더니, rclone이 약 2분 재시도한 뒤 성공하고 `LAST_OK`를 기록했습니다.
- **dev에서 시험하지 못한 것**
  - 수정 초안 업로드(DOC-A12): 개발 기준선이 필요한데, 기준선을 만드는 `POST /api/projects/{id}/design-drafts`가 backend에 없습니다(404, 프론트 계약만 있음). 그래서 `409 version_conflict`로 끝납니다. 이때 먼저 쓴 원본은 보상 삭제되어 bucket에 남지 않았습니다(4개 그대로).
  - 분석 재시도: 실패한 분석이 없어 해당 없습니다(재시도는 `FAILED`만 허용).
- **시험 계정**: SMTP(-272)가 아직 없어 화면 가입이 503입니다. 그래서 시험 팀장 계정(`leader-211@example.com`)을 DB에 직접 넣었습니다. bcrypt 해시에 `{bcrypt}` 접두어를 붙였고, 비밀번호는 전진님이 갖고 있습니다.
