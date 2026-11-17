# 1. 빌드 및 배포

PC에서 실행해 보려면 1-2절을, 새 서버에 배포하려면 1-4절을 순서대로 따르세요.

## 1-1. 사용 제품, 버전, 설정 값

### 런타임과 서버

| 구분 | 제품 | 버전 | 주요 설정 값 | 확인한 파일 |
| --- | --- | --- | --- | --- |
| JVM | Eclipse Temurin (OpenJDK) | 21 (빌드 `21-jdk`, 실행 `21-jre`) | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` (컨테이너 메모리의 75%까지 힙 사용) | `backend/Dockerfile` |
| WAS | Spring Boot 내장 Tomcat | Tomcat 11.0.24 (Spring Boot 4.1.1) | 포트 8080, 종료할 때 처리 중인 요청을 최대 20초 기다림 | 버전은 backend 이미지 안 `tomcat-embed-core-11.0.24.jar`로 확인 (Spring Boot 4.1.1이 정함), 설정은 `application.yml` |
| 빌드 도구 | Gradle Wrapper | 8.14.3 | | `backend/gradle/wrapper/gradle-wrapper.properties` |
| 웹서버 | Nginx | 1.24 (서버는 Ubuntu 패키지, 로컬은 `nginx:1.24-alpine` 이미지) | 요청 전체 최대 2MB(backend는 파일 하나를 1MB까지 받음), `/api/` 응답 대기 60초, `/mcp` 응답 대기 1시간(버퍼링 끔) | `infra/nginx/sites-available/dev.beez.ai.kr.conf` |
| AI 서버 | Python, FastAPI, Uvicorn | Python 3.12 | 포트 8000 (컨테이너 안에서만 열림) | `ai/Dockerfile`, `ai/requirements.lock` |
| 프론트 빌드 | Node.js, Vite | Node 24, Vite 8 | 빌드할 때 `VITE_API_MODE=live` | `frontend/package.json` |
| DB | PostgreSQL + pgvector(벡터 검색 확장) | PostgreSQL 17 | DB 이름 `beez`, 포트 5432, `wal_level=logical` | `infra/compose.yaml` |
| 파일 저장소 | SeaweedFS (S3 호환) | 4.51 | 버킷 `beez-documents`, 포트 8333 (컨테이너 안에서만 열림) | `infra/compose.yaml` |
| 컨테이너 | Docker Engine, Docker Compose | 서버 Docker Engine 29.9.2와 Compose v5.6.1, 로컬 Docker Desktop(Engine 29.6) | | `docs/infra/server-setup.md`, `docs/infra/docker-images.md` |
| 서버 OS | Ubuntu | 24.04 LTS (4 vCPU, RAM 15GiB, 디스크 309GB) | 방화벽(ufw)으로 80, 443만 엶 | `docs/infra/server-setup.md` |

### 주요 라이브러리

| 영역 | 라이브러리 |
| --- | --- |
| Backend | Spring Security, Spring Data JPA, Flyway, springdoc-openapi 3.1.1, MCP Java SDK 2.1.0 |
| AI | FastAPI, pydantic-settings, httpx, openai, tiktoken, markdown-it-py (정확한 버전은 `ai/requirements.lock`) |
| Frontend | React 19, TypeScript 6.0, Tailwind CSS 4, Three.js 0.186, React Three Fiber 9 |

### IDE

| 영역 | IDE | 버전 |
| --- | --- | --- |
| Backend | IntelliJ IDEA | (팀 기입) |
| Frontend, AI | Visual Studio Code | (팀 기입) |

### DB 접속 정보

| 항목 | 로컬 전체 실행 | `bootRun` 개발 실행 | 서버 |
| --- | --- | --- | --- |
| 호스트와 포트 | 컨테이너 안 `postgres:5432` (PC에서는 열지 않음) | `localhost:5432` | 컨테이너 안 `postgres:5432` (서버 밖에서는 열지 않음) |
| DB 이름 | `beez` | `beez` | `beez` |
| 계정 | `beez` / `beez` (`infra/local.env`) | `beez` / `beez` (`backend/compose.yaml`) | 서버 `/opt/beez/.env.dev` |

DB에 직접 접속하려면 아래 명령을 실행합니다. Git Bash에서 `the input device is not a TTY` 오류가 나면 명령 앞에 `winpty`를
붙이세요. `bootRun`으로 띄웠다면 컨테이너 이름이 `beez-postgres`입니다.

```bash
docker exec -it beez-postgres-1 psql -U beez -d beez
```

## 1-2. PC에서 빌드하고 실행하기

[저장소 README "빠르게 실행하기"](../README.md#빠르게-실행하기)를 1)부터 4)까지 차례로 따르세요. 소스 받기, 빌드와 실행,
회원가입, AI 기능 켜기, 끄기까지 그 절에 모두 있습니다.

### 서비스별 빌드 명령

| 서비스 | 명령 | 결과물 |
| --- | --- | --- |
| backend | `cd backend && ./gradlew bootJar` | `backend/build/libs/*.jar` |
| ai | `docker build -t beez-ai ai` | `beez-ai` 이미지 |
| frontend | `cd frontend && npm ci && VITE_API_MODE=live npm run build` | `frontend/dist/` 폴더 (Nginx가 그대로 보여 주는 정적 파일) |

compose의 `--build`가 backend와 ai 이미지를 직접 빌드하므로, 전체 실행에는 위 명령을 따로 돌리지 않아도 됩니다.
backend를 따로 빌드할 때만 PC에 JDK 21이 필요합니다.

## 1-3. 환경변수

설정값은 env 파일 하나에 모아 compose에 넘깁니다. 로컬은 저장소에 든 `infra/local.env`(연습용 가짜 값)를 그대로 쓰고,
서버는 `/opt/beez/.env.dev`를 새로 만듭니다. 모든 변수의 설명은 [docs/infra/env.md](../docs/infra/env.md)에 있고,
여기에는 배포할 때 꼭 정해야 하는 값만 적습니다.

### 빌드할 때

| 이름 | 넣을 값 | 설명 |
| --- | --- | --- |
| `VITE_API_MODE` | `live` | 프론트엔드를 빌드하는 명령 앞에 붙입니다. 빠뜨리면 실제 서버 대신 예시 데이터를 보여 주는 화면이 만들어집니다 |

### 실행할 때 (env 파일)

| 이름 | 필수 | 비밀 | 넣을 값 |
| --- | --- | --- | --- |
| `DB_USERNAME` | 필수 | | DB 계정 이름 (예 `beez`) |
| `DB_PASSWORD` | 필수 | 비밀 | 난수. 처음 DB를 만들 때 이 값으로 정해지므로, 나중에 바꾸려면 DB 안의 비밀번호도 함께 바꿔야 합니다 |
| `DOCUMENT_S3_ACCESS_KEY`, `DOCUMENT_S3_SECRET_KEY` | 필수 | 비밀 | 난수. backend가 문서 원본을 읽고 쓸 때 씁니다 |
| `SEAWEEDFS_BOOTSTRAP_ACCESS_KEY`, `SEAWEEDFS_BOOTSTRAP_SECRET_KEY` | 필수 | 비밀 | 난수. 파일 저장소 관리용입니다 |
| `SEAWEEDFS_BACKUP_ACCESS_KEY`, `SEAWEEDFS_BACKUP_SECRET_KEY` | 필수 | 비밀 | 난수. 백업 스크립트가 씁니다 |
| `AI_INTERNAL_TOKEN` | 필수 | 비밀 | 난수. backend와 ai가 서로를 확인할 때 씁니다 |
| `PUBLIC_ORIGIN` | 필수 | | 사용자가 브라우저에 입력할 주소. 끝에 `/`를 붙이지 않습니다 (예 `https://dev.beez.ai.kr`) |
| `BACKEND_HOST_PORT` | 필수 | | `8081`. 서버의 Nginx 설정이 이 포트로 요청을 보냅니다. 8080은 Jenkins가 쓰므로 피합니다 |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | 서버 필수 | 비밀(PASSWORD) | 가입 인증과 초대 메일을 보낼 SMTP 계정. 서버에서는 이 값이 없으면 회원가입을 할 수 없습니다. 값은 [외부 서비스 2-2](02-external-services.md#2-2-메일-smtp)를 보세요 |
| `GMS_KEY` | | 비밀 | SSAFY GMS API 키. 비우면 AI 기능을 쓸 수 없습니다 |
| `PLANNING_QUESTIONS_ENABLED`, `DESIGN_DRAFTS_ENABLED`, `BEE_CHAT_ENABLED` | | | `true`. 보완 질문, 설계 초안, 비비 질문을 켭니다. 적지 않으면 꺼져 있습니다 |
| `IMPACT_LLM_MODEL` | | | `gpt-6-luna`. 변경 영향 분석을 켭니다. 비우면 꺼져 있습니다 |
| `CONTEXT_SELECTION_ENABLED`, `CONTEXT_SELECTION_MIN_SIMILARITY`, `CONTEXT_SELECTION_MAX_ITEMS`, `CONTEXT_SELECTION_MAX_EVIDENCE`, `CONTEXT_SELECTION_TOTAL_TIMEOUT_SECONDS` | | | 차례로 `true`, `0.3`, `10`, `8`, `30`. 에이전트가 승인된 설계를 읽어 가는 기능이고, 다섯 줄이 모두 있어야 켜집니다. 팀 기본값은 꺼짐이고 값은 AI 담당 확정 전의 제안값입니다. 꺼 두면 에이전트가 설계를 읽지 못합니다 |

난수는 `openssl rand -hex 24`로 만듭니다. 16진수만 쓰세요. 따옴표나 역슬래시가 들어가면 파일 저장소 설정이 깨집니다.

## 1-4. 새 서버에 배포하기

팀 개발 서버는 dev 브랜치에 push하면 자동으로 배포됩니다([1-5](#1-5-팀-개발-서버의-자동-배포)). 이 자동 배포는 팀의
GitHub 저장소 권한과 Jenkins가 있어야 하므로, 새 서버에서는 아래 순서대로 직접 배포합니다.
서버는 Ubuntu 24.04이고, 사용할 도메인이 서버 IP를 가리키도록 DNS에 등록해 두었다고 가정합니다.

### 1) 필요한 프로그램 설치

방화벽에서 80, 443 포트를 열고 Docker를 설치합니다. 명령은 [docs/infra/server-setup.md](../docs/infra/server-setup.md)의
3절(방화벽)과 4절(Docker)에 있습니다. 클라우드 서버라면 클라우드 콘솔의 보안 그룹에서도 80, 443을 열어 두세요. 4절의 `sudo usermod -aG docker ubuntu`에서 `ubuntu`는 내 계정 이름(`$USER`)으로
바꿔 실행하고, 실행한 뒤에는 SSH 접속을 끊었다가 다시 접속하세요. 다시 접속하지 않으면 4)의 `docker compose`가
`permission denied`로 실패합니다. 그다음 Nginx와 certbot을 설치합니다.

```bash
sudo apt-get install -y nginx certbot
```

### 2) 소스 받기

GitLab 저장소는 비공개라 SSAFY GitLab 계정으로 로그인해야 받을 수 있습니다. 비밀번호를 물을 때 로그인이 거절되면
GitLab의 사용자 설정에서 Personal Access Token(`read_repository` 권한)을 만들어 비밀번호 자리에 넣으세요.
개발 서버와 같은 코드를 받도록 `dev` 브랜치를 지정합니다.

```bash
sudo mkdir -p /opt/beez && sudo chown "$USER" /opt/beez
git clone -b dev https://lab.ssafy.com/s15-final/S15P31A108.git /opt/beez/src
```

### 3) env 파일 만들기

아래 명령은 난수를 넣은 env 파일을 만듭니다. 첫 줄의 `D=` 뒤를 내 도메인으로 바꿔서 실행하세요.
이미 파일이 있으면 덮어쓰지 않고 멈춥니다. 실수로 DB 비밀번호를 잃지 않게 하려는 것입니다.

```bash
D=내도메인.com
( set -C; umask 027
  printf '%s\n' \
    'DB_USERNAME=beez' \
    "DB_PASSWORD=$(openssl rand -hex 24)" \
    "DOCUMENT_S3_ACCESS_KEY=$(openssl rand -hex 12)" \
    "DOCUMENT_S3_SECRET_KEY=$(openssl rand -hex 24)" \
    "SEAWEEDFS_BOOTSTRAP_ACCESS_KEY=$(openssl rand -hex 12)" \
    "SEAWEEDFS_BOOTSTRAP_SECRET_KEY=$(openssl rand -hex 24)" \
    "SEAWEEDFS_BACKUP_ACCESS_KEY=$(openssl rand -hex 12)" \
    "SEAWEEDFS_BACKUP_SECRET_KEY=$(openssl rand -hex 24)" \
    "AI_INTERNAL_TOKEN=$(openssl rand -hex 32)" \
    "PUBLIC_ORIGIN=https://$D" \
    'BACKEND_HOST_PORT=8081' \
    'GMS_KEY=' \
    'MAIL_HOST=' 'MAIL_PORT=587' 'MAIL_USERNAME=' 'MAIL_PASSWORD=' 'MAIL_FROM=' \
    'PLANNING_QUESTIONS_ENABLED=true' \
    'DESIGN_DRAFTS_ENABLED=true' \
    'BEE_CHAT_ENABLED=true' \
    'IMPACT_LLM_MODEL=gpt-6-luna' \
    'CONTEXT_SELECTION_ENABLED=true' \
    'CONTEXT_SELECTION_MIN_SIMILARITY=0.3' \
    'CONTEXT_SELECTION_MAX_ITEMS=10' \
    'CONTEXT_SELECTION_MAX_EVIDENCE=8' \
    'CONTEXT_SELECTION_TOTAL_TIMEOUT_SECONDS=30' > /opt/beez/.env.dev ) && ls -l /opt/beez/.env.dev
```

그다음 편집기(`nano /opt/beez/.env.dev`)로 파일을 열어 빈칸을 채웁니다.

- `GMS_KEY=` 뒤에 GMS API 키를 붙입니다([외부 서비스 2-1](02-external-services.md#2-1-llm-api-ssafy-gms)).
- `MAIL_` 다섯 줄을 채웁니다([외부 서비스 2-2](02-external-services.md#2-2-메일-smtp)). 비워 두면 회원가입을 할 수 없습니다.

이 파일은 저장소나 메신저에 올리지 마세요.

### 4) 서비스 띄우기

```bash
cd /opt/beez/src
docker compose -f infra/compose.yaml --env-file /opt/beez/.env.dev up -d --build --wait
docker compose -p beez ps     # postgres, seaweedfs, ai, backend 가 (healthy) 인지 확인
```

서버에서는 Nginx를 컨테이너가 아니라 서버에 직접 설치해 쓰므로 `compose.local.yaml`은 붙이지 않습니다.
DB 테이블은 backend가 처음 뜰 때 자동으로 만들어집니다([1-6의 7번](#1-6-배포할-때-주의할-점)).

### 5) 프론트엔드 올리기

PC(Git Bash)에서 빌드해 서버로 보냅니다. 서버에 Node.js 24가 있으면 서버에서 빌드해도 됩니다. 프론트엔드와 backend의
코드 버전이 같아야 하니, PC 저장소도 `git checkout dev && git pull`로 서버와 같은 `dev` 브랜치에 맞춘 뒤 빌드하세요.

PC의 저장소 폴더에서 빌드합니다.

```bash
cd frontend && npm ci && VITE_API_MODE=live npm run build
```

출력 끝에 `built in`(빌드 완료) 줄이 나온 것을 확인한 뒤, 같은 `frontend` 폴더에서 빌드 결과를 서버로 보냅니다.
`<서버>`는 `ubuntu@서버주소`처럼 SSH로 접속할 대상으로 바꿔 적으세요. 그대로 두면 bash가 `<`를 입력 기호로 읽어 오류가 납니다.

```bash
ssh <서버> 'rm -rf /tmp/beez-dist' && scp -r dist <서버>:/tmp/beez-dist
```

서버에 접속해 받은 파일을 Nginx가 읽는 자리로 옮깁니다.

```bash
sudo mkdir -p /var/www/beez && sudo rm -rf /var/www/beez/current && sudo mv /tmp/beez-dist /var/www/beez/current
```

### 6) Nginx 설정과 HTTPS 인증서

443 포트 설정은 인증서 파일이 있어야 켜지므로, 인증서를 받는 임시 설정을 먼저 켜고 인증서를 받은 뒤 진짜 설정으로
바꿉니다. 첫 줄의 `D=`와 certbot의 `you@example.com`을 내 도메인과 연락 메일로 바꿔서 차례로 실행하세요.

```bash
D=내도메인.com
cd /opt/beez/src/infra/nginx

# 공통 설정과 스니펫 설치, 우분투 기본 사이트 끄기
sudo install -m 644 conf.d/*.conf /etc/nginx/conf.d/
sudo install -m 644 snippets/*.conf /etc/nginx/snippets/
sudo install -m 644 sites-available/00-default.conf /etc/nginx/sites-available/
sudo ln -sf /etc/nginx/sites-available/00-default.conf /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

# 내 도메인으로 바꾼 사이트 설정을 만들어 둡니다 (저장소 파일은 건드리지 않습니다)
sed "s/dev\.beez\.ai\.kr/$D/g" sites-available/dev.beez.ai.kr.conf | sudo tee /etc/nginx/sites-available/beez.conf > /dev/null

# 인증서 발급용 임시 설정을 켜고 인증서를 받습니다
sudo mkdir -p /var/www/letsencrypt
printf 'server {\n    listen 80;\n    listen [::]:80;\n    server_name %s;\n    include snippets/acme-challenge.conf;\n}\n' "$D" \
  | sudo tee /etc/nginx/sites-enabled/beez-temp.conf > /dev/null
sudo nginx -t && sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/letsencrypt -d "$D" \
  -m you@example.com --agree-tos --no-eff-email --deploy-hook "systemctl reload nginx"

# 임시 설정을 지우고 진짜 설정을 켭니다
sudo rm /etc/nginx/sites-enabled/beez-temp.conf
sudo ln -sf /etc/nginx/sites-available/beez.conf /etc/nginx/sites-enabled/beez.conf
sudo nginx -t && sudo systemctl reload nginx
```

certbot이 실패하면 도메인이 이 서버 IP를 가리키는지(`nslookup $D`)와 80 포트가 열려 있는지 확인하세요. 같은 도메인으로
한 시간에 다섯 번 넘게 실패하면 Let's Encrypt가 잠시 발급을 막으니, 처음에는 certbot 명령 끝에 `--dry-run`을 붙여
연습해 보는 것이 안전합니다. 자세한 설명은 [docs/infra/nginx-https.md](../docs/infra/nginx-https.md)에 있습니다.

### 7) 확인

```bash
D=내도메인.com
curl -s https://$D/actuator/health; echo    # {"status":"UP"} 이면 정상
```

브라우저로 `https://내도메인.com`을 열어 회원가입을 해 봅니다. 인증 메일이 오지 않으면 3)의 `MAIL_` 값을 확인하고,
`docker compose -p beez logs backend | grep -i mail`로 오류를 찾아보세요.

## 1-5. 팀 개발 서버의 자동 배포

| 항목 | 값 |
| --- | --- |
| 서버 | `k15a108.p.ssafy.io` (Ubuntu 24.04), 접속 도메인 `dev.beez.ai.kr` |
| 배포 위치 | `/opt/beez` |
| 이미지 | GitHub 컨테이너 저장소(GHCR)의 `ghcr.io/s15a108/beez-backend`, `beez-ai`, `beez-frontend-dist` (태그는 커밋 SHA) |
| 열린 포트 | 80, 443만 엽니다. backend는 서버 안에서만 접속할 수 있는 `127.0.0.1:8081`이고, 나머지 컨테이너는 포트를 열지 않습니다 |

배포는 이렇게 진행됩니다.

1. dev 브랜치에 커밋이 push됩니다.
2. GitHub Actions(`cd.yml`)가 backend, ai, 프론트엔드 이미지를 빌드해 GHCR에 올립니다.
3. GitHub Actions가 서버의 Jenkins에 배포를 요청합니다.
4. Jenkins가 서버에서 `infra/deploy/deploy.sh`를 실행해 새 이미지로 컨테이너를 바꾸고, 프론트엔드 파일을 교체한 뒤
   Nginx 설정을 다시 읽게 합니다.

자세한 내용은 [docs/infra/cd.md](../docs/infra/cd.md)에 있습니다.

## 1-6. 배포할 때 주의할 점

1. **PostgreSQL 설정(`infra/compose.yaml`의 `command`)을 바꾸면 DB 컨테이너가 다시 만들어집니다.** 그동안 서비스가
   잠시 끊기니, 사용자가 적은 시간에 배포하세요.
2. **DB 비밀번호(`DB_PASSWORD`)는 env 파일에서만 바꾸면 안 됩니다.** DB는 처음 만들 때의 비밀번호를 계속 쓰므로,
   env 파일만 바꾸면 backend가 DB에 접속하지 못합니다. DB 안의 비밀번호도 함께 바꾸세요.
3. 필수 환경변수가 빠지면 compose가 빠진 변수 이름을 알려 주고 멈춥니다. 그 줄을 env 파일에 넣고 다시 실행하세요.
4. AI 기능을 켜는 줄은 1-4의 3)에서 env 파일에 모두 넣었습니다. 어떤 기능을 끄려면 그 줄의 값을 `false`(모델은 빈 값)로
   바꾸고 1-4의 4) 명령으로 서비스를 다시 띄우세요.
5. AI 응답 대기 시간은 AI 모델 대기 < backend 대기 < Nginx 대기 순서를 지키세요. 문서 검토 대화라면
   `DOCUMENT_REVIEW_CHAT_LLM_TIMEOUT_SECONDS`(50초) < `DOCUMENT_REVIEW_READ_TIMEOUT`(55초) < `proxy_read_timeout`(60초)입니다.
   - 바꾸는 곳: AI와 backend 값은 env 파일, Nginx 값은 서버의 `/etc/nginx/sites-available/beez.conf`입니다.
     backend 값은 `55s`처럼 단위를 붙여 적습니다.
   - 바꾸는 순서: 늘릴 때는 Nginx, backend, AI 순서로, 줄일 때는 그 반대로 바꿉니다. 순서가 깨지면 Nginx가 먼저 504
     오류를 내고 backend는 계속 돌아 비용만 나갑니다.
6. `VITE_API_MODE`는 프론트엔드를 빌드할 때 정해집니다. 값을 바꿨다면 프론트엔드를 다시 빌드해 올려야 반영됩니다.
7. DB 테이블은 backend가 뜰 때 자동으로 만들어집니다. DB 테이블 생성 도구인 Flyway가
   `backend/src/main/resources/db/migration`(SQL)과 `backend/src/main/java/db/migration`(Java)의 마이그레이션을 최신 것까지
   차례로 적용하므로, SQL을 따로 실행하지 마세요.

## 1-7. 계정과 설정이 들어 있는 파일

DB 접속 정보 같은 계정과 설정이 정의된 파일입니다. 실제 비밀값은 저장소에 없고 서버 파일에만 있습니다.

| 파일 | 내용 | 저장소에 있음 |
| --- | --- | --- |
| `backend/src/main/resources/application.yml` | 공통 설정과 PC 실행용 기본값 (DB 주소, 파일 저장소, AI 서버 주소, 메일 방식 등) | O |
| `backend/src/main/resources/application-local.yml` | PC 실행용 설정 (가입 인증 코드를 로그에 남김) | O |
| `backend/src/main/resources/application-prod.yml` | 서버용 설정. 필수값은 환경변수로만 받습니다 | O |
| `backend/compose.yaml` | `bootRun`이 띄우는 PostgreSQL, SeaweedFS (계정 `beez` / `beez`) | O |
| `backend/src/main/resources/db/migration/` | DB 테이블 정의 (구조 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)) | O |
| `infra/compose.yaml` | 전체 서비스 정의와 필수 환경변수 목록 | O |
| `infra/local.env` | PC 실행용 가짜 계정값. 서버에 쓰지 마세요 | O |
| `infra/monitoring/local.env` | PC 실행용 모니터링 가짜 계정값 | O |
| `ai/.env.example` | ai 단독 실행용 설정 예시. `ai/.env`로 복사해 씁니다 | 예시만 |
| `frontend/.env.example` | `VITE_API_MODE` 예시. `frontend/.env`로 복사해 씁니다 | 예시만 |
| 서버 `/opt/beez/.env.dev` | 실제 DB, 파일 저장소, AI, 메일 비밀값 | X |

아래는 팀 개발 서버의 모니터링, CDC, 자동 배포, 저장소 자동화에만 쓰는 파일입니다. 1-4절의 직접 배포에서는 만들지 않아도
서비스가 동작합니다.

| 파일 | 내용 |
| --- | --- |
| 서버 `/opt/beez/.env.monitoring` | 모니터링(Grafana 등) 계정 |
| 서버 `/opt/beez/.cdc-secrets/database.properties` | 발행된 설계 변경을 분석 대기열로 넘기는 CDC(DB 변경 전달) 커넥터의 DB 계정 ([docs/infra/cdc-connector.md](../docs/infra/cdc-connector.md)) |
| 서버 `/opt/beez/.alertmanager-webhook-url` | Mattermost 알림을 보낼 웹훅 주소 |
| 서버 `/etc/jenkins/casc.env` | Jenkins 관리자와 배포 계정 |
| GitHub 저장소 설정 | 배포 토큰(`JENKINS_API_TOKEN`), GitLab 동기화 토큰(`GITLAB_SYNC_TOKEN`), 자동 병합 토큰(`PR_AUTOMERGE_TOKEN`), AI PR 리뷰 키(`CLAUDE_CODE_OAUTH_TOKEN` 또는 `ANTHROPIC_API_KEY`), `VITE_API_MODE` |
