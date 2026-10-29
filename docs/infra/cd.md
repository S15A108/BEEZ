# CD 런북 (dev 배포)

dev 브랜치에 들어온 커밋을 서버에 자동으로 올리는 경로입니다. 이미지는
GitHub Actions가 한 번만 빌드해 GHCR에 올리고, 서버는 받아서 교체만 합니다.
(S15P31A108-123)

[서버 세팅](server-setup.md) · [Nginx·HTTPS](nginx-https.md) ·
[Jenkins](jenkins.md) · [Docker 이미지](docker-images.md) 런북을 먼저 마친
상태를 전제로 합니다.

## 구성

```
dev 푸시 ─▶ Actions(cd.yml) ─▶ GHCR ─▶ Jenkins 배포 잡 ─▶ infra/deploy/deploy.sh
            이미지 3개 빌드       SHA 태그    (같은 서버)        컨테이너 교체·프론트 전환
```

| 항목 | 값 |
|---|---|
| 워크플로 | `.github/workflows/cd.yml` — dev 푸시(문서만 바뀐 커밋 제외)·수동 실행 |
| 이미지 | `ghcr.io/s15a108/beez-backend` · `beez-ai` · `beez-frontend-dist`, 태그는 커밋 SHA 40자 |
| 배포 로직 | `infra/deploy/deploy.sh` (Jenkins는 이 파일을 부르기만 함) |
| 서버 값 | `/opt/beez/.env.<환경>` — 비밀값 포함, 저장소에 없음 |
| backend | 컨테이너가 `127.0.0.1:8081`에만 열림, 호스트 Nginx가 `/api`·`/mcp`·`/actuator/health`를 넘김 |
| 프론트 | 포장 이미지에서 dist를 꺼내 `/var/www/beez/releases/<sha>`에 두고 `current` 심링크 전환 |

- **빌드는 Actions가 한 번만 합니다.** 서버에서 빌드하면 서비스 중인
  서버의 CPU·메모리를 쓰고, 같은 코드라도 빌드할 때마다 결과가 달라질 수
  있습니다. 서버는 받은 이미지를 그대로 띄웁니다.
- **CD에서 테스트를 다시 돌리지 않습니다.** PR CI(`ci-gate`)를 통과한
  커밋만 dev에 들어오고, dev 푸시 때 `ci.yml`도 따로 돕니다.
- **세 이미지는 항상 같은 SHA로 함께 올라갑니다.** "이 SHA를 배포한다"가 한
  단위가 되고, 예전 SHA로 다시 배포하면 그것이 롤백입니다.
- **프론트 포장 이미지는 서빙용이 아닙니다.** `FROM scratch`에 dist만 담은
  전달 상자이고 서빙은 호스트 Nginx가 합니다. 프론트 결정 D13(Docker 없이
  정적 배포)은 그대로입니다(`infra/frontend-dist/Dockerfile`).
- 배포 로직을 셸 스크립트에 두어 Jenkins를 다른 도구로 바꿔도 그대로 씁니다.

## 이미지와 GHCR

Actions는 기본 제공 `GITHUB_TOKEN`으로 GHCR에 올립니다(`permissions:
packages: write`). 개인 토큰이 필요 없습니다.

**패키지 3개는 공개입니다.** 서버는 로그인 없이 받습니다. 비공개로 두면
서버가 GHCR에 로그인해야 하고, 팀에 봇 계정이 없어 누군가의 개인 토큰을
서버에 둬야 합니다(그 사람의 만료·이탈·유출에 묶임). 저장소가 공개이고
이미지에는 비밀값이 없어(-120 확인) 공개해도 새로 드러나는 정보가 없습니다.

### 새 패키지를 공개로 만들 때

org 설정이 멤버의 공개 패키지 생성을 막고 있습니다(컨테이너는 Private만
허용). 패키지 화면의 Public이 "disabled by organization administrators"로
비활성이면 org 관리자가 아래 순서로 합니다.

1. S15A108 → Settings → Packages → Package creation에서 **Public**을 켜고 Save
2. 패키지 → Package settings → Change visibility → Public, 패키지 이름 입력
3. 1번의 **Public을 다시 끄고** Save

