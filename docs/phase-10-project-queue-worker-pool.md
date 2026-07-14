# Phase 10 — Async Queue and Worker Pool

A payment submitted over HTTP cannot wait for fraud analysis to finish. Fraud analysis touches the database, traverses a graph, and optionally calls a Python service over the network. Doing all of that on the HTTP thread means every concurrent payment occupies a thread for the duration of the analysis. At a thousand concurrent payments that is a thousand blocked threads. The right approach is to decouple submission from analysis: the HTTP thread records the transaction and returns immediately, then a pool of dedicated worker threads picks up the analysis in the background. That is what Phase 10 builds.

---

## What Was Added

```
├── src/
│   ├── main/java/main/
│   │   └── queue/
│   │       ├── TransactionQueue.java
│   │       └── TransactionWorkerPool.java
│   └── test/java/main/
│       └── queue/
│           └── TransactionWorkerPoolTest.java
```

---

## `TransactionQueue`

A thin wrapper around `LinkedBlockingQueue<String>`. Stores transaction IDs rather than full entity objects because entities are not thread-safe once detached from the JPA session. The worker re-fetches the entity from the database inside its own transaction.

```java
private final LinkedBlockingQueue<String> queue;

public void enqueue(Transaction transaction) {
    if (!queue.offer(transaction.getId())) {
        throw new TransactionQueueFullException(
            "Transaction queue at capacity (" + capacity + "). Retry later.");
    }
}

public String take() throws InterruptedException {
    return queue.take();
}
```

`offer()` is non-blocking. If the queue is at capacity it throws `TransactionQueueFullException` rather than silently dropping the transaction or blocking the caller. The caller (`PaymentService.enqueueAfterCommit()`) catches the exception and marks the transaction `DECLINED` using `requiresNewTransactionTemplate`. The transaction does not disappear. It gets a visible terminal status with a reason the user can read.

`take()` blocks. Workers call it in a loop and park until something arrives. No busy-waiting, no sleep-polling.

Queue capacity is configurable via `queue.capacity` in `application.yml`. The default is 10,000. Sizing this correctly matters: too small and you see 503s during legitimate payment bursts, too large and a slow fraud engine causes memory pressure. The configurable default is a reasonable starting point for dev. Production sizing depends on observed fraud engine throughput.

`size()` is exposed for dashboard stats without locking because `LinkedBlockingQueue.size()` uses an `AtomicInteger` internally so the read is already atomic.

---

## `TransactionWorkerPool`

A `ThreadPoolExecutor` with daemon threads. On `@PostConstruct`, one drain loop is submitted per configured pool size:

```java
@PostConstruct
public void start() {
    for (int i = 0; i < poolSize; i++) {
        executor.submit(this::drainLoop);
    }
    log.info("TransactionWorkerPool initialized with {} worker threads", poolSize);
}
```

Each drain loop runs until the thread is interrupted:

