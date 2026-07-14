# Phase 3 — Define the Database Tables (JPA Entities)

Before writing any queries or business logic, I defined what needs to be stored and designed ERD diagram to help me write the code. JPA entities are Java classes that Hibernate turns into database tables automatically. They are built bottom-up so entities with no foreign keys are built first in the application context, then entities that reference others.

---

## What Was Added

```
└── src/
    └── main/
        └── java/main/
            └── databaseModel/
                ├── User.java               ← payment account holder (no FK — built first)
                ├── AppUser.java            ← system login user: admin, analyst (no FK — built first)
                ├── Transaction.java        ← payment record, references User (sender + receiver)
                ├── FraudAlert.java         ← fraud engine output, references Transaction
                ├── MerchantBlacklist.java  ← known bad merchants (no FK — standalone to capture blocked merchants)
                ├── UserSession.java        ← one row per logged-in device, enables multi-device management
                └── EntityDemo.java         ← manual demo, run to verify all entities without a DB
```

---

## Why This Order

Each entity is built before anything that depends on it:

```
User           ← no dependencies, first
AppUser        ← no dependencies, first
MerchantBlacklist ← no dependencies, standalone
UserSession    ← no dependencies, references AppUser only by plain UUID string
Transaction    ← references User (sender, receiver)
FraudAlert     ← references Transaction
```

If you try to declare `Transaction` before `User` exists, the `@ManyToOne private User sender` field has nothing to point to.

---

## The Six Entities

### `User.java` — payment account holder

Represents a customer who sends and receives payments. The foundation everything else builds on.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | Auto-generated, primary key |
| `name` | `String` | Not blank, not unique — people share names |
| `email` | `String` | Unique index only (`@Index(unique=true)`) — no `@Column(unique=true)` to avoid duplicate DDL constraints |
| `phoneNumber` | `String` | E.164 format (`^\+[1-9]\d{6,14}$`), `@NotBlank` + `@Pattern` |
| `balance` | `BigDecimal` | `precision=19, scale=2` — never `double` for money |
| `homeCountry` | `String` | Used by `CROSS_BORDER_HIGH_RISK` rule |
| `homeCity` | `String` | Optional |
| `riskScore` | `double` | 0–100 range, `double` is fine here (not money) |
| `emailVerified` | `boolean` | Default `false` — set to `true` after email OTP confirmed |
| `phoneVerified` | `boolean` | Default `false` — set to `true` after SMS OTP confirmed |
| `active` | `boolean` | Default `true` — soft-delete flag |
| `flagged` | `boolean` | Default `false` — set by fraud engine |
| `version` | `Long` | Optimistic locking — prevents lost-update on concurrent balance changes |
| `sentTransactions` | `List<Transaction>` | Lazy, cascade ALL |
| `receivedTransactions` | `List<Transaction>` | Lazy, **no cascade** — deleting a user must not delete transactions they received |

---

### `AppUser.java` — system login user

A completely separate entity from `User`. This is the admin or analyst who logs into the API, not a customer. Conflating customers with system operators is a security design flaw in banking systems so I wanted to prevent this.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | Auto-generated, primary key |
| `username` | `String` | Unique index only — no duplicate DDL constraint |
| `email` | `String` | Unique index only — no duplicate DDL constraint |
| `password` | `String` | Stores a bcrypt hash, never plaintext |
| `roles` | `Set<String>` | EAGER — Spring Security needs the full role set on every request |
| `linkedUserId` | `String` | Optional UUID pointing to a `User` bank account — plain string, no FK, unique index |
| `failedLoginAttempts` | `int` | Default `0` — primitive, no `nullable=false` (redundant on primitives) |
| `lockedUntil` | `Instant` | Null until lockout — set after too many failed login attempts |
| `active` | `boolean` | Default `true` |
| `lastLoginAt` | `Instant` | Null until first login — used to detect dormant admin accounts |
| `createdAt` | `Instant` | `@CreationTimestamp`, `updatable=false`, `nullable=false` |
| `version` | `Long` | Optimistic locking — prevents lost-update on concurrent failed login attempts |

---

### `Transaction.java` — payment record

Supports two types of payments: merchant (customer → shop) and P2P (customer → customer). The type is determined by whether `receiver` is null.