3번 뒤에도 이미 공개한 패키지는 공개로 남습니다(2026-10-29 확인). 처음
올라간 패키지는 기본이 비공개라 로그인 없는 pull이 403입니다.

```bash
# 로그인 없이 받을 수 있는지(로컬 PC). 200 = 공개
N=beez-backend; SHA=<커밋 SHA 40자>
T=$(curl -s "https://ghcr.io/token?scope=repository:s15a108/$N:pull" | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" \
  -H 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
  "https://ghcr.io/v2/s15a108/$N/manifests/$SHA"
```

### 프론트 빌드 값

`VITE_API_MODE`는 저장소 변수(Settings → Secrets and variables → Actions →
Variables)로 정합니다. 없으면 `mock`입니다. `VITE_` 값은 빌드 결과에 그대로
박히는 공개 값이라 토큰·키를 넣지 않습니다.

## 서버 준비 (서버당 한 번)

### 1. `/opt/beez` 권한

```bash
sudo chgrp beez-deploy /opt/beez && sudo chmod 2750 /opt/beez && ls -ld /opt/beez
# drwxr-s--- 2 ubuntu beez-deploy ... /opt/beez
```

- jenkins(`beez-deploy` 그룹)가 env 파일을 읽을 수 있게 합니다. 쓰기는
  주인(ubuntu)만 합니다.
- `2`는 setgid라 안에 새로 생기는 파일이 `beez-deploy` 그룹이 됩니다.

### 2. env 파일

환경 이름이 `dev`면 `/opt/beez/.env.dev`입니다. compose 파일은 하나이고
환경 차이는 이 파일로만 줍니다.

```bash
( set -C; umask 027
  printf '%s\n' \
    '# BEEZ 서버 환경값 (S15P31A108-123). 비밀값 포함: 저장소·메신저에 올리지 않는다' \
    'DB_USERNAME=beez' \
    "DB_PASSWORD=$(openssl rand -hex 24)" \
    "DOCUMENT_S3_ACCESS_KEY=$(openssl rand -hex 12)" \
    "DOCUMENT_S3_SECRET_KEY=$(openssl rand -hex 24)" \
    "AI_INTERNAL_TOKEN=$(openssl rand -hex 32)" \
    'PUBLIC_ORIGIN=https://dev.beez.ai.kr' \
    'BACKEND_HOST_PORT=8081' \
    'GMS_KEY=' > /opt/beez/.env.dev ) && ls -l /opt/beez/.env.dev
# -rw-r----- 1 ubuntu beez-deploy ... /opt/beez/.env.dev
```

- **이 파일을 다시 만들지 않습니다.** DB는 처음 뜰 때의 `DB_PASSWORD`로
  초기화되므로, 값이 바뀌면 backend가 DB에 접속하지 못합니다. 그래서
  `set -C`(noclobber)로 덮어쓰기를 막습니다. 값을 바꿀 때는 DB 비밀번호도
  함께 바꿔야 합니다.
- `umask 027`로 `640`이 됩니다. Nginx(`www-data`)는 읽지 못합니다.
- `openssl rand -hex`는 16진수만 내므로 env 파일에서 따옴표·`$` 해석 문제가
  없습니다.
- `BACKEND_HOST_PORT`는 8080이 아닌 값이어야 합니다. 8080은 Jenkins가
  씁니다. Nginx `upstream beez_backend`의 포트와 같아야 합니다.
- 변수 목록·`.env.example`·시크릿 관리 정리는 S15P31A108-27에서 합니다.

### 3. GMS 키 넣기·바꾸기

팀 공용 키를 넣습니다. 비워 두면 서버는 뜨지만 AI 호출(임베딩·생성)이
실패합니다.

```bash
read -rsp "GMS key: " K && echo && \
  if [[ "$K" =~ ^[A-Za-z0-9._-]{20,}$ ]]; then \
    ( umask 027; { grep -v '^GMS_KEY=' /opt/beez/.env.dev; printf 'GMS_KEY=%s\n' "$K"; } > /opt/beez/.env.dev.new ) \
    && mv /opt/beez/.env.dev.new /opt/beez/.env.dev && echo "saved (${#K} chars)"; \
  else echo "형식이 예상과 다름(영문·숫자·._- 20자 이상). 넣지 않음"; fi; unset K
```

