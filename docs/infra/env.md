# 환경변수와 비밀값

BEEZ 의 서비스(backend·ai·frontend)와 배포(CI·CD·Jenkins·서버)가 읽는 설정값을 한곳에 모은 문서입니다.
값을 어디에 넣는지, 빠지면 무엇이 보이는지, 비밀값을 어떻게 다루는지를 적습니다. (S15P31A108-27)

원칙은 셋입니다.

1. **비밀값은 저장소에 없습니다.** 저장소에는 이름과 빈 칸(예시 파일)만 둡니다.
2. **환경 차이는 env 파일로만 줍니다.** compose 파일·설정 파일은 환경과 상관없이 하나입니다.
3. **필수값이 빠지면 기동 전에 멈춥니다.** compose 는 `${VAR:?}`, backend prod 는 설정 검증이 이름을 짚어 줍니다.

## 1. 값을 넣는 곳

| 실행 방식 | 값을 읽는 곳 | 비밀값 출처 |
|---|---|---|
| 로컬 backend `./gradlew bootRun` | `application.yml`·`application-local.yml` 기본값 | 없음. 아무것도 넣지 않아도 뜹니다 |
| 로컬 ai `uvicorn` | `ai/.env` (`ai/.env.example` 복사) | 본인 GMS 키 |
| 로컬 frontend `npm run dev` | `frontend/.env` (`frontend/.env.example` 복사, 선택) | 없음 (`VITE_` 값은 공개됩니다) |
| 로컬 전체 스택 compose | `infra/local.env` (더미값, 커밋됨) | GMS 키가 필요하면 셸 환경변수로 |
| CI (`ci.yml`) | `application-test.yml` + Testcontainers | 없음 |
| CD 이미지 빌드 (`cd.yml`) | 저장소 변수 `VITE_API_MODE` (지금 없음 → `mock`) | `GITHUB_TOKEN` (자동 발급) |
| CD 배포 호출 (`cd.yml` deploy 잡) | Environment `dev` 시크릿 | `JENKINS_API_TOKEN` |
| 서버 컨테이너 | `/opt/beez/.env.<환경>` → `deploy.sh` 가 `--env-file` 로 넘김 | 그 파일 (`640 ubuntu:beez-deploy`) |
| Jenkins | `/etc/jenkins/casc.env` | 그 파일 (`600 root:root`) |
| 저장소 자동화 | 저장소 시크릿 | `PR_AUTOMERGE_TOKEN`, `GITLAB_SYNC_TOKEN` |

