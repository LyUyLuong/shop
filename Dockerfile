# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app


RUN addgroup -S shop && adduser -S shop -G shop


COPY --from=build /workspace/target/*.jar app.jar

USER shop
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


