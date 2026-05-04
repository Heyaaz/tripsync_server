# Spring Boot 서버 Dockerfile
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon -Dorg.gradle.daemon=false

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /app/build/libs/ ./libs/
RUN mv ./libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=local
ENV JAVA_OPTS="-Xmx1g -Xms512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
