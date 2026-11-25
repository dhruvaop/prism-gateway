# Root build context so the gateway image includes both the Spring Boot app and
# the runtime data files it needs at startup (routing config and pricing).
# Seed/demo keys are deliberately excluded from production images.
# Use this Dockerfile on Render, Cloud Run, or any container host.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml ./
COPY backend/src ./src
# Maven Central occasionally answers 429 during shared-builder builds. Retry a
# few times before failing so a transient mirror throttle does not abort a deploy.
RUN for attempt in 1 2 3 4; do \
      mvn -q -DskipTests package && break; \
      echo "Maven build attempt $attempt failed; retrying in 15s"; \
      sleep 15; \
    done; \
    test -f /workspace/target/prism-gateway-1.0.0.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/prism-gateway-1.0.0.jar /app/prism.jar
COPY data/gateway_config.sample.json /app/data/gateway_config.sample.json
COPY data/model_pricing.json /app/data/model_pricing.json
ENV PRISM_DATA_DIR=/app/data
ENV PRISM_SEED_KEYS_ENABLED=false
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/prism.jar"]
