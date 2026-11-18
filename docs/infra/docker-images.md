# 컨테이너 이미지와 로컬 전체 스택

backend·ai를 컨테이너 이미지로 만들고, 로컬에서 전체 스택(postgres·seaweedfs·ai·backend·nginx)을
compose 로 띄워 연결을 확인하는 방법입니다. (S15P31A108-120)

명령은 모두 **Git Bash**에서 저장소 루트(`/c/git/BEEZ`) 기준으로 실행합니다. Docker Desktop이 켜져 있어야 합니다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | 멀티스테이지. JDK로 `bootJar` → 층 추출 → JRE 런타임, `app`(10001) 사용자로 실행 |
| `ai/Dockerfile` | `python:3.12-slim`, `requirements.lock`으로 설치, 어휘 파일을 빌드 때 내장, `app`(10001) 사용자로 실행 |
| `ai/requirements.lock` | ai 이미지 전용 버전 고정 목록. `requirements.txt`(버전 없음)는 AI 팀 개발·CI용으로 그대로 둡니다 |
| `backend/.dockerignore`, `ai/.dockerignore` | 빌드 산출물·테스트·비밀값을 빌드 컨텍스트에서 뺍니다 |
| `infra/compose.yaml` | 서버와 로컬이 같이 쓰는 기본 파일. postgres·seaweedfs·ai·backend |
| `infra/compose.local.yaml` | 로컬에서만 겹쳐 쓰는 파일. nginx 컨테이너 추가, backend를 local 프로필로 |
| `infra/nginx/local/default.conf` | 로컬 nginx 설정. 정적 서빙은 서버 `dev.beez.ai.kr.conf`와 같고 `/api` 프록시를 더했습니다 |
| `infra/local.env` | 로컬 전용 더미값. 서버·운영에 쓰지 않습니다 |

프론트는 이미지로 만들지 않습니다. [frontend 결정 D13](../../frontend/docs/decisions.md)대로 `dist/`를 정적
배포하고, 로컬에서는 nginx 컨테이너가 호스트에서 빌드한 `frontend/dist`를 읽기 전용으로 붙여 서빙합니다.

기존 `backend/compose.yaml`은 `bootRun`이 DB·SeaweedFS를 자동으로 띄우는 용도로 그대로 둡니다.
두 스택은 프로젝트 이름이 달라(`backend`·`beez`) 볼륨을 공유하지 않습니다.

## 설계 이유

- **backend 층 분리**: Spring Boot의 `jarmode=tools extract --layers`로 jar를 외부 라이브러리·로더·SNAPSHOT·우리 코드
  네 층으로 나눕니다. 코드만 바뀌면 마지막 층(약 330kB)만 다시 push·pull 됩니다.
- **런타임에 JDK·소스 없음**: 빌드 단계에서만 JDK·Gradle을 쓰고 런타임은 JRE만 둡니다. 빌드 도구의 취약점이
  운영 이미지에 실리지 않습니다. 테스트는 CI가 이미 돌렸으므로 이미지 빌드에서는 `-x test`로 뺍니다.
- **non-root**: 두 이미지 모두 uid·gid 10001인 `app` 사용자로 실행합니다. 번호를 고정해 볼륨 권한을 맞추기 쉽게 했습니다.
- **ai 어휘 파일 내장**: `scripts.prepare_tokenizer`를 빌드 때 돌려 `/app/.tiktoken`에 넣습니다. 기동 때 네트워크가
  필요 없고, 파일이 없거나 손상되면 `lifespan`에서 기동 자체가 실패합니다.
- **ai 버전 고정을 lock 파일로 분리**: `requirements.txt`를 바꾸면 AI 팀의 개발·CI 흐름이 바뀌므로 이미지 전용 lock
  파일을 따로 둡니다. 실행용·개발용(`pytest`·`ruff`) 분리와 CI 전환은 AI 담당과 협의할 일입니다.
- **환경 중립**: 서비스·볼륨 이름에 dev/prod를 넣지 않고, 환경 차이는 `--env-file`로만 줍니다.
  기본 파일의 필수값은 `${VAR:?}`로 적어 빠지면 compose가 바로 멈춥니다.
