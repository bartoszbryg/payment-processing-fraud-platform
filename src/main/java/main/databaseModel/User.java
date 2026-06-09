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
    @Index(name = "idx_users_active_flagged", columnList = "is_active, is_flagged")
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
    @Column(nullable = false)
    private String email;

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
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", updatable = true)
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
