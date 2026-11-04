# 운영 안내 (dev 서버)

dev 서버(`dev.beez.ai.kr`)에 문제가 생겼을 때 팀원 누구나 상태·로그를 보고, 재시작하고,
이전 버전으로 되돌릴 수 있게 정리한 문서입니다. (S15P31A108-74)

자세한 구성과 배경은 [CD 런북](cd.md)에 있고, 이 문서는 그중 운영에 필요한 절차만 모아 링크합니다.
서버 접속(SSH 키 등록)은 [서버 세팅 런북](server-setup.md) 6단계를 따릅니다. 접속 계정은 모두 `ubuntu`입니다.

## 상황별로 먼저 할 일

| 상황 | 먼저 할 일 | 자세히 |
|---|---|---|
| 팀 채널에 배포 실패 알림이 옴 | 알림의 콘솔 출력 링크를 열어 끝부분 확인 | [6. 배포 실패 알림](#6-배포-실패-알림) |
| 화면·API가 안 됨 | 상태부터 확인 | [1. 상태 보기](#1-상태-보기) |
| 방금 배포된 버전에서 기능이 망가짐 | 직전 PR 머지 커밋으로 되돌림 | [4. 이전 버전으로 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) |
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
로그인 계정은 Jenkins 관리자 계정(`beez-admin`)뿐이라, 계정이 없으면 전진에게 요청하거나 [배포 실패 알림](#6-배포-실패-알림)을 봅니다.

## 2. 로그 보기

| 대상 | 어디서 | 명령 |
|---|---|---|
| backend·ai·postgres·seaweedfs | 서버 | `docker compose -p beez logs --tail 200 -f backend` (서비스 이름만 바꿈, `-f`는 계속 보기, `Ctrl+C`로 끝) |
| 특정 시각 이후 | 서버 | `docker compose -p beez logs --since 30m backend` |
| 배포 과정 | Jenkins | 빌드 → Console Output. 헬스체크가 실패하면 backend·ai 로그 80줄도 여기에 찍힙니다 |
| Nginx | 서버 | `tail -f /var/log/nginx/access.log` · `/var/log/nginx/error.log` (`ubuntu`는 `adm` 그룹이라 sudo 없이 읽음) |
| Jenkins 서비스 | 서버 | `sudo journalctl -u jenkins --since "-30 min"` |

- backend 요청 로그는 `com.beez.access` 줄입니다(`GET /api/ping -> 200 (5 ms)`). 대괄호 안 값이 요청 ID입니다.
- **컨테이너가 다시 만들어지면 이전 컨테이너의 로그는 사라집니다**(docker 로그는 컨테이너별). 배포가 실패했을 때의
  로그는 Jenkins 콘솔 출력에 남아 있습니다. 컨테이너 로그는 서비스마다 10MB×3개까지만 남습니다([서버 세팅 4단계](server-setup.md#4-docker)).
- 로그를 메신저에 붙일 때는 값이 섞이지 않았는지 먼저 봅니다. 앱 로그에 env 값이 찍히는 경우는 막고 있지 않습니다.

## 3. 재시작

### 컨테이너 하나

서버:

```bash
flock -w 300 /var/www/beez/.deploy.lock docker compose -p beez restart backend
docker compose -p beez ps backend          # (healthy)가 될 때까지 다시 실행. 첫 healthy까지 1분 안팎
```

- `flock ... .deploy.lock`은 배포 스크립트와 같은 잠금을 잡습니다. 배포가 도는 중이면 끝날 때까지(최대 5분)
  기다렸다가 재시작하므로, 배포 도중 컨테이너를 건드리지 않습니다.
- `restart`는 **같은 컨테이너를 같은 이미지·같은 env 값으로** 다시 켭니다. 버전도 설정도 바뀌지 않습니다.
- backend를 재시작하면 웹 사용자가 모두 로그아웃됩니다(세션이 backend 메모리에 있음, [cd.md 주의](cd.md#주의)).
  dev 서버는 같이 쓰므로 팀 채널에 먼저 알립니다.
- postgres를 재시작했으면 이어서 backend의 health를 봅니다. `DOWN`이면 backend도 재시작합니다.

### env 값을 바꿨을 때

`restart`로는 반영되지 않습니다. Jenkins에서 **지금 떠 있는 SHA로 다시 배포**합니다([화면에서 배포·롤백](cd.md#화면에서-배포롤백)).
compose가 설정이 바뀐 서비스만 다시 만들고, 나머지는 그대로 둡니다.

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

1. **되돌릴 SHA를 찾습니다.** 바로 앞의 성공 빌드(`#번호 짧은SHA dev`)의 SHA, 실패한 빌드 콘솔 앞부분의
   `직전 backend 이미지: ...:<SHA>`, 또는 `git log --first-parent --oneline origin/dev` 중 하나에서 찾습니다.
   Jenkins에는 40자 SHA를 넣습니다.
2. **Jenkins로 배포합니다.** [화면에서 배포·롤백](cd.md#화면에서-배포롤백) 그대로 예전 SHA를 넣습니다.
   Jenkins가 안 되면 [손으로 배포·롤백](cd.md#손으로-배포롤백)을 씁니다.
3. **확인합니다.** [1. 상태 보기](#1-상태-보기)에서 이미지 태그·프론트 릴리스·`started_at`이 바뀌었는지 봅니다.
4. **원인 PR을 revert하거나 고친 PR을 머지합니다.** 되돌린 상태는 임시입니다. 다음 dev 머지가 자동 배포되면서
   문제 커밋까지 다시 올라갑니다. 팀 채널에 "되돌림 중, 원인 PR"을 알립니다.

**되돌릴 수 없는 경우**는 [롤백의 한계](cd.md#롤백의-한계)에 있습니다. Jenkins로는 dev 본줄기(PR 머지 커밋)만
되돌릴 수 있고, DB 스키마는 되돌리지 않습니다. 칼럼을 지우거나 이름을 바꾼 마이그레이션 뒤에는 롤백 대신
고친 커밋을 배포합니다.

- 예전 SHA의 이미지·프론트 릴리스가 서버에서 정리됐어도(이미지 7일, 릴리스 최근 5개) GHCR에서 다시 받아 꺼냅니다.
- 되돌리는 동안에도 웹 사용자는 로그아웃됩니다.

### 2026-11-04 시연

(시연 뒤 기록)

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
🚨 beez-deploy #43 실패 (dev, `5bf2381`, 단계 Deploy) · 콘솔 출력 · 복구: docs/infra/operations.md
```

- 들어가는 값은 빌드 번호·환경·짧은 SHA·실패 단계·콘솔 링크뿐입니다. 콘솔 로그는 넣지 않습니다(실패 때
  찍히는 앱 로그에 값이 섞일 수 있음). 콘솔 링크는 Jenkins 로그인이 필요합니다.
- 중단(Aborted)도 같은 문구로 옵니다. Jenkins 빌드 결과는 `ABORTED`로 남습니다.

### 알림을 받았을 때

콘솔 출력 끝부분의 문구로 어디서 멈췄는지 봅니다.

| 단계 · 문구 | 서버 상태 | 할 일 |
|---|---|---|
| Verify · `GIT_SHA 는 40자…`, `dev 본줄기(PR 머지 커밋)가 아니라…` | 바뀐 것 없음 | 보낸 SHA 확인. Actions가 보낸 것이면 dev 머지 커밋인지 확인 |
| Deploy · `배포 전 점검 실패` | 바뀐 것 없음 | [배포 전에 점검하기](env.md#배포-전에-점검하기)의 문구 표 |
| Deploy · `다른 배포가 5분 넘게 진행 중` | 바뀐 것 없음 | 앞 배포가 끝난 뒤 같은 SHA로 다시 배포 |
| Deploy · `헬스체크 실패` | **backend·ai는 새 버전**, 프론트는 예전 | 콘솔의 backend·ai 로그로 원인 확인 → [4. 되돌리기](#4-이전-버전으로-되돌리기-배포-복구) 또는 고친 커밋 배포 |
| Deploy · `Nginx 경유 확인 실패` | **backend·ai는 새 버전**, 프론트는 예전 | 되돌려도 안 고쳐짐. [cd.md 배포 스크립트](cd.md#배포-스크립트)의 `nginx 다름`·`nginx 확인 실패` 설명대로 |

### 구조

```
beez-deploy 고정 파이프라인(JCasC) ── 실패·중단·시간 초과 ──▶ /etc/jenkins/deploy-notify.sh ──curl──▶ Mattermost 웹훅
                                                              주소: /etc/jenkins/deploy-notify.curl
```

- 파이프라인 전체를 `try/catch`로 감싸고, catch에서 알린 뒤 원래 오류를 다시 던집니다. 알림이 실패해도
  빌드 결과는 바뀌지 않습니다(스크립트가 항상 exit 0).
- **알림 스크립트는 배포할 커밋이 아니라 서버 파일입니다.** Verify 단계 실패는 배포할 커밋을 꺼내기 전이라
  커밋 안의 스크립트를 부를 수 없습니다. 배포 잡의 검사를 커밋 밖에 고정한 것과 같은 이유입니다([cd.md](cd.md#검사를-고정-파이프라인에-두는-이유)).
- 웹훅 주소는 curl 설정 파일(`url = "..."`)에 두고 `curl -K`로 읽습니다. 주소가 명령줄·환경변수에 없어
  프로세스 목록과 Jenkins 콘솔(`sh`의 `+` 명령 출력)에 찍히지 않습니다.
- 플러그인을 늘리지 않았습니다(Mattermost 플러그인·Credentials 바인딩 없음). jenkins 사용자는 어차피 자기
  자격 증명을 풀 수 있어 Credentials에 둬도 보호 수준이 같고, 파일 하나가 설치·교체가 단순합니다.
- Actions가 Jenkins 결과를 기다리게 하지 않은 이유는 [cd.md Actions → Jenkins 호출](cd.md#actions--jenkins-호출)
  (Nginx 인증 요청 제한).

| 서버 파일 | 권한 | 정본 |
|---|---|---|
| `/etc/jenkins/deploy-notify.sh` | `755 root:root` | 저장소 `infra/jenkins/deploy-notify.sh` |
| `/etc/jenkins/deploy-notify.curl` | `640 root:jenkins` | 저장소에 없음(웹훅 주소 = 비밀값) |

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

## 7. 서비스별 실행 정보

환경변수의 이름·필수 여부·빠졌을 때의 문구는 [환경변수와 비밀값](env.md#2-변수-목록)이 정본입니다.
여기서는 어디서 어떻게 도는지만 적습니다(`infra/compose.yaml`, 프로젝트 이름 `beez`).

| 서비스 | 이미지 | 서버에서 닿는 곳 | 헬스체크 | 데이터 | 환경변수 |
|---|---|---|---|---|---|
| backend | `ghcr.io/s15a108/beez-backend:<SHA>` | `127.0.0.1:8081` ← 호스트 Nginx(`/api`·`/mcp`·`/actuator/health`) | `/actuator/health` | 없음(세션은 메모리) | [backend](env.md#backend-spring) |
| ai | `ghcr.io/s15a108/beez-ai:<SHA>` | 열지 않음. backend가 `ai:8000` | `/health` | 없음 | [ai](env.md#ai-fastapi) |
| postgres | `pgvector/pgvector@sha256:…`(digest 고정) | 열지 않음 | `pg_isready` | 볼륨 `beez_pgdata` | [서버 env 파일](env.md#2-변수-목록) |
| seaweedfs | `chrislusf/seaweedfs:4.51@sha256:…` | 열지 않음. backend가 `seaweedfs:8333` | 없음 | 볼륨 `beez_document-originals` | [SeaweedFS](seaweedfs.md) |
| frontend | `beez-frontend-dist:<SHA>`에서 꺼낸 정적 파일 | 호스트 Nginx가 `/var/www/beez/current` 서빙 | 없음 | 릴리스 최근 5개 | [frontend](env.md#frontend-vite) |
| Jenkins | apt 패키지(systemd) | `127.0.0.1:8080` ← `jenkins.beez.ai.kr` | — | `/var/lib/jenkins` | [Jenkins 런북](jenkins.md) |
