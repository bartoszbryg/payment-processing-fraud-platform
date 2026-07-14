# Phase 5 — DTOs: Request and Response Objects

Repositories know how to read and write data. But the API layer can't hand raw entities to callers because entities carry internal fields like `version`, `password`, bcrypt hashes, lazy collections that would blow up outside a transaction, and analyst-only fields like `riskScore` that customers must never see. DTOs are the boundary: request objects carry validated input in, response objects carry safe projections out.

---

## What Was Added

```
└── src/
    └── main/
        └── java/main/
            └── dto/
                ├── DtoDemo.java
                ├── request/
                │   ├── LoginRequest.java
                │   ├── RegisterRequest.java
                │   ├── PaymentRequest.java
                │   ├── RefreshTokenRequest.java
                │   └── RevokeSessionRequest.java
                └── response/
                    ├── LoginResponse.java
                    ├── UserResponse.java
                    ├── PaymentResponse.java
                    ├── TransactionResponse.java
                    ├── FraudAlertResponse.java
                    ├── SessionResponse.java
                    ├── DashboardStatsResponse.java
                    └── ApiErrorResponse.java
```

---

## Request DTOs — Input Validation at the API Boundary

Request DTOs use `@Data` (Lombok) — setters are needed because Spring MVC deserializes JSON by calling them. Jakarta Validation annotations enforce constraints before any service method is called. A violation returns a `400 Bad Request` before the request reaches business logic.

### `RegisterRequest`

Creates both a `User` (bank account) and an `AppUser` (login credentials) in one transaction. The old design required two separate API calls leaving accounts in a half-created state if the second call failed. This DTO handles the complete registration in one atomic step.

| Field | Constraints | Notes |
|---|---|---|
| `name` | `@NotBlank` | Full legal name |
| `email` | `@Email` `@NotBlank` | Used as login contact |
| `username` | `@NotBlank` `@Size(min=3, max=50)` | Chosen login handle |
| `password` | `@NotBlank` `@Size(min=8, max=100)` | bcrypt-hashed before storage |
| `phoneNumber` | `@NotBlank` `@Pattern(E.164)` | Must be `+` followed by 7–15 digits, no spaces |
| `initialBalance` | `@NotNull` `@DecimalMin("0.00")` `@Digits(17,2)` | Opening account balance |
| `homeCountry` | `@NotBlank` `@Size(min=2, max=10)` | ISO country code |
| `homeCity` | — | Optional |

The `@Pattern` regex for phone is `^\+[1-9]\d{6,14}$`. This is intentionally paired with `@NotBlank` — `@Pattern` permits `null` by specification, so without `@NotBlank` a missing phone would pass validation silently.

---

### `LoginRequest`

Carries credentials plus device context captured at login time. Device fields are stored in `UserSession` so the user can see which devices are logged in and the fraud engine can flag logins from new devices or locations.

| Field | Constraints | Notes |
|---|---|---|
| `username` | `@NotBlank` | — |
| `password` | `@NotBlank` | Never logged or stored raw |
| `deviceFingerprint` | — | Optional — stored in `UserSession.deviceFingerprint`, compared on each refresh to detect token theft |
| `deviceName` | — | Optional — shown on "your active sessions" screen |
| `ipAddress` | — | Optional — stored as `UserSession.ipAddressIssued`; mid-session change is a fraud signal |

---

### `PaymentRequest`

Exactly one of `merchant` or `receiverId` must be set. Therefore, this XOR constraint mirrors the entity-level `@PrePersist` guard. The validation is in the service layer, not here, because cross-field rules don't belong in single-field annotations.

`userId` is intentionally absent — the service extracts it from the JWT so the caller can never impersonate a different user by supplying a different ID.

| Field | Constraints | Notes |
|---|---|---|
| `amount` | `@NotNull` `@DecimalMin("0.01")` `@Digits(15,2)` | Must be positive |
| `merchant` | `@Size(max=255)` | Set for merchant payments, null for P2P |
| `receiverId` | — | Set for P2P transfers, null for merchant payments |
| `location` | `@NotBlank` `@Size(max=255)` | Always required — fraud signal |
| `country` | `@Size(max=10)` | Optional two-letter code |
| `ipAddress` | — | Optional — for geo-analysis |
| `deviceFingerprint` | — | Optional — for device-change detection |

