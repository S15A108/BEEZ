# syntax=docker/dockerfile:1
# BEEZ ai(FastAPI) 이미지 (S15P31A108-120).
#
# 단계는 하나다. 필요한 패키지가 전부 wheel 로 제공돼 컴파일러가 필요 없고,
# 소스가 그대로 실행되는 Python 은 "빌드 산출물"이 따로 없어서 멀티스테이지의 이득이 거의 없다.
#
# 빌드 위치: ai/ 에서  docker build -t beez-ai .
# 의존성은 requirements.lock(버전 고정) 으로 설치한다. requirements.txt 는 AI 팀의 개발·CI 용으로 그대로 둔다.

FROM python:3.12-slim

# PYTHONDONTWRITEBYTECODE : .pyc 를 쓰지 않는다 (읽기 전용으로 돌릴 수 있게).
# PYTHONUNBUFFERED        : 로그가 버퍼 없이 바로 나온다 (docker logs 에서 지연 없음).
# PIP_*                   : pip 캐시·버전 확인 끔.
# TIKTOKEN_CACHE_DIR      : 어휘 파일 위치. 아래 prepare_tokenizer 가 여기에 받아 두고, 기동 시에는 이 경로만 읽는다.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TIKTOKEN_CACHE_DIR=/app/.tiktoken

# 전용 시스템 사용자. root 로 띄우지 않는다 (완료 조건). uid·gid 10001 로 고정 (backend 와 같은 규칙).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

# 의존성 목록만 먼저 복사해 설치한다. 소스가 바뀌어도 이 층은 캐시에서 재사용된다.
COPY requirements.lock ./
RUN pip install -r requirements.lock

# 실행에 필요한 코드만 복사한다 (tests·evaluations 는 .dockerignore 로 제외).
COPY app ./app
COPY scripts ./scripts

# 어휘 파일을 빌드 시점에 받아 이미지에 넣는다. 런타임은 네트워크 없이 뜨고,
# 파일이 없거나 손상되면 lifespan 에서 기동 자체가 실패한다 (app/main.py).
RUN python -m scripts.prepare_tokenizer

USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
