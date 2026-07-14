# Phase 4 — Define How You Query the Database (JPA Repositories)

Entities describe what is stored. Repositories describe how it is queried. Spring Data JPA generates the SQL automatically from method names or JPQL, so I never write raw SQL except for one native query where I needed a `LIMIT` clause that JPQL does not support.

---

## What Was Added

```
└── src/
    ├── main/
    │   └── java/main/
    │       ├── databaseModel/
    │       │   ├── User.java           ← modified (see note below)
    │       │   └── Transaction.java    ← modified (see note below)
    │       └── repository/
    │           ├── UserRepository.java
    │           ├── AppUserRepository.java
    │           ├── TransactionRepository.java
    │           ├── FraudAlertRepository.java
    │           ├── MerchantBlacklistRepository.java
    │           └── UserSessionRepository.java
    └── test/
        └── java/main/
            ├── TestApplication.java
            └── repository/
                └── RepositoryIntegrationTest.java
```

> **Note on modifying previous-phase files:** I updated `User.java` and `Transaction.java` from Phase 3 during this phase. This is expected as the project grows. Earlier files may need small fixes or additions to support new functionality. When that happens, I make the change and explain why.

---

## Why These Files Exist

Each repository is a Java interface that extends `JpaRepository<Entity, IdType>`. Spring Data reads the method names and generates the SQL at startup. No implementation class is needed.

The six repositories mirror the six entities from Phase 3:

| Repository | Entity | Primary role in the fraud system |
|---|---|---|
| `UserRepository` | `User` | Balance deduction, risk profile updates, flagged account queries |
| `AppUserRepository` | `AppUser` | Spring Security login lookup, failed login tracking |
| `TransactionRepository` | `Transaction` | Fraud engine queries — velocity, volume, merchant history |
| `FraudAlertRepository` | `FraudAlert` | Alert dashboard, rule-type analytics |
| `MerchantBlacklistRepository` | `MerchantBlacklist` | Real-time blacklist check on every transaction |
| `UserSessionRepository` | `UserSession` | Session lookup, refresh token validation, device revocation |

---

## Small Fixes to Phase 3 Files

Writing the queries revealed a few gaps in the entities. In `User.java`: added `unique = true` on the `email` column (was implied by the index, now explicit), and added a dedicated `idx_users_flagged` single-column index because the composite `(is_active, is_flagged)` index is unusable when querying on `is_flagged` alone. In `Transaction.java`: added `idx_txn_risk_level` for `findByRiskLevel`, and `orphanRemoval = true` on `fraudAlerts` so alerts are deleted when removed from the collection, not just via `fraudAlertRepository.delete()`.

---

## The Six Repositories

### `UserRepository`

All dashboard queries filter `active = true` so deactivated users never appear in analyst views or risk queues. Business queries use the `AndActiveTrue` derived query suffix or an explicit `AND u.active = true` clause in JPQL.

```java
Page<User> findByFlaggedAndActiveTrue(boolean flagged, Pageable pageable);

@Query("SELECT u FROM User u WHERE u.riskScore >= :threshold AND u.active = true ORDER BY u.riskScore DESC")
Page<User> findHighRiskUsers(@Param("threshold") double threshold, Pageable pageable);

@Modifying(clearAutomatically = true)
@Query("UPDATE User u SET u.balance = u.balance - :amount WHERE u.id = :userId AND u.balance >= :amount")
int deductBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

@Modifying(clearAutomatically = true)
@Query("UPDATE User u SET u.riskScore = :score, u.flagged = :flagged WHERE u.id = :userId")
int updateRiskProfile(@Param("userId") String userId, @Param("score") double score, @Param("flagged") boolean flagged);

@Modifying(clearAutomatically = true)
@Query("UPDATE User u SET u.emailVerified = true WHERE u.id = :userId")
int markEmailVerified(@Param("userId") String userId);

@Modifying(clearAutomatically = true)
@Query("UPDATE User u SET u.phoneVerified = true WHERE u.id = :userId")
int markPhoneVerified(@Param("userId") String userId);

Page<User> findByEmailVerifiedAndActiveTrue(boolean emailVerified, Pageable pageable);
Page<User> findByPhoneVerifiedAndActiveTrue(boolean phoneVerified, Pageable pageable);
```

