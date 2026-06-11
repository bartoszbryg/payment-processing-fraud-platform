package main.repository;

import main.application.states.FraudRuleType;
import main.databaseModel.FraudAlert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, String> {

    // All alerts for a single transaction (typically 1-3 rules fired)
    // Hits idx_alert_transaction_id
    List<FraudAlert> findByTransactionId(String transactionId);
    
    // All alerts for a user - page so the analyst dashboard doesn't load thousands of rows
    // Hits idx_alert_user_id (userId is denormalized directly on FraudAlert for fast lookup)
    Page<FraudAlert> findByUserId(String userId, Pageable pageable);

    // Admin-only: all unresolved alerts across every user - analyst review queue
    // Never call from a user-facing endpoint - use findByUserIdAndResolved for that
    Page<FraudAlert> findAllByResolved(boolean resolved, Pageable pageable);

    // User-scoped: only this user's resolved/unresolved alerts
    Page<FraudAlert> findByUserIdAndResolved(String userId, boolean resolved, Pageable pageable);

    // Rule-pattern analysis: "how often has rule X fired in the last hour/day?"
    // Hits idx_alert_rule_type
    @Query("""
        SELECT fa FROM FraudAlert fa
        WHERE fa.ruleType = :ruleType
            AND fa.createdAt >= :since
        ORDER BY fa.createdAt DESC    
        """)
    List<FraudAlert> findByRuleTypeSince(@Param("ruleType") FraudRuleType ruleType, @Param("since") Instant since);

    // Rule-type breakdown for the dashboard (e.g. pie chart of rule frequencies)
    // Returns Object[]{FraudRuleType ruleType, Long count} rows - caller casts
    @Query("""
        SELECT fa.ruleType, COUNT(fa)
        FROM FraudAlert fa
        WHERE fa.createdAt >= :since
        GROUP BY fa.ruleType
        ORDER BY COUNT(fa) DESC    
        """)
    List<Object[]> countByRuleTypeSince(@Param("since") Instant since);

    // Alert-rate check: "how many alerts has this user triggered recently?"
    // Used to detect repeat offenders quickly without loading the full alert list
    long countByUserIdAndCreatedAtAfter(String userId, Instant after);

}