---

### `RefreshTokenRequest`

Sent by the client when the access token expires. The raw refresh token is looked up by bcrypt-hashing it and comparing against `UserSession.refreshTokenHash`. The raw value is never logged or stored anywhere after the login response.

| Field | Constraints | Notes |
|---|---|---|
| `refreshToken` | `@NotBlank` | Raw JWT string — hashed server-side before lookup |

---

### `RevokeSessionRequest`

Sent when a user wants to log out a specific device from their "active sessions" screen. The service verifies that `sessionId` belongs to the requesting user before deactivating it. A user cannot revoke someone else's session by guessing an ID.

| Field | Constraints | Notes |
|---|---|---|
| `sessionId` | `@NotBlank` | `UserSession.id` of the target device |

---

## Response DTOs — Safe Projections Out

Response DTOs use `@Data` + `@Builder` (Lombok) — they are constructed by the service layer and never deserialized from client input. No validation annotations needed here. The design principle: every field the service never sets is simply absent from the DTO, rather than being null in an entity that has more fields.

### `LoginResponse`

Returned once on successful login. The raw `refreshToken` is returned here and never stored server-side because only its bcrypt hash is persisted in `UserSession`. The client must store it securely (e.g., `HttpOnly` cookie or secure storage).

| Field | Notes |
|---|---|
| `accessToken` | Short-lived JWT (minutes) — sent on every API call |
| `refreshToken` | Long-lived JWT (days) — raw value, client must store; server stores only the hash |
| `sessionId` | `UserSession.id` — used to identify this device on the sessions screen |
| `username` | Shown in the UI |
| `tokenType` | Always `"Bearer"` |
| `accessTokenExpiresInSeconds` | 900 (15 min) |
| `refreshTokenExpiresInSeconds` | 604800 (7 days) |

---

### `UserResponse`

Safe projection of the `User` entity. Omits: `version` (internal), `updatedAt` (internal), `sentTransactions`/`receivedTransactions` (lazy collections).

`balance`, `riskScore`, and `flagged` are included for analyst dashboards. Therefore, strip them before returning to customer-facing endpoints.

`phoneNumber` is included unmasked. Masking (e.g., `+1415***2671`) is the service layer's responsibility before building this DTO.

| Field | Notes |
|---|---|
| `id` `name` `email` `phoneNumber` | Identity fields |
| `emailVerified` `phoneVerified` | Verification status |
| `balance` | Current account balance |
| `homeCountry` `homeCity` | Location |
| `riskScore` | 0–100 fraud score — analyst use only |
| `active` `flagged` | Account state |
| `createdAt` | Registration timestamp |

---

### `PaymentResponse`

Immediate acknowledgement after submitting a payment. Deliberately thin because the full result (with fraud alerts) lives in `TransactionResponse`. Omits `receiverId` so this class is safe for both merchant and P2P payments without branching on the caller side.

| Field | Notes |
|---|---|
| `transactionId` | For tracking |
| `status` | `TransactionStatus` at submission time (`PENDING`) |
| `amount` `merchant` | Echo of what was submitted |
| `fraudScore` | Score at submission — may update after async analysis |
| `message` | Human-readable result |
| `submittedAt` | Server timestamp |

---

### `TransactionResponse`

Full transaction detail. Analyst-facing — includes `fraudScore`, `riskLevel`, and the full nested `fraudAlerts` list. Strip `fraudScore` and `riskLevel` for customer endpoints.

| Field | Notes |
|---|---|
| `id` `userId` `receiverId` | IDs |
| `amount` `merchant` `location` `country` | Payment details |
| `status` `riskLevel` `fraudScore` | Fraud analysis result |
| `declineReason` | Null if approved |
| `processingTimeMs` | Latency metric |
| `fraudAlerts` | `List<FraudAlertResponse>` — nested, one entry per rule that fired |
| `createdAt` `processedAt` | Timestamps |

---

### `FraudAlertResponse`

Read-only projection of `FraudAlert`. `userId` is denormalized (copied from the alert row) so this response doesn't need a join back through `Transaction` matching the same design decision made in the entity.

