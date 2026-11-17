# 1. 빌드 및 배포

## 1-1. 사용 제품과 버전

### 런타임·서버

| 구분 | 제품 | 버전 | 근거 파일 |
| --- | --- | --- | --- |
| JVM | Eclipse Temurin (OpenJDK) | 21 (빌드 `21-jdk`, 실행 `21-jre`) | `backend/Dockerfile`, `backend/build.gradle` |
| WAS | Spring Boot 내장 Tomcat | Spring Boot 4.1.1 | `backend/build.gradle` |
| 빌드 도구 | Gradle (Wrapper) | 8.14.3 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| 웹서버 | Nginx | 1.24 (서버: Ubuntu apt, 로컬: `nginx:1.24-alpine`) | `infra/compose.local.yaml`, `docs/infra/nginx-https.md` |
| AI 서버 | Python + FastAPI(Uvicorn) | Python 3.12 | `ai/Dockerfile`, `ai/requirements.lock` |
| 프론트 빌드 | Node.js + Vite | Node 24, Vite 8 | `frontend/package.json` (`engines`) |
| DB | PostgreSQL + pgvector | PostgreSQL 17 (`pgvector/pgvector` digest 고정) | `infra/compose.yaml` |
| 객체 저장소 | SeaweedFS (S3 호환) | 4.51 | `infra/compose.yaml` |
| 컨테이너 | Docker Engine + Compose v2 | 서버 Docker CE, 로컬 Docker Desktop 29.6 | `docs/infra/server-setup.md` |
| CI/CD | GitHub Actions, Jenkins, GHCR | — | `.github/workflows/`, `infra/jenkins/` |
| 서버 OS | Ubuntu | 24.04 LTS (4 vCPU · 15GiB · 309GB) | `docs/infra/server-setup.md` |

### 주요 라이브러리

| 영역 | 라이브러리 |
| --- | --- |
| Backend | Spring Security, Spring Data JPA, Flyway, springdoc-openapi 3.1.1, MCP Java SDK 2.1.0, PostgreSQL JDBC |
| AI | FastAPI, pydantic-settings, httpx, openai, tiktoken, markdown-it-py (전체 고정 버전은 `ai/requirements.lock`) |
| Frontend | React 19, TypeScript 6.0, Tailwind CSS 4, Three.js 0.186, React Three Fiber 9 |

### IDE

| 영역 | IDE | 버전 |
| --- | --- | --- |
| Backend | IntelliJ IDEA | (팀 기입) |
| Frontend·AI | Visual Studio Code | (팀 기입) |

## 1-2. 빌드

### 한 번에 빌드·실행 (로컬)

```bash
cd frontend && npm ci && VITE_API_MODE=live npm run build && cd ..
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
```

<http://localhost:8088>에서 확인합니다. 구성과 문제 해결은 [docs/infra/docker-images.md](../docs/infra/docker-images.md)에 있습니다.

### 서비스별 빌드 산출물

| 서비스 | 빌드 명령 | 산출물 |
| --- | --- | --- |
| backend | `cd backend && ./gradlew bootJar` (이미지: `docker build backend`) | `backend/build/libs/*.jar` → 이미지 `beez-backend` |
| ai | `docker build ai` | 이미지 `beez-ai` (빌드 중 tiktoken 어휘 파일을 받아 넣음) |
| frontend | `cd frontend && npm ci && VITE_API_MODE=live npm run build` | `frontend/dist/` (정적 파일, Nginx가 서빙) |

## 1-3. 빌드·실행 환경변수

전체 목록과 의미는 정본 [docs/infra/env.md](../docs/infra/env.md)에 있습니다. 여기서는 **반드시 필요한 값**만 적습니다.

### 빌드 시점

| 이름 | 위치 | 값 | 설명 |
| --- | --- | --- | --- |
| `VITE_API_MODE` | 프론트 빌드 | `live` | `live`가 아니면 목데이터 모드로 빌드됩니다. 운영은 GitHub 저장소 변수로 `live`를 넣습니다 |

### 실행 시점 (compose env 파일)

compose는 `--env-file`로 값을 받습니다. 로컬은 `infra/local.env`(더미값), 서버는 `/opt/beez/.env.dev`입니다.

| 이름 | 필수 | 비밀 | 설명 |
| --- | --- | --- | --- |
| `DB_USERNAME`·`DB_PASSWORD` | 필수 | 비밀(PW) | PostgreSQL 계정. DB를 처음 만들 때 값으로 굳습니다 |
| `DB_NAME` | | | 기본 `beez` |
| `DOCUMENT_S3_ACCESS_KEY`·`DOCUMENT_S3_SECRET_KEY` | 필수 | 비밀 | backend 전용 SeaweedFS 키 |
| `SEAWEEDFS_BOOTSTRAP_*`·`SEAWEEDFS_BACKUP_*` | 필수 | 비밀 | SeaweedFS 관리·백업 키 (각 ACCESS/SECRET) |
| `AI_INTERNAL_TOKEN` | 필수 | 비밀 | backend ↔ ai 내부 인증 토큰 |
| `PUBLIC_ORIGIN` | 필수 | | 브라우저 주소, 끝 `/` 없이 (예 `https://dev.beez.ai.kr`) |
| `BACKEND_HOST_PORT` | 필수 | | backend를 여는 127.0.0.1 포트 (서버 `8081`, 로컬 `18080`) |
| `GMS_KEY` | | 비밀 | LLM·임베딩 API 키. 없으면 AI 기능이 502 |
| `MAIL_HOST`·`MAIL_PORT`·`MAIL_USERNAME`·`MAIL_PASSWORD`·`MAIL_FROM` | | 비밀(PW) | 가입 인증·초대 메일 SMTP |
| `PLANNING_QUESTIONS_ENABLED`·`DESIGN_DRAFTS_ENABLED`·`BEE_CHAT_ENABLED` | | | AI 기능 스위치. 기본 `false`, 시연 서버는 `true` |
| `IMPACT_LLM_MODEL` | | | 변경 영향 분석 모델 (예 `gpt-6-luna`). 비우면 영향 분석 꺼짐 |

