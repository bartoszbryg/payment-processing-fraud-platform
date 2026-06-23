package main.fraud.rules;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects velocity attacks: many transactions from the same sender in a short window.
 * Stolen cards/accounts are often tested with rapid low-value charges before larger fraud.
 */
@Component
@RequiredArgsConstructor
public class RapidTransactionRule implements FraudRule {

    private final TransactionRepository transactionRepository;

    @Value("${fraud.rules.rapid-transaction.window-minutes:5}")
    private int windowMinutes;

    @Value("${fraud.rules.rapid-transaction.max-count:3}")
    private int maxCount;

    @Value("${fraud.rules.rapid-transaction.critical-count:10}")
    private int criticalCount;

    @PostConstruct
    void validateConfig() {
        if (windowMinutes <= 0) {
            throw new IllegalStateException("windowMinutes must be positive");
        }

        if (maxCount <= 0) {
            throw new IllegalStateException("maxCount must be positive");
        }

        if (criticalCount <= maxCount) {
            throw new IllegalStateException("criticalCount must be greater than maxCount");
        }
    }

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();

        String senderId = transaction.getSender().getId();
        Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);

        long count = transactionRepository.countRecentBySender(senderId, since);

        if (count >= criticalCount) {
            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(senderId)
                .ruleType(FraudRuleType.RAPID_TRANSACTIONS)
                .riskLevel(RiskLevel.CRITICAL)
                .scoreContribution(45.0)
                .description(String.format(
                    "CRITICAL: %d transactions in %d minutes — possible account takeover",
                    count, windowMinutes
                ))
                .metadata(String.format(
                    "{\"count\":%d,\"windowMinutes\":%d,\"criticalThreshold\":%d}",
                    count, windowMinutes, criticalCount
                ))
                .build());
        } else if (count >= maxCount) {
            double score = computeScore(count);

            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(senderId)
                .ruleType(FraudRuleType.RAPID_TRANSACTIONS)
                .riskLevel(RiskLevel.HIGH)
                .scoreContribution(score)
                .description(String.format(
                    "%d transactions in last %d minutes exceeds velocity limit of %d",
                    count, windowMinutes, maxCount
                ))
                .metadata(String.format(
                    "{\"count\":%d,\"windowMinutes\":%d,\"threshold\":%d}",
                    count, windowMinutes, maxCount
                ))
                .build());
        }

        return alerts;
    }

    private double computeScore(long count) {
        double minScore = 15.0;
        double maxScore = 30.0;

        double progress = (double) (count - maxCount) / (criticalCount - maxCount);
        progress = Math.max(0.0, Math.min(1.0, progress));

        return minScore + progress * (maxScore - minScore);
    }

    @Override
    public String getRuleName() {
        return "RAPID_TRANSACTIONS";
    }

    @Override
    public int getPriority() {
        return 20; // one DB count query, so it runs after pure arithmetic rules
    }
}