# Stage 1: build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime (playwright + java, browsers pre-installed)
FROM mcr.microsoft.com/playwright/java:v1.57.0-jammy
WORKDIR /app
COPY --from=builder /app/target/allbest1-1.0-SNAPSHOT.jar allbest.jar
CMD ["java", "-jar", "allbest.jar"]