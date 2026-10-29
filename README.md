# BEEZ

**프로젝트의 설계와 개발 맥락을 함께 관리하는 AI 에이전트 협업 서비스**

BEEZ는 팀의 문서와 설계, 작업 계획을 연결해 사람과 AI 에이전트가 같은 프로젝트 맥락을 바탕으로 협업할 수 있도록 만드는 프로젝트입니다. 웹에서는 3D 공간을 통해 프로젝트에 들어가 문서, 작업, 팀원 등의 정보를 살펴볼 수 있습니다.

> 개발 중인 프로젝트입니다. 아래 기능은 제품이 지향하는 범위를 포함하며, 화면과 API의 구현 및 실제 연동 상태는 서로 다를 수 있습니다. 프론트엔드는 기본적으로 브라우저 내 목데이터를 사용합니다.

## 주요 기능

- **프로젝트 공간**: 3D 도시와 오피스에서 프로젝트를 선택하고 설계 현황과 활동을 확인합니다.
- **문서와 설계**: 프로젝트 문서, 설계 현황 및 변경 제안을 관리합니다.
- **작업과 협업**: 작업 계획과 검증, 팀원, 회의 기록, 알림 및 활동을 한 공간에서 다룹니다.
- **AI 연동**: 문서 파싱·청킹·임베딩을 통해 개발 맥락을 활용하는 기능을 개발합니다. 백엔드가 권한과 상태를 관리하며 AI 서비스는 분석 결과를 제공합니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, Three.js, React Three Fiber |
| Backend | Java 21, Spring Boot 4.1, Spring Security, JPA, Flyway |
| AI | Python 3.12, FastAPI, tiktoken, markdown-it-py |
| 저장소 | PostgreSQL 17, pgvector, SeaweedFS (S3 호환) |

## 저장소 구조

```text
.
├── frontend/  # 웹 클라이언트와 3D 화면
├── backend/   # REST API, 도메인 로직과 데이터 관리
├── ai/        # 문서 처리·임베딩 등 AI 서비스
├── infra/     # Nginx 등 인프라 설정
└── docs/      # API, 설계, 인프라 및 협업 문서
```

## 로컬 실행

### 프론트엔드 미리 보기

Node.js 24가 필요합니다. 기본 목 모드에서는 백엔드 없이 화면을 확인할 수 있습니다.

```bash
cd frontend
npm ci
npm run dev
```

브라우저에서 [http://localhost:5173](http://localhost:5173)에 접속합니다. 실제 API를 사용하려면 `frontend/.env.example`을 `.env`로 복사하고 `VITE_API_MODE=live`로 설정합니다. 개발 서버는 `/api` 요청을 `http://localhost:8080`으로 전달합니다. 실제 백엔드와의 전체 연동은 아직 검증 중입니다. `VITE_` 환경변수에는 비밀 키를 넣지 마세요.

### 백엔드

Java 21과 Docker Desktop이 필요합니다. `backend/`에서 실행하면 Spring Boot가 Compose를 통해 로컬 PostgreSQL과 SeaweedFS를 시작하고 Flyway로 스키마를 적용합니다.

```bash
cd backend
./gradlew bootRun
```

Windows에서는 `./gradlew.bat bootRun`을 사용합니다. 기본 포트는 8080이며, 실행 후 [Swagger UI](http://localhost:8080/swagger-ui.html)에서 API를 확인할 수 있습니다.

### AI 서비스

Python 3.12를 준비하고 `ai/.env.example`을 `ai/.env`로 복사합니다. 임베딩 호출에는 `GMS_KEY`가 필요하며, 내부 API 호출에 사용하는 `AI_INTERNAL_TOKEN`은 백엔드와 같은 값으로 설정합니다. 실제 키는 저장소에 올리지 마세요.

```bash
cd ai
python -m venv .venv
# 가상환경 활성화 후
python -m pip install -r requirements.txt
python -m scripts.prepare_tokenizer
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

토크나이저 준비 명령은 공개 어휘 파일을 다운로드합니다. 서버는 준비된 캐시가 없으면 시작되지 않습니다. 문서 색인 연동 시에는 백엔드와 AI의 임베딩 모델·차원·프로필 설정도 맞춰야 합니다. 자세한 내용은 [청킹·임베딩 실행 안내](ai/app/services/retrieval/README.md)를 참고하세요.

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

Windows에서는 백엔드 검사에도 `gradlew.bat`을 사용합니다.

## 문서

- [프론트엔드 개발 안내](frontend/README.md)
- [시스템 아키텍처 초안](docs/design/01-system-architecture.md)
- API 계약: [공통](docs/api/00-common.md) · [백엔드 REST](docs/api/01-backend-rest.md) · [MCP 도구](docs/api/02-mcp-tools.md) · [AI 내부 API](docs/api/03-ai-internal.md)
- [GitHub & Jira 협업 컨벤션](docs/collaboration-convention.md)
- [GitLab 동기화 운영 가이드](docs/gitlab-sync.md)
- [PR 자동 병합 운영 가이드](docs/pr-auto-merge.md)

## 라이선스

[LICENSE](LICENSE)를 참고하세요.
