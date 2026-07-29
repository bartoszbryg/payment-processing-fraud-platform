package main.repository;

import main.databaseModel.User;


import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.math.BigDecimal;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    // Login / registration checks - hit the unique index on email
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Dashboard: list flagged active accounts - hits dedicated idx_users_flagged single-column index
    // Filters active=true so deactivated users don't appear on analyst dashboards
    // Uses the composite idx_users_active_flagged index (is_active leads, is_flagged follows)
    Page<User> findByFlaggedAndActiveTrue(boolean flagged, Pageable pageable);

    // Risk dashboard: active users above a given fraud-score threshold, ordered worst-first
    // Filters active=true so deactivated accounts don't pollute the risk queue
    @Query("SELECT u FROM User u WHERE u.riskScore >= :threshold AND u.active = true ORDER BY u.riskScore DESC")
    Page<User> findHighRiskUsers(@Param("threshold") double threshold, Pageable pageable);


    // Balance deduction: only succeeds when the user has enough funds.
    // Returns 1 (row updated) or 0 (insufficient funds / user not found) - no exception thrown.
    // @Modifying tells Spring Data this is a DML statement, not a SELECT
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance - :amount WHERE u.id = :userId AND u.balance >= :amount")
    int deductBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    // After the fraud engine scores a transaction it calls this once to persist the new risk profile.
    // Plain overwrite is correct here: this is always called synchronously, in-order, with the
    // fraud engine's fresh, complete assessment of the current transaction.
    // Returns rows affected (1 = updated, 0 = user not found / was deleted between score and write).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.riskScore = :score, u.flagged = :flagged WHERE u.id = :userId")
    int updateRiskProfile(@Param("userId") String userId, @Param("score") double score, @Param("flagged") boolean flagged);

    // Used by async, out-of-order enrichment (MlFraudEnrichmentService) instead of
    // updateRiskProfile. A background ML call can resolve well after later transactions from
    // the same user have already overwritten riskScore with their own fresh assessment, with
    // no ordering guarantee either way - a plain overwrite here could silently clobber a
    // higher, more complete rules+graph score with a late, ML-only number. GREATEST/OR only
    // ever escalates: the stored score can never end up lower and flagged can never flip back
    // to false because of this call, regardless of when it lands.
    // Returns rows affected (1 = updated, 0 = user not found / was deleted between score and write).
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE User u SET
            u.riskScore = CASE WHEN :score > u.riskScore THEN :score ELSE u.riskScore END,
            u.flagged = CASE WHEN :flagged = true THEN true ELSE u.flagged END
        WHERE u.id = :userId
        """)
    int raiseRiskProfile(@Param("userId") String userId, @Param("score") double score, @Param("flagged") boolean flagged);

    // Called by the email verification flow when the user clicks the confirmation link
    // Returns 1 if updated, 0 if the userId no longer exists
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.emailVerified = true WHERE u.id = :userId")
    int markEmailVerified(@Param("userId") String userId);

    // Called by the registration flow when the user submits the correct SMS OTP
    // Returns 1 if updated, 0 if the userId no longer exists
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.phoneVerified = true WHERE u.id = :userId")
    int markPhoneVerified(@Param("userId") String userId);

    // Admin: accounts that registered but never completed email verification
    // Hits idx_users_email_verified - useful for cleanup jobs and fraud investigation
    // Filters active=true - deactivated accounts are irrelevant for cleanup jobs
    Page<User> findByEmailVerifiedAndActiveTrue(boolean emailVerified, Pageable pageable);

    // Admin: accounts with an unverified phone - these cannot receive step-up auth OTPs
    // Filters active=true - deactivated accounts are irrelevant for OTP gap analysis
    Page<User> findByPhoneVerifiedAndActiveTrue(boolean phoneVerified, Pageable pageable);
    
}