FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q test package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S skyfleet && adduser -S skyfleet -G skyfleet
WORKDIR /app
COPY --from=build /workspace/target/skyfleet-logistics-backend-1.0.0.jar app.jar
USER skyfleet
EXPOSE 8095
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
