package main.databaseModel;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/*
    One row per logged-in device. Enables "logged in on 3 devices" and remote session revocation

    refreshTokenHash is a bcrypt hash - the raw refresh token is given to the client once and never stored
    When the client exchanges it, we hash the incoming token and compare against this column

    active = false means logged out or revoked - the row is retained for audit purposes,
    not deleted, so security teams can investigate suspicious session activity
*/

@Entity
@Table(name = "user_sessions", indexes = {
    // Primary query path: all active sessions for a user (the "your devices" page)
    @Index(name = "idx_session_app_user_id", columnList = "app_user_id"),
    // Cleanup job: find and purge rows where expires_at is in the past
    @Index(name = "idx_session_expires_at", columnList = "expires_at"),
    // Active session lookup - composite avoids a filtered scan on large session tables
    @Index(name = "idx_session_app_user_active", columnList = "app_user_id, is_active")
})

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // Plain UUID string. No @ManyToOne so loading sessions never triggers an AppUser join
    @NotBlank
    @Column(name = "app_user_id", nullable = false)
    private String appUserId;

    // bcrypt hash of the refresh token, raw value exists only in the HTTP response and client storage
    @NotBlank
    @Column(name = "refresh_token_hash", nullable = false)
    private String refreshTokenHash;

    // Fingerprint captured at login - compared on each refresh to detect token theft
    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    // Human-readable label shown on the "your active sessions" screen ("John's iPhone 14")
    @Column(name = "device_name")
    private String deviceName;

    // IP at session creation - sudden change mid-session is a fraud signal
    @Column(name = "ip_address_issued")
    private String ipAddressIssued;

    // Updated on every authenticated request - used for "last seen" display and idle detection
    @Column(name = "last_seen_ip")
    private String lastSeenIp;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    // When this refresh token expires - service rejects exchange attempts after this point
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // false = logged out or admin-revoked. Row is kept for audit, not deleted.
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Prevents lost-update when concurrent logout/refresh/revocation operations race on active flag
    @Version
    private Long version;
    
}