- **서버용 기본 파일에 nginx 없음**: 서버는 호스트 Nginx(apt)가 앞에 섭니다([Nginx·HTTPS 런북](nginx-https.md)).
  backend는 `127.0.0.1`에만 포트를 열고, postgres·seaweedfs·ai는 포트를 열지 않습니다.

## 이미지 빌드

```bash
cd /c/git/BEEZ/backend && docker build -t beez-backend .
cd /c/git/BEEZ/ai && docker build -t beez-ai .
```

실행 사용자 확인:

```bash
docker run --rm --entrypoint id beez-backend
```

기대 출력은 `uid=10001(app) gid=10001(app) groups=10001(app)`입니다. ai도 같습니다.

## 로컬 전체 스택

1. 프론트를 실제 API 모드로 빌드합니다. 기본값은 목 모드라 `/api`를 부르지 않습니다.

   ```bash
   cd /c/git/BEEZ/frontend && VITE_API_MODE=live npm run build
   ```

2. 스택을 띄웁니다. 이미지가 없거나 바뀌었으면 `--build`가 다시 빌드합니다.

   ```bash
   cd /c/git/BEEZ && docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build
   ```

3. 상태와 응답을 확인합니다.

   ```bash
   docker compose -p beez ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
   curl -s http://localhost:8088/api/ping; echo
   curl -s http://localhost:8088/actuator/health; echo
   ```

   backend·ai·postgres가 `(healthy)`, 헬스가 `"status":"UP"`이면 정상입니다. 브라우저는 http://localhost:8088 을 엽니다.

4. 내립니다. 볼륨(DB·원본 파일)은 남고, `-v`를 붙이면 볼륨까지 지웁니다.

   ```bash
   docker compose -p beez down
   ```

서버와 같은 prod 프로필로 띄우려면 2번 명령 앞에 `SPRING_PROFILES_ACTIVE=prod `를 붙입니다.
prod는 헬스 상세를 숨기고(`{"status":"UP"}`만), 세션 쿠키에 `Secure`가 붙습니다.
Chrome·Edge·Firefox는 `http://localhost`를 안전한 출처로 보므로 이 쿠키로도 로그인됩니다.

| 포트 | 용도 |
|---|---|
| `127.0.0.1:8088` | nginx (브라우저 주소). `LOCAL_HTTP_PORT`·`PUBLIC_ORIGIN`을 같이 바꿉니다 |
| `127.0.0.1:18080` | backend 직접 접근. `bootRun`(8080)과 겹치지 않게 했습니다 |

## 실측 (2026-10-29, 전진 PC · Docker Desktop 29.6.1)

| 항목 | 값 |
|---|---|
| backend 첫 빌드 | 3분 44초 (JDK 받기 95초·JRE 63초·의존성 75초·`bootJar` 42초) |
| backend 재빌드 (변경 없음) | 3.4초, 전 단계 캐시 |
| backend 이미지 | 압축 184MB / 해제 약 418MB (Ubuntu 117MB·패키지 58MB·JRE 166MB·의존성 76.5MB·코드 0.3MB) |
| ai 첫 빌드 | 37초 (`pip install` 24초·어휘 3.6초, 베이스 이미지는 미리 받아 둔 상태) |
| ai 이미지 | 압축 95MB (pip 층 213MB·코드 0.3MB·어휘 1.7MB) |
| 전체 스택 `up --build` | 54초 (postgres healthy 6초, backend healthy 17초) |

Docker Desktop의 `docker image ls`는 압축본과 해제본을 합쳐 보여 줘서 backend가 602MB로 나옵니다.
전송량은 압축 크기, 디스크 사용량은 해제 크기로 봅니다.

확인한 것:

- 5개 컨테이너 기동, nginx 경유 `/api/ping`·`/actuator/health`(db UP)·`/`(200) 응답
- backend·ai 컨테이너 안 `id`가 `uid=10001(app)`
- prod 프로필 기동: 필수값 누락 없음, 헬스 상세 숨김, nginx 경유 가입(201)·로그인(200, `Secure; HttpOnly; SameSite=Lax`)·`/api/me`(200)
- dev `ea7867d`로 올린 뒤 재확인: 기존 볼륨에 V6·V7 마이그레이션 적용, 재빌드 포함 `up` 34초, local·prod 두 프로필 모두 위와 같은 응답
- 임베딩 프로필: ai 컨테이너가 계산한 실제 값(`gms-small-1024-v1`·`text-embedding-3-small`·1024)과 backend 컨테이너의
  `DOCUMENT_EMBEDDING_PROFILE`·`DOCUMENT_EMBEDDING_MODEL`이 같음. 실제 임베딩 호출은 `GMS_KEY`가 있어야 해서 확인하지 않았습니다

