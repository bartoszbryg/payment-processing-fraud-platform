# Real-Time Payment Fraud Detection System

Most backend projects I find online stop where the interesting problems
start. This one doesn't.

Payment fraud detection: high volume, asynchronous decisions, JWT security,
rule-based and graph-based analysis. Built phase by phase, design
decisions documented as they're made.

---

## Phase 0 — Three Questions I Answered Before Writing Anything

### 1. What does this system actually do?

A user submits a payment. The system decides in real time whether it's
fraud. The decision happens in milliseconds, not minutes.

- If fraud: block the transaction and save an alert
- If clean: process it, transfer the balance

That's it. Everything in this repo exists to make that one flow work
correctly, securely, and at scale.

### 2. What are the hard constraints?

These aren't preferences — they're requirements the architecture must
satisfy from day one:

**High volume** — payments come in bursts. The system can't block the
HTTP thread while analyzing fraud. Needs a queue and async workers.

**Real-time** — fraud windows are minutes wide. A daily email report
is useless. Analysts need a live dashboard that updates the moment
fraud is detected. WebSocket push is planned for the next phase; the
current phase persists alerts and exposes dashboard stats.

**Secure** — this is a banking system. Every endpoint except login
requires authentication. JWT tokens, stateless, role-based access.
Customers and system operators are separate security principals —
conflating them is a design flaw.

**Observable** — you can't tune what you can't measure. Queue depth,
rule trigger counts, analysis latency, fraud rate — all exposed as
Prometheus metrics from day one.

**Explainable** — a block decision without a reason is legally
insufficient in banking. Every fraud rule that fires must leave a
typed, auditable record of what it found and why.

### 3. What are the moving parts?

```
Java Spring Boot
└── REST API (payments, users, alerts, dashboard)
└── JWT security layer
└── Fraud detection engine (rule-based + graph-based)
└── Bounded queue + worker thread pool
└── WebSocket broker (Phase 11, real-time analyst alerts)
└── Prometheus metrics + Spring Actuator

Python FastAPI (Phase 15)
└── Statistical ML model (GradientBoosting)
└── Feature engineering
└── /score endpoint — returns fraud probability 0.0–1.0

H2 in-memory database (dev) / PostgreSQL (prod)

Docker Compose (Phase 17)
└── Wires both services together
└── Health checks before Java starts
```

Java currently handles transactions, security, fraud rules, graph analysis,
and the async worker pipeline. Python statistical anomaly detection is a
later phase. When it is wired in, Java will keep running with rule-based
scoring if Python is unavailable. Loose coupling by design.

---

## Architecture — Target Picture

```
User submits payment (HTTP POST, JWT authenticated)
        │
        ▼
PaymentService validates: sender exists, balance sufficient
        │
        ▼
Transaction saved with status PENDING
        │
        ▼
Enqueued to LinkedBlockingQueue (capacity: 10,000)
API thread returns immediately — async from here
        │
        ▼
Worker pool (4 threads) picks up transaction
        │
        ▼
FraudDetectionEngine runs all rules in priority order
        │
        ├── HighAmountRule          > $10k warning / > $50k critical
        ├── RapidTransactionRule    3+ transactions in 5 minutes
        ├── VelocityBreachRule      daily > $20k or hourly > $5k
        ├── BlacklistedMerchantRule merchant on known-bad list
        ├── UnusualLocationRule     country differs from home
        ├── GraphFraudRule          cycle detection via JGraphT
        └── MlFraudRule             Python model probability ≥ 0.4
        │
        ▼
Scores accumulate (capped at 100)
        │
        ├── score < 40   → APPROVED
        ├── score 40–69  → FLAGGED FOR REVIEW  + alert saved
        └── score ≥ 70   → BLOCKED             + alert saved
                                                + WebSocket broadcast (Phase 11)
                                                + no balance deduction
```

---

## Why Each Technology Is Here

