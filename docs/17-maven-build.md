# 17 · The Maven build, explained

## 1. Multi-module layout

```
spring-boot-showcase (pom, parent = spring-boot-starter-parent 4.1.2)
├── contracts             (jar)  events + generated protobuf/gRPC classes, NOT a Boot app
├── auth-server           (Boot app)
├── api-gateway           (Boot app, reactive)
├── order-service         (Boot app)  depends on contracts
├── inventory-service     (Boot app)  depends on contracts
└── notification-service  (Boot app)  depends on contracts
```

* Maven computes the **reactor build order** from inter-module dependencies, not from the `<module>` order.
  `contracts` always builds first.
* Children inherit `<properties>`, `<dependencyManagement>`, `<pluginManagement>` and `<plugins>`.
  They declare dependencies **without versions**.

## 2. Inheritance vs import

```xml
<parent>                                                  <!-- INHERIT: plugin config, Java version, BOM -->
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.2</version>
</parent>

<dependencyManagement><dependencies>                      <!-- IMPORT more BOMs (versions only) -->
  <dependency>
    <groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-dependencies</artifactId>
    <version>${spring-cloud.version}</version><type>pom</type><scope>import</scope>
  </dependency>
  ... spring-cloud-aws-dependencies, our own modules, springdoc, archunit
</dependencies></dependencyManagement>
```

* **A BOM (bill of materials)** is a POM with only `<dependencyManagement>`: a curated set of versions
  tested together. The Spring Boot BOM manages ~400 libraries. Override one with a property
  (e.g. `<kafka.version>`) only if you must.
* If your company has its own parent POM, import `spring-boot-dependencies` as a BOM instead and
  configure the Boot plugins yourself.
* **`dependencyManagement` doesn't add dependencies**; it only fixes versions. `dependencies` adds them.
  The same split exists for `pluginManagement` (templates) vs `plugins` (active).

## 3. Dependency scopes used

| Scope | On compile classpath | At runtime | In the jar | Example |
|---|---|---|---|---|
| `compile` (default) | ✓ | ✓ | ✓ | starters |
| `runtime` | ✗ | ✓ | ✓ | `postgresql` JDBC driver (code must not depend on driver classes) |
| `provided` | ✓ | ✗ (container provides it) | ✗ | rarely with Boot |
| `test` | test only | test only | ✗ | `spring-boot-starter-*-test`, Testcontainers, ArchUnit |
| `optional` | ✓ | ✓ | ✓, not transitive | `spring-boot-docker-compose`, `spring-boot-configuration-processor` |

## 4. Plugins and the lifecycle

```
validate ─▶ compile ─▶ test ─▶ package ─▶ integration-test ─▶ verify ─▶ install ─▶ deploy
   │           │         │        │              │               │
 enforcer   compiler  surefire   jar +        failsafe       failsafe:verify,
            protobuf   (*Test)   spring-boot:  (*IT)          jacoco report, jacoco:check (-Pci)
            (contracts)          repackage,
                                 build-info
```

| Plugin | Configuration here | Why |
|---|---|---|
| `maven-enforcer-plugin` | Java ≥ 21, Maven ≥ 3.9, no duplicate declarations, banned deps (JUnit 4, Log4j 1, commons-logging < 1.3) | Fail fast with a clear message instead of strange errors later |
| `maven-compiler-plugin` | `release 21`, `-Xlint:all,-processing,-serial`, `-parameters` (from the Boot parent) | Warnings are code review at compile time; `-parameters` lets Spring bind `@PathVariable` etc. by name |
| `protobuf-maven-plugin` + `os-maven-plugin` | Downloads `protoc` and the gRPC plugin for the current OS/arch; `@generated=omit` | Code generation from `.proto` at build time, never checked in |
| `maven-surefire-plugin` | JUnit 5 parallel execution: classes concurrent, methods in a class sequential | Faster unit tests without flaky intra-class sharing |
| `maven-failsafe-plugin` | Runs `*IT` classes; `verify` fails the build *after* cleanup | Integration tests separated from fast tests |
| `jacoco-maven-plugin` | `prepare-agent` + `report`; `check` ≥ 70 % lines in `-Pci` | Coverage visible in MRs, a quality floor |
| `spring-boot-maven-plugin` | `repackage` (executable jar), `build-info` (→ `/actuator/info`), buildpack image settings with CDS | Runnable, traceable artifacts |
| `dependency-check-maven` (`-Psecurity`) | Fails on CVSS ≥ 7, HTML + JSON reports | Known-vulnerability scan (nightly in CI) |

## 5. Profiles

| Profile | Activates | Use |
|---|---|---|
| `ci` | Coverage gate | Always in CI (`MAVEN_CLI_OPTS` contains `-Pci`) |
| `security` | OWASP dependency-check | Nightly pipeline (needs an NVD API key) |
| `fast` | `skipITs=true` | Local builds without Docker |
| `native` (from the Boot parent) | GraalVM AOT + native-image | `mvn -Pnative native:compile -pl order-service` |

## 6. Everyday commands

```bash
mvn verify                                    # everything: unit + integration tests (Docker needed)
mvn verify -DskipITs                          # no containers (what was run to verify this repo)
mvn -T 1C verify                              # parallel module build, one thread per core
mvn -pl order-service -am verify              # one module + what it depends on (-am = also-make)
mvn -pl contracts -amd verify                 # a module + everything depending on it (-amd)
mvn -Dtest=OrderControllerTest test -pl order-service   # one test class
mvn dependency:tree -pl order-service -Dincludes=com.fasterxml.jackson.core   # who pulls Jackson 2?
mvn dependency:analyze -pl order-service      # used-but-undeclared / declared-but-unused deps
mvn versions:display-dependency-updates       # available upgrades
mvn help:effective-pom -pl order-service      # the fully merged POM (inheritance resolved)
mvn spring-boot:run -pl order-service -Dspring-boot.run.profiles=demo
mvn spring-boot:build-image -pl order-service # OCI image via buildpacks
```

## 7. Good practices

* **One version per library across modules** (enforced by the BOM and `dependencyManagement`).
* **No `SNAPSHOT` dependencies on release branches**; `mvn versions:set -DnewVersion=1.4.0` for releases
  (or CI-friendly versions with `${revision}` + `flatten-maven-plugin`).
* **Reproducible builds:** pin plugin versions (the Boot parent does for most), set
  `project.build.outputTimestamp` for byte-identical jars.
* **Maven Wrapper** (`mvn wrapper:wrapper`) pins the Maven version for every developer and CI.
* Keep the build offline-capable and cacheable: CI caches `.m2/repository` keyed by the POM
  ([docs/18](18-gitlab-ci.md)); the Dockerfile uses a BuildKit cache mount.
* Corporate networks: configure mirrors and proxies in `~/.m2/settings.xml`, not in the POM.
