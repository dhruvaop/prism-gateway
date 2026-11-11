# Root build context so the gateway image includes both the Spring Boot app and
# the data files it needs at startup (seed keys, routing config, pricing).
# Use this Dockerfile on Render, Cloud Run, or any container host.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml ./
COPY backend/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/prism-gateway-1.0.0.jar /app/prism.jar
COPY data /app/data
ENV PRISM_DATA_DIR=/app/data
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/prism.jar"]
