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

    // Dashboard: list flagged accounts - hits dedicated idx_users_flagged single-column index
    // The composite (is_active, is_flagged) index has is_active as its leading column, so querying
    // on is_flagged alone would skip it on large tables
    Page<User> findByFlagged(boolean flagged, Pageable pageable);

    
    // Risk dashboard: users above a given fraud-score threshold, ordered worst-first
    @Query("SELECT u FROM User u WHERE u.riskScore >= :threshold ORDER BY u.riskScore DESC")
    Page<User> findHighRiskUsers(@Param("threshold") double threshold, Pageable pageable);


    // Balance deduction: only succeeds when the user has enough funds.
    // Returns 1 (row updated) or 0 (insufficient funds / user not found) - no exception thrown.
    // @Modifying tells Spring Data this is a DML statement, not a SELECT
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance - :amount WHERE u.id = :userId AND u.balance >= :amount")
    int deductBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    // After the fraud engine scores a transaction it calls this once to persist the new risk profile.
    // Returns rows affected (1 = updated, 0 = user not found / was deleted between score and write).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.riskScore = :score, u.flagged = :flagged WHERE u.id = :userId")
    int updateRiskProfile(@Param("userId") String userId, @Param("score") double score, @Param("flagged") boolean flagged);
    
}
