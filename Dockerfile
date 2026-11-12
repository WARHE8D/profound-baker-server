# syntax=docker/dockerfile:1

# ---- build: compile all modules and package the bootable app jar --------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Poms first, so the dependency download is cached until a pom changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/pom.xml common/
COPY identity/pom.xml identity/
COPY catalog/pom.xml catalog/
COPY ordering/pom.xml ordering/
COPY app/pom.xml app/
RUN chmod +x mvnw

COPY common/src common/src
COPY identity/src identity/src
COPY catalog/src catalog/src
COPY ordering/src ordering/src
COPY app/src app/src

# Tests need Docker (Testcontainers) and run in CI, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl app -am package

# Split the jar into layers (libraries change far less often than our code) for smaller image updates.
RUN java -Djarmode=tools -jar app/target/baker.jar extract --layers --destination /extracted

# ---- runtime: JRE only, non-root ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system baker \
 && useradd --system --gid baker --no-create-home --shell /usr/sbin/nologin baker \
 && mkdir -p /app /data/uploads \
 && chown baker:baker /data/uploads

WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER baker

ENV SPRING_PROFILES_ACTIVE=prod \
    APP_STORAGE_PATH=/data/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# 8080: API (behind Caddy). 9080: Actuator, internal only.
EXPOSE 8080 9080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:9080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "baker.jar"]
