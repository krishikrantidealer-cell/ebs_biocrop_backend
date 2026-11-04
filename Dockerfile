# Stage 1: Build stage with Maven and OpenJDK 21
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B || true
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production JRE 21 Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Cloud Run / dynamic container port (defaults to 8080)
ENV PORT=8080
EXPOSE ${PORT}

# Container-aware JVM memory settings:
# -XX:+UseContainerSupport: respects cgroup limits in Cloud Run
# -XX:MaxRAMPercentage=75.0: dynamically sets max heap to 75% of container RAM
# -XX:InitialRAMPercentage=40.0: pre-allocates 40% heap for fast startup
# ${JAVA_OPTS:-}: allows runtime JVM customization via Cloud Run env vars
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=40.0 -Dserver.port=${PORT} ${JAVA_OPTS:-} -jar app.jar"]

