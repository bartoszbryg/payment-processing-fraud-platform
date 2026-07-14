package main.service;

import lombok.RequiredArgsConstructor;
import main.application.states.FraudRuleType;
import main.application.states.TransactionStatus;
import main.dto.response.DashboardStatsResponse;
import main.queue.TransactionQueue;
import main.queue.TransactionWorkerPool;
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

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionQueue transactionQueue;
    private final TransactionWorkerPool workerPool;

    // Cached so 100 concurrent admin refreshes hit the DB once, not 100 times.
    // Cache expires passively via Caffeine's expireAfterWrite(10 min) TTL in CacheConfig.
    @Cacheable(value = "dashboardStats", unless = "#result == null")
    @Transactional(readOnly = true)
    public DashboardStatsResponse getStats() {
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
            .queueDepth(transactionQueue.size())
            .activeWorkers(workerPool.getActiveWorkerCount())
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