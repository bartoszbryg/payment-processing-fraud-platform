package main.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/*
    Exactly one of {@code merchant} or {@code receiverId} must be set.
    Mirroring the XOR constraint on the Transaction entity - validated in the service layer,
    not here, because cross-field rules don't belong in single-field annotations.
    
    userId is intentionally absent because the service layer extracts it from the JWT so
    the caller can never claim to be someone else by supplying a different ID here.
*/
@Data
@Schema(description = "Payment submission request - merchant payment or P2P transfer")
public class PaymentRequest {

    @NotNull
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 2)
    @Schema(description = "Payment amount", example = "250.00")
    private BigDecimal amount;

    // Merchant payments: set merchant, leave receiverId null
    @Size(max = 255)
    @Schema(description = "Merchant name - set for merchant payments, omit for P2P", example = "Amazon")
    private String merchant;

    // P2P transfers: set receiverId, leave merchant null
    @Schema(description = "Recipient user ID - set for P2P transfers, omit for merchant payments", example = "receiver-uuid-here")
    private String receiverId;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Transaction location (city)", example = "New York")
    private String location;

    @Size(max = 5)
    @Schema(description = "Two-letter country code", example = "US")
    private String country;

    @Schema(description = "Client IP address for geo-analysis", example = "192.168.1.1")
    private String ipAddress;

    @Schema(description = "Unique device fingerprint", example = "d41d8cd98f00b204")
    private String deviceFingerprint;
    
}