```java
private void drainLoop() {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            String transactionId = transactionQueue.take();
            activeWorkers.incrementAndGet();
            try {
                transactionTemplate.executeWithoutResult(status ->
                    processTransaction(transactionId));
            } finally {
                activeWorkers.decrementAndGet();
                totalProcessed.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`processTransaction` runs inside a `TransactionTemplate` so every step — the repository load, the fraud engine analysis, the status update, the alert save, the balance deduction — is atomic. Either all of it commits or none of it does.

`activeWorkers` is an `AtomicLong`, not an `int`, because multiple threads increment and decrement it concurrently. `totalProcessed` is also `AtomicLong` for the same reason.

Pool size is configurable via `worker.pool-size` in `application.yml`. Default is 4.

---

## `processTransaction` — The Worker Logic

```java
void processTransaction(String transactionId) {
    Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
    if (transaction == null) {
        log.warn("Transaction not found, skipping: {}", transactionId);
        return;
    }
    if (transaction.getStatus() != TransactionStatus.PENDING) {
        log.debug("Transaction {} already processed (status={}), skipping",
            transactionId, transaction.getStatus());
        return;
    }

    long startNs = System.nanoTime();
    FraudDetectionEngine.FraudAnalysisResult result = fraudDetectionEngine.analyze(transaction);
    applyFraudResult(transaction, result, startNs);
}
```

Two early exits protect against edge cases. The not-found guard handles a scenario where a transaction is enqueued but then deleted by an admin before the worker processes it. The already-processed guard handles duplicate-enqueue scenarios (two workers racing on the same ID).

`applyFraudResult` maps the fraud engine result to a terminal status:

| `RiskLevel` | `TransactionStatus` | Balance deducted | Alerts saved | Graph updated |
|---|---|---|---|---|
| `LOW` | `APPROVED` | Yes (if deduct returns 1) | No | Yes |
| `MEDIUM` | `APPROVED` | Yes (if deduct returns 1) | No | Yes |
| `HIGH` | `FLAGGED_FOR_REVIEW` | No | Yes | Yes |
| `CRITICAL` | `FRAUD_BLOCKED` | No | Yes | No |

Balance deduction uses `userRepository.deductBalance()` which is a conditional update:

```java
@Modifying
@Query("UPDATE User u SET u.balance = u.balance - :amount WHERE u.id = :userId AND u.balance >= :amount")
int deductBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);
```

It returns 1 if the row was updated and 0 if the balance had changed since submission (another concurrent transaction spent it first). A return value of 0 means the transaction is `DECLINED` with reason `"Insufficient balance at processing time"`. The graph is not updated for declined transactions.

Alerts are only persisted when the list is non-empty. For `LOW` and `MEDIUM` results with no alerts, `fraudAlertRepository.saveAll()` is never called. For alerts that arrive without a `transaction` reference set (an edge case in some rule implementations), the worker attaches the reference before saving so the foreign key constraint is satisfied.

`userRepository.updateRiskProfile()` is called on every processed transaction regardless of outcome. This keeps the user's rolling risk score current.

`processingTimeMs` and `processedAt` are set on the transaction before saving so the dashboard and API callers can see how long analysis took.

`FRAUD_BLOCKED` transactions are not added to the graph. Adding a fraudulent transaction to the graph would create false edges that could suppress legitimate future alerts (for example, a known-fraud merchant appearing connected to a clean user).

---

## No ML Wiring — Intentional

`MlFraudScoringService` is not called anywhere in `TransactionWorkerPool`. This is the same architectural decision documented in Phase 9. ML fraud scoring is deferred to Phase 15 where it will be implemented as a proper `FraudRule` with its own `FraudAlert` and `scoreContribution` so every point in a fraud score remains traceable to a named, auditable reason.

---

## Test Suite — `TransactionWorkerPoolTest`

15 tests across two nested classes. No Spring context. All dependencies are mocked with Mockito.

### `PoolConfigurationTests` — 3 tests

Verify that the pool reports the configured size, starts with zero processed, and reports a non-negative active worker count. These are existence checks that confirm the pool initialises correctly before any transaction is submitted.

### `ProcessTransactionTests` — 12 tests

Cover every outcome path through `processTransaction`. Notable cases:

`lowRiskTransaction_isApprovedAndBalanceDeducted` — verifies status, risk level, fraud score, `processedAt`, `processingTimeMs`, balance deduction call, graph update call, and repository save. All six assertions must pass together because the test simulates a real LOW outcome end to end.

`balanceChangedBeforeProcessing_transactionIsDeclined` — stubs `userRepository.deductBalance()` to return 0 (the conditional update found no row to update). Verifies status is `DECLINED`, decline reason contains `"Insufficient balance"`, and graph is never updated.

`existingAlertWithoutTransaction_isAttachedBeforeSave` — creates an alert with `transaction = null`, verifies the worker attaches the correct transaction reference before calling `saveAll`. Uses `ArgumentCaptor` to inspect what was actually passed to the repository.

`transactionMissing_skipsProcessing` and `alreadyProcessedTransaction_skipsProcessing` — both verify that `fraudDetectionEngine` is never called for transactions that should not be re-processed.

`noAlerts_fraudAlertRepositoryNotCalled` — verifies that `fraudAlertRepository.saveAll()` is never called when the fraud engine returns an empty alert list. This is a negative assertion that ensures no empty-list saves happen.

`userRiskProfileUpdated_onEveryProcessedTransaction` — verifies `userRepository.updateRiskProfile()` is called with the correct userId and score on every processed transaction.

There is no `MlFraudScoringService` mock in this test. That is intentional:
ML is not wired into worker decisions until it becomes an explainable
`FraudRule` in a later phase.

---

## Correctness: Test Results

334 tests, 0 failures, 0 errors across the complete project.

```
[INFO] Tests run: 334, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  59.715 s
```

Run the worker pool tests directly:

```powershell
mvn test -Dtest="TransactionWorkerPoolTest"
```

Run a single nested class:

```powershell
mvn test -Dtest="TransactionWorkerPoolTest\$ProcessTransactionTests"
```

Run everything:

```powershell
mvn test
```

---

## What Forces the Next Step

Payments are submitted, queued, and processed asynchronously. Workers classify fraud, update statuses, and save alerts. But analysts have no way to see this in real time without polling. Phase 11 adds WebSocket push so a HIGH or CRITICAL result broadcasts to any connected dashboard the instant the worker finishes. After that, Phase 12 exposes all of this over HTTP so clients can actually reach the system.
