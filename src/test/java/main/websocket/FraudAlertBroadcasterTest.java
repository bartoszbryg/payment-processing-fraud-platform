package main.websocket;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertBroadcasterTest {

    @Mock SimpMessagingTemplate messagingTemplate;

    FraudAlertBroadcaster broadcaster;

    @BeforeEach
    void setup() {
        broadcaster = new FraudAlertBroadcaster(messagingTemplate);
    }

    private User user(String id) {
        User user = User.builder()
            .name("Alice")
            .email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("1000.00"))
            .homeCountry("US")
            .homeCity("New York")
            .build();
        user.setId(id);
        return user;
    }

    private Transaction transaction(String id, RiskLevel riskLevel, double score) {
        Transaction transaction = Transaction.builder()
            .sender(user("user-001"))
            .merchant("Amazon")
            .amount(new BigDecimal("100.00"))
            .location("New York")
            .country("US")
            .status(TransactionStatus.FLAGGED_FOR_REVIEW)
            .riskLevel(riskLevel)
            .fraudScore(score)
            .build();
        transaction.setId(id);
        return transaction;
    }

    private FraudAlert alert(Transaction transaction, FraudRuleType ruleType, RiskLevel riskLevel, double score) {
        return FraudAlert.builder()
            .transaction(transaction)
            .userId(transaction.getSender().getId())
            .ruleType(ruleType)
            .riskLevel(riskLevel)
            .scoreContribution(score)
            .description("test alert: " + ruleType)
            .build();
    }

    @Test
    void highRiskAlert_broadcastsToBothTopics() {
        Transaction transaction = transaction("txn-001", RiskLevel.HIGH, 70.0);
        FraudAlert alert = alert(transaction, FraudRuleType.HIGH_AMOUNT, RiskLevel.HIGH, 70.0);

        broadcaster.broadcast(List.of(alert), transaction);

        ArgumentCaptor<FraudAlertBroadcastMessage> captor = ArgumentCaptor.forClass(FraudAlertBroadcastMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/fraud-alerts"), captor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/fraud-alerts/user-001"), any(FraudAlertBroadcastMessage.class));

        FraudAlertBroadcastMessage message = captor.getValue();
        assertEquals("txn-001", message.getTransactionId());
        assertEquals("user-001", message.getUserId());
        assertEquals(RiskLevel.HIGH, message.getRiskLevel());
        assertEquals(70.0, message.getFraudScore(), 0.001);
        assertEquals(List.of(FraudRuleType.HIGH_AMOUNT), message.getRuleTypes());
        assertNotNull(message.getTimestamp());
    }

    @Test
    void criticalRiskAlert_broadcastsToBothTopics() {
        Transaction transaction = transaction("txn-002", RiskLevel.CRITICAL, 95.0);
        FraudAlert alert = alert(transaction, FraudRuleType.BLACKLISTED_MERCHANT, RiskLevel.CRITICAL, 100.0);

        broadcaster.broadcast(List.of(alert), transaction);

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(FraudAlertBroadcastMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/fraud-alerts"), any(FraudAlertBroadcastMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/fraud-alerts/user-001"), any(FraudAlertBroadcastMessage.class));
    }

    @Test
    void multipleAlerts_ruleTypesAggregatedInMessage() {
        Transaction transaction = transaction("txn-003", RiskLevel.HIGH, 75.0);
        FraudAlert amountAlert = alert(transaction, FraudRuleType.HIGH_AMOUNT, RiskLevel.HIGH, 25.0);
        FraudAlert locationAlert = alert(transaction, FraudRuleType.UNUSUAL_LOCATION, RiskLevel.MEDIUM, 15.0);

        ArgumentCaptor<FraudAlertBroadcastMessage> captor = ArgumentCaptor.forClass(FraudAlertBroadcastMessage.class);
        broadcaster.broadcast(List.of(amountAlert, locationAlert), transaction);

        verify(messagingTemplate).convertAndSend(eq("/topic/fraud-alerts"), captor.capture());
        assertEquals(List.of(FraudRuleType.HIGH_AMOUNT, FraudRuleType.UNUSUAL_LOCATION), captor.getValue().getRuleTypes());
    }

    @Test
    void emptyAlerts_neverBroadcasts() {
        Transaction transaction = transaction("txn-004", RiskLevel.LOW, 5.0);

        broadcaster.broadcast(List.of(), transaction);

        verifyNoInteractions(messagingTemplate);
    }
}