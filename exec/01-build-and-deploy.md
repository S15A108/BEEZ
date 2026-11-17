# 1. 빌드 및 배포

이 문서만 보고 BEEZ를 PC에서 띄우거나 새 서버에 배포할 수 있도록 정리했습니다.

## 1-1. 사용 제품과 버전

### 런타임과 서버

| 구분 | 제품 | 버전 | 확인한 파일 |
| --- | --- | --- | --- |
| JVM | Eclipse Temurin (OpenJDK) | 21 (빌드 `21-jdk`, 실행 `21-jre`) | `backend/Dockerfile`, `backend/build.gradle` |
| WAS | Spring Boot 내장 Tomcat | Spring Boot 4.1.1 | `backend/build.gradle` |
| 빌드 도구 | Gradle Wrapper | 8.14.3 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| 웹서버 | Nginx | 1.24 (서버는 Ubuntu 패키지, 로컬은 `nginx:1.24-alpine` 이미지) | `infra/compose.local.yaml`, `docs/infra/nginx-https.md` |
| AI 서버 | Python, FastAPI, Uvicorn | Python 3.12 | `ai/Dockerfile`, `ai/requirements.lock` |
| 프론트 빌드 | Node.js, Vite | Node 24, Vite 8 | `frontend/package.json` |
| DB | PostgreSQL + pgvector | PostgreSQL 17 | `infra/compose.yaml` |
| 파일 저장소 | SeaweedFS (S3 호환) | 4.51 | `infra/compose.yaml` |
| 컨테이너 | Docker Engine, Docker Compose v2 | 서버 Docker CE, 로컬 Docker Desktop 29.6 | `docs/infra/server-setup.md` |
| 서버 OS | Ubuntu | 24.04 LTS (4 vCPU, RAM 15GiB, 디스크 309GB) | `docs/infra/server-setup.md` |

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

## 1-2. PC에서 빌드하고 실행하기

Docker Desktop과 Node.js 24를 설치하고, 저장소를 클론한 폴더에서 아래 두 명령을 실행합니다.
Windows는 **Git Bash**를 쓰세요. 첫 명령은 PowerShell과 cmd에서 동작하지 않습니다.

```bash
cd frontend && npm ci && VITE_API_MODE=live npm run build && cd ..
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
```

