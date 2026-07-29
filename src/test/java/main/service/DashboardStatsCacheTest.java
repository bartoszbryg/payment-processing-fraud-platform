package main.service;

import main.application.states.FraudRuleType;
import main.application.states.TransactionStatus;
import main.config.CacheConfig;
import main.dto.response.DashboardStatsResponse;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests DashboardStatsCache with the real Spring cache proxy enabled - same approach as
 * MerchantBlacklistCacheTest. getAggregateStats() takes no parameters, so every call
 * produces the same cache key; these tests confirm that single entry is actually reused
 * (repository hit once, not once per call) rather than assuming it from reading the code.
 */
@SpringJUnitConfig(classes = {CacheConfig.class, DashboardStatsCache.class})
class DashboardStatsCacheTest {

    @Autowired
    private DashboardStatsCache dashboardStatsCache;

    @Autowired
    private CacheManager cacheManager;

    @MockBean TransactionRepository transactionRepository;
    @MockBean FraudAlertRepository fraudAlertRepository;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("dashboardStats")).clear();
    }

    private void stubAggregateQueries(long total, long approved, long declined, long blocked, long flagged) {
        when(transactionRepository.count()).thenReturn(total);
        stubStatusPage(TransactionStatus.APPROVED, approved);
        stubStatusPage(TransactionStatus.DECLINED, declined);
        stubStatusPage(TransactionStatus.FRAUD_BLOCKED, blocked);
        stubStatusPage(TransactionStatus.FLAGGED_FOR_REVIEW, flagged);
        when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void stubStatusPage(TransactionStatus status, long count) {
        Page<Object> page = mock(Page.class);
        when(page.getTotalElements()).thenReturn(count);
        when(transactionRepository.findAllByStatus(eq(status), any(Pageable.class)))
            .thenReturn((Page) page);
    }

    @Test
    void cacheHit_repositoryQueriedOnlyOnce() {
        stubAggregateQueries(100, 60, 15, 10, 5);

        DashboardStatsResponse first = dashboardStatsCache.getAggregateStats();
        DashboardStatsResponse second = dashboardStatsCache.getAggregateStats();

        assertEquals(100L, first.getTotalTransactions());
        assertEquals(100L, second.getTotalTransactions());
        verify(transactionRepository, times(1)).count();
        verify(fraudAlertRepository, times(1)).countByRuleTypeSince(any());
    }

    @Test
    void cacheEntry_staysAtOne_regardlessOfCallCount() {
        stubAggregateQueries(0, 0, 0, 0, 0);

        dashboardStatsCache.getAggregateStats();
        dashboardStatsCache.getAggregateStats();
        dashboardStatsCache.getAggregateStats();

        // Same fixed key every call (no parameters) - matches CacheConfig's maximumSize(1)
        // for this cache, which documents that this is the real, permanent ceiling.
        verify(transactionRepository, times(1)).count();
    }

    @Test
    void cachedResult_reflectsValuesFromFirstCall_notLaterDbChanges() {
        stubAggregateQueries(10, 5, 2, 2, 1);
        DashboardStatsResponse first = dashboardStatsCache.getAggregateStats();
        assertEquals(10L, first.getTotalTransactions());

        // DB "changes" after the first call - a cache hit must not see this.
        stubAggregateQueries(999, 5, 2, 2, 1);
        DashboardStatsResponse second = dashboardStatsCache.getAggregateStats();

        assertEquals(10L, second.getTotalTransactions());
    }

    @Test
    void evict_clearsEntry_nextCallHitsRepositoryAgain() {
        stubAggregateQueries(10, 5, 2, 2, 1);
        dashboardStatsCache.getAggregateStats();

        Objects.requireNonNull(cacheManager.getCache("dashboardStats")).clear();
        stubAggregateQueries(20, 5, 2, 2, 1);
        DashboardStatsResponse afterEvict = dashboardStatsCache.getAggregateStats();

        assertEquals(20L, afterEvict.getTotalTransactions());
        verify(transactionRepository, times(2)).count();
    }

    @Test
    void alertsByRuleType_mappedAndCached() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{FraudRuleType.HIGH_AMOUNT, 7L});
        when(fraudAlertRepository.countByRuleTypeSince(any())).thenReturn(rows);
        stubStatusPage(TransactionStatus.APPROVED, 0);
        stubStatusPage(TransactionStatus.DECLINED, 0);
        stubStatusPage(TransactionStatus.FRAUD_BLOCKED, 0);
        stubStatusPage(TransactionStatus.FLAGGED_FOR_REVIEW, 0);
        when(transactionRepository.count()).thenReturn(0L);

        DashboardStatsResponse first = dashboardStatsCache.getAggregateStats();
        DashboardStatsResponse second = dashboardStatsCache.getAggregateStats();

        assertEquals(7L, first.getAlertsByRuleType().get("HIGH_AMOUNT"));
        assertEquals(7L, second.getAlertsByRuleType().get("HIGH_AMOUNT"));
        verify(fraudAlertRepository, times(1)).countByRuleTypeSince(any());
    }
}