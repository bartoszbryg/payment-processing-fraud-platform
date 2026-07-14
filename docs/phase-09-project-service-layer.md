# Phase 9 — Service Layer

Services are where the logic actually lives. The wrong approach is putting business rules in controllers or leaking them into repositories because that makes every component impossible to test in isolation and impossible to reuse across call sites. The right approach is a dedicated service layer: controllers receive and delegate, repositories read and write, services own everything in between. That is what Phase 9 builds.

---

## What Was Added

```
├── src/
│   ├── main/java/main/
│   │   ├── config/
│   │   │   └── AppConfig.java              ← new: RestTemplate + requiresNewTransactionTemplate
│   │   └── service/
│   │       ├── AuthService.java
│   │       ├── DashboardService.java
│   │       ├── FraudAlertService.java
│   │       ├── MlFraudScoringService.java
│   │       ├── PaymentService.java
│   │       ├── TransactionMapper.java
│   │       └── UserService.java
│   └── test/java/main/
│       └── service/
│           ├── AuthServiceTest.java
│           ├── DashboardServiceTest.java
│           ├── FraudAlertServiceTest.java
│           ├── MlFraudScoringServiceTest.java
│           ├── PaymentServiceTest.java
│           ├── ServiceIntegrationTest.java
│           └── TransactionMapperTest.java
```

`CacheConfig` was also extended: the `"dashboardStats"` cache name was added alongside the existing `"merchantBlacklist"` entry. Caffeine strict mode requires all cache names to be declared upfront at construction time so undeclared names throw `IllegalArgumentException` at runtime rather than silently creating an unconfigured cache.

---

## The Services

### `AuthService`

Handles two operations: register and login.

Register creates a `User` and an `AppUser` in a single `@Transactional` boundary. Both entities are written together or neither is. The `AppUser.password` is stored as a BCrypt hash. The `AppUser.linkedUserId` ties the security principal to the domain user so JWTs resolved at the filter layer can reach the correct `User` record without a second lookup.

Login delegates credential verification to Spring's `AuthenticationManager`. There is no manual password comparison here because `DaoAuthenticationProvider` owns that responsibility. On success, `JwtTokenProvider.generateToken()` produces the signed token.

Phone numbers are masked in the register response via `maskPhone()`:

```java
private static String maskPhone(String phone) {
    if (phone == null || phone.length() < 9) return phone;
    return phone.substring(0, 5) + "***" + phone.substring(phone.length() - 4);
}
```

This produces `"+1415***2671"` rather than the raw number. See the fix section below for why this was not present initially.

---

### `UserService`

Five operations: `getUser`, `getAllUsers`, `getHighRiskUsers`, `getFlaggedUsers`, `deactivateUser`.

`getHighRiskUsers` accepts a threshold parameter from the controller rather than hardcoding one. The threshold belongs to the caller's context, not this service. `getFlaggedUsers` delegates to `UserRepository.findFlaggedUsers()` which filters by `riskScore` and `isFlagged` in SQL so no filtering happens in application memory.

`deactivateUser` sets `user.active = false` and saves. No cascade is needed because active status is checked at login time by `AppUserDetailsService.isEnabled()`. Sessions are automatically rejected on the next request because `JwtAuthenticationFilter` calls `loadUserByUsername()` on every request, not just once.

Phone numbers are masked in every `UserResponse` returned by this service for the same reason as `AuthService`. The `maskPhone()` implementation is duplicated between both services. This is a minor cleanup item, acknowledged but not urgent.

---

### `PaymentService`

The most complex service in this phase. Handles payment submission and all transaction queries.

`submitPayment` validates in this order: sender exists, sender is active, sender has sufficient balance, exactly one of `receiverId` or `merchant` is provided. It then builds and saves a `Transaction` with status `PENDING`, then calls `enqueueAfterCommit()`.