| Technology | The reason |
|---|---|
| **Java 21 + Spring Boot 3.2** | Production standard for financial backends. Virtual threads available. Spring Boot keeps config in YAML, not boilerplate. |
| **JWT (stateless auth)** | No server-side session store. Every request is self-verifying. Required for horizontal scaling. |
| **H2 (dev) / PostgreSQL (prod)** | H2 needs zero setup — the app boots with no external dependencies. Production swaps via environment variable, no code change. |
| **LinkedBlockingQueue** | Simulates Kafka without the infrastructure. Bounded capacity means back-pressure is explicit: queue full = 503, not a crash. |
| **JGraphT** | Rule-based scoring looks at transactions individually. A graph finds coordinated fraud — money laundering rings, mule accounts — that individual rules miss entirely. |
| **WebSocket / STOMP** | Planned for Phase 11. HTTP polling adds latency and wastes connections; STOMP push will deliver alerts to dashboards the instant a transaction is blocked. |
| **Python FastAPI + GradientBoosting** | Planned for Phase 15. Rule-based systems catch known patterns. ML catches anomalies that don't match any written rule. Separate service so a Python crash never takes down the Java API. |
| **Micrometer + Prometheus** | Every fraud rule trigger, queue depth, and analysis duration is a metric. Fraud systems need tuning — you tune with data, not guesses. |
| **Resilience4j circuit breaker** | Planned for the ML integration phase. If the Python ML service is slow or down, the circuit opens and Java stops trying. |
| **Lombok + MapStruct** | Annotation processors generate boilerplate at compile time, zero runtime overhead. Lombok handles getters/constructors now; MapStruct is available if mapping grows beyond the current plain `TransactionMapper`. |
| **BigDecimal for amounts** | `double` loses pennies. 0.1 + 0.2 = 0.30000000000000004 in floating point. Banks are legally liable for rounding errors. |

---

## Build Order

I'm building this in the order that dependencies force — you can't write
a service before you have an entity, can't write an entity before you
have the project configured.

| Phase | What gets built | Docs |
|---|---|---|
| **0** | Architecture designed, no code yet | |
| **1** ✅ | `pom.xml` + entry point + YAML config (dev/prod split) | [docs](docs/phase-01-project-foundation.md) |
| **2** ✅ | Domain enums: `TransactionStatus`, `RiskLevel`, `FraudRuleType` | [docs](docs/phase-02-project-enums-creation-for-states.md) |
| **3** ✅ | JPA entities: `User`, `AppUser`, `Transaction`, `FraudAlert`, `MerchantBlacklist`, `UserSession` | [docs](docs/phase-03-project-jpa-entities.md) |
| **4** ✅ | Repositories + custom fraud queries (`@Query`) | [docs](docs/phase-04-project-repository-for-jpa.md) |
| **5** ✅ | DTOs — request/response objects, never expose raw entities | [docs](docs/phase-05-project-dto-mapping.md) |
| **6** ✅ | Typed exceptions + `GlobalExceptionHandler` | [docs](docs/phase-06-project-exception-handling.md) |
| **7** ✅ | JWT security: token provider, filter, `UserDetailsService` | [docs](docs/phase-07-project-security-handling.md) |
| **8** ✅ | Fraud engine: `FraudRule` interface, 5 rules, graph analysis, orchestrator | [docs](docs/phase-08-project-fraud-detection-engine.md) |
| **9** ✅ | Services: `AuthService`, `UserService`, `PaymentService`, `FraudAlertService`, `DashboardService`, `MlFraudScoringService`, `TransactionMapper` | [docs](docs/phase-09-project-service-layer.md) |
| **10** ✅ | Queue + worker pool: `TransactionQueue`, `TransactionWorkerPool`, fraud outcome routing | [docs](docs/phase-10-project-queue-worker-pool.md) |
| **11** ✅ | WebSocket / STOMP real-time alert broadcast | [docs](docs/phase-11-project-websocket-broadcasting.md) |
| **12** | Spring config classes: security, WebSocket, cache, async, OpenAPI | |
| **13** | `DataSeeder` — demo data on startup | |
| **14** | REST controllers — thin layer, no business logic | |
| **15** | Python FastAPI ML service + GradientBoosting model | |
| **16** | `MlFraudRule` — Java `<->` Python HTTP integration | |
| **17** | Docker + Docker Compose — both services wired with health checks | |
| **18** | Tests: unit (rules, engine, service) + integration (controller) | |

Each phase tag links to the repo at that exact stage. You can browse
the code before any service layer existed, before security was added,
before the queue existed — the full construction sequence is visible.

---

## What This Teaches

Every architectural decision in this project exists because real banking
systems need it. This isn't a design I invented — it's the design the
constraints produce when you reason from first principles.

The queue exists because HTTP threads are expensive and fraud analysis
is slow. The graph exists because individual transaction rules are blind
to coordinated fraud. The separate Python service exists because ML
models and transactional APIs have different deployment lifecycles.
BigDecimal exists because float arithmetic is illegal in finance.

Understanding *why* a design decision exists is more useful than knowing
*how* to implement it. This project documents both.

