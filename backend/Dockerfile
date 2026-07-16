# Multi-stage: a JDK compiles, a JRE runs. The runtime image ends up with no compiler,
# no Maven and no source, which makes it smaller and leaves less on it to attack.

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# The pom on its own first. Docker caches each layer, so dependency downloads are only
# repeated when the pom actually changes. Editing a Java file then rebuilds in seconds
# instead of fetching the internet again.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# Tests are skipped here, and that is not me avoiding them. They need a real PostgreSQL
# and a real Redis, and neither exists inside a build container. They run on my machine
# against docker compose before an image is ever built, which is the only place they can
# prove anything.
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
# NOT the -alpine variant, which is the tempting choice because it is half the size.
# Temurin publishes 17-jre-alpine for amd64 only, so it cannot run on the arm64 VM this
# deploys to. I checked the manifest rather than finding out on the server:
#   docker manifest inspect eclipse-temurin:17-jre-alpine
# This tag is multi-arch and runs on both my machine and the VM.
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl is here only so the production compose file can ask /actuator/health whether the
# app is really up. The base image ships neither curl nor wget, and a healthcheck that
# only proves the port is open would report a backend that cannot reach its database as
# healthy, which is the opposite of the point.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# A fixed name, so this stage does not need to know the project's version.
COPY --from=build /build/target/cryptopal-backend-*.jar app.jar

# Run as a user that owns nothing. If anything ever escapes the JVM, it lands somewhere
# with no rights rather than as root.
RUN useradd --system --no-create-home cryptopal
USER cryptopal

EXPOSE 8080

# No .env and no secrets in the image. Every value arrives from the environment at run
# time, which is what lets the same image run on my machine and on the server.
ENTRYPOINT ["java", "-jar", "app.jar"]
