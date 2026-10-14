# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:24-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app sports \
    && mkdir -p /data && chown sports /data
WORKDIR /app
COPY --from=build /src/target/sports-mcp.jar /app/sports-mcp.jar

ENV BIND_HOST=0.0.0.0 \
    PORT=8080 \
    DB_PATH=/data/sports.db \
    LOG_FORMAT=json
VOLUME ["/data"]
EXPOSE 8080
USER sports

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD curl -fsS http://127.0.0.1:8080/health/live >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/sports-mcp.jar"]
