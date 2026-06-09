package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Set;
import java.time.Instant;

/*
    System login user (admin/analyst). Completely separate from User (payment account holder).
    Mixing these two is a security design flaw in banking systems so I design it separately
*/

@Entity
@Table(name = "app_users", indexes = {
    @Index(name = "idx_app_users_username", columnList = "username", unique = true),
    @Index(name = "idx_app_users_active", columnList = "is_active")
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank
    @Column(nullable = false)
    private String password;

    // EAGER is intentional because Spring Security always needs the full role set to authorize a request
    @NotEmpty
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<String> roles;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    // Tracks last login because stale admin credentials are a security risk
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

}
