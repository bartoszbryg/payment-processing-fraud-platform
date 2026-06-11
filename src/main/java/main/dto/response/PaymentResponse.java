package main.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import main.application.states.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/*
    Immediate acknowledgement - deliberately thin. The full result lives in TransactionResponse.
    Omits receiverId so this class is safe for both merchant and P2P payments without branching.
 */
@Data
@Builder
@Schema(description = "Immediate response after submitting a payment")
public class PaymentResponse {

    @Schema(description = "Transaction ID for tracking")
    private String transactionId;

    @Schema(description = "Current status")
    private TransactionStatus status;

    private BigDecimal amount;
    private String merchant;

    @Schema(description = "Fraud score 0-100")
    private double fraudScore;

    @Schema(description = "Human-readable message")
    private String message;

    private Instant submittedAt;
    
}