**`deductBalance`** — the `WHERE u.balance >= :amount` condition is the guard against overdrafts. It returns `1` (row updated) or `0` (insufficient funds or user not found). This avoids an optimistic lock cycle: instead of loading the user, checking the balance in Java, and then saving, the whole check-and-deduct happens in a single atomic SQL statement.

**`clearAutomatically = true`** on all `@Modifying` methods — bulk JPQL updates bypass Hibernate's first-level cache. Without this flag, a `findById` immediately after `deductBalance` returns the stale in-memory object, not the new DB value. `clearAutomatically` evicts the cache so the next read hits the database.

**All `@Modifying` mutations return `int`** — if the fraud engine processes a transaction for a user who was deleted between the score calculation and the write, the update silently affects zero rows. Returning `int` lets the caller detect that no-op case. `void` would swallow it silently.

---

### `AppUserRepository`

```java
Optional<AppUser> findByUsername(String username);
boolean existsByUsername(String username);

@Modifying(clearAutomatically = true)
@Query("UPDATE AppUser a SET a.failedLoginAttempts = a.failedLoginAttempts + 1 WHERE a.id = :id")
int incrementFailedLoginAttempts(@Param("id") String id);

@Modifying(clearAutomatically = true)
@Query("UPDATE AppUser a SET a.failedLoginAttempts = 0, a.lastLoginAt = :now WHERE a.id = :id")
int recordSuccessfulLogin(@Param("id") String id, @Param("now") Instant now);

@Modifying(clearAutomatically = true)
@Query("UPDATE AppUser a SET a.lockedUntil = :until WHERE a.id = :id")
int lockUntil(@Param("id") String id, @Param("until") Instant until);
```

Spring Security needs only `findByUsername` to load the user for authentication and `existsByUsername` for registration duplicate checks. The three mutation methods are safe-increments and atomic resets that avoid the load-check-save race condition on login state.

---

### `TransactionRepository`

The most query-heavy repository because the fraud engine runs multiple checks per transaction.

**Derived method naming** — `Transaction` stores the sender in a field called `sender`, mapped to the `user_id` column. Spring Data generates the SQL from the field name, not the column name. So all derived methods use `BySenderId` and all JPQL uses `t.sender.id`.

```java
// Typed Collection parameter — never string literals for enums in JPQL
@Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.sender.id = :senderId AND t.createdAt >= :since AND t.status NOT IN :excludedStatuses")
Optional<BigDecimal> sumAmountBySenderSince(..., Collection<TransactionStatus> excludedStatuses);

// Native SQL — JPQL has no LIMIT, Pageable adds OFFSET but not LIMIT on aggregates
@Query(value = "SELECT merchant, COUNT(*) AS cnt FROM transactions WHERE created_at >= :since AND status = 'FRAUD_BLOCKED' AND merchant IS NOT NULL GROUP BY merchant ORDER BY cnt DESC LIMIT 10", nativeQuery = true)
List<Object[]> findTopFraudMerchants(@Param("since") Instant since);
```

**Why `Collection<TransactionStatus>` instead of string literals** — JPQL allows `status IN ('FLAGGED_FOR_REVIEW', 'FRAUD_BLOCKED')` with hardcoded strings, but if the enum value is ever renamed, the query silently returns no rows with no compile error. Using a typed `Collection<TransactionStatus>` parameter means the compiler catches any mismatch immediately.

**The one native query** — `findTopFraudMerchants` needs `LIMIT 10` on a `GROUP BY` aggregate. JPQL's `Pageable` works for `SELECT` row limits but not for aggregate-then-limit. This is the only place in the project where JPQL isn't sufficient, so native SQL is used only here.

---

### `FraudAlertRepository`