- **서버 프롬프트에서** 실행하고, `GMS key:` 안내가 뜬 뒤 입력합니다. 입력은
  화면과 셸 기록에 남지 않습니다. Git Bash에서는 `Shift+Insert`로 붙여
  넣습니다(`Ctrl+V`는 안 먹을 수 있음).
- `printf`는 셸 내장 명령이라 키가 프로세스 목록에 드러나지 않습니다.
- 바꾼 값은 다음 배포 때 ai 컨테이너가 다시 만들어지면서 적용됩니다.

값을 보지 않고 확인하기:

```bash
awk -F= '/^[A-Z0-9_]+=/{ v=substr($0, index($0,"=")+1); printf "%-24s len=%d\n", $1, length(v) }' /opt/beez/.env.dev
sudo -u jenkins test -r /opt/beez/.env.dev && echo "jenkins can read"
```

### 4. Nginx 프록시

`infra/nginx/sites-available/dev.beez.ai.kr.conf`와
`infra/nginx/snippets/beez-backend-proxy.conf`를 서버에 넣습니다. **사이트
설정이 snippet을 include하므로 snippet을 먼저** 넣습니다.

```bash
# 로컬 PC(Git Bash)
scp -r /c/git/BEEZ/infra/nginx beez:/tmp/
# 서버
sudo install -m 644 /tmp/nginx/snippets/beez-backend-proxy.conf /etc/nginx/snippets/ && \
sudo install -m 644 /tmp/nginx/sites-available/dev.beez.ai.kr.conf /etc/nginx/sites-available/ && \
sudo nginx -t && sudo systemctl reload nginx && rm -r /tmp/nginx
```

| 경로 | 처리 |
|---|---|
| `/api/` | backend. 업로드 상한 `2m`(backend multipart `max-request-size`와 같음. Nginx 기본 1m이면 1~2MB 요청이 413) |
| `/mcp` | backend. 스트리밍 연결이라 버퍼링 끔, 읽기 대기 1시간 |
| `= /actuator/health` | backend. 상세는 prod 프로필에서 숨김. 다른 `/actuator` 경로는 열지 않음 |
| 그 밖 | 정적 파일, 없으면 `index.html`(SPA) |

프록시가 없으면 `/api/...`도 SPA 규칙에 걸려 `index.html`이 200으로
돌아옵니다. 상태 코드만 보고 "API가 된다"고 판단하지 않습니다.

## 배포 스크립트

```bash
infra/deploy/deploy.sh <git SHA 40자> <환경>
```

1. 프론트 포장 이미지에서 dist를 꺼내 `releases/<sha>`에 둡니다. 이미 있으면
   건너뜁니다. 아직 전환하지 않습니다.
2. backend·ai(와 digest로 고정한 postgres·seaweedfs) 이미지를 받습니다.
3. `docker compose up -d --no-build --remove-orphans --wait`로 바뀐
   컨테이너만 교체하고, healthcheck가 있는 서비스가 healthy가 될 때까지 최대
   240초 기다립니다. backend의 healthcheck는 `/actuator/health`입니다.
4. **3이 성공했을 때만** 프론트 `current`를 새 릴리스로 바꿉니다.
5. 릴리스는 최근 5개만 남기고, 컨테이너가 쓰지 않는 이 저장소 이미지 중
   7일 넘은 것을 지웁니다. 필요하면 GHCR에서 다시 받습니다.

- 헬스체크가 실패하면 컨테이너 상태와 backend·ai 로그를 출력하고 실패로
  끝납니다. 프론트는 바꾸지 않습니다. **backend·ai 컨테이너는 이미 새
  버전으로 바뀐 상태**이므로, 로그 맨 앞의 "직전 backend 이미지" SHA로 다시
  배포해 되돌립니다.
