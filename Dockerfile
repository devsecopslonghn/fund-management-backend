FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:922927df2c662cdd47ddb116443d6bec4696cfae3de1a0ddac8fcc7b87ce61ae AS build

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

FROM eclipse-temurin:21-jre-alpine@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52

RUN apk upgrade --no-cache && \
    addgroup -S -g 10001 fund && \
    adduser -S -D -H -u 10001 -G fund -s /sbin/nologin fund

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
COPY --from=build /workspace/otel/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/opentelemetry-javaagent.jar"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
