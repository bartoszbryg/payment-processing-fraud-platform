package main.service;

import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.dto.response.FraudAlertResponse;
import main.dto.response.TransactionResponse;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/*
    Pure field mapping — no business logic, no I/O.
    Kept as a plain @Component instead of MapStruct so there are zero annotation-processor
    dependencies to manage; the mapping is straightforward enough that generated code adds
    no value here.
*/
@Component
public class TransactionMapper {

    public TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
            .id(t.getId())
            .userId(t.getSender() != null ? t.getSender().getId() : null)
            .receiverId(t.getReceiver() != null ? t.getReceiver().getId() : null)
            .amount(t.getAmount())
            .merchant(t.getMerchant())
            .location(t.getLocation())
            .country(t.getCountry())
            .status(t.getStatus())
            .riskLevel(t.getRiskLevel())
            .fraudScore(t.getFraudScore())
            .declineReason(t.getDeclineReason())
            .processingTimeMs(t.getProcessingTimeMs())
            .fraudAlerts(mapAlerts(t.getFraudAlerts()))
            .createdAt(t.getCreatedAt())
            .processedAt(t.getProcessedAt())
            .build();
    }

    private List<FraudAlertResponse> mapAlerts(List<FraudAlert> alerts) {
        if (alerts == null) return Collections.emptyList();
        return alerts.stream()
            .map(a -> FraudAlertResponse.builder()
                .id(a.getId())
                .transactionId(a.getTransaction() != null ? a.getTransaction().getId() : null)
                .userId(a.getUserId())
                .ruleType(a.getRuleType())
                .riskLevel(a.getRiskLevel())
                .scoreContribution(a.getScoreContribution())
                .description(a.getDescription())
                .metadata(a.getMetadata())
                .resolved(a.isResolved())
                .createdAt(a.getCreatedAt())
                .build())
            .toList();
    }
}