- 같은 SHA로 다시 돌리면 아무것도 다시 만들지 않습니다(2026-10-29 확인).
- `scratch` 이미지에는 실행 명령이 없어 `docker create`가 `no command
  specified`로 거부합니다. 실행하지 않으므로 더미 명령(`noop`)을 줍니다.
- `docker cp`는 파일 주인을 실행한 사용자로 만듭니다. 꺼낸 뒤 배포 폴더
  그룹(`beez-deploy`)과 그룹 쓰기로 맞춰 ubuntu도 지울 수 있게 합니다.

### 손으로 배포·롤백

Jenkins가 고장 났을 때나 처음 검증할 때 씁니다. 실제 배포와 같은 권한으로
돌리도록 jenkins 사용자로 실행합니다.

```bash
# 로컬 PC(Git Bash): 배포할 SHA의 infra/ 를 그대로 올린다 (작업 폴더는 건드리지 않음)
SHA=<SHA 40자>
git -C /c/git/BEEZ fetch -q origin && git -C /c/git/BEEZ archive "$SHA" infra \
  | ssh beez 'rm -rf /tmp/beez-infra && mkdir /tmp/beez-infra && tar -x -C /tmp/beez-infra --strip-components=1'
# 서버
cd /tmp && sudo -u jenkins -H bash /tmp/beez-infra/deploy/deploy.sh <SHA 40자> dev
rm -rf /tmp/beez-infra
```

- `git archive <SHA> infra`는 그 커밋의 `infra/`만 묶어 내보냅니다. 배포
  스크립트·compose 파일이 이미지와 같은 SHA로 맞춰집니다.
- `cd /tmp`는 jenkins가 ubuntu 홈 폴더에 들어가지 못해 생기는 경고를 피합니다.
- 롤백도 같은 명령에 예전 SHA를 줍니다. 그 SHA의 이미지가 GHCR에 있어야
  합니다(Actions가 그 커밋을 빌드했어야 함).

### 지금 떠 있는 SHA

```bash
docker compose -p beez ps --format '{{.Service}}\t{{.Image}}\t{{.Status}}'
readlink /var/www/beez/current
```

## Jenkins 배포 잡 `beez-deploy`

| 항목 | 값 |
|---|---|
| 정의 | `infra/jenkins/casc/jenkins.yaml`의 `jobs:`(Job DSL). Jenkins가 켜질 때마다 이 정의로 다시 만듭니다 |
| 파라미터 | `GIT_SHA`(40자) · `DEPLOY_ENV`(`dev`) · `TARGET`(`local`) |
| 파이프라인 | `infra/deploy/Jenkinsfile` — 체크아웃한 커밋이 `GIT_SHA`인지 확인 → `deploy.sh` 호출(15분 상한) |
| 동작 | 동시 실행 안 함(뒤 요청은 줄을 섬), 기록 50회 보관 |
| 실행 권한 | `beez-admin`, `github-actions`(이 잡만) |

- **Jenkinsfile을 `GIT_SHA` 그 커밋에서 읽습니다.** 잡의 브랜치 지정이
  `${GIT_SHA}`이고 가벼운 체크아웃(lightweight)을 꺼서 파라미터가 펼쳐집니다.
  JCasC 파일에는 `^${GIT_SHA}`로 적습니다(`^`는 JCasC가 자기 변수로 바꾸지
  않게 하는 표기). 그래서 Jenkinsfile·배포 스크립트·compose 파일이 이미지와
  같은 커밋에서 오고, 예전 SHA로 롤백하면 절차도 그 시절 것으로 돌아갑니다.
- 짧은 SHA나 브랜치 이름을 넣으면 체크아웃한 커밋과 달라 `Checkout`
  단계에서 멈춥니다.
- 화면에서 잡 설정을 고치면 다음 재시작 때 JCasC 정의로 돌아갑니다. 바꿀 때는
  저장소 파일을 고칩니다.
- 플러그인은 적게 씁니다. 선언형 파이프라인 대신 스크립트형(`node { }`)이라
  `pipeline-model-definition` 묶음이 필요 없습니다. 대신 `stage`는
  `pipeline-stage-step`, `checkout scm`의 `scm` 변수는 `workflow-multibranch`가
  줍니다(빠지면 각각 `No such DSL method 'stage'`,
  `No such property: scm`으로 실패, 2026-10-29 로컬 확인).