서버 env 파일을 처음 만드는 명령과 GMS 키를 넣는 명령은 [cd.md 서버 준비](cd.md#2-env-파일)에 있습니다.
Jenkins 비밀 파일은 [jenkins.md](jenkins.md)에 있습니다.

## 2. 변수 목록

### 서버 env 파일 (`/opt/beez/.env.<환경>`)

compose 가 읽는 값입니다. **필수**는 compose 가 `${VAR:?}` 로 검사합니다. `infra/local.env` 가 같은 이름의 더미값을 가진
로컬판입니다.

| 이름 | 필수 | 비밀 | 의미 | 들어가는 곳 |
|---|---|---|---|---|
| `DB_USERNAME` | 필수 | | DB 계정 | postgres 초기화, backend |
| `DB_PASSWORD` | 필수 | 비밀 | DB 비밀번호. **DB 를 처음 띄울 때 값으로 굳습니다** — 아래 [교체](#값이-샜을-때) 참고 | postgres 초기화, backend |
| `DB_NAME` | | | 기본 `beez` | postgres, backend `DB_URL` |
| `DOCUMENT_S3_ACCESS_KEY` | 필수 | 비밀 | 문서 원본 저장소 키 | seaweedfs, backend |
| `DOCUMENT_S3_SECRET_KEY` | 필수 | 비밀 | 〃 | seaweedfs, backend |
| `DOCUMENT_S3_BUCKET` | | | 기본 `beez-documents` | seaweedfs, backend |
| `AI_INTERNAL_TOKEN` | 필수 | 비밀 | backend ↔ ai 상호 인증. compose 가 같은 값을 양쪽에 넣습니다 | ai, backend |
| `GMS_KEY` | | 비밀 | LLM·임베딩 API 키. 비워도 뜨지만 AI 호출이 502 입니다 | ai |
| `PUBLIC_ORIGIN` | 필수 | | 브라우저가 여는 주소. **끝에 `/` 없이** (`https://dev.beez.ai.kr`) | backend `CORS_ALLOWED_ORIGINS`·`INVITE_BASE_URL`·`MCP_URL`(+`/mcp`) |
| `BACKEND_HOST_PORT` | 필수 | | backend 를 여는 `127.0.0.1` 포트. 서버는 `8081`, 로컬(`infra/local.env`)은 `18080` | backend 포트, Nginx `upstream beez_backend` 와 같아야 함 |
| `SWAGGER_ENABLED` | | | 기본 `false` | backend |
| `DOCUMENT_EMBEDDING_PROFILE` | | | 기본 `gms-small-1024-v1`. ai 실제 프로필과 같아야 합니다 | backend |
| `DOCUMENT_EMBEDDING_MODEL` | | | 기본 `text-embedding-3-small`. 〃 | backend |
| `SPRING_PROFILES_ACTIVE` | | | 기본 `prod` | backend |

- `BACKEND_HOST_PORT` 는 기본값이 없습니다. 예전 기본값 `8080` 은 서버의 Jenkins 와 겹치는데도 점검을 통과했습니다.
  값이 있어도 `8080` 이거나 Nginx 와 다르면 compose 는 모르므로 [포트 점검](#배포-전에-점검하기)을 따로 돌립니다.
- `BACKEND_IMAGE`·`AI_IMAGE` 는 `deploy.sh` 가 배포 SHA 로 채웁니다. env 파일에 쓰지 않습니다.
- `LOCAL_HTTP_PORT` 는 로컬 nginx 전용입니다(`compose.local.yaml`).

### backend (Spring)

`application.yml` 이 기본값을 갖고, `application-prod.yml` 이 운영 필수값의 기본값을 지웁니다. 서버에서는 대부분
compose 가 채우므로 env 파일에 직접 쓰는 것은 위 표뿐입니다.

| 이름 | prod 필수 | 로컬 기본값 | compose(서버) 값 |
|---|---|---|---|
| `DB_URL` | 필수 | `jdbc:postgresql://localhost:5432/beez` | `jdbc:postgresql://postgres:5432/${DB_NAME}` |
| `DB_USERNAME`·`DB_PASSWORD` | 필수 | `beez`·`beez` | env 파일 |
| `DB_POOL_SIZE` | | `10` | 기본값 |
| `SERVER_PORT` | | `8080` (컨테이너 안) | 기본값 |
| `DOCUMENT_S3_ENDPOINT` | 필수 | `http://127.0.0.1:8333` | `http://seaweedfs:8333` |
| `DOCUMENT_S3_REGION` | | `us-east-1` | 기본값 |
| `DOCUMENT_S3_BUCKET`·`ACCESS_KEY`·`SECRET_KEY` | 필수 | `beez-documents`·`beez-local-access`·`beez-local-secret` | env 파일 (뜻은 [seaweedfs.md](seaweedfs.md)) |
| `DOCUMENT_WORKER_ENABLED` | | `true` | 기본값 |
| `DOCUMENT_POLL_MILLIS` | | `2000` | 기본값 |
| `DOCUMENT_EMBEDDING_PROFILE`·`MODEL` | | `unconfigured` (색인이 멈춤) | 위 표 기본값 |
| `CORS_ALLOWED_ORIGINS` | 필수 | `http://localhost:5173,http://127.0.0.1:5173` | `PUBLIC_ORIGIN` |
| `AI_BASE_URL` | 필수 | `http://localhost:8000` | `http://ai:8000` |
| `AI_INTERNAL_TOKEN` | 필수 | `change-me` | env 파일 |
| `AI_CONNECT_TIMEOUT`·`AI_READ_TIMEOUT` | | `3s`·`20s` | 기본값 |
| `INVITE_BASE_URL` | 필수 | `http://localhost:5173` | `PUBLIC_ORIGIN` |
| `INVITE_TTL` | | `7d` | 기본값 |
| `MCP_URL` | 필수 | `http://localhost:8080/mcp` | `PUBLIC_ORIGIN/mcp` |
| `AGENT_MAX_ACTIVE_TOKENS` | | `5` | 기본값 |
| `SWAGGER_ENABLED` | | (local 은 켜짐) | `false` |

prod 필수값은 `application-prod.yml` 에 **`${X:}`(빈 기본값)** 으로 적습니다. `${X}` 로 적으면 값이 없을 때 Spring 이
오류 없이 `"${X}"` 글자를 그대로 넣고, 이 글자는 비어 있지 않아 `@NotBlank` 를 통과해 그대로 기동합니다(2026-10-30
실측: `AI_*`·`INVITE_BASE_URL`·`MCP_URL` 없이 기동됨. 그대로 두면 초대 링크가 `${INVITE_BASE_URL}/invite?…` 로
만들어질 수 있음). 빈 값이면 설정 검증이 이름을 짚어 멈춥니다.
`DB_USERNAME` 만 예외로 `${X}` 그대로 둡니다. 빠지면 DB 접속 오류에 user `"${DB_USERNAME}"` 이 찍혀 원인이 바로
보입니다. `DB_PASSWORD` 는 `${X}` 로 두면 글자가 비밀번호로 전송돼 "비밀번호 틀림"처럼 보이므로 `${X:}` 입니다.
이 규칙은 `ProdProfilePropertiesTest`(빠른 층 `./gradlew test`)가 검사합니다 — prod 파일에 기본값 없는 `${X}` 가
생기거나, `beez.cors`·`beez.ai`·`beez.invitation`·`beez.agent` 필수값에 기본값이 다시 들어가면 실패합니다.
`DB_URL`·`DB_PASSWORD` 에 기본값을 넣는 것은 잡지 못하므로 리뷰에서 봅니다.

**CDC(`beez.cdc.*`)는 위 표에 없습니다.** 기본으로 꺼진 실험 설정이라 `application.yml` 에도, 서버 compose 에도 없습니다.
`enabled`·`bootstrap-servers`·`group-id`·`topic`·`kafka-security.properties` 의 뜻과 켜는 조건은
[cdc README](../../backend/src/main/java/com/beez/backend/cdc/README.md)를 봅니다. 서버에서 켜려면 env 파일만으로는 안 되고
`infra/compose.yaml` 의 backend `environment` 에도 넘겨야 합니다(compose 는 적힌 변수만 컨테이너에 넣습니다).
그때 이 문서의 표에 옮겨 적습니다.

### ai (FastAPI)

`ai/app/core/config.py` 가 `ai/.env` 와 환경변수를 읽습니다. 서버 compose 는 앞의 둘만 넘깁니다.

| 이름 | 비밀 | 의미 |
|---|---|---|
| `GMS_KEY` | 비밀 | 임베딩·생성 호출 키. 비면 호출이 502 `model_unavailable` |
| `AI_INTERNAL_TOKEN` | 비밀 | backend 가 보내는 `Authorization: Bearer` 와 비교. 비거나 다르면 401 `invalid_internal_token` |
| `GMS_EMBEDDING_MODEL` | | `text-embedding-3-small` 만 허용 |
| `GMS_EMBEDDING_DIMENSION` | | 기본 `1024`. backend(1024)와 같아야 합니다 |
| `BEE_DEFAULT_MODEL`·`BEE_COMPLEX_MODEL` | | 정해진 값만 허용 |
| `TIKTOKEN_CACHE_DIR` | | 어휘 파일 위치. 이미지에는 `/app/.tiktoken` 으로 고정 |

**로컬 함정**: backend 를 `bootRun` 으로 띄우면 `AI_INTERNAL_TOKEN` 기본값이 `change-me` 입니다. `ai/.env.example` 을
그대로 복사하면 빈 값이라 backend → ai 호출이 전부 401 입니다. 로컬에서는 `ai/.env` 에 `AI_INTERNAL_TOKEN=change-me`
를 넣거나, 두 쪽에 같은 값을 환경변수로 줍니다. 로컬 compose 는 `infra/local.env` 하나에서 양쪽에 넣으므로 이 문제가
없습니다.

### frontend (Vite)

| 이름 | 의미 |
|---|---|
| `VITE_API_MODE` | `live` 면 `/api` 를 호출, **그 밖의 값이나 빈 값은 모두 `mock`** |

- `VITE_` 값은 빌드 결과(JS)에 글자로 들어가 누구나 볼 수 있습니다. **비밀값을 넣지 않습니다.**
- 빌드할 때 굳습니다. 서버에서 바꾸려면 다시 빌드해야 합니다. CD 는 저장소 변수 `VITE_API_MODE` 를 빌드 인자로
  넘기고, 변수가 없으면 `mock` 입니다(2026-10-30 기준 없음). `live` 전환은 프론트 연동 PR 때 합니다.

### CI·CD·Jenkins

| 이름 | 위치 | 쓰는 곳 | 교체 |
|---|---|---|---|
| `GITHUB_TOKEN` | Actions 자동 발급 | `cd.yml` GHCR push | 필요 없음 |
| `JENKINS_API_TOKEN` | Environment `dev` 시크릿 | `cd.yml` → Jenkins 배포 잡 호출 | [cd.md 토큰 발급·교체](cd.md#토큰-발급교체) |
| `PR_AUTOMERGE_TOKEN` | 저장소 시크릿 | `pr-auto-merge.yml` | 발급한 사람이 갱신 |
| `GITLAB_SYNC_TOKEN` | 저장소 시크릿 | `sync-gitlab.yml` (SSAFY GitLab 미러) | 〃 |
| `JENKINS_ADMIN_ID`·`JENKINS_ADMIN_PASSWORD` | 서버 `/etc/jenkins/casc.env` | Jenkins 관리자 (JCasC) | [jenkins.md](jenkins.md) |
| `JENKINS_DEPLOY_PASSWORD` | 〃 | Jenkins `github-actions` 계정 | 〃 |

CI(`ci.yml`)는 비밀값을 쓰지 않습니다. backend 테스트는 Testcontainers 가 DB 를 띄우고 나머지는
`application-test.yml` 의 고정값을 씁니다.

## 3. 빠졌을 때 보이는 것

2026-10-30 실측입니다(이미지 `9f9af95` + 이 문서와 같은 PR 의 `application-prod.yml`).

| 상황 | 결과 | 보이는 문구 |
|---|---|---|
| 서버 env 파일에 필수값 없음·빈 값 | **compose 가 시작 전에 멈춤**, 컨테이너는 그대로 | `required variable DB_PASSWORD is missing a value: DB_PASSWORD is required` |
| 서버 env 파일을 읽을 수 없음 | `deploy.sh` 가 멈춤 | `env 파일을 읽을 수 없음: /opt/beez/.env.dev` |
| backend prod, `DB_URL` 없음 | 기동 실패 | `Failed to configure a DataSource: 'url' attribute is not specified` |
| backend prod, `DB_USERNAME` 없음 | 기동 실패 | `password authentication failed for user "${DB_USERNAME}"` |
| backend prod, `DB_PASSWORD` 없음 | 기동 실패 | `The server requested SCRAM-based authentication, but no password was provided.` |
| backend prod, `CORS_ALLOWED_ORIGINS` 없음 | 기동 실패 | `Property: beez.cors.allowedOrigins … must not be empty` |
| backend prod, `AI_*`·`INVITE_BASE_URL`·`MCP_URL` 없음 | 기동 실패 | `Property: beez.agent.mcpUrl … must not be blank` (먼저 걸린 것 하나만 나옴) |
| backend prod, `DOCUMENT_S3_*` 없음 또는 버킷 이름 형식 틀림 | 기동 실패 | `SeaweedFS endpoint, region, bucket and credentials required` |
| backend, 임베딩 프로필 비어 있음 | 기동은 됨, 색인이 멈춤 | `embedding_profile_not_configured` |
| ai, `AI_INTERNAL_TOKEN` 비었거나 backend 와 다름 | 기동은 됨(`/health` 200), 호출이 401 | `invalid_internal_token` |
| ai, `GMS_KEY` 비어 있음 | 기동은 됨, 호출이 502 | `model_unavailable` |
| frontend, `VITE_API_MODE` 오타 | 조용히 `mock` | 없음 — 화면이 목데이터 |

서버에서는 compose 가 먼저 걸러 주므로 backend 의 prod 검증까지 가는 일은 거의 없습니다. prod 검증은 compose 밖에서
jar 나 이미지를 prod 로 띄울 때의 안전장치입니다.

### 배포 전에 점검하기

서버 env 파일을 **배포하지 않고** 두 가지로 확인합니다. 비밀값은 화면에 나오지 않습니다.

```bash
# 로컬 PC (Git Bash, 저장소 루트). compose 파일을 서버로 넘겨 그 자리에서 검사
HOST=beez               # 그 환경의 서버. ~/.ssh/config 의 Host 별칭
ENV_NAME=dev            # 검사할 환경. 서버 env 파일 /opt/beez/.env.<ENV_NAME>
SITE=dev.beez.ai.kr     # 그 환경의 Nginx 설정 /etc/nginx/sites-enabled/<SITE>.conf
REF=dev                 # 필수값을 추가하는 PR 이면 그 PR 의 브랜치 이름

# 1) 필수값이 다 있는가  2) backend 포트를 다른 프로그램이 쓰지 않고, Nginx 가 보내는 포트와 같은가
# 성공이면 env ok · port ok 두 줄, 실패하면 그 자리에서 멈추고 exit 1
git fetch -q origin && cf=$(git show "origin/$REF:infra/compose.yaml" | base64 | tr -d '\n') && [ -n "$cf" ] &&
ssh "$HOST" bash -s -- "$ENV_NAME" "$SITE" "$cf" <<'EOF'
fail() { echo "$1"; exit 1; }
# compose 오류 문구는 env 값을 그대로 담기도 한다(따옴표가 안 닫힌 값, 포트 형식 오류 등).
# 값이 들어가지 않는 형식만 골라 보여 주고, 나머지는 숨긴다
safe() { awk '
  match($0, /required variable [A-Za-z0-9_]+ is missing a value/) { print substr($0, RSTART, RLENGTH); next }
  /^failed to read / && match($0, /: line [0-9]+: /) { print "env 파일 " substr($0, RSTART + 7, RLENGTH - 9) "번째 줄 형식 오류 (값은 숨김)"; next }
  /^failed to read / { print "env 파일 읽기 오류, 예: ${ 문법 (값은 숨김)"; next }
  /^couldn.t find env file: |^unknown flag: |command not found/ { print; next }
  NF { h = 1 }
  END { if (h) print "compose 오류 (값이 섞일 수 있어 문구는 숨김)" }'; }
envf=/opt/beez/.env.$1
cf=$(printf '%s' "$3" | base64 -d) && [ -n "$cf" ] || fail "compose 파일을 받지 못함"
err=$(printf '%s\n' "$cf" | docker compose -f - --env-file "$envf" config --quiet 2>&1 >/dev/null) ||
  { printf '%s\n' "$err" | safe; fail "env 점검 실패"; }
echo "env ok"
blk='/upstream[[:space:]]+beez_backend[[:space:]]*[{]/'
# 포트는 compose 가 해석한 값을 쓴다. 주석·따옴표·CRLF·중복 키(마지막 값)를 compose 와 똑같이 읽는다.
# 이 명령은 env 파일의 모든 값을 내보내므로 포트만 꺼낸다
envs() { printf 'services: {}\n' | docker compose -f - --env-file "$envf" config --environment; }
err=$(envs 2>&1 >/dev/null) || { printf '%s\n' "$err" | safe; fail "env 읽기 실패"; }
p=$(envs 2>/dev/null | sed -n 's/^BACKEND_HOST_PORT=//p')
case $p in ''|*[^0-9]*) fail "port 값 없음·이상 (숫자가 아님, 값은 숨김)" ;; esac
conf=/etc/nginx/sites-enabled/$2.conf
[ -r "$conf" ] || fail "Nginx 설정 없음: $conf"
hit="127\.0\.0\.1:$p([^0-9]|$)"
# (a) Nginx 의 다른 곳이 같은 포트로 보내는가. 꺼져 있는 서비스(예: Jenkins 8080)도 설정으로 잡는다
for f in /etc/nginx/sites-enabled/*; do
  body=$(sed 's/#.*//' "$f") || fail "Nginx 설정 읽기 실패: $f"
  printf '%s\n' "$body" | awk "$blk{s=1} s==0; s&&/[}]/{s=0}" | grep -E "$hit" >/dev/null &&
    fail "port 충돌: Nginx 의 다른 설정이 포트 $p 사용 ($f)"
done
# (b) 지금 그 포트를 연 프로그램이 있으면 떠 있는 backend 여야 한다
listen=$(ss -Hltn "sport = :$p") || fail "ss 실행 실패: 포트 $p 사용 여부 확인 못 함"
if [ -n "$listen" ]; then
  ports=$(docker ps --filter label=com.docker.compose.project=beez --filter label=com.docker.compose.service=backend \
    --format '{{.Ports}}') || fail "docker 조회 실패: 포트 $p 사용 주체 확인 못 함"
  case $ports in *"127.0.0.1:$p->"*) ;; *) fail "port 충돌: 다른 프로그램이 $p 사용 중" ;; esac
fi
# (c) upstream beez_backend 의 server 는 하나여야 하고, 그 포트가 env 와 같아야 한다
up=$(sed 's/#.*//' "$conf" | awk "$blk{f=1} f; f&&/[}]/{exit}")
[ -n "$up" ] || fail "upstream beez_backend 없음: $conf"
servers=$(printf '%s\n' "$up" | grep -oE '(^|[[:space:]{;])server[[:space:]]+[^;]+')
[ "$(printf '%s\n' "$servers" | grep -c .)" -eq 1 ] || fail "upstream beez_backend 의 server 가 하나가 아님: $conf"
printf '%s\n' "$servers" | grep -E "$hit" >/dev/null || fail "port 다름: env=$p"
echo "port ok ($p)"
EOF
```

- **`env ok` 는 값이 있는지만 봅니다.** 값이 맞는지(포트, `PUBLIC_ORIGIN` 형식, 비밀값이 DB 와 같은지)는 보지 않습니다.
  그중 포트는 틀리면 배포가 바로 깨지므로 2) 로 따로 봅니다.
  - `port 충돌`: 그 포트를 backend 가 아닌 곳이 씁니다. 두 가지로 봅니다.
    (a) Nginx 의 켜진 사이트 설정 중 `upstream beez_backend` 밖에서 그 포트로 보내는 곳이 있으면 걸립니다.
    `8080` 이면 Jenkins 설정에 걸리므로 Jenkins 가 잠깐 꺼져 있어도 잡힙니다.
    (b) 지금 그 포트를 연 프로그램(`ss -Hltn` 은 LISTEN 중인 TCP 포트를 숫자로 보여 줌)이 떠 있는 backend 컨테이너가
    아니면 걸립니다. backend 인지는 compose 라벨(`com.docker.compose.project=beez`, `service=backend`)로 찾습니다.
    Nginx 에 없고 지금 꺼져 있는 프로그램의 포트는 잡지 못합니다.
  - `port 다름`: Nginx 의 `upstream beez_backend` 가 다른 포트로 보냅니다. backend 는 떠도 API 가 실패합니다.

  배포(`deploy.sh`)는 컨테이너 안 healthcheck 만 보므로 포트가 어긋나도 성공으로 끝납니다. 값을 바꿨다면 이 점검을 꼭 돌립니다.
- 포트 값은 env 파일을 직접 읽지 않고 compose 가 해석한 값(`config --environment`)으로 봅니다. 그래서
  `BACKEND_HOST_PORT=8081 # 주석`, 따옴표, CRLF, 같은 키 두 번(마지막 값)을 배포 때와 똑같이 읽습니다.
  이 명령은 env 파일의 모든 값을 내보내므로 스크립트 안에서 포트만 꺼내고 화면에는 찍지 않습니다.
- **compose 오류 문구도 그대로 보여 주지 않습니다.** compose 는 따옴표가 안 닫힌 값(`unterminated quoted value "<값>`),
  포트 형식 오류(`invalid hostPort: <값>`), `${` 문법 오류(`Invalid template: "<값>"`)에서 값을 그대로 찍습니다
  (2026-10-30 로컬 실측). 그래서 `safe` 가 값이 들어가지 않는 형식(빠진 변수 이름, 줄 번호, env 파일 경로, 모르는 옵션)만
  골라 보여 주고, 나머지는 `compose 오류 (값이 섞일 수 있어 문구는 숨김)` 한 줄로 바꿉니다. 숨겨진 원인을 봐야 하면
  서버에서 직접 compose 를 돌려 봅니다(채팅·PR 에 붙여 넣지 않습니다).
- **2) 는 디스크의 Nginx 설정 파일을 읽습니다. 실행 중인 Nginx 가 아닙니다.** 파일을 고친 뒤 reload 하기 전에는
  `port ok` 가 나와도 Nginx 는 이전 포트로 보냅니다. 설정을 고쳤다면 `sudo nginx -t && sudo systemctl reload nginx` 까지
  하고, 반영됐는지는 아래 [Nginx 반영 확인](#nginx-반영-확인)으로 봅니다. 배포 단계에서 자동으로 확인하는 것은
  S15P31A108-245 에서 합니다.
- `upstream beez_backend` 에 `server` 가 둘 이상이면 멈춥니다. 하나만 맞아도 Nginx 는 나머지로도 요청을 보내므로,
  compose 가 여는 포트 하나와 같은 **server 하나짜리** 설정만 지원합니다.
- **점검 명령 자체가 실패해도 `port ok` 가 나오지 않습니다.** `port ok` 일 때만 exit 0 이고, 아래 문구를 포함해
  실패는 모두 exit 1 입니다.
  `env 점검 실패`(1) 와 `env 읽기 실패`(2) 는 바로 위에 찍힌 줄로 원인을 구분합니다.
  `required variable X is missing a value` 는 필수값 X 가 없거나 빈 것, `couldn't find env file` 은 `ENV_NAME` 이 틀린 것,
  `env 파일 N번째 줄 형식 오류` 는 그 줄의 따옴표·키 형식이 틀린 것,
  `unknown flag: --environment` 는 서버 compose 가 이 옵션보다 오래된 것입니다(서버 v5.6.1·로컬 v5.2.0 에는 있음, 2026-10-30).
  `docker: command not found` 는 서버에 docker 가 없는 것입니다.
  `port 값 없음·이상` 은 env 파일에 포트가 없거나 숫자가 아닌 것입니다(값은 찍지 않습니다).
  `Nginx 설정 없음` 은 `SITE` 가 틀렸거나 사이트가 꺼진 것입니다. `Nginx 설정 읽기 실패` 는 켜진 사이트 중 읽을 수 없는
  파일(권한, 깨진 링크)이 있는 것입니다.
  `upstream beez_backend 없음` 은 그 설정 파일에 블록이 없는 것입니다(`SITE` 가 다른 사이트이거나 upstream 을 다른 파일로 옮김).
  `ss 실행 실패` 는 서버에 `ss`(iproute2)가 없거나 실행이 실패한 것입니다.
  `docker 조회 실패` 는 SSH 사용자에게 docker 권한이 없거나 데몬이 멈춘 것입니다.
- 다른 환경(운영 등)은 위 네 변수를 바꿉니다. 운영 서버가 따로 있으면(S15P31A108-73) `HOST` 를 그 서버의 별칭으로 바꿉니다.
  `ENV_NAME`·`SITE` 만 바꾸면 여전히 dev 서버를 검사합니다. 그대로 복사하면 dev 를 검사하고 통과합니다.
- **서버 하나에 환경 하나**가 전제입니다. 2) 는 upstream 이름 `beez_backend` 와 compose 프로젝트 이름 `beez` 를
  고정으로 봅니다. 한 서버에 dev 와 운영을 같이 두면 Nginx 가 upstream 이름 중복을 허용하지 않아 운영 쪽 이름이
  달라지고, 2) 가 `port 충돌` 이나 `upstream beez_backend 없음` 으로 잘못 멈춥니다. compose 프로젝트 이름도 같아
  컨테이너부터 겹칩니다. 같은 서버에 두기로 하면 이 점검과 compose 이름을 함께 고칩니다.
- **검사 기준은 `REF` 의 compose 파일입니다.** 머지 전 PR 이 추가한 변수는 dev 의 compose 에 없으므로, `REF=dev` 로
  검사하면 서버에 넣은 값에 오타가 있어도 `env ok` 가 나옵니다. 그 PR 의 브랜치로 검사합니다.
- 브랜치 이름이 틀리면 `fatal: invalid object name` 만 나오고 점검은 돌지 않습니다(`env ok` 는 나오지 않음).
- compose 파일은 base64 로 바꿔 ssh 인자로 보냅니다(표준 입력은 점검 스크립트가 씁니다). 서버에서 `-f -` 로 읽으므로
  서버에 파일을 남기지 않습니다.
- `config --quiet` 는 변수를 채워 보기만 하고 아무것도 띄우지 않습니다. 빠진 값이 있으면 위 표 첫 줄 문구의 앞부분
  (`required variable DB_PASSWORD is missing a value`)이 나옵니다.
- 값의 길이만 보려면 [cd.md](cd.md#2-env-파일)의 `awk` 명령을 씁니다.

로컬 전체 스택도 같은 방식입니다.

```bash
docker compose -f infra/compose.yaml --env-file infra/local.env config --quiet && echo "env ok"
```

#### Nginx 반영 확인

위 점검은 디스크의 설정 파일을 봅니다. Nginx 설정을 고쳤다면 reload 가 실제로 반영됐는지 따로 봅니다
(로컬 PC, 위의 `HOST`·`SITE` 그대로).

```bash
# reload 하면 worker 가 새로 뜬다. 파일 수정이 worker 시작보다 늦으면 아직 반영 전이다.
# sites-enabled 는 sites-available 을 가리키는 링크라, date -r 로 링크가 아닌 실제 파일 시각을 본다
ssh "$HOST" bash -s -- "$SITE" <<'EOF'
echo "설정 파일 수정: $(date -r "/etc/nginx/sites-enabled/$1.conf" '+%F %T')"
ps -o lstart=,cmd= -C nginx | awk '/worker process$/ {print $1,$2,$3,$4,$5}' | sort -u |
  while read -r t; do echo "worker 시작(마지막 reload): $(date -d "$t" '+%F %T')"; done
EOF
# 배포한 뒤: 실제 Nginx 를 거쳐 backend health 를 부른다. 200 이면 지금 Nginx 가 backend 에 닿는다(502 면 다른 포트)
curl -sS -o /dev/null -w '%{http_code}\n' "https://$SITE/actuator/health"
```

- 두 시각이 **같은 초**면 수정과 reload 중 어느 쪽이 먼저인지 알 수 없습니다(시각이 초 단위). 그때는 reload 를 한 번
  더 하거나, 배포 뒤 health 로 확인합니다.
- health 주소는 `SITE` 가 도메인과 같다는 전제입니다(`dev.beez.ai.kr`).

## 4. 환경별 차이

| 항목 | 로컬 `bootRun` | 로컬 compose | CI 테스트 | 서버 dev |
|---|---|---|---|---|
| Spring 프로필 | `local` | `local` | `test` | `prod` |
| DB | `backend/compose.yaml` 자동 기동 | compose postgres | Testcontainers | compose postgres (볼륨 `beez_pgdata`) |
| 문서 원본 | `127.0.0.1:8333` | `seaweedfs:8333` | 닫힌 주소(`127.0.0.1:1`) | `seaweedfs:8333` |
| AI 주소 | `localhost:8000` | `ai:8000` | 닫힌 주소 | `ai:8000` |
| `AI_INTERNAL_TOKEN` | `change-me` | `local.env` | 기본값 | env 파일 (난수) |
| CORS·초대·MCP 주소 | `localhost:5173` 계열 | `http://localhost:8088` | 기본값 | `https://dev.beez.ai.kr` |
| 세션 쿠키 `Secure` | 꺼짐 | 꺼짐 | 꺼짐 | 켜짐 (HTTPS 전용) |
| Swagger | 켜짐 | 켜짐 | — | 꺼짐 |
| 비밀값 | 없음 | 더미 | 없음 | env 파일 |

## 5. 비밀값 규칙

### 저장소에서 빼는 규칙

`.gitignore` 가 이미 막고 있습니다.

| 파일 | 규칙 | 결과 |
|---|---|---|
| 루트 `.gitignore` | `.env`, `.env.*`, `!.env.example` | 어느 폴더의 `.env`·`.env.dev` 사본도 무시, 예시 파일만 추적 |
| `frontend/.gitignore` | 같음 | |
| `ai/.gitignore` | `.env` | |

- 예시 파일은 `ai/.env.example`, `frontend/.env.example`, `infra/local.env` 셋입니다. `local.env` 는 이름이 `.env.` 로
  시작하지 않아 추적되는 **더미값 파일**입니다. 서버·운영 값을 여기에 쓰지 않습니다.
- 서버용 예시 파일은 따로 두지 않습니다. 서버 파일은 [cd.md](cd.md#2-env-파일)의 명령이 난수와 함께 만들고, 이름 목록은
  위 표가 정본입니다.
- 비밀값을 채팅·메신저·PR 코멘트에 붙여 넣지 않습니다. 서버에 넣을 때는 `read -rs` 로 입력받습니다(cd.md GMS 키 절차).

### 값이 샜을 때

이력에서 지우는 것보다 **값을 바꾸는 것이 먼저**입니다. 공개 저장소라 푸시된 순간 복사됐다고 봅니다.

| 값 | 바꾸는 방법 |
|---|---|
| `GMS_KEY` | 발급처에서 폐기·재발급 → [cd.md GMS 키 넣기·바꾸기](cd.md#3-gms-키-넣기바꾸기) → 다음 배포 때 적용 |
| `AI_INTERNAL_TOKEN` | env 파일 값 교체 → 재배포(값이 바뀐 컨테이너만 다시 만들어짐). compose 가 ai·backend 양쪽에 같은 값을 넣습니다 |
| `DOCUMENT_S3_*` | 지금은 seaweedfs 관리 키와 backend 키가 같은 값입니다. 키를 나누고 교체 절차를 확인하는 일은 S15P31A108-211 에서 합니다 |
| `DB_PASSWORD` | **env 파일만 바꾸면 backend 가 DB 에 접속하지 못합니다.** postgres 안의 비밀번호(`ALTER ROLE`)를 먼저 바꾸고 env 파일을 맞춥니다. 정리된 절차는 아직 없습니다 |
| `JENKINS_API_TOKEN` | [cd.md 토큰 발급·교체](cd.md#토큰-발급교체) |
| Jenkins 비밀번호 | [jenkins.md 비밀번호 바꾸기](jenkins.md#비밀번호-바꾸기) |

## 6. 변수를 추가할 때

1. backend 는 `application.yml` 에 로컬 기본값과 함께 넣고, 운영에서 반드시 받아야 하면 `application-prod.yml` 에
   `${X:}` 로 덮고 설정 클래스에 `@NotBlank` 등 검증을 답니다. 새 설정 클래스면 `ProdProfilePropertiesTest` 의
   바인딩 시험에 한 줄 추가합니다.
2. 컨테이너에 넘겨야 하면 `infra/compose.yaml` 에 넣습니다. 필수면 `${X:?X is required}`.
3. 서버 env 파일에 들어가는 값이면 [cd.md 서버 준비 2](cd.md#2-env-파일)의 **파일을 만드는 명령에도** 키를 더합니다.
   그 명령이 키를 직접 나열하므로, 빠뜨리면 새 서버(-73 운영 등)에서 만든 파일에 그 키가 없어 첫 배포가 멈춥니다.
4. frontend `VITE_` 값은 빌드할 때 굳습니다. 서버 화면에도 쓰려면 `infra/frontend-dist/Dockerfile` 의 `ARG` 와
   `.github/workflows/cd.yml` 의 `build-args` 를 **둘 다** 고칩니다. 하나라도 빠지면 로컬에서는 되고 서버 화면만
   조용히 기본값으로 돕니다. 비밀값은 넣지 않습니다.
   지금 `cd.yml` 의 `frontend-dist` 항목은 `build-args` 가 값 하나짜리 한 줄입니다. 두 개 이상이면 여러 줄로 바꿉니다.
   값마다 `|| '기본값'` 을 붙입니다. 저장소 변수가 없을 때 빈 값이 넘어가면 Dockerfile `ARG` 의 기본값을 덮습니다.

   ```yaml
   build-args: |
     VITE_API_MODE=${{ vars.VITE_API_MODE || 'mock' }}
     VITE_NEW_VALUE=${{ vars.VITE_NEW_VALUE || '<ARG 와 같은 기본값>' }}
   ```
5. `infra/local.env` 에 더미값, 예시 파일(`ai/.env.example`·`frontend/.env.example`)에 빈 칸을 넣습니다.
6. 이 문서의 표를 고칩니다.
7. **compose 필수값을 추가한 PR 은 머지 전에 서버 env 파일에 값을 먼저 넣습니다.** 순서가 바뀌면 머지 직후 자동 배포가
   compose 단계에서 멈춥니다(서비스는 이전 버전으로 계속 돕니다). 넣은 뒤 [배포 전에 점검하기](#배포-전에-점검하기)를
   **`REF=<그 PR 브랜치>`** 로 돌려 확인합니다.
