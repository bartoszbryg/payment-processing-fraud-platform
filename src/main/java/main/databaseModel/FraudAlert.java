package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "fraud_alerts", indexes = {
    @Index(name = "idx_alert_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_alert_user_id", columnList = "user_id"),
    @Index(name = "idx_alert_rule_type", columnList = "rule_type"),
    // Analysis filter unresolved alerts; partial-style composite index helps here
    @Index(name = "idx_alert_resolved_created", columnList = "is_resolved, created_at")
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudAlert {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    // Intentional denormalization: avoid a join through transaction when querying alerts by user
    // No FK constraint by design - if User is deleted this alert is retained for audit purposes
    // and will not be automatically cleaned up. The service layer owns that lifecycle
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private FraudRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "score_contribution", nullable = false)
    private double scoreContribution;

    @NotBlank
    @Column(name = "description", nullable = false, length = 1024)
    private String description;

    // Stored as JSON strings - avoid a separate table for sparse, rule-specific metadata
    @Column(name = "metadata", length = 2048)
    private String metadata;

    @Column(name = "is_resolved", nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Prevents lost-update when concurrent fraud rule engines update scoreContribution or resolved
    @Version
    private Long version;

}