```java
List<FraudAlert> findByTransactionId(String transactionId);

@Query("SELECT fa FROM FraudAlert fa WHERE fa.ruleType = :ruleType AND fa.createdAt >= :since ORDER BY fa.createdAt DESC")
List<FraudAlert> findByRuleTypeSince(@Param("ruleType") FraudRuleType ruleType, @Param("since") Instant since);

@Query("SELECT fa.ruleType, COUNT(fa) FROM FraudAlert fa WHERE fa.createdAt >= :since GROUP BY fa.ruleType ORDER BY COUNT(fa) DESC")
List<Object[]> countByRuleTypeSince(@Param("since") Instant since);

long countByUserIdAndCreatedAtAfter(String userId, Instant after);
```

**`findByTransactionId`** — `FraudAlert` has a `@ManyToOne Transaction transaction` field, so `transaction_id` is a direct column on the table. Spring Data resolves `findByTransactionId` to `WHERE fa.transaction_id = ?` so no join needed.

**`countByRuleTypeSince`** returns `List<Object[]>` because JPQL projections with `GROUP BY` produce rows of mixed types (`FraudRuleType`, `Long`). This is used by the analytics dashboard to show which fraud rules are firing most often in a given window.

---

### `MerchantBlacklistRepository`

The soft-delete pattern requires two distinct query paths: a business path that ignores inactive entries and an audit path that can see everything.

```java
boolean existsByMerchantNameAndActiveTrue(String merchantName);
Optional<MerchantBlacklist> findByMerchantNameAndActiveTrue(String merchantName);
Optional<MerchantBlacklist> findByMerchantName(String merchantName);
```

**`existsByMerchantNameAndActiveTrue`** — called on every transaction that has a merchant. A deactivated blacklist entry must not block a transaction, so this filters `active = true`. It generates `SELECT id FROM merchant_blacklist WHERE merchant_name = ? AND is_active = true FETCH FIRST 1 ROWS ONLY`, which hits the unique index and returns immediately.

**`findByMerchantName`** (no active filter) — used by the audit trail to look up the full history of a merchant entry regardless of its current state.

---

### `UserSessionRepository`

```java
Optional<UserSession> findByIdAndActiveTrue(String id);
List<UserSession> findByAppUserIdAndActiveTrue(String appUserId);

@Modifying(clearAutomatically = true)
@Query("UPDATE UserSession s SET s.active = false WHERE s.id = :id")
int deactivateById(@Param("id") String id);

@Modifying(clearAutomatically = true)
@Query("UPDATE UserSession s SET s.active = false WHERE s.appUserId = :appUserId")
int deactivateAllByAppUserId(@Param("appUserId") String appUserId);

@Modifying(clearAutomatically = true)
@Query("UPDATE UserSession s SET s.lastSeenIp = :ip, s.lastSeenAt = :now WHERE s.id = :id")
int updateLastSeen(@Param("id") String id, @Param("ip") String ip, @Param("now") Instant now);

List<UserSession> findByExpiresAtBeforeAndActiveTrue(Instant cutoff);
```

**`findByIdAndActiveTrue`**  — used on every token refresh. A revoked or expired session must not generate a new access token, so this only returns active sessions.

**`deactivateAllByAppUserId`** — used on "log out all devices". One bulk update instead of loading and saving each session individually.

**`updateLastSeen` returns `int`** — if the session was deleted between the request arriving and the update running, the call affects zero rows. Returning `int` lets the caller detect that case; `void` would swallow it silently.

**`findByExpiresAtBeforeAndActiveTrue`** — used by the cleanup job to find sessions whose tokens are expired but haven't been explicitly logged out. These are candidates for deactivation.

---

## Integration Tests

Tests live in `src/test/java/main/repository/RepositoryIntegrationTest.java` and use `@DataJpaTest`, which spins up an H2 in-memory database, loads only the JPA slice of the application context, and rolls back every test automatically.

**`TestApplication.java`** — `@DataJpaTest` searches upward through parent packages from the test class package (`main.repository` → `main` → default) looking for `@SpringBootApplication`. The production `Main.java` lives in `main.application`, which is a sibling of `main.repository`, not a parent. The scan never finds it. `TestApplication.java` is placed in `src/test/java/main/` — the direct parent so the scanner finds it immediately.

**53 tests, all passing:**

```
mvn test
```

```
...
[INFO] Tests run: 53, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.123 s -- in main.repository.RepositoryIntegrationTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  13.241 s
```

