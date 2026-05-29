# Phase 2 — Define the States That Exist (Enums)

Before writing any database table or service, define all the possible states and categories in the system. Enums have zero dependencies — they don't import anything, they don't extend anything, they don't need a database, Spring, or any other class to exist first. They're pure constants that every other class will reference later.

---

## What Was Added

```
└── src/
    └── main/
        └── java/main/application/
            └── states/
                ├── TransactionStatus.java   ← lifecycle states of a transaction
                ├── RiskLevel.java           ← risk tiers with score ranges
                ├── FraudRuleType.java       ← names of all fraud rules that can fire
                └── EnumDemo.java            ← manual demo, run it to see all values printed
```

---

## Why Enums First

Every model that comes next will need to reference these.

For example: a `Transaction` entity will have a `status` field — you can't declare that field as `TransactionStatus` until `TransactionStatus` exists. Same for `RiskLevel` on a `FraudAlert`. Same for `FraudRuleType` on a rule result record.

If you skip this step and go straight to entities, you end up defining states inline as plain strings, which is messy and error-prone. Enums give you compile-time safety — if you typo `FRAUD_BLOKED`, the compiler catches it immediately.

---

## The Three Enums

### `TransactionStatus.java`

The six states a transaction can be in:

| Status | Meaning |
|---|---|
| `PENDING` | Just received, waiting to be picked up |
| `PROCESSING` | A worker thread is currently analyzing it |
| `APPROVED` | Passed all fraud checks |
| `DECLINED` | Rejected (insufficient funds, expired card, etc.) |
| `FLAGGED_FOR_REVIEW` | Score was medium/high — needs a human to look at it |
| `FRAUD_BLOCKED` | Score was critical — automatically blocked |

A transaction moves through these states in order. It starts as `PENDING`, becomes `PROCESSING`, and ends as one of the last four.

---

### `RiskLevel.java`

Four risk tiers, each with a score range:

| Level | Min Score | Max Score |
|---|---|---|
| `LOW` | 0 | 30 |
| `MEDIUM` | 31 | 60 |
| `HIGH` | 61 | 85 |
| `CRITICAL` | 86 | 100 |

It also has a `fromScore(double score)` method. You pass in a number like `92.0` and it returns `CRITICAL`. This logic lives here because multiple classes need to do this conversion — you write it once and everyone calls it.

```java
RiskLevel level = RiskLevel.fromScore(92.0);  // → CRITICAL
```

The fields `minScore` and `maxScore` are stored on each enum value so you can also read back what range a level covers:

```java
RiskLevel.HIGH.getMinScore()  // → 61
RiskLevel.HIGH.getMaxScore()  // → 85
```

**Why does RiskLevel exist if the architecture already defines score thresholds (< 40, 40–69, ≥ 70)?**

Because they do two different things:

- The **score thresholds** in `application.yml` decide what *happens* to a transaction — approved, flagged, or blocked. That is a business rule.
- `RiskLevel` is a **label** attached to a fraud alert or transaction record for display purposes — dashboards, audit logs, filtering.

A transaction blocked with score 92 gets the decision `FRAUD_BLOCKED` (from the threshold) and the label `CRITICAL` (from `RiskLevel`). Without `RiskLevel`, your alert records would only store a raw number. With it, an analyst can filter the dashboard by "show me all CRITICAL alerts today" without doing math on scores.

The ranges don't perfectly overlap with the thresholds on purpose — the thresholds are tunable business rules, the risk tiers are fixed reporting categories.

---

### `FraudRuleType.java`

Every rule that can fire during fraud detection has a name here:

| Rule | What it catches |
|---|---|
| `HIGH_AMOUNT` | Transaction amount over the threshold |
| `RAPID_TRANSACTIONS` | Too many transactions in a short window |
| `VELOCITY_BREACH` | Daily or hourly spend limit exceeded |
| `BLACKLISTED_MERCHANT` | Merchant is on the blocked list |
| `UNUSUAL_LOCATION` | Transaction from an unexpected country or region |
| `GRAPH_CIRCULAR_FLOW` | Money going in circles — laundering pattern |
| `GRAPH_HIGH_DEGREE_NODE` | One account transacting with unusually many others |
| `GRAPH_SUSPICIOUS_CLUSTER` | Tightly connected group of accounts behaving oddly |
| `ANOMALY_SCORE_BREACH` | ML model returned a score above the threshold |
| `CROSS_BORDER_HIGH_RISK` | Cross-border transaction to a high-risk country |

When a rule fires, it leaves a typed record using one of these values. This makes it easy to filter alerts later — for example, show me all transactions blocked by `GRAPH_CIRCULAR_FLOW`.

---

## How the Three Connect

```
RiskLevel.fromScore(92.0)
        ↓
    CRITICAL
        ↓
TransactionStatus.FRAUD_BLOCKED

FraudRuleType.HIGH_AMOUNT fired
        ↓
stored on the fraud alert record
        ↓
visible on the dashboard
```

---

## How to Run the Demo

`EnumDemo.java` is a plain `main()` class — no Spring, no Maven needed. It prints every value from all three enums plus a worked fraud decision example.

**From the project root in PowerShell:**

```powershell
# Step 1 — Compile
javac src/main/java/main/application/states/*.java -d enumDemo

# Step 2 — Run
java -cp enumDemo main.application.states.EnumDemo

# Step 3 — Clean up
rmdir /s /q enumDemo
```

**Expected output:**

```
-- Transaction Status --
PENDING
PROCESSING
APPROVED
DECLINED
FLAGGED_FOR_REVIEW
FRAUD_BLOCKED

Fraud Rule Type:
HIGH_AMOUNT
RAPID_TRANSACTIONS
VELOCITY_BREACH
BLACKLISTED_MERCHANT
UNUSUAL_LOCATION
GRAPH_CIRCULAR_FLOW
GRAPH_HIGH_DEGREE_NODE
GRAPH_SUSPICIOUS_CLUSTER
ANOMALY_SCORE_BREACH
CROSS_BORDER_HIGH_RISK

-- Risk Level Ranges --
LOW: Min = 0 | Max = 30
MEDIUM: Min = 31 | Max = 60
HIGH: Min = 61 | Max = 85
CRITICAL: Min = 86 | Max = 100

-- RiskLevel.fromScore() --
0.0 -> LOW
10.0 -> LOW
30.0 -> LOW
31.0 -> MEDIUM
45.0 -> MEDIUM
60.0 -> MEDIUM
61.0 -> HIGH
70.0 -> HIGH
85.0 -> HIGH
86.0 -> CRITICAL
92.0 -> CRITICAL
100.0 -> CRITICAL

-- Example Fraud Decision --
Score: 92.0
Risk Level: CRITICAL
Final Status: FRAUD_BLOCKED
```

---

## What Forces the Next Step

States are defined. Now you need the actual data structures — the JPA entities (`Transaction`, `Account`, `FraudAlert`) that will use these enums as field types. That is Phase 3.
