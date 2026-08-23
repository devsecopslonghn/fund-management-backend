FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 fund && \
    useradd --system --uid 10001 --gid fund --home-dir /app --shell /usr/sbin/nologin fund

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
