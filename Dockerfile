# ---- 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 내려받기를 먼저 해 두면 소스만 바뀐 빌드에서 캐시를 쓸 수 있습니다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# root가 아닌 사용자로 실행합니다.
RUN useradd --system --uid 10001 continew
USER continew

COPY --from=build /app/build/libs/*.jar app.jar

# 호스팅이 PORT를 정해 주면 그 값을 쓰고, 없으면 8080입니다(application.yml).
EXPOSE 8080

# 컨테이너 메모리의 75%까지만 힙으로 씁니다(무료 플랜의 작은 메모리에서 강제 종료를 피하기 위함).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
