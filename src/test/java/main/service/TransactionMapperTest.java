package main.service;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.dto.response.FraudAlertResponse;
import main.dto.response.TransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransactionMapper.
 * No Spring context — mapper is a plain @Component with no injected dependencies.
 */
class TransactionMapperTest {

    TransactionMapper mapper;
    User sender;
    User receiver;

    @BeforeEach
    void setup() {
        mapper = new TransactionMapper();

        sender = User.builder()
            .name("Alice").email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("5000.00"))
            .homeCountry("US").homeCity("New York")
            .build();
        sender.setId("sender-id");

        receiver = User.builder()
            .name("Bob").email("bob@example.com")
            .phoneNumber("+14155550002")
            .balance(new BigDecimal("1000.00"))
            .homeCountry("US")
            .build();
        receiver.setId("receiver-id");
    }

    private Transaction merchantTransaction() {
        return Transaction.builder()
            .sender(sender)
            .amount(new BigDecimal("250.00"))
            .merchant("Amazon")
            .location("New York")
            .country("US")
            .status(TransactionStatus.APPROVED)
            .riskLevel(RiskLevel.LOW)
            .fraudScore(12.5)
            .processingTimeMs(45L)
            .build();
    }

    private Transaction p2pTransaction() {
        return Transaction.builder()
            .sender(sender)
            .receiver(receiver)
            .amount(new BigDecimal("100.00"))
            .location("New York")
            .country("US")
            .status(TransactionStatus.PENDING)
            .riskLevel(RiskLevel.LOW)
            .build();
    }

    private FraudAlert alert(Transaction txn) {
        return FraudAlert.builder()
            .transaction(txn)
            .userId("sender-id")
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(RiskLevel.HIGH)
            .scoreContribution(40.0)
            .description("High amount detected")
            .metadata("{\"amount\":250}")
            .build();
    }

    // ── Core field mapping ──────────────────────────────────────────────────────

    @Nested
    class CoreMappingTests {

        @Test
        void merchantTransaction_allFieldsMapped() {
            Transaction txn = merchantTransaction();
            txn.setId("txn-001");
            txn.setCreatedAt(Instant.parse("2024-01-01T10:00:00Z"));
            txn.setProcessedAt(Instant.parse("2024-01-01T10:00:01Z"));

            TransactionResponse response = mapper.toResponse(txn);

            assertEquals("txn-001", response.getId());
            assertEquals("sender-id", response.getUserId());
            assertNull(response.getReceiverId());
            assertEquals(new BigDecimal("250.00"), response.getAmount());
            assertEquals("Amazon", response.getMerchant());
            assertEquals("New York", response.getLocation());
            assertEquals("US", response.getCountry());
            assertEquals(TransactionStatus.APPROVED, response.getStatus());
            assertEquals(RiskLevel.LOW, response.getRiskLevel());
            assertEquals(12.5, response.getFraudScore(), 0.001);
            assertEquals(45L, response.getProcessingTimeMs());
            assertNotNull(response.getCreatedAt());
            assertNotNull(response.getProcessedAt());
        }

        @Test
        void p2pTransaction_receiverIdMapped() {
            Transaction txn = p2pTransaction();
            txn.setId("txn-002");

            TransactionResponse response = mapper.toResponse(txn);

            assertEquals("sender-id", response.getUserId());
            assertEquals("receiver-id", response.getReceiverId());
            assertNull(response.getMerchant());
        }

        @Test
        void nullSender_userIdIsNull() {
            Transaction txn = merchantTransaction();
            txn.setSender(null);

            TransactionResponse response = mapper.toResponse(txn);

            assertNull(response.getUserId());
        }

        @Test
        void nullReceiver_receiverIdIsNull() {
            Transaction txn = merchantTransaction();
            // merchant transactions have no receiver — already null in builder

            TransactionResponse response = mapper.toResponse(txn);

            assertNull(response.getReceiverId());
        }

        @Test
        void declineReason_mappedWhenPresent() {
            Transaction txn = merchantTransaction();
            txn.setDeclineReason("Fraud score exceeded threshold");

            TransactionResponse response = mapper.toResponse(txn);

            assertEquals("Fraud score exceeded threshold", response.getDeclineReason());
        }
    }

    // Alert mapping

    @Nested
    class AlertMappingTests {

        @Test
        void nullAlertsList_returnsEmptyList() {
            Transaction txn = merchantTransaction();
            txn.setFraudAlerts(null);

            TransactionResponse response = mapper.toResponse(txn);

            assertNotNull(response.getFraudAlerts());
            assertTrue(response.getFraudAlerts().isEmpty());
        }

        @Test
        void emptyAlertsList_returnsEmptyList() {
            Transaction txn = merchantTransaction();
            // @Builder.Default initialises to empty list

            TransactionResponse response = mapper.toResponse(txn);

            assertTrue(response.getFraudAlerts().isEmpty());
        }

        @Test
        void alerts_allFieldsMapped() {
            Transaction txn = merchantTransaction();
            txn.setId("txn-003");
            FraudAlert a = alert(txn);
            a.setId("alert-001");
            a.setCreatedAt(Instant.parse("2024-01-01T10:00:00Z"));
            txn.setFraudAlerts(List.of(a));

            TransactionResponse response = mapper.toResponse(txn);

            assertEquals(1, response.getFraudAlerts().size());
            FraudAlertResponse ar = response.getFraudAlerts().get(0);
            assertEquals("alert-001", ar.getId());
            assertEquals("txn-003", ar.getTransactionId());
            assertEquals("sender-id", ar.getUserId());
            assertEquals(FraudRuleType.HIGH_AMOUNT, ar.getRuleType());
            assertEquals(RiskLevel.HIGH, ar.getRiskLevel());
            assertEquals(40.0, ar.getScoreContribution(), 0.001);
            assertEquals("High amount detected", ar.getDescription());
            assertEquals("{\"amount\":250}", ar.getMetadata());
            assertFalse(ar.isResolved());
        }

        @Test
        void alertWithNullTransaction_transactionIdIsNull() {
            Transaction txn = merchantTransaction();
            FraudAlert a = alert(txn);
            a.setTransaction(null);
            txn.setFraudAlerts(List.of(a));

            TransactionResponse response = mapper.toResponse(txn);

            assertNull(response.getFraudAlerts().get(0).getTransactionId());
        }

        @Test
        void multipleAlerts_allMapped() {
            Transaction txn = merchantTransaction();
            FraudAlert a1 = alert(txn);
            a1.setId("alert-001");
            FraudAlert a2 = alert(txn);
            a2.setId("alert-002");
            a2.setRuleType(FraudRuleType.VELOCITY_BREACH);
            txn.setFraudAlerts(List.of(a1, a2));

            TransactionResponse response = mapper.toResponse(txn);

            assertEquals(2, response.getFraudAlerts().size());
        }
    }
}