### 화면에서 배포·롤백

`https://jenkins.beez.ai.kr` → `beez-admin` 로그인 → `beez-deploy` → **Build
with Parameters** → `GIT_SHA`에 40자 SHA → Build. 롤백은 예전 SHA를 넣습니다.
빌드 이름이 `#번호 짧은SHA 환경`으로 바뀌고, Console Output 끝이
`[deploy …] 완료: <SHA>`와 `Finished: SUCCESS`면 성공입니다.

### 잡·플러그인·권한을 바꿀 때 (서버 반영)

`casc/jenkins.yaml`이나 `plugins.txt`를 고쳤으면 아래 순서로 넣습니다. JCasC는
모르는 항목이 있으면 Jenkins 기동 자체를 멈추므로, **바꾼 설정은 먼저 로컬
컨테이너(`jenkins/jenkins:<같은 버전>-lts-jdk21` + `jenkins-plugin-cli`)에서
띄워 봅니다.**

```bash
# 로컬 PC(Git Bash)
scp /c/git/BEEZ/infra/jenkins/plugins.txt /c/git/BEEZ/infra/jenkins/casc/jenkins.yaml beez:/tmp/
# 서버 — 플러그인 설치 도구는 Jenkins 런북 4단계와 같다(체크섬 확인)
sudo install -D -m 600 /etc/jenkins/casc/jenkins.yaml /var/backups/jenkins/jenkins.yaml.before-$(date +%Y%m%d-%H%M) && \
sudo systemctl stop jenkins && \
sudo -u jenkins java -jar /tmp/jenkins-plugin-manager-2.15.0.jar --war /usr/share/java/jenkins.war \
  --plugin-download-directory /var/lib/jenkins/plugins --plugin-file /tmp/plugins.txt && \
sudo install -m 644 -o root -g root /tmp/jenkins.yaml /etc/jenkins/casc/jenkins.yaml && \
sudo systemctl start jenkins && systemctl is-active jenkins && \
sudo journalctl -u jenkins --since "-3 min" | grep -cE "SEVERE|ConfiguratorException|unresolved"   # active, 0
rm -f /tmp/plugins.txt /tmp/jenkins.yaml
```

- 백업은 `/var/backups`에 둡니다. `/etc/jenkins/casc` 안에 두면 JCasC가 그
  파일까지 읽습니다.
- 켜지지 않으면 백업을 되돌려 놓고
  `sudo systemctl reset-failed jenkins && sudo systemctl start jenkins`.
  새로 깐 플러그인은 그대로 둬도 됩니다.
- `unresolved`가 나오면 `casc.env`에 없는 변수를 쓴 것입니다. JCasC는 빈
  문자열로 넣고 계속 뜹니다.

## Actions → Jenkins 호출

`cd.yml`의 `deploy` 잡이 이미지 3개를 다 올린 뒤 `beez-deploy`에
`buildWithParameters`를 **한 번** 보냅니다. Jenkins가 접수하면 201이고, 그
밖이면 잡이 실패합니다. **배포 결과는 기다리지 않습니다.** 기다리며 상태를
반복해서 물으면 Jenkins 앞 Nginx의 인증 요청 제한(IP당 분당 10회, API
토큰과 비밀번호를 구분할 수 없어 함께 셈)에 걸립니다. Actions의 초록불은
"배포 요청 접수"까지이고, 배포 성공은 Jenkins에서 봅니다.

| 응답 | 뜻 |
|---|---|
| 201 | 접수됨 |
| 401 | 토큰이 틀렸거나 폐기됨 → 아래 "토큰 발급·교체" |
| 403 / 404 | `github-actions`에 이 잡 권한이 없음 → JCasC의 `authorizationMatrix` |
| 429 | Nginx 요청 제한. 1분 뒤 Actions에서 다시 실행 |

### 계정과 권한

GitHub에는 관리자 토큰을 두지 않습니다. 전용 계정 `github-actions`의 토큰만
둡니다.

