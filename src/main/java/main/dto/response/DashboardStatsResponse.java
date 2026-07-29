package main.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/*
    Aggregate read - never maps to a single entity row.
    All counts come from repository projection queries, not entity graph traversal,
    to avoid loading thousands of records into memory.
*/
@Data
@Builder(toBuilder = true)
public class DashboardStatsResponse {
    private long totalTransactions;
    private long approvedTransactions;
    private long declinedTransactions;
    private long fraudBlockedTransactions;
    private long flaggedForReview;
    private BigDecimal totalVolume;
    private BigDecimal fraudPreventedAmount;
    private double averageFraudScore;
    private int queueDepth;
    private long activeWorkers;
    private Map<String, Long> alertsByRuleType;
    private Map<String, Long> transactionsByStatus;
}