| Field | Type | Notes |
|---|---|---|
| `sender` | `User` | FK to `users.id`, lazy, not null |
| `receiver` | `User` | FK to `users.id`, lazy, **nullable** — null means merchant payment |
| `amount` | `BigDecimal` | Min `0.01`, precision 19 scale 2 |
| `merchant` | `String` | Nullable — null for P2P transfers |
| `location` | `String` | Always required, fraud signal |
| `country` | `String` | Used by `CROSS_BORDER_HIGH_RISK` rule |
| `status` | `TransactionStatus` | Default `PENDING` |
| `riskLevel` | `RiskLevel` | Default `LOW`, `nullable=false` |
| `fraudScore` | `double` | Default `0.0`, set by fraud engine |
| `processingTimeMs` | `Long` | Metrics field — performance monitoring |
| `processedAt` | `Instant` | Nullable — not set until transaction leaves `PENDING` |
| `fraudAlerts` | `List<FraudAlert>` | Lazy, cascade ALL, `orphanRemoval=true` |
| `version` | `Long` | Optimistic locking — prevents lost-update when concurrent fraud rules update the same transaction |

---

### `FraudAlert.java` — fraud engine output

Created by the fraud engine every time a rule fires. One transaction can produce multiple alerts — one per rule that triggered.

| Field | Type | Notes |
|---|---|---|
| `transaction` | `Transaction` | FK, lazy |
| `userId` | `String` | Intentional denormalization — avoids a join through transaction when querying alerts by user |
| `ruleType` | `FraudRuleType` | Which rule fired |
| `riskLevel` | `RiskLevel` | Severity of this specific rule |
| `scoreContribution` | `double` | How much this rule added to the total fraud score |
| `description` | `String` | Human-readable explanation, max 1024 chars |
| `metadata` | `String` | JSON string with rule-specific details, max 2048 chars |
| `resolved` | `boolean` | Default `false` — set to `true` by an analyst after review |
| `version` | `Long` | Optimistic locking — prevents lost-update when concurrent fraud engines update `scoreContribution` or `resolved` |

The `userId` column has no FK constraint by design. If a user is deleted, their fraud alerts are retained for audit purposes and will not be automatically cleaned up. The service layer owns that lifecycle.

---

### `MerchantBlacklist.java` — known bad merchants

Simple lookup table. Checked by the `BLACKLISTED_MERCHANT` fraud rule.

| Field | Type | Notes |
|---|---|---|
| `merchantName` | `String` | Unique index only — no duplicate DDL constraint |
| `reason` | `String` | Why this merchant was blacklisted |
| `addedBy` | `String` | Username of the analyst who added it |
| `active` | `boolean` | Soft-delete — deactivating preserves audit history of when and why the merchant was added |
| `version` | `Long` | Optimistic locking — prevents lost-update on concurrent admin soft-delete operations |

---

### `UserSession.java` — logged-in device record

One row per logged-in device per user. Enables "logged in on 3 devices" and remote session revocation from the account security screen.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | Auto-generated, primary key |
| `appUserId` | `String` | Plain UUID string — no `@ManyToOne` so loading sessions never triggers an `AppUser` join |
| `refreshTokenHash` | `String` | bcrypt hash of the refresh token — raw value given to client once and never stored server-side |
| `deviceFingerprint` | `String` | Captured at login — compared on each refresh to detect token theft |
| `deviceName` | `String` | Human-readable label shown on "your active sessions" screen |
| `ipAddressIssued` | `String` | IP at session creation — sudden mid-session change is a fraud signal |
| `lastSeenIp` | `String` | Updated on every authenticated request |
| `lastSeenAt` | `Instant` | Updated on every authenticated request — "last seen" display and idle detection |
| `expiresAt` | `Instant` | When the refresh token expires — service rejects exchange attempts after this |
| `active` | `boolean` | Default `true` — `false` means logged out or admin-revoked; row kept for audit |
| `createdAt` | `Instant` | `@CreationTimestamp`, `updatable=false`, `nullable=false` |
| `version` | `Long` | Optimistic locking — prevents lost-update on concurrent logout/refresh/revocation races |

---

## Entity Relationship Diagram

