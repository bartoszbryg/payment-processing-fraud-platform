package main.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/*
    Safe public projection of the User entity.
    Omits: version (internal), updatedAt (internal), sentTransactions / receivedTransactions (lazy collections).
    balance, riskScore, and flagged are included here for analyst dashboards — strip them for customer-facing endpoints.
*/
@Data
@Builder
public class UserResponse {
    private String id;
    private String name;
    private String email;
    private String phoneNumber; // masked in customer-facing responses (e.g. "+1415***2671")
    private boolean emailVerified;
    private boolean phoneVerified;
    private BigDecimal balance;
    private String homeCountry;
    private String homeCity;
    private double riskScore;
    private boolean active;
    private boolean flagged;
    private Instant createdAt;
}
