# BEEZ

팀원이 각자 AI 에이전트(Claude Code 등)로 개발해도, 모두 같은 설계를 기준으로 일하게 돕는 협업 서비스입니다.

에이전트마다 보는 문서가 다르면 같은 API를 서로 다르게 고치는 일이 생깁니다. BEEZ에서는 팀이 승인한 설계를
하나로 모아 두고, 에이전트가 코드를 쓰기 전에 그 설계와 자기 계획이 맞는지 검사받습니다. 설계가 바뀌면 영향을
받는 작업을 찾아 다시 검사받게 합니다.

- 개발 서버: <https://dev.beez.ai.kr>
- 제출용 포팅 매뉴얼: [`exec/`](exec/README.md)

## 목차

- [어떻게 쓰나요](#어떻게-쓰나요)
- [주요 기능](#주요-기능)
- [시스템 구성](#시스템-구성)
- [빠르게 실행하기](#빠르게-실행하기)
- [서비스별 개발 실행](#서비스별-개발-실행)
- [에이전트 연결하기](#에이전트-연결하기)
- [검사와 테스트](#검사와-테스트)
- [저장소 구조](#저장소-구조)
- [문서](#문서)

## 어떻게 쓰나요

1. 팀이 기획안, 요구사항, ERD, API 명세를 올립니다. BEEZ 안의 AI 도우미 **비비**가 문서끼리 어긋난 곳을 짚고,
   팀장이 승인하면 이 설계가 팀의 첫 기준(v1)이 됩니다.
2. 팀원의 에이전트는 MCP(Model Context Protocol)로 BEEZ에 연결해 승인된 설계를 읽고 작업 계획을 올립니다.
   비비가 계획을 설계와 비교해 통과시키거나 막고, 통과한 계획만 작업을 시작할 수 있습니다.
3. 설계를 바꾸려면 변경 제안을 올립니다. 비비가 영향받는 작업을 찾아 주고, 팀장이 승인하면 그 작업들은
   새 설계로 다시 검사받습니다.

## 주요 기능

| 영역 | 할 수 있는 것 |
| --- | --- |
| 프로젝트 공간 | 3D 도시에서 프로젝트를 고르고, 사무실에서 팀원과 에이전트가 무엇을 하는지 봅니다 |
| 문서와 설계 | 문서를 올리면 비비가 검토하고 빠진 결정을 묻습니다. 답을 모아 설계 항목 초안을 만들고, 팀장이 승인해 발행합니다 |
| 작업 계획과 검증 | 에이전트가 올린 계획이 승인 설계와 맞는지 판정하고, 판정 근거를 남깁니다 |
| 설계 변경 | 변경 제안이 어느 설계 항목과 작업에 영향을 주는지 분석하고, 승인되면 그 작업을 다시 검사받게 합니다 |
| 회의 | 회의 대화를 비비가 정리하고, 결정 사항을 문서 수정 초안이나 변경 제안으로 만듭니다 |
| 팀과 알림 | 팀원을 이메일로 초대하고 역할을 정합니다. 활동 기록과 알림을 봅니다 |
| 에이전트 연결 | 프로젝트마다 토큰을 발급해 Claude Code 같은 에이전트를 연결합니다 |

## 시스템 구성

```text
 브라우저 ──▶ Nginx ──┬─ /       ─▶ 프론트엔드 정적 파일 (React)
                     ├─ /api/   ─▶ backend (Spring Boot) ──▶ PostgreSQL + pgvector
 에이전트 ──▶ Nginx ──┴─ /mcp    ─▶ backend                ├─▶ SeaweedFS (문서 원본 저장)
                                                            └─▶ ai (FastAPI) ──▶ LLM API
```

| 영역 | 기술 |
| --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, Three.js, React Three Fiber |
| Backend | Java 21, Spring Boot 4.1, Spring Security, JPA, Flyway, MCP Java SDK |
| AI | Python 3.12, FastAPI, SSAFY GMS(OpenAI 호환 API), tiktoken |
| 저장소 | PostgreSQL 17 + pgvector, SeaweedFS(S3 호환) |
| 인프라 | Docker Compose, Nginx, GitHub Actions, Jenkins |

## 빠르게 실행하기

PC에서 전체 서비스(Nginx, 프론트엔드, backend, ai, PostgreSQL, SeaweedFS)를 한 번에 띄웁니다.

준비물: Docker Desktop(실행 중이어야 합니다), Node.js 24, Git, 그리고 Windows라면 Git Bash

이 절의 명령은 모두 Git Bash(Windows) 또는 macOS·Linux 터미널에서 실행하세요. `VITE_API_MODE=live npm run build`,
`grep`, `read` 같은 명령은 PowerShell과 cmd에서 동작하지 않습니다.

### 1) 소스 받기

```bash
git clone -b dev https://lab.ssafy.com/s15-final/S15P31A108.git beez
cd beez
```

GitHub에서 받는다면 주소를 `https://github.com/S15A108/BEEZ.git`으로 바꾸세요. 개발 서버와 같은 코드인 `dev` 브랜치를 받습니다.
SSAFY GitLab은 비공개라 SSAFY GitLab 계정으로 로그인해야 합니다. 비밀번호로 로그인이 거절되면 GitLab 사용자 설정에서
Personal Access Token(`read_repository` 권한)을 만들어 비밀번호 자리에 넣으세요.

### 2) 빌드하고 띄우기

```bash
# 프론트엔드를 실제 API에 붙는 모드로 빌드합니다 (frontend/dist 생성)
cd frontend && npm ci && VITE_API_MODE=live npm run build && cd ..

# 이미지를 빌드하고 전체 서비스를 띄웁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
```

첫 명령 출력 끝에 `built in`(빌드 완료) 줄이 나왔는지 확인하고 두 번째 명령을 실행하세요. 첫 명령이 중간에 실패하면
터미널이 `frontend` 폴더에 남아 있으니 `cd ..`로 돌아와 다시 실행합니다.

두 번째 명령이 끝나면 브라우저에서 <http://localhost:8088>을 엽니다. 처음에는 이미지를 빌드하느라 5분쯤 걸리고,
다음부터는 1분 안에 뜹니다.

### 3) 회원가입하기

PC에서는 인증 메일을 보내지 않고 backend 로그에 인증 코드를 남깁니다. 가입 화면에서 코드를 요청한 뒤 아래 명령으로
6자리 코드를 확인해 입력하세요. 여러 번 요청했다면 맨 아래에 나온 코드가 최신입니다.

```bash
docker compose -p beez logs --no-log-prefix backend | grep -A2 "가입 인증 코드입니다"
```

### 4) AI 기능 켜기

키 없이도 서비스는 뜨지만 문서 검토, 비비 질문 같은 AI 기능은 실패합니다. AI 기능을 쓰려면 SSAFY GMS API 키
([발급 방법](exec/02-external-services.md#2-1-llm-api-ssafy-gms), SSAFY 교육생만 발급 가능)를 받아 아래 명령을 차례로
실행합니다. 키는 파일에 저장하지 않고 지금 열린 터미널에만 둡니다.

먼저 아래 한 줄만 실행하고 키를 붙여 넣은 뒤 Enter를 누릅니다. Git Bash에서는 `Shift+Insert`로 붙여 넣고, 입력한 키는
화면에 보이지 않습니다.

```bash
read -rs GMS_KEY && export GMS_KEY
```

그다음 나머지 명령을 실행합니다.

```bash
export PLANNING_QUESTIONS_ENABLED=true DESIGN_DRAFTS_ENABLED=true BEE_CHAT_ENABLED=true IMPACT_LLM_MODEL=gpt-6-luna \
       BEEZ_CDC_INTAKE_ENABLED=true
export CONTEXT_SELECTION_ENABLED=true CONTEXT_SELECTION_MIN_SIMILARITY=0.3 CONTEXT_SELECTION_MAX_ITEMS=10 \
       CONTEXT_SELECTION_MAX_EVIDENCE=8 CONTEXT_SELECTION_TOTAL_TIMEOUT_SECONDS=30
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait
```

- 첫 `export`는 보완 질문, 설계 초안, 비비 질문, 영향 분석을 켜고, 기준을 발행한 뒤의 영향 분석을 처리하게 합니다.
  둘째 `export`는 에이전트가 승인된 설계를 읽어 가는 기능을 켭니다. 이 기능들은 기본으로 꺼져 있습니다.
- 둘째 `export`의 기능은 팀 문서([docs/infra/env.md](docs/infra/env.md))에 아직 켜지 않는 기능으로 되어 있고, 값은 팀이 아직
  확정하지 않은 값입니다. 이 기능이 꺼져 있으면 에이전트가 설계를 읽지 못하므로 함께 켭니다.
- `export`한 값은 지금 열린 터미널에만 남습니다. 터미널을 새로 열었다면 위 명령을 처음부터 다시 실행한 뒤 서비스를 띄우세요.

### 주소

| 주소 | 용도 |
| --- | --- |
| <http://localhost:8088> | 웹 화면 |
| <http://localhost:8088/mcp> | 에이전트 연결 주소 |
| <http://localhost:8088/actuator/health> | 서버 상태 확인 |
| <http://localhost:18080/swagger-ui.html> | API 문서 |

### 상태 보기와 끄기

```bash
docker compose -p beez ps                 # postgres, seaweedfs, ai, backend 가 (healthy) 면 정상입니다. nginx 는 Up 으로만 나옵니다
docker compose -p beez logs -f backend    # backend 로그 보기 (Ctrl+C 로 빠져나옵니다)
docker compose -p beez down               # 끄기. 데이터는 남습니다
docker compose -p beez down -v            # 데이터까지 지우기. 가입한 계정과 올린 문서가 모두 지워집니다
```

실행이 안 되면 [docs/infra/docker-images.md의 "안 될 때"](docs/infra/docker-images.md#안-될-때) 표에서 증상을 찾아보세요.

## 서비스별 개발 실행

코드를 고치면서 개발할 때는 서비스를 하나씩 따로 띄웁니다.

### 프론트엔드

Node.js 24가 필요합니다.

```bash
cd frontend
npm ci
npm run dev
```

<http://localhost:5173>을 엽니다. 처음에는 backend 없이 브라우저 안의 예시 데이터(목데이터)로 화면만 뜹니다.

실제 데이터로 보려면 아래 백엔드를 먼저 띄우고, `frontend/.env.example`을 `frontend/.env`로 복사한 뒤
`VITE_API_MODE=live`로 바꿔 개발 서버를 다시 켜세요. 개발 서버가 `/api` 요청을 `http://localhost:8080`의 backend로
넘겨 줍니다.

`VITE_`로 시작하는 값은 빌드된 JS 파일에 그대로 들어가 누구나 볼 수 있습니다. 비밀번호나 키를 넣지 마세요.

### 백엔드

Java 21과 Docker Desktop이 필요합니다. Docker Desktop을 켜 둔 상태에서 실행하세요. PostgreSQL과 SeaweedFS가 자동으로
함께 뜨고, DB 테이블도 자동으로 만들어집니다. PostgreSQL이 PC의 5432 포트를 쓰므로, PC에 PostgreSQL이 이미 설치되어
실행 중이라면 먼저 꺼 주세요.

```bash
cd backend
./gradlew bootRun      # PowerShell 은 .\gradlew.bat bootRun, cmd 는 gradlew.bat bootRun
```

<http://localhost:8080/swagger-ui.html>에서 API 목록을 볼 수 있습니다.

### AI 서비스

Python 3.12가 필요합니다.

```bash
cd ai
cp .env.example .env
# .env 를 열어 GMS_KEY=<발급받은 키>, AI_INTERNAL_TOKEN=change-me 로 채웁니다
python -m venv .venv
source .venv/Scripts/activate        # macOS·Linux 는 source .venv/bin/activate
python -m pip install -r requirements.txt
python -m scripts.prepare_tokenizer  # 처음 한 번만. 인터넷에서 어휘 파일을 받습니다
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

- `AI_INTERNAL_TOKEN`은 backend와 ai가 서로를 확인하는 값입니다. `bootRun`으로 띄운 backend는 `change-me`를 쓰므로
  ai에도 같은 값을 넣어야 둘이 연결됩니다.
- `prepare_tokenizer`를 건너뛰면 ai 서버가 시작되지 않습니다. 처음 한 번은 인터넷이 되는 곳에서 실행하세요.
- 그 밖의 설정값은 [docs/infra/env.md](docs/infra/env.md)에서 찾아보세요.

## 에이전트 연결하기

1. 웹에 로그인하고 연결할 프로젝트에 들어갑니다. 오른쪽 위의 톱니바퀴(설정) 버튼을 누르고, 설정 창 왼쪽 메뉴의 **내 에이전트 관리**에서
   토큰을 발급합니다. 토큰은 프로젝트마다 따로 발급하고, 발급할 때 한 번만 보이니 바로 복사하세요.
2. 화면에 나온 명령을 작업할 저장소 폴더에서 실행합니다. 로컬 서버라면 주소가 `http://localhost:8088/mcp`입니다.

   ```bash
   claude mcp add --transport http beez http://localhost:8088/mcp --header "Authorization: Bearer <토큰>"
   ```

3. 같은 폴더에서 `claude`를 켜고 `/mcp`를 입력합니다. 목록에서 `beez`가 connected로 보이면 연결된 것입니다.

막힐 때는 [에이전트 연결 매뉴얼](docs/guides/agent-connection.md)을 보세요. 에이전트가 쓸 수 있는 도구 목록은
[MCP 도구 계약](docs/api/02-mcp-tools.md)에 있습니다.

## 검사와 테스트

표의 위치 폴더로 이동해서 명령을 실행하세요.

| 위치 | 명령 | 하는 일 |
| --- | --- | --- |
| `frontend/` | `npm run check` | 린트, 포맷, 단위 테스트, 빌드 |
| `frontend/` | `npx playwright install chromium` 후 `npm run test:e2e` | 브라우저 E2E 테스트 |
| `backend/` | `./gradlew test` | 단위 테스트 |
| `backend/` | `./gradlew integrationTest` | DB를 띄우는 통합 테스트 (Docker 필요) |
| `ai/` | `python -m pytest -q` | AI 테스트 (`prepare_tokenizer`를 먼저 한 번 실행) |
| `ai/` | `python -m ruff check .` 와 `python -m ruff format --check .` | 린트, 포맷 검사 |

## 저장소 구조

```text
.
├── frontend/   웹 화면과 3D 공간 (React)
├── backend/    REST API, MCP 서버, 업무 로직 (Spring Boot)
├── ai/         문서 처리, 임베딩, LLM 기능 (FastAPI)
├── infra/      Docker Compose, Nginx, 배포, 백업, 모니터링 설정
├── docs/       API 계약, 설계, 인프라, 협업 문서
└── exec/       포팅 매뉴얼
```

## 문서

| 분류 | 문서 |
| --- | --- |
| 설계 | [시스템 아키텍처](docs/design/01-system-architecture.md), [시스템 흐름](docs/design/02-system-flow.md), [ERD](docs/design/03-erd.md), [작업 수명주기](docs/design/04-task-lifecycle.md) |
| API 계약 | [공통](docs/api/00-common.md), [백엔드 REST](docs/api/01-backend-rest.md), [MCP 도구](docs/api/02-mcp-tools.md), [AI 내부 API](docs/api/03-ai-internal.md) |
| 인프라 | [환경변수와 비밀값](docs/infra/env.md), [Docker 이미지와 로컬 실행](docs/infra/docker-images.md), [배포](docs/infra/cd.md), [서버 세팅](docs/infra/server-setup.md) |
| 개발 | [프론트엔드 개발 안내](frontend/README.md), [에이전트 연결 매뉴얼](docs/guides/agent-connection.md) |
| 협업 | [GitHub와 Jira 협업 컨벤션](docs/collaboration-convention.md), [GitLab 동기화](docs/gitlab-sync.md), [PR 자동 병합](docs/pr-auto-merge.md) |

## 라이선스

[MIT License](LICENSE)