명령이 끝나면 <http://localhost:8088>을 엽니다. 가입 인증 코드 확인, AI 기능 켜기, 종료 방법은
[저장소 README "빠르게 실행하기"](../README.md#빠르게-실행하기)에 있습니다.

### 서비스별 빌드 명령

| 서비스 | 명령 | 결과물 |
| --- | --- | --- |
| backend | `cd backend && ./gradlew bootJar` | `backend/build/libs/*.jar` |
| ai | `docker build -t beez-ai ai` | `beez-ai` 이미지 |
| frontend | `cd frontend && npm ci && VITE_API_MODE=live npm run build` | `frontend/dist/` 폴더 (Nginx가 그대로 보여 주는 정적 파일) |

compose의 `--build`가 backend와 ai 이미지를 직접 빌드하므로, 전체 실행에는 위 명령을 따로 돌리지 않아도 됩니다.

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
| `GMS_KEY` | | 비밀 | SSAFY GMS API 키. 비우면 AI 기능을 쓸 수 없습니다 |
| `PLANNING_QUESTIONS_ENABLED`, `DESIGN_DRAFTS_ENABLED`, `BEE_CHAT_ENABLED` | | | `true`. 보완 질문, 설계 초안, 비비 질문을 켭니다. 적지 않으면 꺼져 있습니다 |
| `IMPACT_LLM_MODEL` | | | `gpt-6-luna`. 변경 영향 분석을 켭니다. 비우면 꺼져 있습니다 |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | | 비밀(PASSWORD) | 가입 인증과 초대 메일을 보낼 SMTP 계정. 비우면 서버에서 회원가입을 할 수 없습니다. 값은 [외부 서비스 문서](02-external-services.md#2-2-메일--smtp)를 보세요 |

난수는 `openssl rand -hex 24`로 만듭니다. 16진수만 쓰세요. 따옴표나 역슬래시가 들어가면 파일 저장소 설정이 깨집니다.

## 1-4. 새 서버에 배포하기

우리 팀 서버는 dev 브랜치에 push하면 GitHub Actions와 Jenkins가 자동으로 배포합니다([1-5](#1-5-우리-서버의-자동-배포)).
이 자동 배포는 팀의 GitHub 저장소 권한과 Jenkins가 있어야 하므로, 새 서버에서는 아래 순서대로 직접 배포합니다.
서버는 Ubuntu 24.04, 도메인은 서버 IP를 가리키도록 미리 등록해 두었다고 가정합니다.

### 1) 필요한 프로그램 설치

Docker, Nginx, certbot을 설치하고 방화벽에서 80, 443 포트를 엽니다. 명령은
[docs/infra/server-setup.md](../docs/infra/server-setup.md)의 3절(방화벽), 4절(Docker)을 따르세요.

```bash
sudo apt-get install -y nginx certbot
```

### 2) 소스 받기

```bash
sudo mkdir -p /opt/beez && sudo chown "$USER" /opt/beez
git clone <GitLab 저장소 주소> /opt/beez/src
cd /opt/beez/src
```

### 3) env 파일 만들기

아래 명령은 난수를 넣은 env 파일을 만듭니다. `PUBLIC_ORIGIN`의 도메인만 내 도메인으로 바꿔서 실행하세요.
이미 파일이 있으면 덮어쓰지 않고 멈춥니다(DB 비밀번호를 잃지 않게 하려는 것입니다).

```bash
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
    'PUBLIC_ORIGIN=https://내도메인' \
    'BACKEND_HOST_PORT=8081' \
    'GMS_KEY=' \
    'PLANNING_QUESTIONS_ENABLED=true' \
    'DESIGN_DRAFTS_ENABLED=true' \
    'BEE_CHAT_ENABLED=true' \
    'IMPACT_LLM_MODEL=gpt-6-luna' > /opt/beez/.env.dev ) && ls -l /opt/beez/.env.dev
```

그다음 편집기로 `/opt/beez/.env.dev`를 열어 `GMS_KEY=` 뒤에 키를 붙이고, 메일을 보내려면 `MAIL_*` 줄을 추가합니다.
이 파일은 저장소나 메신저에 올리지 마세요.

### 4) 서비스 띄우기

```bash
cd /opt/beez/src
docker compose -f infra/compose.yaml --env-file /opt/beez/.env.dev up -d --build --wait
docker compose -p beez ps     # postgres, seaweedfs, ai, backend 가 (healthy) 인지 확인
```

서버에서는 Nginx를 컨테이너가 아니라 서버에 직접 설치해 쓰므로 `compose.local.yaml`은 붙이지 않습니다.
DB 테이블은 backend가 처음 뜰 때 자동으로 만들어집니다.

### 5) 프론트엔드 올리기

PC(Git Bash)에서 빌드해 서버로 보냅니다. 서버에 Node.js 24가 있으면 서버에서 빌드해도 됩니다.

```bash
# PC
cd frontend && npm ci && VITE_API_MODE=live npm run build
scp -r dist <서버>:/tmp/beez-dist
# 서버
sudo mkdir -p /var/www/beez && sudo rm -rf /var/www/beez/current && sudo mv /tmp/beez-dist /var/www/beez/current
```

### 6) Nginx와 HTTPS 설정

1. [docs/infra/nginx-https.md](../docs/infra/nginx-https.md) 3절 순서대로 certbot으로 인증서를 먼저 받습니다.
   사이트 설정이 인증서 파일을 가리키므로, 순서가 바뀌면 Nginx 설정 검사가 실패합니다.
2. 저장소의 Nginx 설정에서 `dev.beez.ai.kr`을 내 도메인으로 바꿔 설치합니다.

   ```bash
   cd /opt/beez/src/infra/nginx
   sed -i 's/dev\.beez\.ai\.kr/내도메인/g' sites-available/dev.beez.ai.kr.conf
   sudo install -m 644 snippets/beez-backend-proxy.conf /etc/nginx/snippets/
   sudo install -m 644 sites-available/dev.beez.ai.kr.conf /etc/nginx/sites-available/beez.conf
   sudo ln -sf /etc/nginx/sites-available/beez.conf /etc/nginx/sites-enabled/beez.conf
   sudo nginx -t && sudo systemctl reload nginx
   ```

### 7) 확인

```bash
curl -s https://내도메인/actuator/health; echo    # {"status":"UP"} 이면 정상
```

브라우저로 `https://내도메인`을 열어 회원가입과 로그인을 해 봅니다.

## 1-5. 우리 서버의 자동 배포

| 항목 | 값 |
| --- | --- |
| 서버 | `k15a108.p.ssafy.io` (Ubuntu 24.04), 접속 도메인 `dev.beez.ai.kr` |
| 흐름 | dev에 push → GitHub Actions가 이미지를 빌드해 GHCR에 올림 → Jenkins가 서버에서 `infra/deploy/deploy.sh` 실행 → 새 이미지로 교체하고 프론트 파일을 바꾼 뒤 Nginx를 다시 읽음 |
| 배포 위치 | `/opt/beez` |
| 이미지 | `ghcr.io/s15a108/beez-backend`, `beez-ai`, `beez-frontend-dist` (태그는 커밋 SHA) |
| 열린 포트 | 80, 443만 엽니다. backend는 서버 안에서만 접속할 수 있는 `127.0.0.1:8081`, 나머지 컨테이너는 포트를 열지 않습니다 |

자세한 내용은 [docs/infra/cd.md](../docs/infra/cd.md)에 있습니다.

## 1-6. 배포할 때 주의할 점

1. **필수 환경변수가 빠지면 배포가 시작되지 않습니다.** compose가 빠진 변수 이름을 알려 주니, 그 줄을 env 파일에 넣고 다시 실행하세요.
2. **AI 기능은 env 파일에 켜는 줄이 있어야 동작합니다.** `GMS_KEY`만 넣으면 보완 질문, 설계 초안, 비비 질문, 영향 분석은 계속 실패합니다. 1-4의 3)에 켜는 줄을 넣어 두었습니다.
3. **AI 호출 시간 제한을 늘릴 때는 순서를 지키세요.** AI 모델 호출 시간 < backend 대기 시간 < Nginx 대기 시간(60초) 순서여야 합니다. 바깥쪽이 더 짧으면 Nginx가 먼저 504 오류를 냅니다. 늘릴 때는 AI 쪽 값을 늘린 다음 backend 값을 늘립니다.
4. **`VITE_API_MODE`는 프론트엔드를 빌드할 때 정해집니다.** 값을 바꿨다면 프론트엔드를 다시 빌드해 올려야 반영됩니다.
5. **DB 테이블은 backend가 뜰 때 자동으로 만들어집니다.** Flyway가 `backend/src/main/resources/db/migration`(SQL)과 `backend/src/main/java/db/migration`(Java)의 V1~V52를 차례로 적용하므로, SQL을 따로 실행하지 마세요.
6. **PostgreSQL 설정(`infra/compose.yaml`의 `command`)을 바꾸면 DB 컨테이너가 다시 만들어집니다.** 그동안 서비스가 잠시 끊기니, 팀원에게 시간을 미리 알리고 배포하세요.

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
| 서버 `/opt/beez/.env.monitoring` | 모니터링(Grafana 등) 계정 | X |
| 서버 `/etc/jenkins/casc.env` | Jenkins 관리자와 배포 계정 | X |
| GitHub 저장소 설정 | 배포 토큰(`JENKINS_API_TOKEN`), GitLab 동기화 토큰(`GITLAB_SYNC_TOKEN`), 자동 병합 토큰(`PR_AUTOMERGE_TOKEN`), `VITE_API_MODE` | X |