The queue enqueue must happen after the DB commit is durable. Enqueuing before commit means a worker could pick up the transaction ID, call `transactionRepository.findById()`, and get an empty result because the row is not yet visible outside the transaction. `TransactionSynchronizationManager.registerSynchronization()` defers the enqueue to `afterCommit()`:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        try {
            transactionQueue.enqueue(transaction);
        } catch (TransactionQueueFullException e) {
            log.warn("Queue full after commit — declining txn {}", transaction.getId());
            requiresNewTemplate.executeWithoutResult(status -> {
                transactionRepository.findById(transaction.getId()).ifPresent(t -> {
                    t.setStatus(TransactionStatus.DECLINED);
                    t.setDeclineReason("Payment gateway at capacity — please retry");
                    transactionRepository.save(t);
                });
            });
        }
    }
});
```

The queue-full recovery path and why `requiresNewTemplate` is required are covered in the fixes section below.

The response returns `TransactionStatus.PENDING` and `fraudScore: 0.0`. The actual score comes later, when the worker finishes analysis. In the current phase callers poll `GET /payments/{id}` to see the final outcome. WebSocket alert push is added later.

---

### `FraudAlertService`

Five operations: `getAlertsForTransaction`, `getAlertsForUser`, `getUnresolvedAlerts`, `getAlertsByRuleSince`, `resolveAlert`.

`resolveAlert` loads the alert by ID, sets `resolved = true` and `resolvedAt = Instant.now()`, then saves. The alert entity owns the resolved state so no join table is needed.

Exception handling uses the established factory convention. See the fix section below.

---

### `DashboardService`

One operation: `getStats()`. Aggregates numbers from multiple repositories and the queue and worker pool, then assembles a `DashboardStatsResponse`.

The result is cached:

```java
@Cacheable("dashboardStats")
public DashboardStatsResponse getStats() { ... }
```

The cache has a 10-minute TTL configured in `CacheConfig`. It expires passively — there is no scheduled eviction, no manual `@CacheEvict` triggered on every transaction. Dashboard stats are aggregate approximations, not real-time counts. A 10-minute lag is acceptable and the passive TTL approach is simpler and cheaper than active invalidation.

---

### `TransactionMapper`

Pure field mapping from `Transaction` entity to `TransactionResponse` DTO. No logic. No conditions. Every field is copied directly or left null.

`FraudAlert` entities nested on the transaction are mapped to `FraudAlertResponse` objects by a separate `toAlertResponse()` method. This keeps the mapping composable.

If a field name changes on either side, the mapper breaks at compile time, not at runtime. That is the point of a dedicated mapper rather than ad-hoc `new TransactionResponse(...)` calls scattered across services.

---

### `MlFraudScoringService`

Calls the Python ML microservice over HTTP and returns a `MlScoreResponse` wrapped in `Optional`. Returns `Optional.empty()` on any failure condition: service unreachable, timeout, null response body, unexpected exception. The service never throws. Callers treat `Optional.empty()` as "ML unavailable, proceed with rule-based score only."

The Python service URL and enabled flag are externalised in `application.yml`:

```yaml
ml:
  service:
    url: http://localhost:8000
    enabled: false   # disabled by default until Phase 15
```

When `ml.service.enabled = false`, `score()` returns `Optional.empty()` immediately without making an HTTP call. This means the Python service does not need to be running during development or testing.

The `RestTemplate` used here is the bean defined in `AppConfig` with a 2-second connect timeout and a 5-second read timeout. Both values are short. Fraud analysis must not stall the payment pipeline waiting for a slow ML response.

**Architectural decision — `MlFraudScoringService` is not wired into `TransactionWorkerPool`.**

This service exists and is fully tested, but nothing in `TransactionWorkerPool` calls it. This is intentional, not an omission.

The original approach blended the ML probability into the worker's final fraud score invisibly:

```java
// What was rejected:
double finalScore = mlFraudScoringService.score(transaction)
    .map(ml -> Math.min(100.0, result.fraudScore() * 0.7 + ml.fraudProbability() * 100.0 * 0.3))
    .orElse(result.fraudScore());
