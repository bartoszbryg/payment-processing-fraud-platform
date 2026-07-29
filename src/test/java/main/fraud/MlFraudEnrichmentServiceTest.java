package main.fraud;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.repository.FraudAlertRepository;
import main.repository.TransactionRepository;
import main.repository.UserRepository;
import main.service.MlFraudScoringService;
import main.websocket.FraudAlertBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*
    runEnrichment() is executed directly (package-private) rather than through
    enrichAfterApproval(), the same way TransactionWorkerPoolTest calls processTransaction()
    directly instead of racing the queue/executor — deterministic, no async waiting needed.
*/
@ExtendWith(MockitoExtension.class)
class MlFraudEnrichmentServiceTest {

    @Mock MlFraudScoringService mlFraudScoringService;
    @Mock TransactionRepository transactionRepository;
    @Mock FraudAlertRepository fraudAlertRepository;
    @Mock UserRepository userRepository;
    @Mock FraudAlertBroadcaster fraudAlertBroadcaster;
    @Mock TransactionTemplate transactionTemplate;

    MlFraudEnrichmentService enrichmentService;

    @BeforeEach
    void setup() {
        enrichmentService = new MlFraudEnrichmentService(
            mlFraudScoringService,
            transactionRepository,
            fraudAlertRepository,
            userRepository,
            fraudAlertBroadcaster,
            transactionTemplate);
        ReflectionTestUtils.setField(enrichmentService, "suspicionThreshold", 0.4);

        // TransactionTemplate mock just invokes the callback inline, mirroring how
        // requiresNewTransactionTemplate behaves in production.
        doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Transaction transaction(String id) {
        User sender = User.builder()
            .name("Alice").email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        sender.setId("user-001");

        Transaction t = Transaction.builder()
            .sender(sender)
            .amount(new BigDecimal("200.00"))
            .merchant("Amazon")
            .location("New York")
            .country("US")
            .build();
        t.setId(id);
        return t;
    }

    @Test
    void probabilityAboveThreshold_createsAlertAndUpdatesRiskProfile() {
        Transaction transaction = transaction("txn-001");
        when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(transaction));
        // 0.90 * 100 = 90 -> CRITICAL (86-100)
        when(mlFraudScoringService.score(transaction))
            .thenReturn(Optional.of(new MlFraudScoringService.MlScoreResult(0.90, "CRITICAL")));

        enrichmentService.runEnrichment("txn-001");

        ArgumentCaptor<FraudAlert> captor = ArgumentCaptor.forClass(FraudAlert.class);
        verify(fraudAlertRepository).save(captor.capture());

        FraudAlert alert = captor.getValue();
        assertSame(transaction, alert.getTransaction());
        assertEquals("user-001", alert.getUserId());
        assertEquals(FraudRuleType.ANOMALY_SCORE_BREACH, alert.getRuleType());
        assertEquals(90.0, alert.getScoreContribution(), 0.001);
        assertEquals(RiskLevel.CRITICAL, alert.getRiskLevel());
        assertTrue(alert.getDescription().contains("0.90"));
        assertTrue(alert.getMetadata().contains("\"riskBand\":\"CRITICAL\""));

        verify(userRepository).raiseRiskProfile("user-001", 90.0, true);
    }

    @Test
    void highRiskAlert_broadcastsOverWebSocket() {
        Transaction transaction = transaction("txn-001");
        when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(transaction));
        when(mlFraudScoringService.score(transaction))
            .thenReturn(Optional.of(new MlFraudScoringService.MlScoreResult(0.85, "CRITICAL")));

        enrichmentService.runEnrichment("txn-001");

        verify(fraudAlertBroadcaster).broadcast(anyList(), eq(transaction));
    }

    @Test
    void mediumRiskAlert_neverBroadcasts() {
        Transaction transaction = transaction("txn-001");
        when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(transaction));
        // 0.45 * 100 = 45 -> MEDIUM (31-60), above the 0.4 suspicion threshold but not HIGH risk
        when(mlFraudScoringService.score(transaction))
            .thenReturn(Optional.of(new MlFraudScoringService.MlScoreResult(0.45, "MEDIUM")));

        enrichmentService.runEnrichment("txn-001");

        verify(fraudAlertRepository).save(any());
        verify(userRepository).raiseRiskProfile("user-001", 45.0, false);
        verify(fraudAlertBroadcaster, never()).broadcast(any(), any());
    }

    @Test
    void probabilityBelowThreshold_noAlertCreated() {
        Transaction transaction = transaction("txn-002");
        when(transactionRepository.findById("txn-002")).thenReturn(Optional.of(transaction));
        when(mlFraudScoringService.score(transaction))
            .thenReturn(Optional.of(new MlFraudScoringService.MlScoreResult(0.2, "LOW")));

        enrichmentService.runEnrichment("txn-002");

        verifyNoInteractions(fraudAlertRepository);
        verify(userRepository, never()).raiseRiskProfile(any(), anyDouble(), anyBoolean());
        verify(fraudAlertBroadcaster, never()).broadcast(any(), any());
    }

    @Test
    void mlServiceReturnsEmpty_noAlertCreated() {
        Transaction transaction = transaction("txn-003");
        when(transactionRepository.findById("txn-003")).thenReturn(Optional.of(transaction));
        when(mlFraudScoringService.score(transaction)).thenReturn(Optional.empty());

        enrichmentService.runEnrichment("txn-003");

        verifyNoInteractions(fraudAlertRepository);
        verify(userRepository, never()).raiseRiskProfile(any(), anyDouble(), anyBoolean());
    }

    @Test
    void transactionNotFound_noInteractionsWithMlService() {
        when(transactionRepository.findById("ghost")).thenReturn(Optional.empty());

        enrichmentService.runEnrichment("ghost");

        verifyNoInteractions(mlFraudScoringService);
        verifyNoInteractions(fraudAlertRepository);
    }

    @Test
    void mlServiceThrows_doesNotPropagate() {
        Transaction transaction = transaction("txn-004");
        when(transactionRepository.findById("txn-004")).thenReturn(Optional.of(transaction));
        when(mlFraudScoringService.score(transaction)).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> enrichmentService.runEnrichment("txn-004"));

        verifyNoInteractions(fraudAlertRepository);
    }

    @Test
    void probabilityExactlyAtThreshold_createsAlert() {
        Transaction transaction = transaction("txn-005");
        when(transactionRepository.findById("txn-005")).thenReturn(Optional.of(transaction));
        when(mlFraudScoringService.score(transaction))
            .thenReturn(Optional.of(new MlFraudScoringService.MlScoreResult(0.4, "MEDIUM")));

        enrichmentService.runEnrichment("txn-005");

        verify(fraudAlertRepository).save(any());
    }
}