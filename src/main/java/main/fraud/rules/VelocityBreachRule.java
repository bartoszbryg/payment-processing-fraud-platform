package main.fraud.rules;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects daily and hourly spending limit breaches.
 * Excluded statuses ensure declined/blocked transactions do not inflate spend totals.
 */
@Component
@RequiredArgsConstructor
public class VelocityBreachRule implements FraudRule {

    private final TransactionRepository transactionRepository;

    private static final Set<TransactionStatus> EXCLUDED = Set.of(
        TransactionStatus.DECLINED,
        TransactionStatus.FRAUD_BLOCKED
    );

    @Value("${fraud.rules.velocity.daily-limit:20000}")
    private BigDecimal dailyLimit;

    @Value("${fraud.rules.velocity.hourly-limit:5000}")
    private BigDecimal hourlyLimit;

    @PostConstruct
    void validateConfig() {
        if (hourlyLimit == null || hourlyLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("hourlyLimit must be positive");
        }

        if (dailyLimit == null || dailyLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("dailyLimit must be positive");
        }

        if (dailyLimit.compareTo(hourlyLimit) < 0) {
            throw new IllegalStateException("dailyLimit should be greater than or equal to hourlyLimit");
        }
    }

    @Override
    public List<FraudAlert> evaluate(Transaction transaction) {
        List<FraudAlert> alerts = new ArrayList<>();

        String senderId = transaction.getSender().getId();
        Instant now = Instant.now();

        checkPeriod(
            transaction,
            senderId,
            alerts,
            hourlyLimit,
            now.minus(1, ChronoUnit.HOURS),
            "hourly",
            20.0
        );

        checkPeriod(
            transaction,
            senderId,
            alerts,
            dailyLimit,
            now.minus(24, ChronoUnit.HOURS),
            "daily",
            25.0
        );

        return alerts;
    }

    private void checkPeriod(
        Transaction transaction,
        String senderId,
        List<FraudAlert> alerts,
        BigDecimal limit,
        Instant since,
        String period,
        double baseScore
    ) {
        BigDecimal spent = transactionRepository
            .sumAmountBySenderSince(senderId, since, EXCLUDED)
            .orElse(BigDecimal.ZERO);

        BigDecimal projected = spent.add(transaction.getAmount());

        if (projected.compareTo(limit) > 0) {
            double overage = projected.doubleValue() / limit.doubleValue();
            double score = Math.min(35.0, baseScore + (overage - 1.0) * 10.0);

            alerts.add(FraudAlert.builder()
                .transaction(transaction)
                .userId(senderId)
                .ruleType(FraudRuleType.VELOCITY_BREACH)
                .riskLevel(overage > 2.0 ? RiskLevel.CRITICAL : RiskLevel.HIGH)
                .scoreContribution(score)
                .description(String.format(
                    "%s spend limit breached: projected $%.2f vs limit $%.2f",
                    period,
                    projected,
                    limit
                ))
                .metadata(String.format(
                    "{\"period\":\"%s\",\"spent\":%.2f,\"limit\":%.2f,\"projected\":%.2f}",
                    period,
                    spent.doubleValue(),
                    limit.doubleValue(),
                    projected.doubleValue()
                ))
                .build());
        }
    }

    @Override
    public String getRuleName() {
        return "VELOCITY_BREACH";
    }

    @Override
    public int getPriority() {
        return 30;
    }
}