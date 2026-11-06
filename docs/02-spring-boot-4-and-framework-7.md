# 02 · Spring Boot 4 & Spring Framework 7: what's new

**Generations:** Spring Boot 3.x ⇢ Spring Framework 6.x (Nov 2022). Spring Boot 4.x ⇢ Spring Framework
7.x (Nov 2025). This repo uses **Boot 4.1.2 / Framework 7.0**. Boot 3.5 was the last 3.x line.

## Baselines

| | Boot 3 / Spring 6 | Boot 4 / Spring 7 |
|---|---|---|
| Java | 17+ | 17+ (Java 21/25 recommended; some features need 21, e.g. jOOQ 3.20 in Boot 4.1) |
| Jakarta EE | 9 → 10 | **11** (Servlet 6.1, JPA 3.2, Bean Validation 3.1) |
| Servlet containers | Tomcat 10, Jetty 11/12, Undertow | Tomcat 11, Jetty 12.1 (**Undertow dropped**) |
| JSON | Jackson 2 (`com.fasterxml.jackson`) | **Jackson 3** (`tools.jackson`), with Jackson 2 still supported as an option |
| Hibernate | 6.x | **7.x** |
| Null safety | Spring's own `@Nullable` | **JSpecify** (`@NullMarked`, `@Nullable`) across the portfolio |
| Kotlin | 1.9 | 2.2+ |
| Testcontainers | 1.x | **2.x** (new artifact names and packages) |

## Spring Framework 7 features used in this repo

| Feature | What it replaces | Where |
|---|---|---|
| **API versioning**: `@GetMapping(version = "2")` + `ApiVersionConfigurer` | Hand-rolled URL prefixes, header checks | `WebConfig`, `OrderController` |
| **`@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods`** in core | spring-retry library, custom semaphores | `CatalogPricingAdapter`, `OrderApplicationService`, `ResilienceConfig` |
| **HTTP Service Registry**: `@ImportHttpServices(group = ...)` | Manual `HttpServiceProxyFactory` wiring, OpenFeign | `HttpClientsConfig`, `CatalogClient` |
| **`BeanRegistrar`**: programmatic, AOT-friendly bean registration | `BeanDefinitionRegistryPostProcessor`, piles of `@Conditional` | `FraudScreeningRegistrar` |
| **JSpecify null-safety** | `org.springframework.lang.Nullable` | `package-info.java` with `@NullMarked`, `@Nullable` fields |
| **Commons Logging 1.3** directly (spring-jcl removed) | spring-jcl bridge | See [lessons learned](20-lessons-learned.md) (enforcer rule) |
| `RestTestClient` (MockMvc-style testing for `RestClient` setups), `MockMvcTester` (6.2) | `WebTestClient` for MVC apps | `MockMvcTester` in web tests |
| `@MockitoBean` / `@MockitoSpyBean` (6.2) | Boot's deprecated `@MockBean` | All slice tests |

## Spring Boot 4 changes that affect code and builds

1. **Modularized auto-configuration.** The monolithic `spring-boot-autoconfigure` jar was split into
   focused modules (`spring-boot-webmvc`, `spring-boot-data-jpa`, `spring-boot-kafka`,
   `spring-boot-health`...). Consequences you'll hit:
   * package moves, e.g. `HealthIndicator` → `org.springframework.boot.health.contributor`, and test
     slices such as `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`
   * functionality you used to get "for free" now needs its starter: **`spring-boot-starter-restclient`**
     for `RestClient` and HTTP-interface auto-configuration, **`spring-boot-starter-flyway`** for Flyway
2. **Starter renames:** `-web` → `-webmvc`, `-aop` → `-aspectj`, `-oauth2-resource-server` →
   `-security-oauth2-resource-server`, `-oauth2-authorization-server` →
   `-security-oauth2-authorization-server`. The old names are deprecated.
3. **A test starter per technology** (`spring-boot-starter-webmvc-test`, `-data-jpa-test`,
   `-security-test`, `-graphql-test`...).
4. **Property moves**, e.g. MongoDB connection settings `spring.data.mongodb.uri` → `spring.mongodb.uri`.
5. **OpenTelemetry starter** (`spring-boot-starter-opentelemetry`): OTLP export of traces, metrics and
   logs; Boot 4.1 also reads standard `OTEL_*` environment variables.
6. **Spring Authorization Server moved into Spring Security 7** (same coordinates, versioned with Security).
7. Async: Boot wraps `AsyncConfigurer` beans to fall back to its executor. Don't make a `@Configuration`
   with `@Bean` methods also implement `AsyncConfigurer` ([lessons learned](20-lessons-learned.md)).

## Spring Boot 4.1 additions (June 2026)

* **gRPC auto-configuration** in Boot itself (`spring-boot-starter-grpc-server/-client`; the Spring gRPC
  project keeps the programming model: `@GrpcService`, `GrpcChannelFactory`). Used by order- and
  inventory-service.
* HTTP-client **SSRF mitigation** with `InetAddressFilter`.
* Jackson read/write feature properties (`spring.jackson.read.*`, `spring.jackson.write.*`).
* Removal of APIs deprecated in 4.0, including the `layertools` jar mode. Use `-Djarmode=tools`
  (see the Dockerfile).

## Spring Cloud 2025.1 ("Oakwood")

* Gateway 5: new artifact `spring-cloud-starter-gateway-server-webflux` (or `-webmvc`) and property
  prefix `spring.cloud.gateway.server.webflux.*`.
* CircuitBreaker 5 (Resilience4j), Config 5, Kubernetes 5. Compatible with Boot 4.0.x/4.1.x.

## Migration checklist: Boot 3.x → 4.x

1. Upgrade to the latest **3.5.x** first and fix all deprecation warnings.
2. Java 17+ (21 recommended); Maven 3.9+ / Gradle 9.
3. Replace renamed starters; add `spring-boot-starter-restclient`, `-flyway`... where needed.
4. Jackson 3: update imports (`com.fasterxml.jackson.databind` → `tools.jackson.databind`), use
   `JsonMapper` (immutable, builder-based), and expect unchecked `JacksonException`. Some APIs were
   renamed (`JsonNode.asText()` → `asString()`).
5. Update test imports (`@MockBean` → `@MockitoBean`, slice packages), Testcontainers 2 artifacts.
6. Hibernate 7: re-check native queries, `@Type` usages and ID generators; run with `ddl-auto=validate`.
7. Spring Security 7: lambda DSL only (`.and()` is gone); the authorization server is now part of Security.
8. Check moved properties with `spring-boot-properties-migrator` (temporary dependency that logs renames).
9. Rebuild container images (`layertools` → `tools` jar mode).
