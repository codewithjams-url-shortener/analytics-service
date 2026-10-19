# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

# The shared libs come from GitHub Packages, read with a token passed as BuildKit secrets so it never lands in an image
# layer: docker buildx build --secret id=github_actor,env=GITHUB_ACTOR --secret id=github_token,env=GITHUB_TOKEN ...
RUN --mount=type=secret,id=github_actor \
    --mount=type=secret,id=github_token \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew bootJar --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 5235
ENTRYPOINT ["java", "-jar", "app.jar"]
