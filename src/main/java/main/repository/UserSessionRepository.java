package main.repository;

import main.databaseModel.UserSession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    // "Your active devices" screen - all non-revoked sessions for this user
    List<UserSession> findByAppUserIdAndActiveTrue(String appUserId);

    // Refresh token exchange: find the session row to validate against
    // refreshTokenHash is bcrypt-hashed on the way in - never query on raw token value
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    // Revocation: ownership check baked into the query - user can only revoke their own sessions
    Optional<UserSession> findByIdAndAppUserId(String id, String appUserId);

    // "Log out all devices": deactivate every session for this user in one statement
    // Returns rows affected so the service can verify at least one row changed
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.active = false WHERE s.appUserId = :appUserId AND s.active = true")
    int deactivateAllByAppUserId(@Param("appUserId") String appUserId);

    // "Log out this device": deactivate a single session
    // Returns 1 if updated, 0 if the session was already deleted or never existed
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.active = false WHERE s.id = :id")
    int deactivateById(@Param("id") String id);

    // Update last-seen metadata on every authenticated request - keeps the devices page current
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.lastSeenIp = :ip, s.lastSeenAt = :now WHERE s.id = :id")
    int updateLastSeen(@Param("id") String id, @Param("ip") String ip, @Param("now") Instant now);

    // Scheduled cleanup: purge expired inactive sessions older than the retention window
    // Keeps the table from growing unbounded without losing recent audit history
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserSession s WHERE s.active = false AND s.expiresAt < :cutoff")
    int deleteExpiredSessions(@Param("cutoff") Instant cutoff);

    // Fraud signal: count how many active sessions this user has - abnormally high count is suspicious
    long countByAppUserIdAndActiveTrue(String appUserId);

}