package main.service;

import main.config.CacheConfig;
import main.dto.response.DashboardStatsResponse;
import main.queue.TransactionQueue;
import main.queue.TransactionWorkerPool;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Collections;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the getStats() split with a real Spring cache proxy in place: the DB-derived
 * aggregate (via DashboardStatsCache) stays frozen across calls within the cache TTL,
 * while queueDepth/activeWorkers - cheap in-memory reads - change on every call even
 * though the overall cache entry was hit, not missed.
 */
@SpringJUnitConfig(classes = {CacheConfig.class, DashboardStatsCache.class, DashboardService.class})
class DashboardServiceCachingTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private CacheManager cacheManager;

    @MockBean TransactionRepository transactionRepository;
    @MockBean FraudAlertRepository fraudAlertRepository;
    @MockBean TransactionQueue transactionQueue;
    @MockBean TransactionWorkerPool workerPool;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("dashboardStats")).clear();
    }

    @SuppressWarnings("unchecked")
    private void stubZeroedAggregate() {
        when(transactionRepository.count()).thenReturn(0L);
        Page<Object> page = mock(Page.class);
        when(page.getTotalElements()).thenReturn(0L);
        when(transactionRepository.findAllByStatus(any(), any(Pageable.class))).thenReturn((Page) page);
        when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void aggregateFieldsCached_liveFieldsRecomputedEveryCall() {
        stubZeroedAggregate();
        when(transactionRepository.count()).thenReturn(1L, 2L); // would change if re-queried
        when(transactionQueue.size()).thenReturn(5, 9);
        when(workerPool.getActiveWorkerCount()).thenReturn(1L, 2L);

        DashboardStatsResponse first = dashboardService.getStats();
        DashboardStatsResponse second = dashboardService.getStats();

        // Cache hit on the second call: totalTransactions frozen at the first call's value.
        assertEquals(1L, first.getTotalTransactions());
        assertEquals(1L, second.getTotalTransactions());

        // Never cached: each call reflects the queue/worker pool's current state.
        assertEquals(5, first.getQueueDepth());
        assertEquals(9, second.getQueueDepth());
        assertEquals(1L, first.getActiveWorkers());
        assertEquals(2L, second.getActiveWorkers());
    }
}