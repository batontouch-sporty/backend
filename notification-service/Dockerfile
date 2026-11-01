# build 스테이지는 서비스 8개가 똑같아야 BuildKit이 한 번만 돌린다. 고칠 때는 8개를 같이 고친다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle sh gradlew --no-daemon bootJar -x :legacy-sporty:bootJar

FROM eclipse-temurin:17-jre
COPY --from=build /src/notification-service/build/libs/*.jar /app.jar
USER nobody
HEALTHCHECK --interval=5s --timeout=3s --start-period=120s --retries=12 CMD curl -fs http://localhost:8085/actuator/health
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app.jar"]