```
┌─────────────────────────────────────┐
│              AppUser                │
│─────────────────────────────────────│
│ id (PK)                             │
│ username (UNIQUE)                   │
│ email (UNIQUE)                      │
│ password                            │
│ roles ──────────────────────────────┼──► app_user_roles (collection table)
│ linkedUserId (UNIQUE, no FK)        │
│ failedLoginAttempts                 │
│ lockedUntil (nullable)              │
│ active                              │
│ lastLoginAt (nullable)              │
│ createdAt                           │
│ version                             │
└──────────────┬──────────────────────┘
               │ appUserId (plain string, no FK)
               ▼
┌─────────────────────────────────────┐
│            UserSession              │
│─────────────────────────────────────│
│ id (PK)                             │
│ app_user_id (no FK — plain UUID)    │
│ refreshTokenHash                    │
│ deviceFingerprint                   │
│ deviceName                          │
│ ipAddressIssued                     │
│ lastSeenIp                          │
│ lastSeenAt                          │
│ expiresAt                           │
│ active                              │
│ createdAt                           │
│ version                             │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│                User                 │
│─────────────────────────────────────│
│ id (PK)                             │
│ name                                │
│ email (UNIQUE)                      │
│ phoneNumber                         │
│ balance                             │
│ homeCountry                         │
│ homeCity                            │
│ riskScore                           │
│ emailVerified                       │
│ phoneVerified                       │
│ active                              │
│ flagged                             │
│ version                             │
│ createdAt                           │
│ updatedAt                           │
└──────────────┬──────────────────────┘
               │ 1
               │ ┌─── sender (NOT NULL)
               │ └─── receiver (nullable)
               ▼ N
┌─────────────────────────────────────┐
│            Transaction              │
│─────────────────────────────────────│
│ id (PK)                             │
│ sender_id (FK → users)              │
│ receiver_id (FK → users, nullable)  │
│ amount                              │
│ merchant (nullable)                 │
│ location                            │
│ country                             │
│ ipAddress                           │
│ deviceFingerprint                   │
│ status                              │
│ riskLevel                           │
│ fraudScore                          │
│ processingTimeMs                    │
│ declineReason                       │
│ createdAt                           │
│ processedAt (nullable)              │
│ version                             │
└──────────────┬──────────────────────┘
               │ 1
               ▼ N
┌─────────────────────────────────────┐
│             FraudAlert              │
│─────────────────────────────────────│
│ id (PK)                             │
│ transaction_id (FK → transactions)  │
│ user_id (no FK — intentional)       │
│ ruleType                            │
│ riskLevel                           │
│ scoreContribution                   │
│ description                         │
│ metadata (JSON string)              │
│ resolved                            │
│ createdAt                           │
│ version                             │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│         MerchantBlacklist           │
│─────────────────────────────────────│
│ id (PK)                             │
│ merchantName (UNIQUE)               │
│ reason                              │
│ addedBy                             │
│ active                              │
│ createdAt                           │
│ version                             │
└─────────────────────────────────────┘
```

---

## `@PrePersist` and `@PreUpdate` — Validation Before Every Save

```java
@PrePersist
@PreUpdate
private void validateMerchantOrReceiver() {
    boolean hasMerchant = false;
    if(merchant != null && !merchant.isBlank()) hasMerchant = true;

    boolean hasReceiver = false;
    if(receiver != null) hasReceiver = true;

    if (hasMerchant == hasReceiver) {
        throw new IllegalStateException(
            "Transactions must have either a merchant (payment) or a receiver (P2P), not both or neither"
        );
    }
}
```

`@PrePersist` fires before every `INSERT`, `@PreUpdate` before every `UPDATE`. JPA calls this method automatically — you never call it yourself.

