# syntax=docker/dockerfile:1.7
# =============================================================================
#  ONE MULTI-STAGE DOCKERFILE FOR EVERY SERVICE
# =============================================================================
#  Build any module by passing its name:
#
#    docker build --build-arg MODULE=order-service -t showcase/order-service .
#
#  Advanced techniques used (each explained where it's used):
#    1. Multi-stage build      the JDK + Maven toolchain never reaches the runtime image
#    2. BuildKit cache mounts  ~/.m2 survives between builds without becoming an image layer
#    3. POM-first copy         dependency resolution is cached until a pom.xml changes
#    4. Spring Boot layers     dependencies / loader / snapshots / app copied as separate layers,
#                              so a code change re-ships only about 100 KB, not about 100 MB
#    5. CDS archive            Class Data Sharing training run: up to ~30-40% faster startup
#    6. Non-root user, exec-form ENTRYPOINT, container-aware JVM flags, OCI labels
#
#  Needs BuildKit (the default since Docker 23). Explained in detail in docs/16-docker.md.
# =============================================================================

ARG JAVA_VERSION=21

# -----------------------------------------------------------------------------
# Stage 1: BUILD. Compile and package the requested module (plus its in-repo dependencies, via -am).
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /workspace

# Copy ONLY the POMs first. Docker caches each layer by its inputs; source changes don't touch these
# files, so the expensive "download the internet" step below stays cached.
COPY pom.xml ./
COPY contracts/pom.xml            contracts/
COPY auth-server/pom.xml          auth-server/
COPY api-gateway/pom.xml          api-gateway/
COPY order-service/pom.xml        order-service/
COPY inventory-service/pom.xml    inventory-service/
COPY notification-service/pom.xml notification-service/

ARG MODULE
RUN test -n "$MODULE" || (echo "ERROR: pass --build-arg MODULE=<module-name>" && exit 1)

# --mount=type=cache keeps the local Maven repository OUTSIDE the image layers but persistent between
# builds on the same builder. "|| true": go-offline can't resolve everything (e.g. plugin deps chosen at
# runtime), and that's fine; the real build below fetches the rest.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl "${MODULE}" -am dependency:go-offline -DskipTests || true

# Now the sources. Only this layer and later ones rebuild on a code change.
COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl "${MODULE}" -am package -DskipTests \
 && cp "${MODULE}"/target/"${MODULE}"-*.jar /workspace/app.jar

# -----------------------------------------------------------------------------
# Stage 2: EXTRACT. Explode the fat jar into Spring Boot's layers.
#   Since Boot 3.3 the "tools" jar mode replaces "layertools" (which Boot 4.1 removed).
#   The result: dependencies/ spring-boot-loader/ snapshot-dependencies/ application/
# -----------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-noble AS extract
WORKDIR /extract
COPY --from=build /workspace/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# -----------------------------------------------------------------------------
# Stage 3: RUNTIME. A small JRE image, non-root, layered for cache efficiency.
#   Alternatives: gcr.io/distroless/java21-debian12 (no shell, smaller attack surface) or a custom
#   jlink runtime. We keep a shell so the compose healthcheck can use bash /dev/tcp.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-noble AS runtime

ARG MODULE
ARG VERSION=dev
ARG REVISION=unknown
# OCI image annotations: registries and scanners display these
LABEL org.opencontainers.image.title="${MODULE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.vendor="showcase" \
      org.opencontainers.image.source="https://gitlab.example.com/showcase/spring-boot-showcase"

# Never run as root: if the app is compromised, the attacker doesn't get root in the container.
# A fixed numeric UID lets Kubernetes enforce it (runAsNonRoot: true needs a numeric USER).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# Order = least to most frequently changing. Each COPY is its own layer, so a code change
# only invalidates the last one.
COPY --from=extract --chown=app:app /extract/extracted/dependencies/          ./
COPY --from=extract --chown=app:app /extract/extracted/spring-boot-loader/    ./
COPY --from=extract --chown=app:app /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extract/extracted/application/           ./

USER 10001

# CDS (Class Data Sharing) TRAINING RUN: start the app until the context refreshes, then dump the
# loaded classes into app.jsa. At runtime the JVM maps that archive instead of parsing and verifying
# thousands of classes again.
#   - spring.context.exit=onRefresh stops right after startup
#   - services that NEED a database at startup may fail the training run (no DB at build time). We
#     disable the obvious ones, and "|| true" keeps the build going. -Xshare:auto below simply ignores
#     a missing archive.
#   - On JDK 24+, prefer the AOT cache (JEP 483: -XX:AOTCacheOutput / -XX:AOTCache), which also stores
#     linked and initialized classes.
ARG ENABLE_CDS=true
RUN if [ "$ENABLE_CDS" = "true" ]; then \
      java -XX:ArchiveClassesAtExit=app.jsa \
           -Dspring.context.exit=onRefresh \
           -Dspring.profiles.active=cds-training \
           -jar app.jar > /tmp/cds.log 2>&1 || echo "CDS training run skipped (see /tmp/cds.log)"; \
    fi

# CONTAINER-AWARE JVM SETTINGS
#   MaxRAMPercentage=75    the heap is sized from the CONTAINER memory limit, not the host's RAM
#   ExitOnOutOfMemoryError crash fast on OOM and let the orchestrator restart us, instead of
#                          limping along in a broken state
#   JAVA_TOOL_OPTIONS is read by the JVM itself, so it also applies to the CDS run and debugging tools.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC" \
    SPRING_MAIN_BANNER_MODE=off

EXPOSE 8080

# EXEC FORM (a JSON array) makes java PID 1, so it receives SIGTERM directly and Spring Boot's graceful
# shutdown runs (in-flight requests finish, Kafka consumers commit). The shell form ("java -jar ...")
# would wrap java in /bin/sh, which does NOT forward signals: you'd get a hard SIGKILL after the grace
# period.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-Xshare:auto", "-jar", "app.jar"]
