# ================================================================
# Multi-stage Dockerfile — layered jar for fast Docker builds.
# Layer order: dependencies (rarely change) → app code (changes often)
# This means re-builds after code changes only re-push the last layer.
# ================================================================

# Stage 1: Extract layers from the fat jar
FROM eclipse-temurin:17-jre-alpine AS builder
WORKDIR /app
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Security: run as non-root user
RUN addgroup -S raggroup && adduser -S raguser -G raggroup

# Copy layers in order of change frequency (least → most)
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# Log directory — must create before switching to non-root user
RUN mkdir -p /var/log/rag && chown raguser:raggroup /var/log/rag

USER raguser

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]