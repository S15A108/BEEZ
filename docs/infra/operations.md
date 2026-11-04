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
| 방금 배포된 버전에서 기능이 망가짐 | DB에 적용된 마이그레이션 중 되돌릴 버전보다 새 것에 파괴적 변경이 없는지 보고, 팀에 알린 뒤 직전 배포 SHA로 되돌림 | [4. 이전 버전으로 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) |
| 컨테이너 하나가 응답이 없음 | 그 컨테이너만 재시작 | [3. 재시작](#3-재시작) |
| 서버 env 값을 바꿈 | 같은 SHA로 다시 배포 | [3. 재시작](#env-값을-바꿨을-때) |
| 검증 결과가 `STALE`·`ERROR`가 됨 | 배포 문제가 아닙니다 | [5. 배포 복구와 검증 롤백](#5-배포-복구와-검증업무-롤백) |

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
  DB 백업은 S15P31A108-211에서 다룹니다.

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
그렇습니다. 원인만 고쳐 같은 SHA나 고친 커밋을 배포합니다. 어느 실패가 어디까지 바꿨는지는
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
2. **파괴적 DB 마이그레이션이 DB에 들어가 있는지 봅니다.** 되돌려도 DB 스키마는 그대로라([롤백의 한계](cd.md#롤백의-한계)),
   칼럼·테이블을 지우거나 이름·타입을 바꾼 마이그레이션 뒤에는 예전 backend가 동작하지 않습니다.
   **지금 떠 있는 SHA가 아니라 DB의 Flyway 적용 이력을 기준으로** 봅니다. 앞서 새 버전이 마이그레이션을 적용한 뒤
   예전 이미지로 되돌린 적이 있으면, 지금 이미지보다 DB 스키마가 더 새것일 수 있기 때문입니다.

   서버(`ssh beez`)에서 DB에 실제 적용된 최신 버전을 봅니다(조회만 함).

   ```bash
   docker compose -p beez exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
     "select version, success, installed_on from flyway_schema_history order by installed_rank desc limit 5"'
   ```

   로컬 PC(Git Bash)에서 되돌릴 SHA가 아는 최신 버전과, 그보다 새 마이그레이션의 의심 줄을 봅니다.

   ```bash
   OLD=<되돌릴 SHA>; DIR=backend/src/main/resources/db/migration
   git -C /c/git/BEEZ fetch -q origin
   git -C /c/git/BEEZ ls-tree --name-only "$OLD" -- "$DIR/" | grep -oE 'V[0-9]+__' | tr -dc '0-9\n' | sort -n | tail -n 1
   git -C /c/git/BEEZ diff --name-status "$OLD" origin/dev -- "$DIR"
   git -C /c/git/BEEZ diff "$OLD" origin/dev -- "$DIR" \
     | grep -inE '^\+.*(drop |rename |alter column .* type |set not null|add column .* not null)'
   ```

   - DB의 최신 버전이 되돌릴 SHA가 아는 최신 버전 **이하**면 스키마 걱정 없이 되돌릴 수 있습니다.
   - DB 쪽이 더 높으면, 되돌릴 SHA가 모르는 그 버전들(`V<OLD 최신+1>` ~ `V<DB 최신>`)이 판단 대상입니다. 셋째 명령에
     찍힌 파일 중 그 버전들을 엽니다. 넷째 명령은 의심 줄을 좁힐 뿐 판정이 아닙니다. 지우기·이름 바꾸기·타입 바꾸기,
     기본값 없는 `NOT NULL` 추가가 있으면 되돌리지 않고 **고친 커밋을 배포**합니다. 테이블·칼럼 추가만이면 되돌려도 됩니다
     (예전 backend의 Flyway는 모르는 상위 버전을 경고만 하고, 엔티티 검증 `ddl-auto: validate`는 남는 칼럼을 문제 삼지 않습니다).
   - DB 최신 버전의 파일이 `origin/dev`에 없으면(마이그레이션을 revert한 경우)
     `git -C /c/git/BEEZ log --all --oneline -- "$DIR/V<번호>__*"`로 그 파일이 있던 커밋을 찾아 봅니다.
   - `success`가 `f`인 행이 있으면 마이그레이션이 중간에 실패한 것입니다. 되돌리기 전에 그 버전의 스키마 상태부터 확인합니다.
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
| Deploy · `다른 배포가 5분 넘게 진행 중` | 바뀐 것 없음 | 앞 배포가 끝난 뒤 같은 SHA로 다시 배포 |
| Deploy · `배포 전 점검 실패` | 바뀐 것 없음 | [배포 전에 점검하기](env.md#배포-전에-점검하기)의 문구 표 |
| Deploy · 마지막 줄이 `프론트 dist 꺼내기`·`이미지 받기`이고 그 뒤 docker 오류(`manifest unknown`·`denied`·연결 오류 등) | 컨테이너·프론트 `current`는 그대로. 꺼낸 릴리스 폴더·받은 이미지만 남을 수 있음(다음 배포가 다시 쓰거나 정리) | GHCR에 그 SHA 이미지가 있는지 봅니다. 그 커밋의 Actions CD `image` 잡이 성공했는지, 문서만 바뀐 커밋(`paths-ignore`)이 아닌지, 패키지가 공개인지([새 패키지를 공개로 만들 때](cd.md#새-패키지를-공개로-만들-때)). GHCR 일시 장애면 같은 SHA로 다시 배포 |
| Deploy · `헬스체크 실패` | **backend·ai는 새 버전**(DB 마이그레이션이 이미 돌았을 수 있음), 프론트는 예전 | 콘솔의 backend·ai 로그로 원인 확인 → [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) 또는 고친 커밋 배포 |
| Deploy · `Nginx 경유 확인 실패` | **backend·ai는 새 버전**, 프론트는 예전 | 되돌려도 안 고쳐짐. [cd.md 배포 스크립트](cd.md#배포-스크립트)의 `nginx 다름`·`nginx 확인 실패` 설명대로 |
| 중단(`Aborted by …`)·시간 초과(잡 전체 15분) | 끊긴 단계에 따라 다름 → 아래 표 | 콘솔의 마지막 `[deploy …]` 줄로 단계를 찾고 `docker compose -p beez ps`로 이미지 태그 확인 |

중단·시간 초과는 어느 단계에서든 일어나므로 콘솔의 마지막 `[deploy …]` 줄과 `compose ps`로 판단합니다.
배포 스크립트는 중단 신호를 받으면 꺼내기용 컨테이너·임시 폴더를 지우고 끝나지만, 이미 시작한 컨테이너 교체를
되돌리지는 않습니다.

| 마지막 `[deploy …]` 줄 | 서버 상태 | 할 일 |
|---|---|---|
| 없음(Verify에서 끊겼거나, 앞 배포의 잠금을 기다리던 중), `배포 전 점검`, `배포 SHA`·`직전 …`, `프론트 dist 꺼내기`·`프론트 릴리스가 이미 있어…`, `이미지 받기` | 그대로 | 같은 SHA로 다시 배포 |
| `컨테이너 교체 + 헬스체크 대기` | 서비스마다 다름. `compose ps`의 이미지 태그가 새 SHA인 서비스만 바뀜 | 다시 배포해 끝내거나 [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) |
| `Nginx 경유 확인` | backend·ai는 새 버전, 프론트는 대개 예전 | `readlink /var/www/beez/current`로 프론트도 확인(전환 직후·기록 전에 끊겼으면 새 버전). 그 뒤 위 `Nginx 경유 확인 실패`와 같음 |
| `프론트 전환: …` 이후(`오래된 릴리스 삭제` 등) | 모두 새 버전. 정리 중에 끊김 | 할 일 없음. 다음 배포가 정리 |

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
