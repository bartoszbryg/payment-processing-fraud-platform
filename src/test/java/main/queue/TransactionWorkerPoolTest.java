package main.queue;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.fraud.FraudDetectionEngine;
import main.fraud.MlFraudEnrichmentService;
import main.fraud.graph.TransactionGraphService;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import main.repository.UserRepository;
import main.websocket.FraudAlertBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionWorkerPoolTest {

    @Mock TransactionQueue queue;
    @Mock FraudDetectionEngine fraudDetectionEngine;
    @Mock TransactionRepository transactionRepository;
    @Mock FraudAlertRepository fraudAlertRepository;
    @Mock UserRepository userRepository;
    @Mock TransactionGraphService graphService;
    @Mock FraudAlertBroadcaster fraudAlertBroadcaster;
    @Mock MlFraudEnrichmentService mlFraudEnrichmentService;
    @Mock TransactionTemplate transactionTemplate;

    TransactionWorkerPool workerPool;

    @BeforeEach
    void setup() {
        workerPool = new TransactionWorkerPool(
            queue,
            fraudDetectionEngine,
            transactionRepository,
            fraudAlertRepository,
            userRepository,
            graphService,
            fraudAlertBroadcaster,
            mlFraudEnrichmentService,
            transactionTemplate,
            4);
    }

    private User user(String id, BigDecimal balance) {
        User user = User.builder()
            .name("Alice")
            .email(id + "@example.com")
            .phoneNumber("+14155550001")
            .balance(balance)
            .homeCountry("US")
            .homeCity("New York")
            .build();
        user.setId(id);
        return user;
    }

    private Transaction transaction(String id) {
        Transaction transaction = Transaction.builder()
            .sender(user("user-001", new BigDecimal("1000.00")))
            .merchant("Amazon")
            .amount(new BigDecimal("100.00"))
            .location("New York")
            .country("US")
            .status(TransactionStatus.PENDING)
            .build();
        transaction.setId(id);
        return transaction;
    }

    private FraudAlert alert(Transaction transaction, double score, RiskLevel riskLevel) {
        return FraudAlert.builder()
            .transaction(transaction)
            .userId(transaction.getSender().getId())
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(riskLevel)
            .scoreContribution(score)
            .description("test alert")
            .build();
    }

    private void stubResult(Transaction transaction, double score, RiskLevel riskLevel, List<FraudAlert> alerts) {
        when(fraudDetectionEngine.analyze(transaction))
            .thenReturn(new FraudDetectionEngine.FraudAnalysisResult(score, riskLevel, alerts));
    }

    // Pool configuration

    @Nested
    class PoolConfigurationTests {

        @Test
        void poolSize_matchesConfiguredValue() {
            TransactionWorkerPool pool = new TransactionWorkerPool(
                queue,
                fraudDetectionEngine,
                transactionRepository,
                fraudAlertRepository,
                userRepository,
                graphService,
                fraudAlertBroadcaster,
                mlFraudEnrichmentService,
                transactionTemplate,
                6);

            assertEquals(6, pool.getPoolSize());
        }

        @Test
        void noTransactionsProcessed_initialCountIsZero() {
            assertEquals(0L, workerPool.getTotalProcessed());
        }

        @Test
        void activeWorkerCount_returnsNonNegativeValue() {
            assertTrue(workerPool.getActiveWorkerCount() >= 0);
        }
    }

    // processTransaction outcomes

    @Nested
    class ProcessTransactionTests {

        @Test
        void lowRiskTransaction_isApprovedAndBalanceDeducted() {
            Transaction transaction = transaction("txn-001");
            when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 10.0, RiskLevel.LOW, List.of());
            when(userRepository.deductBalance("user-001", new BigDecimal("100.00"))).thenReturn(1);

            workerPool.processTransaction("txn-001");

            assertEquals(TransactionStatus.APPROVED, transaction.getStatus());
            assertEquals(RiskLevel.LOW, transaction.getRiskLevel());
            assertEquals(10.0, transaction.getFraudScore(), 0.001);
            assertNotNull(transaction.getProcessedAt());
            assertNotNull(transaction.getProcessingTimeMs());
            verify(userRepository).deductBalance("user-001", new BigDecimal("100.00"));
            verify(transactionRepository).save(transaction);
            verify(graphService).addTransaction(transaction);
            verify(fraudAlertBroadcaster, never()).broadcast(any(), any());
            verify(mlFraudEnrichmentService).enrichAfterApproval("txn-001");
        }

        @Test
        void highRiskTransaction_isFlaggedAndAlertsAreSaved() {
            Transaction transaction = transaction("txn-002");
            FraudAlert alert = alert(transaction, 70.0, RiskLevel.HIGH);
            when(transactionRepository.findById("txn-002")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 70.0, RiskLevel.HIGH, List.of(alert));

            workerPool.processTransaction("txn-002");

            assertEquals(TransactionStatus.FLAGGED_FOR_REVIEW, transaction.getStatus());
            assertEquals(RiskLevel.HIGH, transaction.getRiskLevel());
            verify(fraudAlertRepository).saveAll(List.of(alert));
            verify(userRepository, never()).deductBalance(anyString(), any());
            verify(transactionRepository).save(transaction);
            verify(graphService).addTransaction(transaction);
            verify(fraudAlertBroadcaster).broadcast(List.of(alert), transaction);
            verify(mlFraudEnrichmentService).enrichAfterApproval("txn-002");
        }

        @Test
        void criticalTransaction_isBlockedWithDeclineReason() {
            Transaction transaction = transaction("txn-003");
            FraudAlert alert = alert(transaction, 90.0, RiskLevel.CRITICAL);
            when(transactionRepository.findById("txn-003")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 90.0, RiskLevel.CRITICAL, List.of(alert));

            workerPool.processTransaction("txn-003");

            assertEquals(TransactionStatus.FRAUD_BLOCKED, transaction.getStatus());
            assertEquals(RiskLevel.CRITICAL, transaction.getRiskLevel());
            assertTrue(transaction.getDeclineReason().contains("CRITICAL"));
            verify(fraudAlertRepository).saveAll(List.of(alert));
            verify(userRepository, never()).deductBalance(anyString(), any());
            verify(graphService, never()).addTransaction(transaction);
            verify(fraudAlertBroadcaster).broadcast(List.of(alert), transaction);
            verify(mlFraudEnrichmentService, never()).enrichAfterApproval(anyString());
        }

        @Test
        void balanceChangedBeforeProcessing_transactionIsDeclined() {
            Transaction transaction = transaction("txn-004");
            when(transactionRepository.findById("txn-004")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 5.0, RiskLevel.LOW, List.of());
            when(userRepository.deductBalance("user-001", new BigDecimal("100.00"))).thenReturn(0);

            workerPool.processTransaction("txn-004");

            assertEquals(TransactionStatus.DECLINED, transaction.getStatus());
            assertTrue(transaction.getDeclineReason().contains("Insufficient balance"));
            verify(graphService, never()).addTransaction(transaction);
            verify(mlFraudEnrichmentService, never()).enrichAfterApproval(anyString());
        }

        @Test
        void existingAlertWithoutTransaction_isAttachedBeforeSave() {
            Transaction transaction = transaction("txn-005");
            FraudAlert alert = alert(transaction, 80.0, RiskLevel.HIGH);
            alert.setTransaction(null);
            when(transactionRepository.findById("txn-005")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 80.0, RiskLevel.HIGH, List.of(alert));

            workerPool.processTransaction("txn-005");

            ArgumentCaptor<List<FraudAlert>> captor = ArgumentCaptor.forClass(List.class);
            verify(fraudAlertRepository).saveAll(captor.capture());
            assertSame(transaction, captor.getValue().get(0).getTransaction());
        }

        @Test
        void transactionMissing_skipsProcessing() {
            when(transactionRepository.findById("ghost")).thenReturn(Optional.empty());

            workerPool.processTransaction("ghost");

            verifyNoInteractions(fraudDetectionEngine);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedTransaction_skipsProcessing() {
            Transaction transaction = transaction("txn-006");
            transaction.setStatus(TransactionStatus.APPROVED);
            when(transactionRepository.findById("txn-006")).thenReturn(Optional.of(transaction));

            workerPool.processTransaction("txn-006");

            verifyNoInteractions(fraudDetectionEngine);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        void mediumRiskTransaction_isApprovedWithBalanceDeducted() {
            Transaction transaction = transaction("txn-007");
            when(transactionRepository.findById("txn-007")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 35.0, RiskLevel.MEDIUM, List.of());
            when(userRepository.deductBalance("user-001", new BigDecimal("100.00"))).thenReturn(1);

            workerPool.processTransaction("txn-007");

            assertEquals(TransactionStatus.APPROVED, transaction.getStatus());
            verify(userRepository).deductBalance("user-001", new BigDecimal("100.00"));
            verify(graphService).addTransaction(transaction);
            verify(fraudAlertBroadcaster, never()).broadcast(any(), any());
        }

        @Test
        void userRiskProfileUpdated_onEveryProcessedTransaction() {
            Transaction transaction = transaction("txn-008");
            when(transactionRepository.findById("txn-008")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 25.0, RiskLevel.LOW, List.of());
            when(userRepository.deductBalance(any(), any())).thenReturn(1);

            workerPool.processTransaction("txn-008");

            verify(userRepository).updateRiskProfile("user-001", 25.0, false);
        }

        @Test
        void noAlerts_fraudAlertRepositoryNotCalled() {
            Transaction transaction = transaction("txn-009");
            when(transactionRepository.findById("txn-009")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 5.0, RiskLevel.LOW, List.of());
            when(userRepository.deductBalance(any(), any())).thenReturn(1);

            workerPool.processTransaction("txn-009");

            verify(fraudAlertRepository, never()).saveAll(any());
        }

        @Test
        void blockedTransaction_notAddedToGraph() {
            Transaction transaction = transaction("txn-010");
            when(transactionRepository.findById("txn-010")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 91.0, RiskLevel.CRITICAL, List.of());

            workerPool.processTransaction("txn-010");

            verify(graphService, never()).addTransaction(any());
        }

        @Test
        void declinedTransaction_notAddedToGraph() {
            Transaction transaction = transaction("txn-011");
            when(transactionRepository.findById("txn-011")).thenReturn(Optional.of(transaction));
            stubResult(transaction, 5.0, RiskLevel.LOW, List.of());
            when(userRepository.deductBalance(any(), any())).thenReturn(0);

            workerPool.processTransaction("txn-011");

            verify(graphService, never()).addTransaction(any());
        }
    }
}