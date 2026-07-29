package main.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/*
    JSON shape pushed to /topic/fraud-alerts over STOMP. NON_NULL keeps the payload
    compact for fields a caller building this by hand might leave unset.
*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FraudAlertBroadcastMessage {

    private String transactionId;
    private String userId;
    private RiskLevel riskLevel;
    private double fraudScore;
    private List<FraudRuleType> ruleTypes;
    private String description;
    private Instant timestamp;

    // Single-alert convenience factory — callers with just one FraudAlert don't need
    // to reach for the transaction directly.
    public static FraudAlertBroadcastMessage from(FraudAlert alert) {
        Transaction transaction = alert.getTransaction();

        return FraudAlertBroadcastMessage.builder()
            .transactionId(transaction != null ? transaction.getId() : null)
            .userId(alert.getUserId())
            .riskLevel(alert.getRiskLevel())
            .fraudScore(transaction != null ? transaction.getFraudScore() : alert.getScoreContribution())
            .ruleTypes(List.of(alert.getRuleType()))
            .description(alert.getDescription())
            .timestamp(alert.getCreatedAt() != null ? alert.getCreatedAt() : Instant.now())
            .build();
    }

    // Aggregate factory — one transaction can trigger several rules at once, so the
    // broadcast message rolls every fired rule type into a single push instead of one per alert.
    public static FraudAlertBroadcastMessage from(List<FraudAlert> alerts, Transaction transaction) {
        List<FraudRuleType> ruleTypes = alerts.stream()
            .map(FraudAlert::getRuleType)
            .distinct()
            .toList();

        String description = alerts.stream()
            .map(FraudAlert::getDescription)
            .distinct()
            .collect(Collectors.joining("; "));

        return FraudAlertBroadcastMessage.builder()
            .transactionId(transaction.getId())
            .userId(transaction.getSender().getId())
            .riskLevel(transaction.getRiskLevel())
            .fraudScore(transaction.getFraudScore())
            .ruleTypes(ruleTypes)
            .description(description)
            .timestamp(transaction.getProcessedAt() != null ? transaction.getProcessedAt() : Instant.now())
            .build();
    }
}