| Field | Notes |
|---|---|
| `id` `transactionId` `userId` | IDs |
| `ruleType` | Which fraud rule fired |
| `riskLevel` `scoreContribution` | Severity and contribution |
| `description` | Human-readable rule explanation |
| `metadata` | JSON string with rule-specific details |
| `resolved` | `false` until analyst marks it reviewed |
| `createdAt` | Alert timestamp |

---

### `SessionResponse`

One entry on the "your active sessions" screen. Never includes `refreshTokenHash` because the hash is an internal server secret. The client uses `sessionId` to request revocation via `RevokeSessionRequest`.

| Field | Notes |
|---|---|
| `sessionId` | Used to target this session for revocation |
| `deviceName` `deviceFingerprint` | Device identity |
| `ipAddressIssued` `lastSeenIp` `lastSeenAt` | IP/activity history |
| `expiresAt` | When this session expires |
| `active` | `false` if logged out or revoked |
| `createdAt` | Login timestamp |

---

### `DashboardStatsResponse`

Aggregate read — never maps to a single entity row. All counts come from repository projection queries, not entity graph traversal, to avoid loading thousands of records into memory.

| Field | Notes |
|---|---|
| `totalTransactions` `approvedTransactions` `declinedTransactions` | Transaction counts |
| `fraudBlockedTransactions` `flaggedForReview` | Fraud counts |
| `totalVolume` `fraudPreventedAmount` | Dollar amounts |
| `averageFraudScore` | System-wide average |
| `queueDepth` `activeWorkers` | Processing queue state |
| `alertsByRuleType` | `Map<String, Long>` — which rules are firing most |
| `transactionsByStatus` | `Map<String, Long>` — breakdown by status |

---

### `ApiErrorResponse`

Uniform error envelope for every 4xx/5xx response. Avoids leaking stack traces or Spring's default whitelabel error page to clients.

| Field | Notes |
|---|---|
| `status` | HTTP status code (400, 401, 403, 500, …) |
| `error` | Short label ("Bad Request", "Unauthorized") |
| `message` | Human-readable explanation |
| `path` | Which endpoint was called |
| `timestamp` | When the error occurred |
| `details` | List of individual field violations (populated for 400s) |

---

## Validation in Practice

Jakarta Validation runs before any controller method is called. Spring MVC wires it automatically when you annotate a controller parameter with `@Valid`. A violation bypasses the method entirely and triggers `MethodArgumentNotValidException`, which the `GlobalExceptionHandler` (Phase 6) maps to an `ApiErrorResponse` with `status=400` and a `details` list of per-field messages.

**Phone E.164 edge cases** (verified in `DtoDemo`):

```
+14155552671    → ACCEPTED  (valid US)
+447911123456   → ACCEPTED  (valid UK)
14155552671     → REJECTED  (missing +)
+0141555        → REJECTED  (country code starts with 0)
+44 791 112     → REJECTED  (spaces not allowed)
+1              → REJECTED  (too short)
```

---

## How to Run the Demo

```powershell
mvn compile exec:java "-Dexec.mainClass=main.dto.DtoDemo"
```

The demo builds every request and response DTO, validates all request DTOs against the Jakarta validator (the same validator Spring MVC runs on controller input), and prints pass/fail results with violation details. No database or running server required.

**Output (selected):**

