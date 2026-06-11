package main.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/*
    Omits internal fields like {@code version}, {@code updatedAt}, and raw FK IDs
    so the API contract stays stable even if the entity schema changes.
    Analyst-facing: includes fraudScore and riskLevel. Strip these for customer endpoints.
*/
@Data
@Builder
@Schema(description = "Transaction detail response")
public class TransactionResponse {

    private String id;
    private String userId; // sender
    private String receiverId; // null for merchant payments, set for P2P
    private BigDecimal amount;
    private String merchant; // null for P2P transfers
    private String location;
    private String country;
    private TransactionStatus status;
    private RiskLevel riskLevel;
    private double fraudScore;
    private String declineReason;
    private Long processingTimeMs;
    private List<FraudAlertResponse> fraudAlerts;
    private Instant createdAt;
    private Instant processedAt;
}