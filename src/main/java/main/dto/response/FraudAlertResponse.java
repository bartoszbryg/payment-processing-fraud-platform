package main.dto.response;

import lombok.Builder;
import lombok.Data;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;

import java.time.Instant;

/*
    Read-only projection of FraudAlert.
    userId is denormalized (copied from the alert row) so this response doesn't need a join
    back through Transaction - matches the same design decision made in the entity.
 */
@Data
@Builder
public class FraudAlertResponse {
    private String id;
    private String transactionId;
    private String userId;
    private FraudRuleType ruleType;
    private RiskLevel riskLevel;
    private double scoreContribution;
    private String description;
    private String metadata;
    private boolean resolved;
    private Instant createdAt;
}
