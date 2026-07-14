package main.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.fraud.FraudDetectionEngine;
import main.fraud.graph.TransactionGraphService;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import main.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/*
    Thread pool that drains the TransactionQueue and runs fraud analysis.
    DashboardService calls getActiveWorkerCount() to report live queue utilization.
*/
@Component
@Slf4j
public class TransactionWorkerPool {

    private final TransactionQueue queue;
    private final FraudDetectionEngine fraudDetectionEngine;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final UserRepository userRepository;
    private final TransactionGraphService graphService;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicLong threadCounter = new AtomicLong(0);
    private final AtomicLong processedCount = new AtomicLong(0);

    public TransactionWorkerPool(
            TransactionQueue queue,
            FraudDetectionEngine fraudDetectionEngine,
            TransactionRepository transactionRepository,
            FraudAlertRepository fraudAlertRepository,
            UserRepository userRepository,
            TransactionGraphService graphService,
            TransactionTemplate transactionTemplate,
            @Value("${worker.pool.size:4}") int poolSize) {
        this.queue = queue;
        this.fraudDetectionEngine = fraudDetectionEngine;
        this.transactionRepository = transactionRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.userRepository = userRepository;
        this.graphService = graphService;
        this.transactionTemplate = transactionTemplate;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize,
            r -> {
                Thread t = new Thread(r, "fraud-worker-" + threadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        log.info("TransactionWorkerPool initialized with {} worker threads", poolSize);
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < executor.getCorePoolSize(); i++) {
            executor.submit(this::drainLoop);
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String transactionId = queue.take();
                activeWorkers.incrementAndGet();
                try {
                    transactionTemplate.executeWithoutResult(status -> processTransaction(transactionId));
                    processedCount.incrementAndGet();
                } finally {
                    activeWorkers.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Worker failed while processing queued transaction: {}", e.getMessage(), e);
            }
        }
    }

    @Transactional
    void processTransaction(String transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null) {
            log.warn("Queued transaction {} no longer exists; skipping", transactionId);
            return;
        }

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            log.debug("Queued transaction {} already processed with status {}; skipping",
                transactionId, transaction.getStatus());
            return;
        }

        long startNs = System.nanoTime();
        transaction.setStatus(TransactionStatus.PROCESSING);
        FraudDetectionEngine.FraudAnalysisResult result = fraudDetectionEngine.analyze(transaction);
        applyFraudResult(transaction, result, startNs);
        transactionRepository.save(transaction);
        if (transaction.getStatus() != TransactionStatus.FRAUD_BLOCKED
                && transaction.getStatus() != TransactionStatus.DECLINED) {
            graphService.addTransaction(transaction);
        }
    }

    private void applyFraudResult(
            Transaction transaction,
            FraudDetectionEngine.FraudAnalysisResult result,
            long startNs) {
        List<FraudAlert> alerts = result.alerts();
        alerts.forEach(alert -> {
            alert.setTransaction(transaction);
            if (alert.getUserId() == null && transaction.getSender() != null) {
                alert.setUserId(transaction.getSender().getId());
            }
        });

        if (!alerts.isEmpty()) {
            fraudAlertRepository.saveAll(alerts);
        }

        transaction.setFraudScore(result.fraudScore());
        transaction.setRiskLevel(result.riskLevel());
        transaction.setProcessedAt(Instant.now());
        transaction.setProcessingTimeMs((System.nanoTime() - startNs) / 1_000_000);

        String senderId = transaction.getSender().getId();
        userRepository.updateRiskProfile(senderId, result.fraudScore(), result.isHighRisk());

        if (result.isCritical()) {
            transaction.setStatus(TransactionStatus.FRAUD_BLOCKED);
            transaction.setDeclineReason("Fraud score reached CRITICAL risk level");
            return;
        }

        if (result.riskLevel() == RiskLevel.HIGH) {
            transaction.setStatus(TransactionStatus.FLAGGED_FOR_REVIEW);
            return;
        }

        int deducted = userRepository.deductBalance(senderId, transaction.getAmount());
        if (deducted == 0) {
            transaction.setStatus(TransactionStatus.DECLINED);
            transaction.setDeclineReason("Insufficient balance at processing time");
            return;
        }

        transaction.setStatus(TransactionStatus.APPROVED);
    }

    // Number of worker threads currently executing a task (not idle).
    // Called by DashboardService to report live utilization.
    public long getActiveWorkerCount() {
        return activeWorkers.get();
    }

    public int getPoolSize() {
        return executor.getCorePoolSize();
    }

    public long getTotalProcessed() {
        return processedCount.get();
    }
}
