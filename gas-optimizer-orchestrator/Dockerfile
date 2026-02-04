# ============================================================
# Build Stage
# ============================================================
FROM gradle:8.14-jdk21 AS build

WORKDIR /home/gradle/project

# Copy Gradle wrapper and build files first (better caching)
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle gradlew .
COPY --chown=gradle:gradle build.gradle.kts .
COPY --chown=gradle:gradle settings.gradle.kts .

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY --chown=gradle:gradle src ./src

# Make gradlew executable
RUN chmod +x ./gradlew

# Build the application
RUN ./gradlew clean bootJar --no-daemon

# ============================================================
# Runtime Stage
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install Docker CLI (needed for docker compose commands)
# eclipse-temurin:21-jre is Ubuntu-based, so use Ubuntu repos
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin && \
    rm -rf /var/lib/apt/lists/*

# Create share directory mount point
RUN mkdir -p /share

# Copy the built jar
COPY --from=build /home/gradle/project/build/libs/*.jar /app/app.jar

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]