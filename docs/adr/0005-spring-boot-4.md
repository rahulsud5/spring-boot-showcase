# 0005 · Target Spring Boot 4 / Spring Framework 7 on Java 21

* **Status:** Accepted
* **Date:** 2026-11-01

## Context
Spring Boot 4.0 (November 2025) and 4.1 (2026) are the current generation, built on Spring Framework 7,
Jakarta EE 11, Jackson 3 and Hibernate 7. Spring Boot 3.5 is the last 3.x line. The project should show
current practice and the differences from the Spring 6 generation.

## Decision
Use **Spring Boot 4.1.x**, **Spring Cloud 2025.1**, **Spring Cloud AWS 4.x**, on **Java 21 (LTS)**.
Use Framework 7 features where they replace libraries or boilerplate: API versioning, HTTP service
registry, built-in `@Retryable`/`@ConcurrencyLimit`, `BeanRegistrar`, JSpecify null-safety; and Boot 4.1's
gRPC auto-configuration.

## Consequences
* ✅ Fewer third-party libraries (spring-retry, OpenFeign not needed); longer support window.
* ⚠️ Some ecosystem libraries lag behind major versions; check compatibility (springdoc 3.x,
  Testcontainers 2.x, ArchUnit) before upgrading.
* ⚠️ Migration costs documented in [docs/02](../02-spring-boot-4-and-framework-7.md) and
  [docs/20](../20-lessons-learned.md) (renamed starters, moved packages and properties, Jackson 3).
* Java 25 (LTS) can be adopted by changing `java.version`; JDK 24+ also removes most virtual-thread pinning.
