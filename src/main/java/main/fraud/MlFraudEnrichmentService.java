package main.fraud;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import main.repository.UserRepository;
import main.service.MlFraudScoringService;
import main.websocket.FraudAlertBroadcaster;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/*
    Post-approval ML enrichment — the "everything that leaves the process over a network
    never gates approval" half of the fraud architecture. Rules and graph checks are
    in-memory, bounded, and always available, so they gate TransactionWorkerPool's
    APPROVED/FLAGGED/BLOCKED decision synchronously. The ML scoring service is none of
    those things: it's a network call to a separate Python process with its own uptime and
    latency profile. Blocking a fraud-worker thread on it would turn one slow ML call into
    queue backpressure for every other transaction waiting behind it.

    So this runs on its own tiny background pool, after the worker's decision has already
    committed, and only ever adds information: a new FraudAlert plus a bumped risk score.
    It never touches the transaction's status or balance — by the time this fires, the
    money has already moved (or been correctly withheld) based on the fast, synchronous
    checks. A late "this looks like fraud" from ML flags the account for tighter scrutiny
    on future transactions; it does not reverse a payment that already cleared.

    Disabled by default (ml.service.enabled=false in application.yml) via
    MlFraudScoringService itself - wiring this in ahead of the Python service existing
    (Phase 15) is safe because the whole enrichment is a no-op until score() has something
    to return.
*/
@Service
@Slf4j
public class MlFraudEnrichmentService {

    private final MlFraudScoringService mlFraudScoringService;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final UserRepository userRepository;
    private final FraudAlertBroadcaster fraudAlertBroadcaster;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final ThreadPoolExecutor executor;

    @Value("${ml.service.suspicion-threshold:0.4}")
    private double suspicionThreshold;

    public MlFraudEnrichmentService(
            MlFraudScoringService mlFraudScoringService,
            TransactionRepository transactionRepository,
            FraudAlertRepository fraudAlertRepository,
            UserRepository userRepository,
            FraudAlertBroadcaster fraudAlertBroadcaster,
            @Qualifier("requiresNewTransactionTemplate") TransactionTemplate requiresNewTransactionTemplate) {
        this.mlFraudScoringService = mlFraudScoringService;
        this.transactionRepository = transactionRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.userRepository = userRepository;
        this.fraudAlertBroadcaster = fraudAlertBroadcaster;
        this.requiresNewTransactionTemplate = requiresNewTransactionTemplate;
        // Bounded queue, not the default unbounded one: if the ML service is slow but not
        // timing out, an unbounded backlog here would grow without limit under sustained
        // load - the same class of problem TransactionQueue's bounded offer() exists to
        // avoid. AbortPolicy (default) means enrichAfterApproval must catch rejection rather
        // than let it propagate — the caller is a TransactionSynchronization.afterCommit
        // callback running on a fraud-worker thread, and CallerRunsPolicy would silently
        // reintroduce the exact "ML call blocks a fraud worker" problem this whole class
        // exists to avoid.
        this.executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "ml-enrichment");
                t.setDaemon(true);
                return t;
            });
    }

    // Fire-and-forget entry point called by TransactionWorkerPool after its decision commits.
    public void enrichAfterApproval(String transactionId) {
        try {
            executor.submit(() -> runEnrichment(transactionId));
        } catch (RejectedExecutionException e) {
            log.warn("ML enrichment backlog full - dropping enrichment for txn {}", transactionId);
        }
    }

    // Package-private so tests can run it synchronously instead of racing the executor.
    void runEnrichment(String transactionId) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
                if (transaction == null) {
                    log.warn("Transaction {} no longer exists; skipping ML enrichment", transactionId);
                    return;
                }

                mlFraudScoringService.score(transaction)
                    .filter(result -> result.fraudProbability() >= suspicionThreshold)
                    .ifPresent(result -> persistAlert(transaction, result));
            });
        } catch (Exception e) {
            log.error("ML enrichment failed for txn {}: {}", transactionId, e.getMessage(), e);
        }
    }

    private void persistAlert(Transaction transaction, MlFraudScoringService.MlScoreResult result) {
        double score = result.fraudProbability() * 100;
        String senderId = transaction.getSender().getId();

        FraudAlert alert = FraudAlert.builder()
            .transaction(transaction)
            .userId(senderId)
            .ruleType(FraudRuleType.ANOMALY_SCORE_BREACH)
            .riskLevel(RiskLevel.fromScore(score))
            .scoreContribution(score)
            .description(String.format(
                "[ML] Post-approval anomaly score %.2f (band=%s) exceeds suspicion threshold %.2f",
                result.fraudProbability(), result.riskBand(), suspicionThreshold))
            .metadata(String.format(
                "{\"fraudProbability\":%.4f,\"riskBand\":\"%s\"}",
                result.fraudProbability(), result.riskBand()))
            .build();

        fraudAlertRepository.save(alert);

        // raiseRiskProfile, not updateRiskProfile: this call is asynchronous and can land
        // well after later transactions from the same user have already stamped their own
        // fresh risk score via the synchronous path. A plain overwrite here would let this
        // late, ML-only number silently win a last-write-wins race against a higher, more
        // complete rules+graph score - raiseRiskProfile can only ever push the score up.
        boolean highRisk = alert.getRiskLevel() == RiskLevel.HIGH || alert.getRiskLevel() == RiskLevel.CRITICAL;
        userRepository.raiseRiskProfile(senderId, score, highRisk);

        if (highRisk) {
            fraudAlertBroadcaster.broadcast(List.of(alert), transaction);
        }

        log.info("ML post-approval alert created for txn {}: probability={}, band={}",
            transaction.getId(), result.fraudProbability(), result.riskBand());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}