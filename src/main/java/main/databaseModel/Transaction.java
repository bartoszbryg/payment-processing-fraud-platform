package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_txn_user_id", columnList = "user_id"),
    @Index(name = "idx_txn_receiver_id", columnList = "receiver_id"),
    @Index(name = "idx_txn_status", columnList = "status"),
    @Index(name = "idx_txn_created_at", columnList = "created_at"),
    @Index(name = "idx_txn_merchant", columnList = "merchant"),
    // Composite index covers the most common fraud-query pattern: user + time range
    @Index(name = "idx_txn_user_created", columnList = "user_id, created_at"),
    // Graph-based fraud rules (circular flow, high degree nodes) query P2P pairs
    @Index(name = "idx_txn_p2p", columnList = "user_id, receiver_id")
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // Avoid loading the full User graph on every transaction query
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User sender;

    // null = merchant payment; non-null = P2P transfer between account holders
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id")
    private User receiver;

    @NotNull
    @DecimalMin("0.01")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // null when this is a P2P transfer; @PrePersist enforces that exactly one of merchant/receiver is set
    @Column
    private String merchant;

    @NotBlank
    @Column(nullable = false)
    private String location;

    @Column
    private String country;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    @Builder.Default
    private RiskLevel riskLevel = RiskLevel.LOW;

    // double is intentional for a score (0-100 range); BigDecimal is only needed for monetary amounts
    @Column(name = "fraud_score")
    @Builder.Default
    private double fraudScore = 0.0;

    // column/field for metrics - used for performance monitoring
    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "decline_reason")
    private String declineReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    // Map to transaction field inside FraudAlert
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FraudAlert> fraudAlerts = new ArrayList<>();

    // Convenience - avoid WHERE receiver_id IS NOT NULL spread across query code
    @Transient
    public boolean isP2P() {
        return receiver != null;
    }

    @PrePersist
    @PreUpdate
    private void validateMerchantOrReceiver() {
        boolean hasMerchant = false;
        if(merchant != null && !merchant.isBlank()) hasMerchant = true;

        boolean hasReceiver = false;
        if(receiver != null) hasReceiver = true;

        if (hasMerchant == hasReceiver) {
            throw new IllegalStateException(
                "Transactions must have either a merchant (payment) or a receiver (P2P), not both or neither"
            );
        }
    }

}
