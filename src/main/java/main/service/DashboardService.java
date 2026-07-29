package main.service;

import lombok.RequiredArgsConstructor;
import main.dto.response.DashboardStatsResponse;
import main.queue.TransactionQueue;
import main.queue.TransactionWorkerPool;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardStatsCache dashboardStatsCache;
    private final TransactionQueue transactionQueue;
    private final TransactionWorkerPool workerPool;

    // Combines the cached DB aggregates (DashboardStatsCache, expires every 10 min via
    // CacheConfig's Caffeine TTL) with queueDepth/activeWorkers, which are cheap in-memory
    // reads recomputed on every call so they always reflect the current moment.
    public DashboardStatsResponse getStats() {
        return dashboardStatsCache.getAggregateStats().toBuilder()
            .queueDepth(transactionQueue.size())
            .activeWorkers(workerPool.getActiveWorkerCount())
            .build();
    }
}