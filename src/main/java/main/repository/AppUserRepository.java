package main.repository;

import main.databaseModel.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional; 

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    // Called by Spring Security's service on every JWT-authenticated request
    // Hits the unique index on username - must be fast!
    Optional<AppUser> findByUsername(String username);

    // Registration guard: reject duplicate usernames before attempting insert
    boolean existsByUsername(String username);

    // Registration guard: reject duplicate emails before attempting insert
    boolean existsByEmail(String email);

    // Registration guard: one User account cannot have two login accounts
    boolean existsByLinkedUserId(String linkedUserId);

    // Used when a customer changes their email - must stay unique across the table
    Optional<AppUser> findByEmail(String email);

    // JWT resolution: after token validation load the AppUser to get roles and linkedUserId
    Optional<AppUser> findByLinkedUserId(String linkedUserId);

    // Brute-force protection: increment counter on failed login
    // Returns 1 if updated, 0 if the account was deleted between the failed attempt and this call
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser a SET a.failedLoginAttempts = a.failedLoginAttempts + 1 WHERE a.id = :id")
    int incrementFailedLoginAttempts(@Param("id") String id);

    // Called on successful login: clear counter and record timestamp
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser a SET a.failedLoginAttempts = 0, a.lockedUntil = null, a.lastLoginAt = :now WHERE a.id = :id")
    int recordSuccessfulLogin(@Param("id") String id, @Param("now") Instant now);

    // Lockout: set until-time when threshold is exceeded (e.g. 5 failed attempts = 15 min lockout)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser a SET a.lockedUntil = :until WHERE a.id = :id")
    int lockUntil(@Param("id") String id, @Param("until") Instant until);
    
}