Tests cover: CRUD for all six entities, unique constraint violations, entity default values (`active`, `flagged`, `riskScore`, `createdAt`), `@PrePersist` and `@PreUpdate` guards, all custom JPQL queries, the native SQL merchant query, `deductBalance` (success and insufficient funds), `updateRiskProfile` (with DB verification), orphan removal via collection, `findByRuleTypeSince`, `countByRuleTypeSince`, `sumAmountBySenderSince` (with and without matching rows), `existsBySenderIdAndMerchantAndCreatedAtAfter`, all `UserSession` operations including `updateLastSeen` return value (1 = hit, 0 = miss), soft-delete filtering on `MerchantBlacklist`, and deactivated-user exclusion on all four dashboard queries (`findByFlaggedAndActiveTrue`, `findHighRiskUsers`, `findByEmailVerifiedAndActiveTrue`, `findByPhoneVerifiedAndActiveTrue`).

All 7 paginated queries assert both `getTotalElements()` (correct count) and `getContent()` (correct records returned, deactivated users excluded).

**Selected output (Hibernate SQL log, significant parts):**

```
...
Hibernate:
    update
        users
    set
        is_active=?, balance=?, email=?, is_flagged=?, ...
    where
        id=?
        and version=?                          ← optimistic lock: version must match

...
Hibernate:
    SELECT
        merchant,
        COUNT(*) AS cnt
    FROM
        transactions
    WHERE
        created_at >= ?
        AND status = 'FRAUD_BLOCKED'
        AND merchant IS NOT NULL
    GROUP BY
        merchant
    ORDER BY
        cnt DESC
    LIMIT
        10                                     ← native SQL, the only query JPQL couldn't express

...
Hibernate:
    delete
    from
        fraud_alerts
    where
        id=?                                   ← orphan removal: fired by removing from collection
```

---

## Interesting Design Decisions

**Atomic balance deduction in SQL**
The check and the deduct happen in one `UPDATE` statement. The alternative — load the user, check balance in Java, save — requires two round-trips and risks a race condition between the check and the write even with optimistic locking. One SQL statement eliminates both problems.

**`clearAutomatically = true` is not optional**
This was discovered during testing, not assumed. Without it, a read immediately after a bulk `UPDATE` returns the pre-update value from Hibernate's cache. The test for `deductBalance` verified the actual balance after the update, which is how this was caught.

**Typed enum parameters over JPQL string literals**
The temptation is to write `status IN ('FLAGGED_FOR_REVIEW', 'FRAUD_BLOCKED')` directly in the query. If the enum is renamed or a value is removed, that query silently returns nothing so there is no compile error, no runtime exception. Using `Collection<TransactionStatus>` as a parameter makes it a compile-time contract.

**`saveAndFlush` vs `save` in tests**
`@CreationTimestamp` is set by Hibernate during flush, not during `persist()`. Calling `save()` enqueues the insert but doesn't execute it, so `entity.getCreatedAt()` is `null` on the returned object. All setup in `@BeforeEach` and any test that checks `createdAt` uses `saveAndFlush()`.

**`orphanRemoval` and `saveAndFlush` return value**
When `saveAndFlush(txn)` cascades to a new `FraudAlert`, the alert's UUID is written to the managed entity that `saveAndFlush` *returns*, not the original object passed in. Reading `alert.getId()` from the original reference returns `null`. The fix is to reassign `txn` to the return value and read the saved alert from `txn.getFraudAlerts()`.

**Soft-delete filtering is a business-path concern**
Every query that feeds a dashboard, a fraud rule, or a business decision filters `active = true`. Audit queries (administrator viewing deactivated merchants, security team reviewing revoked sessions) intentionally skip that filter. The naming convention makes this explicit: `existsByMerchantNameAndActiveTrue` for the business path, `findByMerchantName` for the audit path.

---

## What Forces the Next Step

Repositories know how to read and write data, but the API layer needs safe, stable objects to send to callers — not raw entities with internal fields like `version`, `password`, and lazy collections. That boundary is the DTO layer: request objects that carry validated input in, response objects that carry safe projections out. That is Phase 5.