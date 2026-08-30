FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

# Keep the agent version pinned so the runtime instrumentation is reproducible.
# The agent provides inbound/outbound HTTP, MongoDB, JDBC and log correlation
# without changing financial domain code.
RUN mvn --batch-mode --no-transfer-progress \
    dependency:copy \
    -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0 \
    -DoutputDirectory=/workspace/otel \
    -Dmdep.stripVersion=true

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 fund && \
    useradd --system --uid 10001 --gid fund --home-dir /app --shell /usr/sbin/nologin fund

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
COPY --from=build /workspace/otel/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/opentelemetry-javaagent.jar"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
