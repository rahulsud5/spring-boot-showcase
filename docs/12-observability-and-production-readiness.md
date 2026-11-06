# 12 · Observability, Actuator and production readiness

## 1. The three signals (plus one)

| Signal | Question it answers | Stack here | Spring piece |
|---|---|---|---|
| **Metrics** | *Is* something wrong? How much, how often? | Micrometer → OTLP / Prometheus → Grafana | `MeterRegistry`, `@Observed`, `/actuator/prometheus` |
| **Traces** | *Where* is it slow or failing across services? | Micrometer Tracing → OpenTelemetry → Tempo | `spring-boot-starter-opentelemetry`, observation-enabled Kafka/Rabbit templates |
| **Logs** | *Why* exactly? | Structured JSON (ECS) → Loki | `logging.structured.format.console: ecs`, trace id in every line |
| Health | Should this instance get traffic / be restarted? | Actuator health groups → Kubernetes probes | `HealthIndicator`, probes |

Locally, `docker compose up` also starts `grafana/otel-lgtm` (Grafana + Loki + Tempo +
Prometheus/Mimir + an OpenTelemetry Collector). Grafana: http://localhost:3000. The services export with
the **standard** `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable, which Spring Boot 4.1 reads natively.

## 2. Micrometer Observation: one API, many signals

```java
@Observed(name = "orders.place", contextualName = "place-order")   // OrderApplicationService
public OrderDetails placeOrder(PlaceOrderCommand command) { ... }
```
One annotation produces a **timer** metric (`orders.place`, with percentiles) **and** a **span**
(`place-order`) in the current trace. Requires `management.observations.annotations.enabled=true`.

Automatically observed: HTTP server and client requests, JDBC (with the datasource-micrometer
integration), Kafka/RabbitMQ send and receive (`observation-enabled: true`), gRPC, `@Scheduled`,
Spring Data repositories, Redis (Lettuce).

**Trace propagation:** W3C `traceparent` headers over HTTP and gRPC metadata; Kafka and RabbitMQ
**message headers**. One trace covers gateway → order-service → (outbox relay span) → Kafka →
inventory-service → Kafka → order-service.

> The outbox breaks the in-process trace: the relay publishes later, on a different thread. To keep
> one trace, store the `traceparent` in the outbox row and restore it when publishing. This repo
> keeps the envelope's `eventId` + `aggregateId` for correlation instead (a documented trade-off).

## 3. Custom metrics in this repo

| Metric | Type | Where | Use |
|---|---|---|---|
| `orders.placed{currency}` | Counter | `OrderMetrics` | Business throughput (plot the rate) |
| `orders.placed.degraded` | Counter | `OrderMetrics` | Orders accepted without the inventory pre-check: alert on a spike |
| `orders.place` | Timer (via `@Observed`) | `OrderApplicationService` | Latency SLO |
| `outbox.backlog` | Gauge (`MeterBinder`) | `OutboxMetrics` | Kafka or relay problems; alert if > threshold for 5 min |
| `http.server.requests` | Timer + histogram | Auto | RED metrics per endpoint |
| `resilience4j.circuitbreaker.state` | Gauge | Auto | Breaker open alerts |
| `kafka.consumer.fetch.manager.records.lag.max` | Gauge | Auto | Consumer lag |
| `hikaricp.connections.pending` | Gauge | Auto | Pool saturation |
| `jvm.gc.pause`, `jvm.memory.used` | Timer/Gauge | Auto | JVM health |

**Cardinality:** never tag metrics with unbounded values (order id, user id, raw URL). Each unique tag
combination is a time series. Use `uri` templates (`/api/orders/{id}`), not actual paths.

**RED and USE:** for services, track Rate, Errors, Duration; for resources (pools, CPU, queues),
track Utilization, Saturation, Errors.

## 4. Actuator endpoints

```yaml
management.endpoints.web.exposure.include: health, info, prometheus, metrics, outbox, loggers, sbom
```

| Endpoint | Purpose | Access |
|---|---|---|
| `/actuator/health`, `/health/liveness`, `/health/readiness` | Probes and load balancers | Public (details only for OPS) |
| `/actuator/info` | Build info (version, git commit via `build-info` goal), Java, OS | Public |
| `/actuator/prometheus` | Scrape endpoint | Public on the management network |
| `/actuator/metrics/{name}` | Ad-hoc metric inspection | OPS |
| `/actuator/loggers/{name}` | **Change log levels at runtime** (POST) | OPS |
| `/actuator/sbom` | Software bill of materials (CycloneDX) | OPS |
| `/actuator/outbox` | **Custom endpoint**: GET backlog stats, POST trigger a relay run | OPS |

Never expose `heapdump`, `env`, `threaddump` or `configprops` publicly: they leak secrets and memory
contents. In production, consider a separate management port (`management.server.port`) reachable
only inside the cluster.

## 5. Health: liveness vs readiness

```yaml
management.endpoint.health:
  probes.enabled: true
  group:
    readiness.include: readinessState, db, redis, outbox   # dependencies → stop routing traffic
    liveness.include: livenessState                         # NOT the DB → no restart storms
  status:
    order: DOWN, OUT_OF_SERVICE, DEGRADED, UP, UNKNOWN
    http-mapping.DEGRADED: 200
