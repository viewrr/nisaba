# Stage 1: Build the native image
FROM ghcr.io/graalvm/native-image-community:21 AS builder

WORKDIR /app

# Install required tools
RUN microdnf install -y findutils

# Copy Gradle wrapper and build files first (for better caching)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle.properties* ./

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src src
COPY nodes.yml nodes.yml

# Build the native image
RUN ./gradlew nativeCompile --no-daemon -x test

# Stage 2: Create minimal runtime image
FROM debian:bookworm-slim

WORKDIR /app

# Install minimal runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the native binary from builder
COPY --from=builder /app/build/native/nativeCompile/nisaba /app/nisaba

# Copy config files
COPY nodes.yml /app/nodes.yml

# Create non-root user
RUN useradd -r -u 1000 nisaba && chown -R nisaba:nisaba /app
USER nisaba

# Expose the default port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["/app/nisaba"]
