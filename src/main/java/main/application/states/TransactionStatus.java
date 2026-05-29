package main.application.states;

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    DECLINED,
    FLAGGED_FOR_REVIEW,
    FRAUD_BLOCKED
}