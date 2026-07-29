package main.service;

import main.application.states.FraudRuleType;
import main.application.states.TransactionStatus;
import main.dto.response.DashboardStatsResponse;
import main.queue.TransactionQueue;
import main.queue.TransactionWorkerPool;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock FraudAlertRepository fraudAlertRepository;
    @Mock TransactionQueue transactionQueue;
    @Mock TransactionWorkerPool workerPool;

    DashboardService dashboardService;
    DashboardStatsCache dashboardStatsCache;

    @BeforeEach
    void setup() {
        dashboardStatsCache = new DashboardStatsCache(transactionRepository, fraudAlertRepository);
        dashboardService = new DashboardService(dashboardStatsCache, transactionQueue, workerPool);
    }

    private void stubStatusPage(TransactionStatus status, long count) {
        Page<Object> page = mock(Page.class);
        when(page.getTotalElements()).thenReturn(count);
        when(transactionRepository.findAllByStatus(eq(status), any(Pageable.class)))
            .thenReturn((Page) page);
    }

    private void stubAllStatusPages(long approved, long declined, long blocked, long flagged) {
        stubStatusPage(TransactionStatus.APPROVED, approved);
        stubStatusPage(TransactionStatus.DECLINED, declined);
        stubStatusPage(TransactionStatus.FRAUD_BLOCKED, blocked);
        stubStatusPage(TransactionStatus.FLAGGED_FOR_REVIEW, flagged);
    }

    @Nested
    class GetStatsTests {

        @Test
        void getStats_returnsTransactionCounts() {
            when(transactionRepository.count()).thenReturn(100L);
            stubAllStatusPages(60, 15, 10, 5);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertEquals(100L, stats.getTotalTransactions());
            assertEquals(60L, stats.getApprovedTransactions());
            assertEquals(15L, stats.getDeclinedTransactions());
            assertEquals(10L, stats.getFraudBlockedTransactions());
            assertEquals(5L, stats.getFlaggedForReview());
        }

        @Test
        void getStats_pendingIsTotal_minusKnownStatuses() {
            when(transactionRepository.count()).thenReturn(100L);
            stubAllStatusPages(60, 15, 10, 5);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            // 100 - 60 - 15 - 10 - 5 = 10
            assertEquals(10L, stats.getTransactionsByStatus().get("PENDING"));
        }

        @Test
        void getStats_negativePending_clampedToZero() {
            // If counts exceed total (e.g., due to race condition), Math.max(0, pending) prevents negative
            when(transactionRepository.count()).thenReturn(50L);
            stubAllStatusPages(30, 15, 10, 5);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            // 50 - 30 - 15 - 10 - 5 = -10 → clamped to 0
            assertEquals(0L, stats.getTransactionsByStatus().get("PENDING"));
        }

        @Test
        void getStats_alertsByRuleType_mappedToName() {
            when(transactionRepository.count()).thenReturn(0L);
            stubAllStatusPages(0, 0, 0, 0);
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{FraudRuleType.HIGH_AMOUNT, 7L});
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(rows);
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertEquals(7L, stats.getAlertsByRuleType().get("HIGH_AMOUNT"));
        }

        @Test
        void getStats_multipleRuleTypes_allMapped() {
            when(transactionRepository.count()).thenReturn(0L);
            stubAllStatusPages(0, 0, 0, 0);
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{FraudRuleType.HIGH_AMOUNT, 5L});
            rows.add(new Object[]{FraudRuleType.VELOCITY_BREACH, 3L});
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(rows);
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertEquals(5L, stats.getAlertsByRuleType().get("HIGH_AMOUNT"));
            assertEquals(3L, stats.getAlertsByRuleType().get("VELOCITY_BREACH"));
        }

        @Test
        void getStats_queueDepthFromTransactionQueue() {
            when(transactionRepository.count()).thenReturn(0L);
            stubAllStatusPages(0, 0, 0, 0);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(42);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertEquals(42, stats.getQueueDepth());
        }

        @Test
        void getStats_activeWorkersFromWorkerPool() {
            when(transactionRepository.count()).thenReturn(0L);
            stubAllStatusPages(0, 0, 0, 0);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(3L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertEquals(3L, stats.getActiveWorkers());
        }

        @Test
        void getStats_transactionsByStatus_containsAllKeys() {
            when(transactionRepository.count()).thenReturn(10L);
            stubAllStatusPages(4, 2, 2, 1);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getTransactionsByStatus().containsKey("APPROVED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("DECLINED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("FRAUD_BLOCKED"));
            assertTrue(stats.getTransactionsByStatus().containsKey("FLAGGED_FOR_REVIEW"));
            assertTrue(stats.getTransactionsByStatus().containsKey("PENDING"));
        }

        @Test
        void getStats_noAlerts_alertsByRuleTypeIsEmpty() {
            when(transactionRepository.count()).thenReturn(0L);
            stubAllStatusPages(0, 0, 0, 0);
            when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
            when(transactionQueue.size()).thenReturn(0);
            when(workerPool.getActiveWorkerCount()).thenReturn(0L);

            DashboardStatsResponse stats = dashboardService.getStats();

            assertTrue(stats.getAlertsByRuleType().isEmpty());
        }
    }
}