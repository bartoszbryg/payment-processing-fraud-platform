package main.repository;

import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.Transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


import java.util.List;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    // User transaction history (sent) - hits idx_txn_user_id
    Page<Transaction> findBySenderId(String senderId, Pageable pageable);

    // User transaction history (received P2P) - hits idx_txn_receiver_id
    Page<Transaction> findByReceiverId(String receiverId, Pageable pageable);

    // Admin-only: returns ALL users' transactions filtered by status.
    // Never call from a user-facing endpoint - use findBySenderIdAndStatus for that.
    Page<Transaction> findAllByStatus(TransactionStatus status, Pageable pageable);

    // Admin-only: returns ALL users' transactions filtered by risk level.
    // Never call from a user-facing endpoint - use findBySenderIdAndRiskLevel for that.
    Page<Transaction> findAllByRiskLevel(RiskLevel riskLevel, Pageable pageable);

    // User-scoped status filter - safe for user-facing "my declined transactions" endpoint.
    Page<Transaction> findBySenderIdAndStatus(String senderId, TransactionStatus status, Pageable pageable);

    // User-scoped risk filter - safe for user-facing "my flagged transactions" endpoint.
    Page<Transaction> findBySenderIdAndRiskLevel(String senderId, RiskLevel riskLevel, Pageable pageable);

    // Ownership-enforcing single-transaction lookup - returns empty if the transaction
    // exists but belongs to a different user, preventing one user from viewing another's details.
    Optional<Transaction> findByIdAndSenderId(String id, String senderId);

    // Data-range slice (used for reports / export) - hits idx_txn_user_created composite index
    List<Transaction> findBySenderIdAndCreatedAtBetween(String senderId, Instant from, Instant to);


    // Fraud-rule queries (optimized performance: run on every payment)

    // RAPID_TRANSACTIONS rule: "has this user sent n+ transactions in the last m minutes?"
    // Uses idx_txn_user_created composite index
    @Query("SELECT t FROM Transaction t WHERE t.sender.id = :senderId AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentBySender(@Param("senderId") String senderId, @Param("since") Instant since);


    // COUNT variant: cheaper than loading full entities when only the count matters
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.sender.id = :senderId AND t.createdAt >= :since")
    long countRecentBySender(@Param("senderId") String senderId, @Param("since") Instant since);

    // VELOCITY_BREACH rule: "has this user spent more than $X today?"
    // Excludes failed/blocked transactions so they don't inflate the spend total
    @Query("""
        SELECT SUM(t.amount) FROM Transaction t
        WHERE t.sender.id = :senderId
            AND t.createdAt >= :since
            AND t.status NOT IN :excludedStatuses
        """)
    Optional<BigDecimal> sumAmountBySenderSince(@Param("senderId") String senderId, 
                                                @Param("since") Instant since, 
                                                @Param("excludedStatuses") Collection<TransactionStatus> excludedStatuses);

    
    // BLACKLISTED_MERCHANT check: recent activity at the same merchant
    @Query("""
        SELECT t FROM Transaction t 
        WHERE t.merchant = :merchant
            AND t.createdAt >= :since
        ORDER BY t.createdAt DESC
        """)
    List<Transaction> findRecentByMerchant(@Param("merchant") String merchant, @Param("since") Instant since);

    // Dashboard / analyst queries

    // Review queue: transactions awaiting analyst decision, ordered by fraud score (worst first)
    // Statuses passed as typed enums - renaming a constant is a compile error, not a silent wrong result.
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status IN :statuses
        ORDER BY t.fraudScore DESC
        """)
    Page<Transaction> findFlaggedTransactions(@Param("statuses") Collection<TransactionStatus> statuses, Pageable pageable);

    // Volume: top senders by total spend in a time window
    // Pageable here controls LIMIT - pass PageRequest.of(0, 10) for top 10.
    @Query("""
        SELECT t.sender.id, SUM(t.amount), COUNT(t)
        FROM Transaction t
        WHERE t.createdAt >= :since
        GROUP BY t.sender.id
        ORDER BY SUM(t.amount) DESC    
        """)
    List<Object[]> findTopSendersByVolumeSince(@Param("since") Instant since, Pageable pageable);

    // Fraud merchants that have the most fraud-blocked transactions recently
    // 'transactions' and 'user_id' match the @Table(name=) and @JoinColumn(name=) on Transaction

    @Query(value = """
        SELECT merchant, COUNT(*) AS cnt
        FROM transactions
        WHERE created_at >= :since
            AND status = 'FRAUD_BLOCKED'
            AND merchant IS NOT NULL
        GROUP BY merchant
        ORDER BY cnt DESC
        LIMIT 10 
        """, nativeQuery = true)
    List<Object[]> findTopFraudMerchants(@Param("since") Instant since);

    boolean existsBySenderIdAndMerchantAndCreatedAtAfter(String senderId, String merchant, Instant after);
    
}
