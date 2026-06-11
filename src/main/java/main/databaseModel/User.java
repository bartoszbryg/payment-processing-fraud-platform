package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


@Entity
@Table (name="users", indexes = {
    @Index(name = "idx_users_email", columnList = "email", unique = true),
    @Index(name = "idx_users_active_flagged", columnList = "is_active, is_flagged"),
    @Index(name = "idx_users_flagged", columnList = "is_flagged"),
    @Index(name = "idx_users_risk_score", columnList = "risk_score"),
    // Admin can quickly find accounts that registered but never completed OTP verification
    @Index(name = "idx_users_email_verified", columnList = "email_verified"),
    @Index(name = "idx_users_phone_verified", columnList = "phone_verified")
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Email
    @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    // Required at registration - verified via SMS OTP before account activation
    // E.164 format enforced at both the DTO and entity level - SMS gateways reject anything else
    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$")
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    // false until the user clicks the verification link sent to their email after registration
    // Payments are blocked until both flags are true - a field that was never confirmed
    // cannot be trusted for OTP delivery or fraud contract
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    // false until the user enters the SMS OTP sent to phoneNumber during registration
    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    @NotNull
    @DecimalMin("0.00")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @NotBlank
    @Column(name = "home_country", nullable = false)
    private String homeCountry;

    @Column(name = "home_city")
    private String homeCity;

    @Column(name = "risk_score")
    @Builder.Default
    private double riskScore = 0.0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "is_flagged", nullable = false)
    @Builder.Default
    private boolean flagged = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Locking: Prevents lost-update when two threads modify balance/riskScore concurrently
    @Version
    private Long version;

    @OneToMany(mappedBy = "sender", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Transaction> sentTransactions = new ArrayList<>();

    // No cascade - deleting a User should not delete transactions they received 
    @OneToMany(mappedBy = "receiver", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Transaction> receivedTransactions = new ArrayList<>();

}
