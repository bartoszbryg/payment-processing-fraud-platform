package main.fraud.rules;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure BigDecimal comparison — zero I/O, runs first.
 * Score scales from 15 to 25 between the high and critical thresholds.
 */
@Component
public class HighAmountRule implements FraudRule {

    @Value("${fraud.rules.high-amount.threshold:10000}")
    private BigDecimal highThreshold;

    @Value("${fraud.rules.high-amount.critical-threshold:50000}")
    private BigDecimal criticalThreshold;

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();
        BigDecimal amount = transaction.getAmount();

        if (amount.compareTo(criticalThreshold) >= 0) {
            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(transaction.getSender().getId())
                .ruleType(FraudRuleType.HIGH_AMOUNT)
                .riskLevel(RiskLevel.CRITICAL)
                .scoreContribution(40.0)
                .description(String.format("CRITICAL: Amount $%.2f far exceeds critical threshold $%.2f",
                    amount, criticalThreshold))
                .metadata(String.format("{\"amount\":%.2f,\"criticalThreshold\":%.2f}",
                    amount.doubleValue(), criticalThreshold.doubleValue()))
                .build());
        } else if (amount.compareTo(highThreshold) >= 0) {
            double score = computeScore(amount);

            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(transaction.getSender().getId())
                .ruleType(FraudRuleType.HIGH_AMOUNT)
                .riskLevel(RiskLevel.HIGH)
                .scoreContribution(score)
                .description(String.format("Amount $%.2f exceeds high-value threshold $%.2f",
                    amount, highThreshold))
                .metadata(String.format("{\"amount\":%.2f,\"threshold\":%.2f}",
                    amount.doubleValue(), highThreshold.doubleValue()))
                .build());
        }

        return alerts;
    }

    private double computeScore(BigDecimal amount) {
        double minScore = 15.0;
        double maxScore = 25.0;

        double high = highThreshold.doubleValue();
        double critical = criticalThreshold.doubleValue();
        double value = amount.doubleValue();

        double progress = (value - high) / (critical - high);
        progress = Math.max(0.0, Math.min(1.0, progress));

        return minScore + progress * (maxScore - minScore);
    }

    @Override
    public String getRuleName() {
        return "HIGH_AMOUNT";
    }

    @Override
    public int getPriority() {
        return 5; // pure BigDecimal comparison — cheapest, runs first
    }
}