# 16 · Docker: images and Compose, advanced configuration

## 1. The Dockerfile (one file for all five services)

```bash
docker build --build-arg MODULE=order-service -t showcase/order-service .
```

```
stage "build"    maven:3.9-eclipse-temurin-21
  ├─ COPY all pom.xml files first ───────▶ the dependency layer is cached until a POM changes
  ├─ RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline
  ├─ COPY sources; mvn -pl $MODULE -am package
stage "extract"  eclipse-temurin:21-jre-noble
  └─ java -Djarmode=tools -jar app.jar extract --layers   (Boot 4.1: "tools" replaces "layertools")
stage "runtime"  eclipse-temurin:21-jre-noble (JRE only: no compiler, no Maven, smaller attack surface)
  ├─ non-root user uid 10001
  ├─ COPY layers in order of change frequency: dependencies → loader → snapshot deps → application
  ├─ CDS training run → app.jsa (class data sharing archive)
  └─ ENTRYPOINT java -XX:SharedArchiveFile=app.jsa -Xshare:auto -jar app.jar
```

### Techniques and why they matter

| Technique | Benefit |
|---|---|
| **Multi-stage build** | The final image has no JDK, Maven, sources or `~/.m2`. Smaller and safer |
| **POM-first copy** | Changing Java code doesn't invalidate the dependency download layer |
| **BuildKit cache mount** (`--mount=type=cache`) | The Maven repository persists across builds without ending up in an image layer |
| **Layered jar** | A code change pushes/pulls only the small `application` layer (KBs), not 100+ MB of dependencies |
| **Non-root user** (`USER 10001`, numeric) | Kubernetes `runAsNonRoot` can verify a numeric UID; a container escape doesn't start as root |
| **CDS archive** | Pre-parsed JDK + app classes: typically noticeably faster startup and lower memory. Training uses profile `cds-training` with `spring.context.exit=onRefresh` (start the context, then exit) |
| **`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`** | The JVM sizes its heap from the container memory limit (leave room for metaspace, threads, direct buffers) |
| **`-XX:+ExitOnOutOfMemoryError`** | A broken JVM dies and gets restarted instead of limping |
| **OCI labels** (`org.opencontainers.image.*`) | Traceability from image back to the commit |
| **Exec-form ENTRYPOINT** | Java is PID 1 and receives SIGTERM, so graceful shutdown works |
| **`.dockerignore`** | Keeps `target/`, `.git`, secrets and IDE files out of the build context |

### Alternatives

* **Buildpacks:** `mvn -pl order-service spring-boot:build-image` builds an OCI image without a Dockerfile
  (configured in the root pom). Good defaults, less control.
* **Jib:** daemonless image builds from Maven, no Dockerfile.
* **GraalVM native image:** `-Pnative` with `native-maven-plugin`. Starts in milliseconds, uses little
  memory, but has a long build and closed-world constraints (reflection hints). Spring's AOT engine
  (and `BeanRegistrar` instead of reflection-heavy registration) helps.
* **Distroless / Chiseled JRE** base images for an even smaller attack surface (no shell, so no
  bash-based healthchecks).

## 2. Docker Compose (`docker-compose.yml`)

```bash
cp docker/secrets/postgres_password.example.txt docker/secrets/postgres_password.txt
docker compose up -d                                   # infrastructure only (run services from the IDE)
docker compose --profile apps up -d --build            # + the 5 services
docker compose --profile apps --profile tools up -d    # + Kafka UI
```

| Feature | How it's used |
|---|---|
| **Extension fields + YAML anchors** | `x-java-service: &java-service` and `x-java-env` are merged into every app (`<<: *java-service`). Write the hardening once |
| **Profiles** | `apps` and `tools` are optional groups; plain `up` starts only infrastructure + Grafana |
| **Healthchecks + `depends_on: condition: service_healthy`** | Real startup ordering: order-service waits for Postgres, Redis, RabbitMQ and the auth server to be *healthy*, not just started |
| **Healthchecks without curl** | `bash /dev/tcp` sends a tiny HTTP request to `/actuator/health/readiness` |
| **Docker secrets → Spring configtree** | `/run/secrets/postgres_password` becomes a property via `spring.config.import=optional:configtree:/run/secrets/`; Postgres reads `POSTGRES_PASSWORD_FILE` |
| **Hardening** | `read_only: true` + `tmpfs: /tmp`, `cap_drop: [ALL]`, `no-new-privileges`, non-root image |
| **Resource limits** | `memory: 768M`, `cpus: 1.0`; the JVM heap follows via `MaxRAMPercentage` |
| **Networks** | `backend` for service-to-service; `edge` for ports published to the host |
| **Init container** | `kafka-init` creates topics with explicit partitions, then exits (`restart: "no"`) |
| **Mongo replica set** | `--replSet rs0`; the healthcheck initiates it idempotently and reports healthy once PRIMARY |
| **Kafka KRaft** | Kafka 4 has no ZooKeeper; two advertised listeners (`kafka:29092` for containers, `localhost:9092` for the IDE) |
| **Tuned infrastructure** | Postgres with `pg_stat_statements` and `log_min_duration_statement=250`; Redis AOF + `allkeys-lru` |
| **Named volumes** | Data survives `docker compose down` (use `down -v` to wipe it) |

### Spring Boot's Docker Compose integration

With `spring-boot-docker-compose` on the classpath (order-service, `optional` scope), running the app
from the IDE **starts `docker-compose.yml` automatically** and creates `ConnectionDetails` beans from
the running containers, with no hard-coded ports. `lifecycle-management: start-only` leaves containers
running between restarts.

## 3. Debugging containers

```bash
docker compose ps                          # health state of every service
docker compose logs -f order-service       # JSON logs (ECS)
docker compose exec postgres psql -U orders -d orders
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
docker stats                               # live CPU/memory vs limits
docker run --rm -it --entrypoint bash showcase/order-service   # inspect an image
```

JVM diagnostics inside a running container: `jcmd 1 VM.flags`, `jcmd 1 Thread.print`,
`jcmd 1 GC.heap_info` (the JRE image includes `jcmd`).

## 4. From Compose to Kubernetes

The `deploy/k8s` manifests carry the same ideas over: probes = healthchecks, `securityContext` = the
Compose hardening, resource limits, Secrets mounted as files (configtree), plus PodDisruptionBudgets,
HPAs and IRSA service accounts. Kustomize overlays (`staging`, `production`) change replicas, image
tags and resources per environment.
