# Stage 1: Build stage with Maven and OpenJDK 21
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production JRE 21 Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Render assigns a dynamic port via PORT environment variable (defaults to 8080)
ENV PORT=8080
EXPOSE ${PORT}

# JVM Memory optimization for Render Free Tier (512 MB RAM limit):
# -Xms128m -Xmx256m : Max Heap set to 256MB
# -XX:MaxMetaspaceSize=128m : Metaspace capped at 128MB
# -XX:ReservedCodeCacheSize=64m : JIT Code Cache capped at 64MB
# -Xss256k : Thread stack size reduced to 256KB
# -XX:+UseSerialGC : Serial GC reduces runtime memory overhead compared to G1GC
# -XX:+UseContainerSupport : Enable cgroup container memory limit awareness
ENTRYPOINT ["sh", "-c", "exec java -Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -Xss256k -XX:+UseSerialGC -XX:+UseContainerSupport -Dserver.port=${PORT} -jar app.jar"]