The rule it enforces: a transaction must have **exactly one** of `merchant` or `receiver` set. Both null = invalid (orphan transaction). Both set = invalid (can't be merchant and P2P at the same time). The `hasMerchant == hasReceiver` condition is true in both invalid cases at once, so one check handles both.

This guard runs at the JPA layer, therefore deeper than the API layer and before SQL is ever sent to the database.

---

## Two-Layer Protection: `@NotBlank` + `nullable = false`

Every required string field has both:

```java
@NotBlank                         // ← Bean Validation: catches it at the API layer
@Column(nullable = false)         // ← DB constraint: last line of defence
private String merchantName;
```

These are not redundant — they operate at different levels:

| Layer | Annotation | When it fires |
|---|---|---|
| API / service | `@NotBlank`, `@NotNull`, `@Email` | Before any DB call — returns a 400 error |
| Database | `nullable = false` | If the application layer is bypassed (raw SQL, test inserts, future code path) |

Fields set internally by Hibernate (`createdAt`, `version`, `status`) get only `nullable = false` — adding `@NotBlank` there would fire validation against Hibernate's own writes, which is wrong.

Primitive fields (`int`, `boolean`, `double`) must **not** have `nullable = false` — primitives cannot be null at the JVM level, so the annotation is redundant and misleading.

---

## Interesting Design Decisions

**`BigDecimal` for money, `double` for scores**
`balance` and `amount` use `BigDecimal(precision=19, scale=2)`. Floating point can't represent `0.10` exactly because if you use `double` for money, you'll get rounding errors in financial records. Scores (`riskScore`, `fraudScore`) use `double` because they are 0–100 approximations, not monetary amounts where rounding matters.

**Asymmetric cascade on `User`**
`sentTransactions` has `cascade = CascadeType.ALL` — deleting a user deletes their transaction history. `receivedTransactions` has no cascade because if Alice gets deleted, the transactions where Alice was the receiver still belong to the sender and must not be removed.

**`@Version` on all six entities**
Without it, two threads loading the same entity and writing simultaneously produce a lost update — thread 2 silently overwrites thread 1's change. With `@Version`, Hibernate adds `WHERE version = ?` to every `UPDATE`. If the version doesn't match, it throws `OptimisticLockException` instead of silently corrupting data. Every entity with a concurrent write risk carries `@Version`: `User` (balance), `AppUser` (failed login counter), `Transaction` (fraud rule results), `FraudAlert` (resolved flag), `MerchantBlacklist` (soft-delete), `UserSession` (logout/revoke race).

**No `@Column(unique=true)` alongside `@Index(unique=true)`**
Both annotations generate a DDL unique constraint — having both creates two separate constraints on the same column, which wastes an index. All unique constraints in this project use only `@Index(unique=true)` inside `@Table(indexes={...})`.

**Denormalized `userId` on `FraudAlert`**
`FraudAlert` stores `userId` as a plain string with no FK. Querying "all alerts for user X" would otherwise require joining `fraud_alerts → transactions → users`. The denormalized column eliminates that join. The trade-off is that if a user is deleted, their alerts stay in the table with a dangling ID. This is intentional, because fraud records must be stored for audit purposes even after account deletion.

**Soft-delete on `MerchantBlacklist`**
Setting `active = false` instead of deleting the row preserves the full audit trail: who added the merchant, when, and why. Hard-deleting loses that history permanently.

**`lastLoginAt` on `AppUser`**
Banks must detect dormant admin credentials because an account that hasn't logged in for 90+ days is a target for attackers. Without this field there is no way to enforce that policy.

**`UserSession` uses a plain `appUserId` string, not `@ManyToOne`**
Loading sessions should never trigger an `AppUser` join — the session table is queried on almost every authenticated request. A plain UUID string avoids that join entirely. The trade-off is no referential integrity at the DB level, which is acceptable because `UserSession` rows are cleaned up by an expiry job, not by cascading deletes.

---

## Database Indexes

| Index | Columns | Why |
|---|---|---|
| `idx_users_email` | `email` (unique) | Login and duplicate checks |
| `idx_users_active_flagged` | `is_active, is_flagged` | Dashboard filters |
| `idx_txn_user_id` | `user_id` | All transactions for a user |
| `idx_txn_receiver_id` | `receiver_id` | All transactions received by a user |
| `idx_txn_user_created` | `user_id, created_at` | Most common fraud query: user + time range |
| `idx_txn_p2p` | `user_id, receiver_id` | Graph-based fraud rules walk sender→receiver pairs |
| `idx_alert_user_id` | `user_id` | Alerts by user without joining through transaction |
| `idx_alert_resolved_created` | `is_resolved, created_at` | Analyst dashboard: unresolved alerts by date |
| `idx_merchant_name` | `merchant_name` (unique) | Blacklist lookup on every transaction |
| `idx_session_app_user_id` | `app_user_id` | All sessions for a user ("your devices" page) |
| `idx_session_expires_at` | `expires_at` | Cleanup job: find and purge expired sessions |
| `idx_session_app_user_active` | `app_user_id, is_active` | Active session lookup without a filtered scan |

Indexes are a database-level feature — they are verified when Spring Boot starts and generates the schema, not in the Java demo.

---

## How to Run the Demo

```powershell
mvn compile exec:java "-Dexec.mainClass=main.databaseModel.EntityDemo"
```

The demo tests everything that doesn't require a running database: builder defaults, field values, the `isP2P()` helper, and the `@PrePersist` validation guard. The `@PrePersist` method is private — it is invoked via Java reflection to replicate exactly what JPA does internally.

**Output:**

```
=== USER ===
Name:        Alice Smith
Email:       alice@example.com
Balance:     1500.00
Country:     US
Risk Score:  0.0  (default 0.0)
Active:      true  (default true)
Flagged:     false  (default false)
Version:     null  (null until first save)
Sent txns:   0  (empty list by default)
Recv txns:   0  (empty list by default)

=== APP USER (system login, not a customer) ===
Username:    admin
Roles:       [ROLE_ANALYST, ROLE_ADMIN]
Active:      true  (default true)
LastLogin:   null  (null until first login)

=== TRANSACTION — merchant payment ===
Merchant:    Amazon
Amount:      49.99
Status:      PENDING  (default PENDING)
Risk Level:  LOW  (default LOW)
Fraud Score: 0.0  (default 0.0)
isP2P():     false  (false — has merchant)

=== TRANSACTION — P2P transfer ===
Sender:      Alice Smith
Receiver:    Bob Jones
Merchant:    null  (null for P2P)
isP2P():     true  (true — has receiver)

=== FRAUD ALERT ===
Rule:        HIGH_AMOUNT
Risk Level:  HIGH
Score Cont:  35.0
Resolved:    false  (default false)
Metadata:    {"threshold": 50, "userAvg": 16.5}

=== MERCHANT BLACKLIST ===
Merchant:    ShadyStore
Reason:      Associated with multiple chargebacks
Added by:    analyst_01
Active:      true  (default true — soft delete)

=== @PrePersist VALIDATION ===
Both merchant AND receiver set (invalid): PASS (blocked — Transactions must have either a merchant (payment) or a receiver (P2P), not both or neither)
Neither merchant NOR receiver set (invalid): PASS (blocked — Transactions must have either a merchant (payment) or a receiver (P2P), not both or neither)
Only merchant set (valid): PASS (no exception)
Only receiver set (valid): PASS (no exception)

=== RISK LEVEL INTEGRATION ===
Score 15.0  -> LOW      -> APPROVED
Score 45.0  -> MEDIUM   -> APPROVED
Score 72.0  -> HIGH     -> FLAGGED_FOR_REVIEW
Score 91.0  -> CRITICAL -> FRAUD_BLOCKED
```

---

## `@ManyToOne`, `@OneToMany`, and `@Transient`

### `@ManyToOne` — the FK side

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User sender;
```

`@ManyToOne` means: many transactions can belong to one user. This is the **owning side**. It holds the actual foreign key column (`user_id`) in the database. JPA writes the FK here.

`fetch = FetchType.LAZY` means the `User` object is **not loaded** when you load a `Transaction`. It's loaded only if you actually call `getSender()`. Without `LAZY`, every transaction query would pull the full user row too which is an unnecessary join on every read.

`@JoinColumn(name = "user_id")` tells JPA what to name the FK column in the table. Without it, JPA generates an ugly name like `sender_id_fk_col`.

---

### `@OneToMany` — the inverse side

```java
@OneToMany(mappedBy = "sender", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
@Builder.Default
private List<Transaction> sentTransactions = new ArrayList<>();
```

`@OneToMany` means: one user has many transactions. This is the **inverse side**. It holds no FK column. The FK lives in the `transactions` table, not here.

`mappedBy = "sender"` tells JPA: "the relationship is already mapped by the `sender` field on `Transaction` to not create a second join table". If you forget `mappedBy`, JPA creates a useless extra join table.

`cascade = CascadeType.ALL` means any operation on the `User` (save, delete) cascades to their sent transactions. `receivedTransactions` deliberately has no cascade because deleting a user must not delete transactions where they were only the receiver.

`@Builder.Default` with `= new ArrayList<>()` is required because Lombok's `@Builder` ignores field initializers. Without it, the list is `null` when you use the builder, and calling `.add()` later throws a `NullPointerException`.

---

### Bidirectional relationship — owning vs inverse

The `@ManyToOne` and `@OneToMany` on `Transaction` and `User` form one bidirectional relationship. The rule:

- **`@ManyToOne` (Transaction.sender)** — owning side, holds the FK, JPA writes here
- **`@OneToMany` (User.sentTransactions)** — inverse side, read-only mirror, `mappedBy` points back to the owning field

There is only one FK column in the database (`user_id` on `transactions`). The two annotations are just two Java views of the same relationship.

---

### `@Transient` — field that is never persisted

```java
@Transient
public boolean isP2P() {
    return receiver != null;
}
```

`@Transient` tells JPA: ignore this entirely — no column, no read, no write. It exists only in memory.

`isP2P()` is a convenience method. Without it, every service class would repeat `transaction.getReceiver() != null` to check whether a transaction is P2P. With it, you write `transaction.isP2P()` — the intent is immediately clear, and the logic is in one place.

Without `@Transient`, JPA would try to find a matching column in the `transactions` table and throw an error on startup because no such column exists.

---

## What Forces the Next Step

Entities are defined but there is no way to query them yet. Every entity needs a repository. Therefore, I will create a Spring Data interface that generates the SQL automatically. That is Phase 4.