```

* **Liveness failure → Kubernetes restarts the pod.** Only include things a restart fixes (deadlock,
  broken internal state). If the DB is down and liveness includes it, Kubernetes restarts every pod at
  once, which makes the outage worse.
* **Readiness failure → the pod is removed from the Service** (no traffic, no restart).
* **Custom status `DEGRADED`** (`OutboxHealthIndicator`): the outbox backlog exceeds the threshold. The
  service still works (HTTP 200) but dashboards and alerts see the problem.
* `startupProbe` gives the JVM time to start before liveness checks begin.

## 6. Production-readiness checklist

**Runtime**
- [x] Graceful shutdown (`server.shutdown=graceful`, `timeout-per-shutdown-phase`) + `preStop` sleep
      (lets the load balancer deregister the pod before the app stops accepting connections)
- [x] `terminationGracePeriodSeconds` > shutdown phase + preStop
- [x] Probes: startup, liveness, readiness (separate semantics)
- [x] Resource requests/limits; JVM sized from the container (`MaxRAMPercentage=75`)
- [x] HPA + PodDisruptionBudget; `maxUnavailable: 0` rolling updates
- [x] Non-root user, read-only root filesystem, dropped capabilities
- [x] Startup time: CDS archive in the image; consider AOT/native for scale-to-zero workloads

**Configuration and secrets**
- [x] Externalized config, validated `@ConfigurationProperties` (fail fast on bad config)
- [x] Secrets from files / AWS Secrets Manager, never in images or Git
- [x] Profiles for environments; no `localhost` defaults in production profiles

**Data**
- [x] Flyway migrations, `ddl-auto=validate`, online index builds (`CONCURRENTLY`)
- [x] Connection pool sized to DB capacity; statement and lock timeouts
- [x] Backups and restore drills (RDS automated backups, PITR)

**Operations**
- [x] Structured logs with trace ids; no PII in logs
- [x] Dashboards (RED per service, Kafka lag, outbox backlog, breakers)
- [x] Alerts on SLO burn rate rather than raw thresholds
- [x] Runbooks for each alert; build info in `/actuator/info` to know what's deployed
- [x] Runtime log-level changes via `/actuator/loggers`

## 7. SLOs

Example: "99.5% of `POST /api/orders` succeed in < 300 ms over 30 days" → error budget = 0.5% ≈ 3.6 h/month.
Compute it from the `http.server.requests` histogram:

```promql
sum(rate(http_server_requests_seconds_bucket{uri="/api/orders",method="POST",le="0.3",status!~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count{uri="/api/orders",method="POST"}[5m]))
```
Alert when the budget burns too fast (multi-window burn-rate alerts), not on every blip.
