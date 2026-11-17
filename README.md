# BEEZ

**각자 AI 에이전트로 개발하는 팀이 같은 설계를 기준으로 일하게 돕는 협업 서비스**

팀원마다 Claude Code 같은 AI 에이전트로 코드를 쓰면, 에이전트마다 다른 문서와 다른 기억을 보고 일하게 됩니다.
BEEZ는 팀이 승인한 설계를 **하나의 기준**으로 묶어 두고, 에이전트가 작업을 시작하기 전에 그 기준을 읽고
계획을 검증받게 합니다. 설계가 바뀌면 영향을 받는 작업을 찾아 알려 줍니다.

- 운영(개발) 서버: <https://dev.beez.ai.kr>
- 제출용 포팅 매뉴얼: [`exec/`](exec/README.md)

## 목차

- [어떻게 동작하나요](#어떻게-동작하나요)
- [주요 기능](#주요-기능)
- [시스템 구성](#시스템-구성)
- [빠르게 실행하기 (Docker Compose)](#빠르게-실행하기-docker-compose)
- [서비스별 개발 실행](#서비스별-개발-실행)
- [에이전트 연결하기](#에이전트-연결하기)
- [검사와 테스트](#검사와-테스트)
- [저장소 구조](#저장소-구조)
- [문서](#문서)

## 어떻게 동작하나요

```text
 ① 설계 확정          ② 작업 시작 전 검증         ③ 설계 변경과 영향
 ───────────         ──────────────────         ─────────────────
 기획안·요구사항·      에이전트가 MCP로            변경 제안 → 영향 분석
 ERD·API 명세 업로드   승인 설계를 읽고 계획 등록    → 승인되면 닿는 작업을
 → 비비(AI)가 검토     → 기준과 어긋나면 FAIL        재검증 대기로 돌림
 → 팀장 승인 = 기준 v1 → PASS여야 작업 시작        → 계획 수정 후 재개
```

- **사람**은 웹에서 문서를 올리고, 설계를 승인하고, 작업 현황을 봅니다.
- **에이전트**는 MCP(Model Context Protocol)로 BEEZ에 붙어 승인 설계를 읽고, 계획을 올리고, 상태를 보고합니다.
- **비비**는 BEEZ 안의 AI 도우미입니다. 문서 검토, 보완 질문, 계획 검증, 회의 정리, 영향 분석을 맡습니다.

## 주요 기능

| 영역 | 내용 |
| --- | --- |
| 프로젝트 공간 | 3D 도시에서 프로젝트를 고르고, 사무실에서 팀원·에이전트의 상태를 봅니다 |
| 문서와 설계 | 문서를 올리면 비비가 검토하고, 보완 질문과 답으로 설계 항목 초안을 만듭니다. 팀장이 승인하면 기준 버전이 발행됩니다 |
| 작업 계획과 검증 | 에이전트가 올린 계획을 승인 설계와 비교해 PASS/FAIL로 판정하고 근거를 남깁니다 |
| 설계 변경 | 변경 제안을 올리면 영향받는 설계 항목과 작업을 분석하고, 승인 뒤 해당 작업을 재검증 대기로 돌립니다 |
| 회의 | 회의 대화를 비비가 정리하고, 결정 사항을 문서 수정 초안이나 변경 제안으로 이어 줍니다 |
| 팀과 알림 | 이메일 초대, 역할, 활동 기록과 알림 |
| 에이전트 연결 | 프로젝트별 토큰을 발급해 Claude Code 등을 MCP로 연결합니다 |

## 시스템 구성

```text
 브라우저 ──▶ Nginx ──┬─ /            프론트엔드 정적 파일 (React)
                     ├─ /api/        ─▶ backend (Spring Boot) ──▶ PostgreSQL + pgvector
 에이전트 ──▶ Nginx ──┴─ /mcp         ─▶ backend                ├─▶ SeaweedFS (문서 원본, S3 호환)
                                                                 └─▶ ai (FastAPI) ──▶ LLM API
```

| 영역 | 기술 |
| --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, Three.js, React Three Fiber |
| Backend | Java 21, Spring Boot 4.1, Spring Security, JPA, Flyway, MCP Java SDK |
| AI | Python 3.12, FastAPI, OpenAI 호환 API(SSAFY GMS), tiktoken |
| 저장소 | PostgreSQL 17 + pgvector, SeaweedFS (S3 호환) |
| 인프라 | Docker Compose, Nginx, GitHub Actions(CI·이미지 빌드), Jenkins(배포) |

## 빠르게 실행하기 (Docker Compose)

클론한 뒤 **명령 두 줄**로 전체 서비스(Nginx·frontend·backend·ai·PostgreSQL·SeaweedFS)를 띄웁니다.

**필요한 것**: Docker Desktop(Compose v2), Node.js 24

```bash
# 1) 프론트엔드를 실제 API 모드로 빌드합니다 (frontend/dist 생성)
cd frontend && npm ci && VITE_API_MODE=live npm run build && cd ..

# 2) 전체 스택을 빌드하고 띄웁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
```

브라우저에서 <http://localhost:8088>을 엽니다. 첫 실행은 이미지 빌드까지 5분쯤 걸리고(2026-11-17 실측), 다음부터는 1분 안에 뜹니다.

- **회원가입 인증 코드**: 로컬은 메일을 보내지 않고 backend 로그에 코드를 찍습니다.
  `docker compose -p beez logs --no-log-prefix backend | grep -A2 "가입 인증 코드입니다"` 로 6자리 코드를 확인합니다.
- **AI 기능**: LLM 키 없이도 서비스는 뜨지만, 문서 검토·색인·비비 같은 AI 기능은 실패합니다. 키가 있으면 파일에 쓰지 말고
  셸 환경변수로 넘겨 2번 명령을 다시 실행합니다.

  ```bash
  read -rs GMS_KEY && export GMS_KEY
  ```

- `infra/local.env`는 **로컬 전용 더미값**이라 저장소에 들어 있습니다. 서버에 쓰지 않습니다.

| 주소 | 용도 |
| --- | --- |
| <http://localhost:8088> | 웹 화면 (Nginx) |
| <http://localhost:8088/mcp> | 에이전트 MCP 주소 |
| <http://localhost:8088/actuator/health> | 헬스 체크 |
| <http://localhost:18080/swagger-ui.html> | API 문서 (backend 직접, local 프로필) |

상태 확인과 종료:

```bash
docker compose -p beez ps                 # 컨테이너 상태 (모두 healthy 면 정상)
docker compose -p beez logs -f backend    # 로그 보기
docker compose -p beez down               # 내리기 (데이터 볼륨은 남음, -v 를 붙이면 삭제)
```

잘 안 될 때는 [docs/infra/docker-images.md "안 될 때"](docs/infra/docker-images.md#안-될-때)를 봅니다.

## 서비스별 개발 실행

코드를 고치며 개발할 때는 서비스를 따로 띄웁니다.

### 프론트엔드

Node.js 24가 필요합니다.

```bash
cd frontend
npm ci
npm run dev
```

<http://localhost:5173>에 접속합니다. 개발 서버는 `/api` 요청을 `http://localhost:8080`(backend)으로 넘깁니다.

`VITE_API_MODE`로 데이터 출처를 고릅니다. `frontend/.env.example`을 `frontend/.env`로 복사해 바꿉니다.

| 값 | 동작 |
| --- | --- |
| `live` | 실제 backend API를 호출합니다. 운영 서버는 이 값으로 빌드합니다 |
| `mock` (기본, 그 밖의 값 포함) | backend 없이 브라우저 안의 목데이터로 화면만 봅니다 |

`VITE_` 값은 빌드 결과에 그대로 들어가 누구나 볼 수 있으니 비밀 값을 넣지 않습니다.

### 백엔드

Java 21과 Docker Desktop이 필요합니다. `bootRun`이 Spring Boot Docker Compose 연동으로 PostgreSQL·SeaweedFS를 함께 띄우고
Flyway로 스키마를 적용합니다.

```bash
cd backend
./gradlew bootRun      # Windows: gradlew.bat bootRun
```

기본 포트는 8080이고, [Swagger UI](http://localhost:8080/swagger-ui.html)에서 API를 확인합니다.

### AI 서비스

Python 3.12가 필요합니다.

```bash
cd ai
cp .env.example .env          # GMS_KEY 와 AI_INTERNAL_TOKEN 을 채웁니다
python -m venv .venv
# 가상환경 활성화 후
python -m pip install -r requirements.txt
python -m scripts.prepare_tokenizer
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

- `bootRun`으로 띄운 backend는 `AI_INTERNAL_TOKEN` 기본값이 `change-me`입니다. `ai/.env`에도 같은 값을 넣어야
  backend → ai 호출이 401로 막히지 않습니다.
- 토크나이저 준비 명령은 공개 어휘 파일을 받습니다. 준비된 캐시가 없으면 서버가 시작되지 않습니다.
- 환경변수 전체 목록은 [docs/infra/env.md](docs/infra/env.md)에 있습니다.

## 에이전트 연결하기

1. 웹에 로그인해 프로젝트에 들어간 뒤 **설정 → 내 에이전트 관리**에서 토큰을 발급합니다.
2. 화면에 나온 명령을 작업할 저장소 폴더에서 실행합니다.

   ```bash
   claude mcp add --transport http beez <서버 주소>/mcp --header "Authorization: Bearer <토큰>"
   ```

3. 같은 폴더에서 `claude`를 켜고 BEEZ 연결을 확인합니다.

자세한 절차와 문제 해결은 [에이전트 연결 매뉴얼](docs/guides/agent-connection.md), 도구 목록은
[MCP 도구 계약](docs/api/02-mcp-tools.md)을 봅니다.

## 검사와 테스트

각 명령은 해당 디렉터리에서 실행합니다.

| 위치 | 명령 | 내용 |
| --- | --- | --- |
| `frontend/` | `npm run check` | 린트, 포맷, 단위 테스트, 빌드 |
| `frontend/` | `npx playwright install chromium` 후 `npm run test:e2e` | 브라우저 E2E 테스트 |
| `backend/` | `./gradlew test` | 단위·슬라이스 테스트 |
| `backend/` | `./gradlew integrationTest` | Testcontainers 통합 테스트 (Docker 필요) |
| `ai/` | `python -m pytest -q` | AI 테스트 (토크나이저 사전 준비 필요) |
| `ai/` | `python -m ruff check .` 및 `python -m ruff format --check .` | 린트 및 포맷 검사 |

Windows에서는 백엔드 명령에 `gradlew.bat`을 사용합니다.

## 저장소 구조

```text
.
├── frontend/   # 웹 클라이언트와 3D 화면 (React)
├── backend/    # REST API, MCP 서버, 도메인 로직 (Spring Boot)
├── ai/         # 문서 처리·임베딩·LLM 기능 (FastAPI)
├── infra/      # Docker Compose, Nginx, 배포·백업·모니터링 설정
├── docs/       # API 계약, 설계, 인프라, 협업 문서
└── exec/       # 포팅 매뉴얼 (빌드·배포, 외부 서비스, DB, 시연 시나리오)
```

## 문서

| 분류 | 문서 |
| --- | --- |
| 설계 | [시스템 아키텍처](docs/design/01-system-architecture.md) · [시스템 흐름](docs/design/02-system-flow.md) · [ERD](docs/design/03-erd.md) · [작업 수명주기](docs/design/04-task-lifecycle.md) |
| API 계약 | [공통](docs/api/00-common.md) · [백엔드 REST](docs/api/01-backend-rest.md) · [MCP 도구](docs/api/02-mcp-tools.md) · [AI 내부 API](docs/api/03-ai-internal.md) |
| 인프라 | [환경변수와 비밀값](docs/infra/env.md) · [Docker 이미지·로컬 스택](docs/infra/docker-images.md) · [배포(CD)](docs/infra/cd.md) · [서버 세팅](docs/infra/server-setup.md) |
| 개발 | [프론트엔드 개발 안내](frontend/README.md) · [에이전트 연결 매뉴얼](docs/guides/agent-connection.md) |
| 협업 | [GitHub & Jira 협업 컨벤션](docs/collaboration-convention.md) · [GitLab 동기화](docs/gitlab-sync.md) · [PR 자동 병합](docs/pr-auto-merge.md) |

## 라이선스

[MIT License](LICENSE)