```

This breaks explainability. A transaction that scores 40 from rules but 62 after ML blending has a score that no analyst can trace back to a specific reason. The `FraudAlert` design (see Phase 8 and the DTO docs in Phase 5) is built on the invariant that every point in a fraud score comes from a named rule with its own `FraudAlert`, its own `scoreContribution`, and its own human-readable `description`.

ML fraud scoring belongs in Phase 15 as a proper `FraudRule` implementation with `FraudRuleType.ANOMALY_SCORE_BREACH`. When it fires, it will produce a `FraudAlert` like any other rule, visible to analysts, with its probability and feature weights in the description. Until then `MlFraudScoringService` sits tested and ready but deliberately unconnected.

---

## `AppConfig`

Two beans.

`RestTemplate` is configured with short timeouts for the ML service HTTP calls described above.

`requiresNewTransactionTemplate` is a `TransactionTemplate` configured with `PROPAGATION_REQUIRES_NEW`:

```java
@Bean("requiresNewTransactionTemplate")
public TransactionTemplate requiresNewTransactionTemplate(PlatformTransactionManager tm) {
    TransactionTemplate tt = new TransactionTemplate(tm);
    tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tt;
}
```

Why this exists is covered in the fixes section below.

---

## Three Fixes Made During Review

### Fix 1 — Queue-full after commit could strand a transaction at PENDING forever

**Problem.** `afterCommit()` fires after the physical DB commit but before Spring finishes transaction cleanup. The `TransactionTemplate` that was already present used default `PROPAGATION_REQUIRED` propagation. In this window, `REQUIRED` can silently join the half-torn-down transaction context depending on the JPA provider. The result would be a repair write that either fails silently or throws, leaving the transaction stuck at `PENDING` with no worker ever consuming it.

**Fix.** A dedicated `requiresNewTransactionTemplate` bean is created in `AppConfig` with `PROPAGATION_REQUIRES_NEW`. This guarantees the repair write always opens a completely fresh physical transaction regardless of what state the transaction manager is in when `afterCommit()` fires. `PaymentService` receives it via `@Qualifier("requiresNewTransactionTemplate")`. Because Lombok's `@RequiredArgsConstructor` cannot place `@Qualifier` on generated constructor parameters, `PaymentService` uses an explicit constructor.

---

### Fix 2 — Phone numbers leaked unmasked in `UserResponse`

**Problem.** `AuthService.toResponse()` and `UserService.toResponse()` both returned `user.getPhoneNumber()` directly. The DTO contract documented in Phase 5 specifies masked phone numbers in `UserResponse`. The raw number was visible in every response.

**Fix.** `maskPhone()` was added to both services. The masked form is `"+1415***2671"` for an input of `"+14155552671"`. The implementation was added to both services separately rather than extracted to a shared utility. That extraction is a future cleanup item, not a bug, because the format is stable and the two usages are identical.

---

### Fix 3 — `FraudAlertService.resolveAlert` used a raw exception constructor

**Problem.** When the alert was not found, `resolveAlert` threw `new ResourceNotFoundException("Alert not found: " + alertId)` rather than using the factory convention established in Phase 6. The Phase 6 exception docs specify factory methods for every entity type (`ResourceNotFoundException.forUser()`, `ResourceNotFoundException.forTransaction()`, and so on) so error messages are consistent and callers can rely on the message format.

**Fix.** `ResourceNotFoundException.forAlert(alertId)` was added as a factory method and used in `resolveAlert`. The error message format now matches every other `ResourceNotFoundException` in the codebase.

---

## Test Coverage

The full suite is green: **334 tests, 0 failures, 0 errors**.

```
[INFO] Tests run: 334, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

New test classes added this phase:

| Class | Tests | What it covers |
|---|---|---|
| `AuthServiceTest` | 11 | Register (active/inactive/duplicate), login, phone masking |
| `UserServiceTest` | 11 | User queries, deactivation, phone masking in response |
| `PaymentServiceTest` | 18 | Submit (merchant/P2P/validation), get transaction, pagination, queue-full path |
| `DashboardServiceTest` | 9 | Stats aggregation, cache behaviour |
| `FraudAlertServiceTest` | 13 | Alert queries, resolve, factory exception on missing |
| `MlFraudScoringServiceTest` | 7 | Success, null response, HTTP errors, disabled flag |
| `TransactionMapperTest` | 10 | Entity to DTO field mapping, nested alert mapping |
| `ServiceIntegrationTest` | 30 | Full Spring context, real H2, cross-service scenarios |

The test worth calling out specifically is `PaymentServiceTest`'s queue-full coverage. It hand-drives `TransactionSynchronizationManager` to simulate the post-commit callback in a unit test. This is not a mock-the-queue trick; it actually registers a `TransactionSynchronization`, calls `afterCommit()` directly, and verifies that `requiresNewTemplate.executeWithoutResult()` is invoked with the DECLINED status written. That test exercises real transactional behaviour rather than just mock interactions and it is the one test in this phase that would have caught the `PROPAGATION_REQUIRED` bug described in Fix 1.

The `WARN` lines visible during the test run are correct. The ML error-handling tests deliberately throw `RestClientException` and a generic `RuntimeException` to verify the fallback. The security tests exercise invalid-token paths. None of these are failures.

Run the service tests in isolation:

```powershell
mvn test -Dtest="AuthServiceTest,UserServiceTest,PaymentServiceTest,DashboardServiceTest,FraudAlertServiceTest,MlFraudScoringServiceTest,TransactionMapperTest"
```

Run the full integration suite:

```powershell
mvn test -Dtest="ServiceIntegrationTest"
```

Run everything:

```powershell
mvn test
```

---

## What Forces the Next Step

All business logic is now implemented and tested. Every service delegates correctly, every exception path is handled, and every response is correctly shaped. But the services are not yet reachable over HTTP and they have no data to work with on startup. Phase 10 builds the async queue and worker pool that sits between payment submission and fraud analysis. After that, Phase 11 adds the REST controllers that expose all of this over HTTP.