# tx-sieve: Real-Time Payment Fraud Detection System

Fraud detection at transaction volume is an unsolved engineering problem 
for most backend systems. Rule-based checks miss coordinated patterns. 
Graph traversal on every payment saturates CPU. ML models add latency 
that breaks real-time guarantees.

This platform addresses all three: a cost-sensitive rule pipeline that 
short-circuits on early signals, structural graph anomaly detection on a 
precomputed volatile transaction graph — circular flow, hub-node degree, 
connected-component cluster analysis — and a GradientBoosting ML 
microservice decoupled behind a circuit breaker. Transactions are 
processed asynchronously through a bounded queue with explicit 
back-pressure, fraud signals broadcast in real time over WebSocket, 
every rule trigger and analysis latency exposed as a Micrometer metric.

The backend is grounded in rigorous data modeling: normalized JPA entities, 
custom JPQL fraud queries with index-aware filtering, DTO separation across 
every API boundary, typed exception handling, and stateless JWT 
authentication with role-based access control. Designed for PostgreSQL 
in production with H2 for zero-dependency local development.

Built phase by phase, benchmarked with JMH, and documented at every step. 
Every architectural decision has a reason.

---

## Phase 0 — Three Questions I Answered Before Writing Anything

### 1. What does this system actually do?

A user submits a payment. The system decides in real time whether it's
fraud. The decision happens in milliseconds, not minutes.

- If fraud: block the transaction, save an alert, notify analysts instantly
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
fraud is detected. That means WebSocket push, not polling.

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
└── WebSocket broker (real-time analyst alerts)
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

Java handles transactions, security, and real-time alerting.
Python handles statistical anomaly detection.
They communicate over HTTP. If Python goes down, Java keeps running
with rule-based scoring only. Loose coupling by design.

---

## Architecture — The Full Picture

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
                                                + WebSocket broadcast
                                                + balance reversed
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
| **WebSocket / STOMP** | HTTP polling adds latency and wastes connections. STOMP push delivers alerts to dashboards the instant a transaction is blocked. |
| **Python FastAPI + GradientBoosting** | Rule-based systems catch known patterns. ML catches anomalies that don't match any written rule. Separate service so a Python crash never takes down the Java API. |
| **Micrometer + Prometheus** | Every fraud rule trigger, queue depth, and analysis duration is a metric. Fraud systems need tuning — you tune with data, not guesses. |
| **Resilience4j circuit breaker** | If the Python ML service is slow or down, the circuit opens and Java stops trying. Requests degrade gracefully instead of piling up waiting threads. |
| **Lombok + MapStruct** | Annotation processors — generate boilerplate at compile time, zero runtime overhead. Lombok handles getters/constructors, MapStruct handles entity↔DTO conversion. |
| **BigDecimal for amounts** | `double` loses pennies. 0.1 + 0.2 = 0.30000000000000004 in floating point. Banks are legally liable for rounding errors. |

---

## Build Order

I'm building this in the order that dependencies force — you can't write
a service before you have an entity, can't write an entity before you
have the project configured.

| Phase | What gets built |
|---|---|
| **0** | Architecture designed, no code yet |
| **1** ✅ | `pom.xml` + entry point + YAML config (dev/prod split) |
| **2** ✅ | Domain enums: `TransactionStatus`, `RiskLevel`, `FraudRuleType` |
| **3** ✅ | JPA entities: `User`, `AppUser`, `Transaction`, `FraudAlert`, `MerchantBlacklist`, `UserSession` |
| **4** ✅ | Repositories + custom fraud queries (`@Query`) |
| **5** ✅ | DTOs — request/response objects, never expose raw entities |
| **6** | Typed exceptions + `GlobalExceptionHandler` |
| **7** | JWT security: token provider, filter, `UserDetailsService` |
| **8** | Fraud engine: `FraudRule` interface, 5 rules, graph analysis, orchestrator |
| **9** | Services: `PaymentService`, `FraudAlertService`, `DashboardService` |
| **10** | Queue + worker pool: producer-consumer, back-pressure |
| **11** | WebSocket / STOMP real-time alert broadcast |
| **12** | Spring config classes: security, WebSocket, cache, async, OpenAPI |
| **13** | `DataSeeder` — demo data on startup |
| **14** | REST controllers — thin layer, no business logic |
| **15** | Python FastAPI ML service + GradientBoosting model |
| **16** | `MlFraudRule` — Java `<->` Python HTTP integration |
| **17** | Docker + Docker Compose — both services wired with health checks |
| **18** | Tests: unit (rules, engine, service) + integration (controller) |

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
