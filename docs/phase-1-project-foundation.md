# Phase 1 — Configure System so it runs

Get Spring Boot compiling and serving HTTP on port 8080. No business logic yet.

---

## Project Structure

```
├── pom.xml                          ← Maven project descriptor (project root, always)
├── docs/
│   └── phase-1-boot.md
└── src/
    ├── main/
    │   ├── java/main/application/
    │   │   └── Main.java            ← entrypoint
    │   └── resources/
    │       ├── application.yml      ← shared config
    │       ├── application-dev.yml  ← dev overrides
    │       └── application-prod.yml ← prod overrides
    └── test/java/                   ← empty for now
```

---

## Why Files Live Where They Do

Maven enforces a **Standard Directory Layout** — these paths are not configurable, they are baked in.

- `src/main/java/` — all production Java source. The directory path after this **must match the package declaration** in the file. `package main.application;` → file must be at `main/application/Main.java`.
- `src/main/resources/` — non-Java files (YAML, SQL, etc.). Maven copies these into `target/classes/`, which is on the classpath. Spring reads `application.yml` from the classpath, so it ends up there automatically.
- `target/` — Maven's output directory. Never edit it; it is in `.gitignore` because it is fully reproducible.

---

## How It Runs

```
mvn spring-boot:run
        ↓
Maven (JVM #1) reads pom.xml, compiles Main.java → target/classes/Main.class
        ↓
spring-boot-maven-plugin launches JVM #2 (your app)
        ↓
JVM calls main() → SpringApplication.run() → Tomcat starts on :8080
```

Your `.java` source is never executed directly. It is always compiled to `.class` bytecode first, then the JVM runs that.

---

## `pom.xml` Key Sections

**Parent POM** — inherits Spring Boot's dependency version management so you don't need to specify versions for most Spring libraries.

**Properties** — named version constants (`${mapstruct.version}` etc.) so upgrades are a one-line change.

**Dependencies:**

| Dependency | Purpose |
|---|---|
| `starter-web` | Embedded Tomcat, HTTP |
| `starter-data-jpa` | Hibernate ORM, HikariCP connection pool |
| `starter-validation` | `@NotNull`, `@Min` etc. on request models |
| `starter-websocket` | Real-time fraud alerts to clients |
| `starter-actuator` | `/actuator/health`, `/actuator/metrics` |
| `starter-security` | Locks all endpoints; requires auth |
| `jjwt-api/impl/jackson` | JWT creation and verification |
| `h2` (runtime) | In-memory DB for dev; no install needed |
| `jgrapht-core` | Graph algorithms for fraud ring detection |
| `resilience4j` | Circuit breaker for external service calls |
| `micrometer-prometheus` | Exports metrics to Prometheus/Grafana |
| `springdoc-openapi` | Auto-generates Swagger UI at `/swagger-ui.html` |
| `mapstruct` | Compile-time entity↔DTO mapping code generation |
| `lombok` | Compile-time `@Data`, `@Builder`, `@Slf4j` etc. |
| `jackson-datatype-jsr310` | Serialize Java date/time as ISO-8601 strings |
| `starter-test` + `security-test` + `awaitility` | JUnit 5, Mockito, async test helpers |

**Plugins:**

- `spring-boot-maven-plugin` — provides `mvn spring-boot:run`, packages executable fat JAR
- `maven-compiler-plugin` — the `annotationProcessorPaths` block is required so Lombok and MapStruct run at compile time in the right order (Lombok first, then MapStruct)

---

## Configuration Files

Spring loads `application.yml` always, then merges the active profile file on top.

**`application.yml`** — values that never change: fraud thresholds, queue size, thread pool size, Jackson settings.

**`application-dev.yml`** — H2 in-memory DB, H2 console, Swagger enabled, `ddl-auto: create-drop` (schema auto-created/dropped), DEBUG logging, all actuator endpoints open.

**`application-prod.yml`** — PostgreSQL via `${DB_URL}` env vars, JWT secret via `${JWT_SECRET}`, `ddl-auto: validate`, Swagger disabled, only health + prometheus exposed.

---

## Boot Output — Key Lines

| Line | Meaning |
|---|---|
| `profile is active: "dev"` | application-dev.yml loaded |
| `Found 0 JPA repository interfaces` | No entities yet — expected |
| `HikariPool-1 - Start completed` | DB connection pool ready |
| `H2 console available at '/h2-console'` | Browser SQL tool live |
| `Using generated security password: ...` | No auth configured yet; use `user` + this password |
| `Exposing 5 endpoint(s)` | Actuator endpoints live |
| `Started Main in 8.477 seconds` | App ready |

---

## How to Run

```bash
# Default (dev profile)
mvn spring-boot:run

# Package and run as JAR
mvn package -DskipTests
java -jar target/payment-fraud-detection-1.0.0.jar

# Production
java -jar target/payment-fraud-detection-1.0.0.jar --spring.profiles.active=prod
```

Verify: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