| `github-actions` 토큰으로 | 결과 (2026-10-29 로컬 확인) |
|---|---|
| `beez-deploy` 실행 | 201 |
| 잡 설정 보기·고치기, 관리 화면, 스크립트 콘솔 | 403 |
| 잡 삭제, 새 잡 만들기 | 403 |

토큰이 새도 할 수 있는 일은 "GHCR에 이미 있는 SHA로 배포"까지입니다.
`github-actions`의 비밀번호는 `/etc/jenkins/casc.env`의
`JENKINS_DEPLOY_PASSWORD`(무작위, 출력하지 않음)이고 토큰을 만들 때만
씁니다([Jenkins 런북](jenkins.md) 2단계).

### GitHub Environment `dev`

토큰은 저장소 전체 시크릿이 아니라 Environment `dev`의 시크릿
`JENKINS_API_TOKEN`입니다. 이 환경은 **`dev` 브랜치에서만** 쓸 수 있게
묶었습니다. 다른 브랜치에서 워크플로를 고쳐 토큰을 꺼내려 해도 막힙니다.
배포 기록은 저장소 화면의 Deployments에 남습니다.

```bash
# 처음 만들 때 (저장소 관리자, 로컬 PC)
gh api -X PUT repos/S15A108/BEEZ/environments/dev \
  -F 'deployment_branch_policy[protected_branches]=false' -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/S15A108/BEEZ/environments/dev/deployment-branch-policies -f name=dev -f type=branch
```

### 토큰 발급·교체

로컬 PC(Git Bash, 저장소 폴더)에서 실행합니다. 서버가 만든 토큰이 변수
`T`를 거쳐 바로 GitHub 시크릿으로 들어가고, 화면·파일에 남지 않습니다.

```bash
T=$(ssh beez 'sudo bash -s' < /c/git/BEEZ/infra/jenkins/issue-deploy-token.sh) && [ ${#T} -ge 30 ] && \
  printf %s "$T" | gh secret set JENKINS_API_TOKEN --env dev --repo S15A108/BEEZ && echo "secret set (${#T} chars)"; unset T
```

- `ssh beez 'sudo bash -s' < 파일`은 PC의 스크립트를 서버에서 root로
  실행합니다. 스크립트는 `127.0.0.1:8080`에 직접 붙고, 비밀번호는 명령줄
  인자 대신 `curl -K -`(표준 입력)로 넘겨 프로세스 목록에 보이지 않습니다.
- `[ ${#T} -ge 30 ]`은 토큰이 비어 있을 때 시크릿을 빈 값으로 덮어쓰지 않게
  합니다.
- **새 토큰을 만들어도 옛 토큰은 살아 있습니다.** 교체한 뒤 `beez-admin`으로
  로그인해 사람 목록 → `github-actions` → Security → API Token에서 옛 토큰을
  폐기합니다. 토큰 이름에 발급 날짜(`github-actions-cd-YYYYMMDD`)가 붙습니다.

## 운영 환경을 붙일 때 바뀌는 범위

운영 환경 선택(A: t3a 서버 추가 / B: 이 서버를 main에 연결)은
S15P31A108-73에서 정합니다. 어느 쪽이든 **이미지·Dockerfile·compose
파일·배포 스크립트의 흐름은 바꾸지 않습니다.** 같은 SHA의 이미지를 다른
환경 값으로 띄울 뿐입니다.

| 바뀌는 것 | A: t3a 추가 | B: 이 서버를 main으로 |
|---|---|---|
| env 파일 | 새 서버에 `/opt/beez/.env.prod`(서버 준비 1~3 다시) | 이 서버에 `/opt/beez/.env.prod` 추가, 또는 `.env.dev`를 운영 값으로 |
| Jenkins 잡 파라미터 | `DEPLOY_ENV`에 `prod`, `TARGET`에 운영 서버(ssh) 선택지 추가 | `DEPLOY_ENV`에 `prod` 추가 |
| 배포 대상 | ssh 타깃 구현(Jenkinsfile의 `TARGET` 분기, 운영 서버 deploy 사용자·키) — **유일한 코드 추가** | 없음 |
| GitHub | Environment `prod`(main만, 시크릿 `JENKINS_API_TOKEN`), `cd.yml` 트리거에 `main` + 환경 이름을 브랜치로 고름 | 같음 |
| DNS | `beez.ai.kr` A 레코드 → t3a | `beez.ai.kr` A 레코드 → 이 서버 |
| 인증서·Nginx | t3a에 Nginx·certbot([Nginx·HTTPS 런북](nginx-https.md) "호스트 추가"), `beez.ai.kr` 설정 파일 | `beez.ai.kr` 설정 파일 추가·`certbot`으로 발급 |
| 서버 준비 | t3a에 서버 세팅·Docker·`/var/www/beez`·`/opt/beez` 런북 다시 | 없음 |

