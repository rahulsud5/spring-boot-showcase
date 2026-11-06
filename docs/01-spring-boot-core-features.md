# 01 · Spring Boot core features

Spring Boot = Spring Framework + **opinionated defaults** + **auto-configuration** + **production
features**. You write business code; Boot wires the infrastructure, until you override it.

## 1. Inversion of Control & dependency injection

The `ApplicationContext` creates objects ("beans"), injects their dependencies and manages their
lifecycle. This project uses **constructor injection everywhere** (an ArchUnit rule forbids field
injection):

```java
@Service
public class OrderCommandService {
    private final OrderRepository orders;          // final: immutable, can't be null after construction
    public OrderCommandService(OrderRepository orders, IntegrationEventPublisher events, AuditService audit) { ... }
}
```

Why constructor injection: dependencies are explicit, objects are always fully initialized, unit tests
just call `new`, and circular dependencies fail fast at startup.

**Bean scopes:** `singleton` (default, one per context), `prototype`, `request`, `session`. Singletons
must be thread-safe: keep them stateless (all collaborators `final`, no mutable fields).

**Stereotypes:** `@Component` (generic), `@Service` (application logic), `@Repository` (persistence;
adds exception translation to `DataAccessException`), `@Controller`/`@RestController` (web),
`@Configuration` (bean factories). Use `@Configuration(proxyBeanMethods = false)` when `@Bean` methods
don't call each other: it's cheaper and AOT/native-friendly.

## 2. Auto-configuration: how the "magic" works

1. `@SpringBootApplication` includes `@EnableAutoConfiguration`.
2. Boot reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` from every jar.
3. Each auto-configuration class is guarded by **conditions**:

| Condition | Meaning | Example in this repo |
|---|---|---|
| `@ConditionalOnClass` | A library is on the classpath | Hikari present → DataSource config activates |
| `@ConditionalOnMissingBean` | You didn't define your own | Our `RedisCacheManager` bean → Boot's cache manager backs off |
| `@ConditionalOnProperty` | A property enables it | `spring.threads.virtual.enabled=true` → virtual-thread executor |
| `@ConditionalOnWebApplication` | Servlet vs reactive | Gateway (reactive) vs order-service (servlet) |

**Debugging:** start with `--debug` (or `debug: true`) to print the *conditions evaluation report*,
or call `/actuator/conditions`. That's the answer to "why is (or isn't) this bean here?"

**Overriding** is usually one of: define your own bean (Boot backs off), set a property, or use a
`*Customizer` bean (adjusts Boot's bean without replacing it). Examples: `CacheConfig` replaces the
cache manager; `ResilienceConfig` customizes the circuit-breaker factory.

## 3. Starters

A starter is a dependency descriptor: one coordinate brings tested library versions + auto-configuration.
Boot 4 renamed several of them (`spring-boot-starter-webmvc`, `-aspectj`, `-security-oauth2-*`) and
added a test starter per technology. See [docs/02](02-spring-boot-4-and-framework-7.md).

## 4. Externalized configuration

Property sources in increasing precedence (later wins):

```
application.yml defaults  <  application-{profile}.yml / profile documents  <  OS env vars
  <  Java system properties  <  command-line args (--server.port=9090)
```

* **Relaxed binding:** `app.inventory.grpc-target` = `APP_INVENTORY_GRPCTARGET` = `APP_INVENTORY_GRPC_TARGET`.
* **Type-safe binding:** `@ConfigurationProperties` records (`AppProperties`) with `@Validated`, so bad
  configuration fails startup. Durations like `800ms` and `7d`, sizes like `10MB`.
* **Profiles:** `docker`, `kubernetes`, `demo`, `cds-training`, as multi-document YAML sections with
  `spring.config.activate.on-profile`.
* **Config trees:** `spring.config.import=optional:configtree:/run/secrets/`. Each file becomes a
  property (Docker/Kubernetes secrets). See `docker-compose.yml`, `order-service`.
* **Remote sources:** `spring.config.import=optional:aws-secretsmanager:/showcase/notification`
  (notification-service). Spring Cloud Config and Vault work the same way.

## 5. Embedded servers and the application lifecycle

* Tomcat (servlet, order-service) or Netty (reactive, gateway) run inside the jar. `java -jar app.jar`
  is all you need, and the jar is "fat" (nested jars + Boot's loader).
* **Graceful shutdown** (`server.shutdown=graceful`): stop accepting requests, finish in-flight ones
  within `spring.lifecycle.timeout-per-shutdown-phase`, stop Kafka consumers cleanly.
* **Availability states:** `LivenessState` and `ReadinessState`, exposed as `/actuator/health/liveness`
  and `/readiness`. You can publish a state change yourself, for example to mark the app "refusing
  traffic" while warming caches.

## 6. Developer experience

| Feature | What it does | Here |
|---|---|---|
| Docker Compose support | `spring-boot:run` starts `docker-compose.yml` and creates connection details from the containers | order-service pom + `spring.docker.compose.*` |
| Testcontainers at dev time | `TestOrderServiceApplication.main()` runs the app with containers | order-service tests |
| `@ServiceConnection` | Containers → connection beans, no property juggling | `TestcontainersConfiguration` |
| Configuration processor | IDE autocompletion for your `app.*` properties | order-service pom |
| DevTools | Restarts on classpath change, LiveReload | (add when needed) |
| Buildpacks | `mvn spring-boot:build-image`: OCI image without a Dockerfile | root pom `<image>` config |

## 7. Data access stack

* **Spring Data JPA:** repositories generated from interfaces: derived queries, `@Query`,
  projections, Specifications, `Window`/`ScrollPosition` keyset scrolling, auditing.
* **JdbcClient** (Spring 6.1+): fluent SQL for reports and housekeeping.
* **Spring Data MongoDB:** `MongoRepository`, `MongoTemplate`, aggregation pipeline.
* **Flyway:** versioned and repeatable migrations; Hibernate only *validates* (`ddl-auto=validate`).
* **Transactions:** `@Transactional`, `TransactionTemplate`, `@TransactionalEventListener`
  ([docs/04](04-transactions.md)).

## 8. Web stack

* `@RestController`, Bean Validation (`@Valid`, composed constraints like `@Sku`), Problem Details (RFC 9457).
* **API versioning** (Spring 7), **HTTP interface clients** (`@HttpExchange` + `@ImportHttpServices`).
* `RestClient` for imperative calls; `WebClient` for reactive ones.
* GraphQL (`@QueryMapping`, `@BatchMapping`) and gRPC (`@GrpcService`) beside REST.

## 9. Production features

Actuator health, metrics (Micrometer), tracing (OpenTelemetry), info (build + git), SBOM, loggers,
custom endpoints ([docs/12](12-observability-and-production-readiness.md)).
