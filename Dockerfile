# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine-3.23
WORKDIR /app

RUN apk upgrade --no-cache p11-kit p11-kit-trust \
    && addgroup -S shop \
    && adduser -S shop -G shop

COPY --from=build /workspace/target/*.jar app.jar

USER shop
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
