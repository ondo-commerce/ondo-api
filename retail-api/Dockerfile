# 소매 API 배포 이미지.
#
# 2단계로 나눈다. 빌드에 쓰는 JDK·그래들·소스는 1단계에만 있고,
# 실제로 돌아가는 이미지에는 JRE 와 jar 만 남는다. 이미지가 작고, 소스가 안 실린다.

# ── 1단계 · 빌드 ────────────────────────────────────────────
#
# --platform=$BUILDPLATFORM 은 「이 단계는 빌드하는 기계의 아키텍처로 돌려라」다 (MUL-105).
#
# 최종 이미지는 ARM64 여야 한다 — 태스크 정의가 ARM 이라 x86 이미지는 안 뜬다
# (ecs.tf). 그런데 GitHub 러너는 x86 이다. 이 줄이 없으면 그래들 빌드 전체가
# ARM 에뮬레이션으로 돌아서 몇 배 느려진다.
#
# jar 는 바이트코드라 어느 아키텍처에서 만들든 결과가 같다. 그래서 빌드는 러너
# 네이티브로 하고, 아키텍처가 실제로 중요한 2단계(JRE)만 ARM 으로 둔다.
# 에뮬레이션이 필요한 건 아래 useradd 한 줄뿐이다.
#
# 맥(M시리즈)에서 docker build 하면 BUILDPLATFORM 이 arm64 라 지금까지와 똑같다
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 빌드 스크립트를 먼저 넣고 의존성만 받아둔다.
# 소스만 고쳤을 때 이 층이 캐시로 남아서 라이브러리를 다시 안 받는다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

# ── 2단계 · 실행 ────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 안 돌린다. 앱이 뚫려도 컨테이너 안에서 할 수 있는 일이 줄어든다.
RUN useradd --system --create-home --shell /usr/sbin/nologin ondo
USER ondo

COPY --from=build /app/build/libs/*.jar app.jar

# Datadog APM tracer (MUL-148). 버전을 고정한다 — latest 를 받으면 같은 커밋으로
# 빌드해도 이미지마다 tracer 가 달라진다. 올릴 때는 이 줄만 바꾼다.
#
# 넣어만 둔다. 켜는 건 태스크 정의의 JAVA_TOOL_OPTIONS 다(infra/ecs.tf) —
# 로컬 docker run 에는 Agent 가 없어서 여기서 켜면 연결 실패 로그만 쌓인다.
# --chmod 가 없으면 URL 로 받은 파일이 600 이라 ondo 사용자가 못 읽는다
ARG DD_JAVA_AGENT_VERSION=1.68.1
ADD --chmod=644 https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar /app/dd-java-agent.jar

# 프로필은 여기서 안 박는다 (MUL-104).
#
# 전에는 ENV SPRING_PROFILES_ACTIVE=prod 가 있었다. 그때는 배포 프로필이 하나뿐이라
# 문제가 없었지만, 지금은 dev 와 prod 가 갈렸다. 이미지에 박아 두면 같은 이미지를
# 두 환경에 못 쓰거나, 더 나쁘게는 개발 환경이 자기를 운영이라 부른다.
#
# 어느 환경인지는 태스크 정의(infra/ecs.tf)가 SPRING_PROFILES_ACTIVE 로 준다.
# 안 주면 기본값 local 로 떠서 컨테이너 안 localhost 를 보다 기동에 실패한다 —
# 조용히 잘못된 설정으로 도는 것보다 낫다.

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
