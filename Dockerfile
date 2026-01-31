FROM mcr.microsoft.com/playwright/java:v1.57.0-jammy

WORKDIR /app
COPY target/allbest1-1.0-SNAPSHOT.jar ./allbest.jar
CMD ["java", "-jar", "allbest.jar"]