## 문서 색인을 확인하려면

문서 업로드 뒤 색인(임베딩)까지 보려면 `GMS_KEY`가 필요합니다. 키는 파일에 쓰지 말고 셸 환경변수로 넘깁니다.

```bash
read -rs GMS_KEY && export GMS_KEY && docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d
```

`read -rs`는 입력을 화면에 보이지 않게 받습니다. 셸 환경변수가 `--env-file`의 빈 값보다 우선합니다.

backend의 `DOCUMENT_EMBEDDING_PROFILE`·`DOCUMENT_EMBEDDING_MODEL`은 ai가 실제로 쓰는 프로필과 같아야 합니다.
ai는 프로필 이름을 `gms-small-{GMS_EMBEDDING_DIMENSION}-v1`로 만들고, backend 차원은 1024로 고정입니다.
compose 기본값이 ai 기본값과 같으므로 ai 설정을 바꿀 때만 두 값을 같이 덮어씁니다.
값을 확인하는 명령입니다.

```bash
docker compose -p beez exec ai python -c "from app.core.config import get_settings; from app.services.retrieval.tokenization import embedding_profile; p=embedding_profile(get_settings()); print(p.profile_id, p.model, p.dimension)"
docker compose -p beez exec backend sh -c 'echo "$DOCUMENT_EMBEDDING_PROFILE $DOCUMENT_EMBEDDING_MODEL"'
```

## ai 의존성 다시 고정하기

`requirements.txt`가 바뀌면 lock 파일을 다시 만듭니다. 깨끗한 컨테이너에서 뽑아야 PC에 깔린 패키지가 섞이지 않습니다.

```bash
cd /c/git/BEEZ/ai && MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/src:ro" python:3.12-slim sh -c "pip install -q -r /src/requirements.txt && pip freeze" > requirements.lock
```

`MSYS_NO_PATHCONV=1`은 Git Bash가 `/src`를 Windows 경로로 바꾸는 것을 막습니다.
다시 만든 뒤 파일 맨 위 머리말 주석을 되살립니다.

## 안 될 때

| 증상 | 확인 |
|---|---|
| `required variable … is missing a value` | `--env-file infra/local.env`를 빠뜨렸습니다 |
| `port is already allocated` | 8088·18080을 쓰는 프로세스를 끄거나 `infra/local.env`의 포트를 바꿉니다 |
| backend가 healthy가 안 됨 | `docker compose -p beez logs backend --tail 40`. Flyway 오류면 이전 스택의 볼륨이 남은 것일 수 있습니다(`down -v`) |
| 화면은 뜨는데 API가 안 불림 | 프론트를 `VITE_API_MODE=live`로 다시 빌드했는지 확인합니다 |
| 색인이 `embedding_profile_not_configured` | backend에 `DOCUMENT_EMBEDDING_*`가 전달되지 않았습니다. `infra/compose.yaml`의 backend `environment`를 확인합니다 |
| 색인이 `model_unavailable`(502) | ai에 `GMS_KEY`가 없습니다. 위 "문서 색인을 확인하려면"대로 넘깁니다 |
| 색인이 `invalid_ai_input`(422) | backend 프로필·모델과 ai 실제 프로필이 다릅니다. 위 확인 명령으로 두 값을 비교합니다 |

## 이 문서 범위 밖

- GHCR 푸시·SHA 태그·서버 배포, 서버 Nginx `/api` 프록시: [CD 런북](cd.md) (S15P31A108-123)
- SeaweedFS bootstrap 키와 backend 전용 키 분리, 서버 볼륨·백업: S15P31A108-211
- 환경변수 목록·비밀값 주입: [환경변수와 비밀값](env.md) (S15P31A108-27)
