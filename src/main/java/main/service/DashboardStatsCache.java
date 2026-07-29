package main.service;

import lombok.RequiredArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.TransactionStatus;
import main.dto.response.DashboardStatsResponse;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Separate bean so @Cacheable calls go through the Spring AOP proxy.
 *
 * Self-invocation problem: if DashboardService called its own @Cacheable method,
 * the call would go directly to 'this' — bypassing the proxy — and the cache would never
 * be populated. Extracting the DB-aggregate query here (same pattern as
 * MerchantBlacklistCache) ensures every call is an inter-bean call that passes through
 * the AOP interceptor.
 *
 * Only the DB-derived aggregate fields live here — queueDepth/activeWorkers are free
 * in-memory reads and are deliberately kept out of this cached method; DashboardService
 * recomputes those on every call, cache hit or not.
 */
@Service
@RequiredArgsConstructor
public class DashboardStatsCache {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;

    // Cached so 100 concurrent admin refreshes hit the DB once, not 100 times.
    // Cache expires passively via Caffeine's expireAfterWrite(10 min) TTL in CacheConfig.
    @Cacheable(value = "dashboardStats", unless = "#result == null")
    @Transactional(readOnly = true)
    public DashboardStatsResponse getAggregateStats() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);

        // countByRuleTypeSince returns Object[]{FraudRuleType, Long} rows
        List<Object[]> alertCounts = fraudAlertRepository.countByRuleTypeSince(since);
        Map<String, Long> alertsByRule = alertCounts.stream()
            .collect(Collectors.toMap(
                row -> ((FraudRuleType) row[0]).name(),
                row -> (Long) row[1]
            ));

        // PageRequest.of(0,1) is the cheapest way to get getTotalElements() from a paginated repo.
        // Spring Data executes a COUNT query rather than loading all rows.
        PageRequest one = PageRequest.of(0, 1);
        long total    = transactionRepository.count();
        long approved = transactionRepository.findAllByStatus(TransactionStatus.APPROVED, one).getTotalElements();
        long declined = transactionRepository.findAllByStatus(TransactionStatus.DECLINED, one).getTotalElements();
        long blocked  = transactionRepository.findAllByStatus(TransactionStatus.FRAUD_BLOCKED, one).getTotalElements();
        long flagged  = transactionRepository.findAllByStatus(TransactionStatus.FLAGGED_FOR_REVIEW, one).getTotalElements();
        long pending  = total - approved - declined - blocked - flagged;

        return DashboardStatsResponse.builder()
            .totalTransactions(total)
            .approvedTransactions(approved)
            .declinedTransactions(declined)
            .fraudBlockedTransactions(blocked)
            .flaggedForReview(flagged)
            .totalVolume(BigDecimal.ZERO)       // populated via analytics query in production
            .fraudPreventedAmount(BigDecimal.ZERO)
            .averageFraudScore(0.0)
            .alertsByRuleType(alertsByRule)
            .transactionsByStatus(Map.of(
                "APPROVED", approved,
                "DECLINED", declined,
                "FRAUD_BLOCKED", blocked,
                "FLAGGED_FOR_REVIEW", flagged,
                "PENDING", Math.max(0, pending)
            ))
            .build();
    }
}