비밀값은 `openssl rand -hex 32`처럼 난수로 만듭니다. 서버 env 파일을 만드는 명령은
[docs/infra/cd.md "2. env 파일"](../docs/infra/cd.md#2-env-파일)에 있습니다.

## 1-4. 배포

### 배포 흐름

```text
dev 브랜치 push ─▶ GitHub Actions(cd.yml) ─▶ 이미지 빌드 후 GHCR push
                                         └─▶ Jenkins 배포 잡 호출 ─▶ 서버 infra/deploy/deploy.sh
                                                                     ├─ 이미지 pull, compose up
                                                                     └─ 프론트 dist 교체, Nginx reload
```

| 항목 | 값 |
| --- | --- |
| 서버 | `k15a108.p.ssafy.io` (Ubuntu 24.04), 서비스 도메인 `dev.beez.ai.kr` |
| 배포 위치 | `/opt/beez` (compose 프로젝트 이름 `beez`) |
| 이미지 | `ghcr.io/s15a108/beez-backend` · `beez-ai` · `beez-frontend-dist`, 태그는 커밋 SHA |
| 공개 포트 | 80·443만 (ufw). backend는 `127.0.0.1:8081`, 나머지 컨테이너는 포트를 열지 않음 |

### 배포 시 특이사항

1. **서버에는 Nginx를 컨테이너가 아닌 호스트(apt)에 둡니다.** 설정 정본은 `infra/nginx/sites-available/`이고
   HTTPS 인증서는 certbot(Let's Encrypt)으로 받습니다([nginx-https.md](../docs/infra/nginx-https.md)).
   `/mcp`는 스트리밍 연결이라 `proxy_buffering off`·`proxy_read_timeout 1h`가 필요합니다.
2. **필수 환경변수가 빠지면 배포가 멈춥니다.** compose의 `${VAR:?}` 검사와 `infra/deploy/check-port.sh`가 컨테이너를
   바꾸기 전에 확인합니다.
3. **AI 기능 스위치는 기본 꺼짐입니다.** 새 서버에서는 env 파일에 `*_ENABLED=true`·`IMPACT_LLM_MODEL`을 넣어야
   기획 분석·비비·영향 분석이 동작합니다([env.md "AI 기능 켜기"](../docs/infra/env.md#ai-기능-켜기-서버)).
4. **대기 시간 사슬을 지킵니다.** AI 모델 호출 < backend 읽기 제한 < Nginx `/api/` 60초 순서가 깨지면 Nginx가 먼저 504를
   냅니다. 값을 올릴 때는 안쪽부터 올립니다.
5. **`VITE_API_MODE`는 빌드 때 굳습니다.** 값을 바꾸면 dev에 새 커밋이 들어가야 서버에 반영됩니다.
6. **DB 스키마는 Flyway가 backend 기동 시 적용합니다**(SQL `backend/src/main/resources/db/migration` + Java `backend/src/main/java/db/migration`, V1~V52). 따로 DDL을
   실행하지 않습니다.
7. PostgreSQL은 CDC용으로 `wal_level=logical`로 뜹니다. 이 설정이 바뀌면 postgres 컨테이너가 다시 만들어집니다.

## 1-5. 계정·프로퍼티 파일 목록

DB 접속 정보 등 주요 계정과 설정이 정의된 파일입니다. **실제 비밀값은 저장소에 없습니다.**

| 파일 | 내용 | 저장소 포함 |
| --- | --- | --- |
| `backend/src/main/resources/application.yml` | 공통 설정과 로컬 기본값 (DB URL, S3, AI, 메일 모드 등) | O |
| `backend/src/main/resources/application-local.yml` | 로컬 프로필 (가입 인증 코드를 로그로 출력, 설계 초안 자동 생성 켬) | O |
| `backend/src/main/resources/application-prod.yml` | 운영 프로필. 필수값은 기본값 없이 환경변수로만 받고, Swagger는 `SWAGGER_ENABLED`로만 켬 | O |
| `backend/compose.yaml` | `bootRun` 개발용 PostgreSQL·SeaweedFS (계정 `beez`/`beez`) | O |
| `backend/src/main/resources/db/migration/` | Flyway 스키마 (ERD 정본은 [docs/design/03-erd.md](../docs/design/03-erd.md)) | O |
| `infra/compose.yaml` | 전체 스택 정의, 필수 환경변수 목록 | O |
| `infra/local.env` | 로컬 전체 스택용 **더미** 계정값 | O |
| `infra/monitoring/local.env` | 로컬 모니터링 스택 더미값 | O |
| `ai/.env.example` → `ai/.env` | AI 서버 설정 (`GMS_KEY`, `AI_INTERNAL_TOKEN`) | 예시만 |
| `frontend/.env.example` → `frontend/.env` | `VITE_API_MODE` | 예시만 |
| 서버 `/opt/beez/.env.dev` | 운영 DB·S3·AI·메일 비밀값 (권한 `640`) | X |
| 서버 `/opt/beez/.env.monitoring` | Grafana·모니터링 DB 계정 | X |
| 서버 `/etc/jenkins/casc.env` | Jenkins 관리자·배포 계정 | X |
| GitHub 저장소 시크릿·변수 | `JENKINS_API_TOKEN`, `GITLAB_SYNC_TOKEN`, `PR_AUTOMERGE_TOKEN`, `VITE_API_MODE` | X |