- 이렇게 범위가 좁은 이유는 처음부터 환경 이름을 경로·볼륨·서비스 이름에
  넣지 않고(`/opt/beez`, `beez_pgdata`), 환경 차이를 env 파일과 잡
  파라미터로만 주게 만들었기 때문입니다.
- B에서 dev와 운영을 한 서버에 같이 두려면 compose 프로젝트 이름과 backend
  호스트 포트를 환경별로 나눠야 합니다(`name: beez`가 고정이라 지금은 한
  서버에 한 환경). 이 경우 compose의 `name`과 `BACKEND_HOST_PORT`를 env
  파일로 빼는 변경이 추가됩니다.

## 확인

로컬 PC:

```bash
curl -s https://dev.beez.ai.kr/api/ping                                          # {"ok":true,"service":"beez-backend",...}
curl -s -o /dev/null -w '%{http_code}\n' https://dev.beez.ai.kr/actuator/health  # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://dev.beez.ai.kr/mcp       # 401 (토큰 없음)
curl -s -o /dev/null -w '%{content_type}\n' https://dev.beez.ai.kr/actuator/env  # text/html (backend로 안 감)
```

서버:

```bash
sudo ss -ltnp | grep -E ':(8080|8081|5432|8000|8333) '   # 127.0.0.1:8080(Jenkins)·127.0.0.1:8081(backend)만
curl -s http://127.0.0.1:8081/actuator/health            # {"groups":[...],"status":"UP"}
```

## 주의

- **서버에 접속하는 팀원은 모두 `ubuntu` 계정을 씁니다.** `.env.dev`는
  `www-data`·다른 서비스 계정으로부터는 막혀 있지만, ubuntu로 들어온
  사람은 읽을 수 있습니다. 계정 분리는 운영 환경 결정(S15P31A108-73) 때
  다룹니다.
- **DB 데이터는 `beez_pgdata` 볼륨에 있습니다.** `docker compose down -v`나
  볼륨 삭제는 데이터를 지웁니다. 백업 절차는 아직 없습니다.
- SeaweedFS는 지금 backend와 같은 키를 씁니다. bootstrap 키와 backend 전용
  키 분리는 S15P31A108-211입니다.

## 2026-10-29 결과

| 항목 | 값 |
|---|---|
| CD 첫 실행(캐시 없음) | backend 1분 53초, ai 1분 10초, frontend-dist 1분 18초(병렬, 전체 약 2분) |
| 이미지 크기(서버) | backend 602MB, ai 444MB, frontend-dist 2.88MB |
| 첫 수동 배포 | 57초(이미지 받기 22초, 컨테이너 생성·healthy 32초), Flyway V9까지 적용 |
| 같은 SHA 재배포 | 3초, 컨테이너 재생성 없음 |
| 외부 확인 | `/api/ping` JSON, `/actuator/health` 200, `/mcp` 401, 1.5MB 요청 통과·3MB 413 |
| Jenkins 화면 배포(#1) | 26초, backend·ai만 재생성(postgres·seaweedfs 유지), 꺼낸 dist는 `jenkins:beez-deploy` 664/775 |
| CD 재실행(캐시 적중) | 이미지 3개 각 19~25초 |
| 자동 흐름(#2, 1531d05) | push → 이미지 → Jenkins 호출 201 → 배포 완료까지 약 1분 10초, Jenkins 빌드 시작 계정 `github-actions` |
