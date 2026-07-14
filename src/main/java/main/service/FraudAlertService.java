package main.service;

import lombok.RequiredArgsConstructor;
import main.databaseModel.FraudAlert;
import main.dto.response.FraudAlertResponse;
import main.exception.ResourceNotFoundException;
import main.repository.FraudAlertRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import main.application.states.FraudRuleType;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FraudAlertService {

    private final FraudAlertRepository fraudAlertRepository;

    @Transactional(readOnly = true)
    public List<FraudAlertResponse> getAlertsForTransaction(String transactionId) {
        return fraudAlertRepository.findByTransactionId(transactionId)
            .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<FraudAlertResponse> getAlertsForUser(String userId, Pageable pageable) {
        return fraudAlertRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    // Admin-only: unresolved alerts across all users — analyst review queue.
    @Transactional(readOnly = true)
    public Page<FraudAlertResponse> getUnresolvedAlerts(Pageable pageable) {
        return fraudAlertRepository.findAllByResolved(false, pageable).map(this::toResponse);
    }

    // Analyst tool: "show me every HIGH_AMOUNT alert fired in the last 24 hours"
    // countByRuleTypeSince stays in DashboardService — that returns counts for a chart, not alert objects.
    @Transactional(readOnly = true)
    public List<FraudAlertResponse> getAlertsByRuleSince(FraudRuleType ruleType, Instant since) {
        return fraudAlertRepository.findByRuleTypeSince(ruleType, since)
            .stream().map(this::toResponse).toList();
    }

    @Transactional
    public FraudAlertResponse resolveAlert(String alertId) {
        FraudAlert alert = fraudAlertRepository.findById(alertId)
            .orElseThrow(() -> ResourceNotFoundException.forAlert(alertId));
        alert.setResolved(true);
        return toResponse(fraudAlertRepository.save(alert));
    }

    private FraudAlertResponse toResponse(FraudAlert a) {
        return FraudAlertResponse.builder()
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
            .build();
    }
}