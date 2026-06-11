package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Set;
import java.time.Instant;

/*
    System login user (admin/analyst/customer). Completely separate from User (payment account holder).
    Mixing these two is a security design flaw in banking systems so I design it separately.

    linkedUserId bridges this record to a User (bank account) for customers.
    Admins and analysts have linkedUserId = null — they have no bank account.
*/

@Entity
@Table(name = "app_users", indexes = {
    @Index(name = "idx_app_users_username", columnList = "username", unique = true),
    @Index(name = "idx_app_users_email", columnList = "email", unique = true),
    @Index(name = "idx_app_users_active", columnList = "is_active"),
    @Index(name = "idx_app_users_linked_user_id", columnList = "linked_user_id", unique = true)
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
    @Column(nullable = false)
    private String username;

    // Stored as bcrypt hash - raw password is never persisted anywhere
    @NotBlank
    @Column(nullable = false)
    private String password;

    @Email
    @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    // EAGER is intentional because Spring Security always needs the full role set to authorize a request
    @NotEmpty
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<String> roles;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    // Points to User.id for customers. Null for admin/analysts who have no bank account
    // Plain UUID string - no @OneToOne so Spring Security never joins to the users table
    // during token validation, and revoking access never touches the financial record.
    @Column(name = "linked_user_id")
    private String linkedUserId;

    // Brute-force protection: incremented on every failed login attempt, reset on success
    @Column(name = "failed_login_attempts")
    @Builder.Default
    private int failedLoginAttempts = 0;

    // Set when failedLoginAttempts hits the threshold - login rejected until time passes
    @Column(name = "locked_until")
    private Instant lockedUntil;

    // Tracks last login because stale admin credentials are a security risk
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Prevents lost-update when concurrent requests modify failedLoginAttempts or lockedUntil
    @Version
    private Long version;
    
}
