# syntax=docker/dockerfile:1
# 프론트 dist 배포 포장 이미지 (S15P31A108-123).
#
# 서빙용 이미지가 아니다. 프론트는 Docker 없이 정적 파일로 배포한다 (frontend/docs/decisions.md D13).
# 이 이미지는 빌드 결과(dist)를 GHCR 에 SHA 태그로 실어 나르는 상자이고, 안에 실행할 프로그램이 없다.
# 서버 배포 스크립트가 docker create + docker cp 로 /dist 를 꺼내 /var/www/beez/releases/<sha> 에 푼다.
# backend·ai 와 같은 통로(GHCR·SHA 태그)를 쓰므로 과거 SHA 로 되돌리기도 같은 방법으로 한다.
#
# 빌드 위치: 저장소 루트에서 (빌드 컨텍스트는 frontend/, 이 파일은 frontend/ 밖에 둔다)
#   docker build -f infra/frontend-dist/Dockerfile -t beez-frontend-dist frontend

# Node 는 package.json engines(>=24 <25)·CI 와 맞춘다
FROM node:24-bookworm-slim AS build
WORKDIR /app

# 의존성 목록만 먼저 복사해 설치한다. 소스가 바뀌어도 이 층은 캐시에서 재사용된다.
# .npmrc 의 engine-strict 때문에 Node 버전이 engines 와 다르면 여기서 멈춘다.
COPY package.json package-lock.json .npmrc ./
RUN npm ci

COPY . .

# VITE_ 값은 빌드 결과에 그대로 박히는 공개 값이다. 토큰·키를 넘기지 않는다.
# ARG 는 아래 RUN 에 환경변수로 보이고, Vite 는 환경변수의 VITE_ 값을 .env 파일보다 먼저 읽는다.
ARG VITE_API_MODE=mock
RUN npm run build

# 결과물만 담는다. 베이스 이미지가 없어(scratch) 이미지 크기는 dist 크기와 같다.
FROM scratch
COPY --from=build /app/dist /dist
