# 19 · Clean code and code review practices

## 1. Principles, with examples from this repo

| Principle | In practice | Example |
|---|---|---|
| **Names reveal intent** | Domain language, no `Manager`/`Helper`/`Util` dumping grounds | `Order.cancel(reason)`, `OutboxRelay`, `InventoryPort.Availability.Unknown` |
| **Small, focused units (SRP)** | One reason to change per class | `OrderCommandService` (writes) vs `OrderQueryService` (reads) vs `OrderApplicationService` (orchestration) |
| **Make illegal states unrepresentable** | Types instead of comments and checks | Sealed `Availability`, `Money` that can't mix currencies, `OrderStatus` transitions guarded in `Order` |
| **Immutability by default** | Records, `final` fields, unmodifiable collections | DTOs/events as records; `order.getLines()` returns an unmodifiable view |
| **Fail fast** | Validate at the boundaries and at startup | Bean Validation on requests, `@Validated` config properties, Maven Enforcer |
| **Explicit dependencies** | Constructor injection only | ArchUnit `noFieldInjection` |
| **Dependency rule** | Inner layers know nothing about outer layers | Domain is framework-free (ArchUnit) |
| **No magic numbers or strings** | Named constants or configuration | `Messaging.Topics.ORDER_EVENTS`, `app.outbox.batch-size` |
| **Errors are part of the API** | Typed exceptions → Problem Details; expected outcomes as types | `GlobalExceptionHandler`, `IdempotencyService.Outcome` |
| **Comments explain *why*** | The code says *what* | Migration comments explain which query each index serves |
| **Tests are documentation** | Test names state behavior | `cancellingAShippedOrderFails`, `replaysACompletedIdempotentRequestWithoutCreatingAnotherOrder` |
| **Boy-scout rule** | Leave code better than you found it, in small separate commits | |

> This repo is a teaching project, so its comments are far more verbose than production code should
> be. In real code, explain *why* (constraints, trade-offs, links to tickets/ADRs) and let names carry the *what*.

## 2. Java and Spring specifics

* Prefer records for data, sealed interfaces for closed variants, `switch` patterns over `instanceof` chains.
* `Optional` only as a return type, never as a field or parameter. Don't call `get()` without a check.
* Null-safety: `@NullMarked` packages (JSpecify) so `null` must be declared with `@Nullable`.
* Use `BigDecimal` (with explicit scale and rounding) or a `Money` type for money. Never `double`.
* Use `java.time` with an injected `Clock`; never `new Date()` or `LocalDateTime.now()` in logic.
* `@Transactional` on application services, not controllers or repositories; keep transactions short.
* Don't catch `Exception` to log and continue; let it propagate to a handler that knows what to do.
* Log with placeholders (`log.info("Order {} placed", id)`), at the right level, without PII or secrets.
* `@ConfigurationProperties` records instead of scattered `@Value`.
* Keep controllers thin: map HTTP ↔ commands/views, nothing else.

## 3. Code review: how

**For authors**
* Small MRs (< 400 changed lines) with one purpose. Refactorings separate from behavior changes.
* A description that explains *why*, how it was tested, risks, and screenshots/curl output when useful.
* Self-review the diff first; make the pipeline green before asking for review.

**For reviewers**
* Review within one working day; unblock first, polish second.
* Prefix comments by weight: **blocking**, *suggestion*, *nit*, *question*, *praise*.
* Ask questions instead of making accusations ("What happens if inventory times out here?").
* Automate the boring parts (formatting, lint, coverage, security scans) so humans focus on design and correctness.
* Approve when it's better than before and safe, not when it's how you'd have written it.

## 4. Code review checklist

**Correctness**
- [ ] Does it do what the ticket says, including edge cases (empty, null, duplicates, concurrency, time zones)?
- [ ] Are state transitions valid? Are invariants enforced in the domain, not only in the UI?
- [ ] Money: `BigDecimal`, currency checks, rounding mode explicit?

**Transactions and data**
- [ ] Transaction boundaries correct? No remote calls inside DB transactions?
- [ ] Self-invocation bypassing `@Transactional`/`@Cacheable`/`@Async`?
- [ ] Concurrency: optimistic/pessimistic locking where updates can race? Lock timeouts set?
- [ ] New queries: indexed? Checked with `EXPLAIN`? No N+1? Pagination (keyset for large sets)?
- [ ] Migrations: backward compatible with the running version (expand → migrate → contract)?
      `CONCURRENTLY` for indexes on big tables? Reversible plan?

**Messaging and integration**
- [ ] DB write + publish atomic (outbox)? Consumer idempotent? Poison messages go to a DLQ?
- [ ] Event schema change backward compatible? Key chosen for ordering?
- [ ] Timeouts on every remote call? Retries only for idempotent operations, with back-off and jitter?
- [ ] Fallback behavior sensible and honest?

**Security**
- [ ] Endpoint authorized (deny by default)? Object-level ownership checked (IDOR)?
- [ ] Input validated; no string-concatenated SQL/JPQL/Mongo queries?
- [ ] Secrets out of code and logs; PII masked?
- [ ] Error responses leak nothing (stack traces, SQL, internal ids)?
- [ ] New dependency: necessary, maintained, license OK, no known CVEs?

**Operability**
- [ ] Logs useful for an on-call engineer at 3 a.m.? Metrics for new flows? Trace context propagated?
- [ ] Config externalized with safe defaults? Feature flag for risky changes?
- [ ] Health checks updated for new dependencies (readiness, not liveness)?

**Tests and design**
- [ ] Tests cover the behavior and failure paths, at the lowest effective level of the pyramid?
- [ ] Tests deterministic (fixed `Clock`, Awaitility, no sleeps, no order dependence)?
- [ ] Architecture rules respected (ArchUnit green)? No new package cycles?
- [ ] Is it the simplest design that works? Is any abstraction speculative (YAGNI)?
- [ ] Public API/contract changes documented (OpenAPI, proto, GraphQL schema, ADR)?

## 5. Tooling that makes reviews cheaper

| Concern | Tool |
|---|---|
| Formatting | `.editorconfig` (in this repo); Spotless / google-java-format / palantir-java-format |
| Static analysis | `-Xlint:all` (enabled), Error Prone, SpotBugs, PMD, SonarQube (`quality:sonar`) |
| Null-safety | JSpecify annotations + NullAway |
| Architecture | ArchUnit (`ArchitectureTest`) |
| Security | GitLab SAST, Secret Detection, Dependency Scanning, Trivy, OWASP dependency-check |
| Coverage | JaCoCo + MR coverage visualization |
| Ownership | `CODEOWNERS` with required approvals |
| History | Conventional Commits (`feat:`, `fix:`, `refactor:`), ADRs for decisions ([docs/adr](adr)) |
