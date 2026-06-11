package main.databaseModel;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "merchant_blacklist", indexes = {
    @Index(name = "idx_merchant_name", columnList = "merchant_name", unique = true)
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantBlacklist {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank
    @Column(name = "merchant_name", nullable = false, unique = true)
    private String merchantName;

    @NotBlank
    @Column(nullable = false)
    private String reason;

    @Column(name = "added_by")
    private String addedBy;

    // Deactivating preserves audit history of when/why the merchant was blacklisted
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Prevents lost-update when concurrent admin operations modify or soft-delete the same entry
    @Version
    private Long version;

}
