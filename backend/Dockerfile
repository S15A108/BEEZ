# syntax=docker/dockerfile:1
# BEEZ backend 이미지 (S15P31A108-120).
#
# 단계가 둘이다.
#   build   : JDK + Gradle 래퍼로 bootJar 를 만들고, Spring Boot 의 층(layer) 추출 도구로 jar 를 네 묶음으로 나눈다.
#   runtime : JRE 만 있는 작은 이미지에 그 네 묶음을 "잘 안 바뀌는 것부터" 복사한다.
# 런타임 이미지에는 소스·Gradle·JDK 가 없다. 이미지가 작고, 빌드 도구의 취약점이 운영 이미지에 실리지 않는다.
#
# 빌드 위치: backend/ 에서  docker build -t beez-backend .
# 실행 설정(프로필·DB 주소·비밀값)은 이미지에 넣지 않고 compose 의 environment 로 준다.

# ---------- 1) build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 해석에 필요한 파일만 먼저 복사한다. 이 파일들이 그대로면 아래 RUN 층은 캐시에서 재사용된다.
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN chmod +x gradlew

# --mount=type=cache : 빌더가 ~/.gradle(래퍼 배포본·의존성 jar)을 빌드 사이에 보관한다. 이미지에는 남지 않는다.
# --no-daemon        : 컨테이너 안에서는 데몬이 다음 빌드까지 살아 있을 이유가 없다.
# dependencies -q    : 소스 없이도 도는 작업으로 Gradle 배포본을 받고 의존성 그래프를 미리 해석해 둔다(출력은 버린다).
#                      jar 본체 일부는 bootJar 때 받히지만 같은 캐시 마운트에 남아 다음 빌드부터는 다시 받지 않는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies -q > /dev/null

# 소스는 가장 자주 바뀌므로 마지막에 복사한다. 테스트는 CI 가 이미 돌렸으므로 여기서는 뺀다(-x test).
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*.jar application.jar

# jar 를 층으로 나눈다 (Spring Boot 3.3+ 의 tools jarmode).
#   dependencies/          외부 라이브러리 — 거의 안 바뀜
#   spring-boot-loader/    부트 로더 — 거의 안 바뀜
#   snapshot-dependencies/ SNAPSHOT 라이브러리 — 가끔
#   application/           우리 코드 — 매번
# 바뀐 층만 다시 push·pull 되므로 배포 전송량이 줄어든다.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- 2) runtime ----------
FROM eclipse-temurin:21-jre
# 전용 시스템 사용자. root 로 띄우지 않는다 (완료 조건). uid·gid 를 같은 값으로 고정해 두면 볼륨 권한을 맞추기 쉽다.
# (--user-group 은 gid 를 알아서 고르므로(999) 그룹을 먼저 명시적으로 만든다.)
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

# 잘 안 바뀌는 층부터 복사해야 위쪽 층의 캐시가 산다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# 컨테이너 메모리 한도의 75% 까지 힙으로 쓴다. 한도가 없으면 호스트 메모리 기준.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
