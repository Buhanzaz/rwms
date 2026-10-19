# One image contains the Java deployables from one source revision.
FROM node:22-bookworm-slim AS node
FROM eclipse-temurin:25-jdk-noble AS build
COPY --from=node /usr/local/ /usr/local/
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/
COPY platform/ platform/
COPY contracts/ contracts/
COPY services/ services/
RUN bash ./gradlew --no-daemon --max-workers=2 \
    :services:auth-service:bootJar :services:api-gateway-service:bootJar \
    :services:warehouse-service:bootJar :services:asset-service:bootJar \
    :services:task-board-service:bootJar :services:maintenance-service:bootJar \
    :services:inventory-service:bootJar :services:logistics-service:bootJar \
    :services:dossier-service:bootJar :services:analytics-service:bootJar \
    :services:assistant-service:bootJar \
    && mkdir /artifacts && for d in services/*; do \
       if [ -d "$d/build/libs" ]; then \
         find "$d/build/libs" -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' "/artifacts/$(basename "$d").jar" \;; \
       fi; done
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /artifacts/ /app/
USER 10001:10001
