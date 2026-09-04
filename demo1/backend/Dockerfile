FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S zhixun && adduser -S zhixun -G zhixun
WORKDIR /app
COPY --from=build /workspace/target/zhixun-demo-backend-1.0.0.jar app.jar
USER zhixun
EXPOSE 8095
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