```
-----------------------------
  REQUEST DTOs
-----------------------------

-- RegisterRequest (valid) --
  RegisterRequest(name=Jane Smith, email=jane.smith@example.com, username=janesmith, password=S3cur3P@ss!, phoneNumber=+14155552671, initialBalance=10000.00, homeCountry=US, homeCity=New York)
 Validation: PASS (0 violations)

-- RegisterRequest (invalid inputs) --
 Validation: FAIL (7 violation(s))
    [phoneNumber] Phone number must be in E.164 format, e.g. +14155552671 - got: '+0123456789'
    [name] must not be blank - got: ''
    [initialBalance] must be greater than or equal to 0.00 - got: '-5.00'
    [email] must be a well-formed email address - got: 'not-an-email'
    [homeCountry] size must be between 2 and 10 - got: 'X'
    [username] size must be between 3 and 50 - got: 'ab'
    [password] size must be between 8 and 100 - got: 'short'

-- Phone E.164 validation edge cases --
  +14155552671              -> ACCEPTED
  +447911123456             -> ACCEPTED
  +35312345678              -> ACCEPTED
  14155552671               -> REJECTED
  +0141555                  -> REJECTED
  +44 791 112               -> REJECTED
  +1                        -> REJECTED
  +1111111111111111         -> REJECTED

-----------------------------
  RESPONSE DTOs  (built by the server, no validation)
-----------------------------

-- LoginResponse --
  LoginResponse(accessToken=eyJhbGciOiJIUzI1NiJ9.access, refreshToken=eyJhbGciOiJIUzI1NiJ9.refresh, sessionId=session-uuid-abc, username=janesmith, tokenType=Bearer, accessTokenExpiresInSeconds=900, refreshTokenExpiresInSeconds=604800)

-- PaymentResponse --
  PaymentResponse(transactionId=txn-uuid-xyz, status=APPROVED, amount=250.00, merchant=Amazon, fraudScore=12.5, message=Payment approved, submittedAt=2026-06-11T00:43:28.571860300Z)

-- TransactionResponse (with fraud alerts) --
  TransactionResponse(id=txn-uuid-xyz, userId=user-uuid-jane, receiverId=null, amount=250.00, merchant=Amazon, location=New York, country=US, status=FLAGGED_FOR_REVIEW, riskLevel=MEDIUM, fraudScore=22.0, declineReason=null, processingTimeMs=38, fraudAlerts=[FraudAlertResponse(id=alert-uuid-1, transactionId=txn-uuid-xyz, userId=user-uuid-jane, ruleType=HIGH_AMOUNT, riskLevel=MEDIUM, scoreContribution=22.0, description=Amount is 2x above user average, metadata=null, resolved=false, createdAt=2026-06-11T00:43:28.581226300Z)], createdAt=2026-06-11T00:43:28.583225300Z, processedAt=null)
 fraudAlerts[0].ruleType:  HIGH_AMOUNT
 fraudAlerts[0].score:     22.0
```

---

## What Changed in Previous Phases

Small but important corrections made during Phase 4 and Phase 5 audits. None of these required redesigning the entities or repositories — they were gaps caught by writing tests and reviewing field-by-field.

**Phase 3 — Entities:**
- Added `UserSession` entity (one row per logged-in device; enables multi-device management and remote revocation)
- Added `@Version` optimistic locking to all entities that had concurrent write risk: `AppUser`, `Transaction`, `FraudAlert`, `MerchantBlacklist`, `UserSession` (`User` already had it)
- Removed `nullable=false` from primitive fields (`int failedLoginAttempts` in `AppUser`) — primitives cannot be null at the JVM level; the annotation was redundant and misleading
- Added `nullable=false` to `Transaction.riskLevel` column (missing from the original)
- Removed duplicate unique constraints — `@Column(unique=true)` alongside `@Index(unique=true)` created two DDL constraints on the same column; all unique constraints now use only `@Index(unique=true)`
- Added `@NotBlank` alongside `@Pattern` on `User.phoneNumber` — `@Pattern` permits `null` by specification, so without `@NotBlank` a missing phone would silently pass validation

**Phase 4 — Repositories:**
- All `@Modifying` mutations changed from `void` to `int` return type — a void return makes it impossible to detect when an update affects zero rows (user deleted, session already revoked, etc.)
- Dashboard queries updated to filter `active=true` — deactivated users were appearing in analyst views; fixed with `AndActiveTrue` suffix on derived queries and explicit `AND u.active = true` in JPQL
- `existsByMerchantName` replaced by `existsByMerchantNameAndActiveTrue` — a soft-deleted (inactive) merchant was blocking new transactions under the same name
- Added `UserSessionRepository` with all six methods
- Test count grew from 30 to 53 — added deactivated-user exclusion cases, `@PreUpdate` coverage, `updateLastSeen` return value assertions (1 = hit, 0 = miss), and content assertions on all 7 paginated queries

---

## What Forces the Next Step

DTOs define what the API sends and receives, but nothing decides what to do when a bad input arrives like an expired JWT, a non-existent user, an overdraft attempt. Typed exceptions map those failure modes to specific HTTP responses, and a `GlobalExceptionHandler` catches them all in one place instead of scattering `try/catch` blocks across every service. That is Phase 6.