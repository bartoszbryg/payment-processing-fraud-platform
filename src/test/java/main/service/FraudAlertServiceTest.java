package main.service;

import main.application.states.FraudRuleType;
import main.application.states.RiskLevel;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.dto.response.FraudAlertResponse;
import main.exception.ResourceNotFoundException;
import main.repository.FraudAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertServiceTest {

    @Mock FraudAlertRepository fraudAlertRepository;

    FraudAlertService fraudAlertService;

    @BeforeEach
    void setup() {
        fraudAlertService = new FraudAlertService(fraudAlertRepository);
    }

    private FraudAlert alert(String id, String userId, boolean resolved) {
        Transaction txn = new Transaction();
        txn.setId("txn-001");

        FraudAlert a = FraudAlert.builder()
            .transaction(txn)
            .userId(userId)
            .ruleType(FraudRuleType.HIGH_AMOUNT)
            .riskLevel(RiskLevel.HIGH)
            .scoreContribution(40.0)
            .description("High amount")
            .metadata("{\"amount\":5000}")
            .resolved(resolved)
            .build();
        a.setId(id);
        a.setCreatedAt(Instant.now());
        return a;
    }

    // getAlertsForTransaction

    @Nested
    class GetAlertsForTransactionTests {

        @Test
        void returnsAlertsMappedToResponse() {
            when(fraudAlertRepository.findByTransactionId("txn-001"))
                .thenReturn(List.of(alert("alert-001", "user-001", false)));

            List<FraudAlertResponse> result =
                fraudAlertService.getAlertsForTransaction("txn-001");

            assertEquals(1, result.size());
            assertEquals("alert-001", result.get(0).getId());
            assertEquals("txn-001", result.get(0).getTransactionId());
            assertEquals("user-001", result.get(0).getUserId());
            assertEquals(FraudRuleType.HIGH_AMOUNT, result.get(0).getRuleType());
            assertEquals(RiskLevel.HIGH, result.get(0).getRiskLevel());
            assertEquals(40.0, result.get(0).getScoreContribution(), 0.001);
            assertFalse(result.get(0).isResolved());
        }

        @Test
        void noAlertsForTransaction_returnsEmptyList() {
            when(fraudAlertRepository.findByTransactionId("txn-999"))
                .thenReturn(List.of());

            List<FraudAlertResponse> result =
                fraudAlertService.getAlertsForTransaction("txn-999");

            assertTrue(result.isEmpty());
        }

        @Test
        void multipleAlerts_allReturned() {
            when(fraudAlertRepository.findByTransactionId("txn-001"))
                .thenReturn(List.of(
                    alert("a1", "user-001", false),
                    alert("a2", "user-001", false)));

            List<FraudAlertResponse> result =
                fraudAlertService.getAlertsForTransaction("txn-001");

            assertEquals(2, result.size());
        }
    }

    // getAlertsForUser

    @Nested
    class GetAlertsForUserTests {

        @Test
        void returnsPagedAlerts() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<FraudAlert> page = new PageImpl<>(List.of(alert("a1", "user-001", false)));
            when(fraudAlertRepository.findByUserId("user-001", pageable)).thenReturn(page);

            Page<FraudAlertResponse> result =
                fraudAlertService.getAlertsForUser("user-001", pageable);

            assertEquals(1, result.getContent().size());
            assertEquals("user-001", result.getContent().get(0).getUserId());
        }

        @Test
        void emptyPage_returnsEmpty() {
            Pageable pageable = PageRequest.of(0, 10);
            when(fraudAlertRepository.findByUserId("user-999", pageable))
                .thenReturn(Page.empty());

            Page<FraudAlertResponse> result =
                fraudAlertService.getAlertsForUser("user-999", pageable);

            assertTrue(result.getContent().isEmpty());
        }
    }

    // getUnresolvedAlerts

    @Nested
    class GetUnresolvedAlertsTests {

        @Test
        void returnsOnlyUnresolved() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<FraudAlert> page = new PageImpl<>(List.of(
                alert("a1", "user-001", false),
                alert("a2", "user-002", false)));
            when(fraudAlertRepository.findAllByResolved(false, pageable)).thenReturn(page);

            Page<FraudAlertResponse> result =
                fraudAlertService.getUnresolvedAlerts(pageable);

            assertEquals(2, result.getContent().size());
            result.getContent().forEach(r -> assertFalse(r.isResolved()));
        }

        @Test
        void allResolved_returnsEmpty() {
            Pageable pageable = PageRequest.of(0, 10);
            when(fraudAlertRepository.findAllByResolved(false, pageable))
                .thenReturn(Page.empty());

            assertTrue(fraudAlertService.getUnresolvedAlerts(pageable).isEmpty());
        }
    }

    // getAlertsByRuleSince

    @Nested
    class GetAlertsByRuleSinceTests {

        @Test
        void returnsAlertsForRuleType() {
            Instant since = Instant.now().minusSeconds(3600);
            FraudAlert a = alert("a1", "user-001", false);
            a.setRuleType(FraudRuleType.HIGH_AMOUNT);
            when(fraudAlertRepository.findByRuleTypeSince(FraudRuleType.HIGH_AMOUNT, since))
                .thenReturn(List.of(a));

            List<FraudAlertResponse> result =
                fraudAlertService.getAlertsByRuleSince(FraudRuleType.HIGH_AMOUNT, since);

            assertEquals(1, result.size());
            assertEquals(FraudRuleType.HIGH_AMOUNT, result.get(0).getRuleType());
        }

        @Test
        void noMatchingAlerts_returnsEmpty() {
            Instant since = Instant.now().minusSeconds(3600);
            when(fraudAlertRepository.findByRuleTypeSince(FraudRuleType.VELOCITY_BREACH, since))
                .thenReturn(List.of());

            assertTrue(fraudAlertService
                .getAlertsByRuleSince(FraudRuleType.VELOCITY_BREACH, since).isEmpty());
        }
    }

    // getMostRecentAlerts

    @Nested
    class GetMostRecentAlertsTests {

        @Test
        void returnsAlertsMappedToResponse() {
            Page<FraudAlert> page = new PageImpl<>(List.of(
                alert("a1", "user-001", false),
                alert("a2", "user-002", true)));
            when(fraudAlertRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5)))
                .thenReturn(page);

            List<FraudAlertResponse> result = fraudAlertService.getMostRecentAlerts(5);

            assertEquals(2, result.size());
            assertEquals("a1", result.get(0).getId());
            assertEquals("a2", result.get(1).getId());
        }

        @Test
        void limitIsPassedThroughAsPageSize() {
            when(fraudAlertRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10)))
                .thenReturn(Page.empty());

            fraudAlertService.getMostRecentAlerts(10);

            verify(fraudAlertRepository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));
        }

        @Test
        void noAlerts_returnsEmptyList() {
            when(fraudAlertRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5)))
                .thenReturn(Page.empty());

            assertTrue(fraudAlertService.getMostRecentAlerts(5).isEmpty());
        }
    }

    // resolveAlert

    @Nested
    class ResolveAlertTests {

        @Test
        void resolve_setsResolvedTrue() {
            FraudAlert a = alert("alert-001", "user-001", false);
            when(fraudAlertRepository.findById("alert-001")).thenReturn(Optional.of(a));
            when(fraudAlertRepository.save(a)).thenReturn(a);

            FraudAlertResponse response = fraudAlertService.resolveAlert("alert-001");

            assertTrue(response.isResolved());
            verify(fraudAlertRepository).save(a);
        }

        @Test
        void resolve_notFound_throws() {
            when(fraudAlertRepository.findById("ghost")).thenReturn(Optional.empty());

            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> fraudAlertService.resolveAlert("ghost"));

            assertTrue(ex.getMessage().contains("ghost"));
            verify(fraudAlertRepository, never()).save(any());
        }

        @Test
        void resolve_alreadyResolved_stillSaves() {
            FraudAlert a = alert("alert-001", "user-001", true); // already resolved
            when(fraudAlertRepository.findById("alert-001")).thenReturn(Optional.of(a));
            when(fraudAlertRepository.save(a)).thenReturn(a);

            FraudAlertResponse response = fraudAlertService.resolveAlert("alert-001");

            assertTrue(response.isResolved());
            verify(fraudAlertRepository).save(a); // save still called
        }

        @Test
        void resolve_returnsMappedResponse() {
            FraudAlert a = alert("alert-001", "user-001", false);
            when(fraudAlertRepository.findById("alert-001")).thenReturn(Optional.of(a));
            when(fraudAlertRepository.save(a)).thenAnswer(inv -> inv.getArgument(0));

            FraudAlertResponse response = fraudAlertService.resolveAlert("alert-001");

            assertEquals("alert-001", response.getId());
            assertEquals("txn-001", response.getTransactionId());
            assertEquals("user-001", response.getUserId());
            assertEquals(FraudRuleType.HIGH_AMOUNT, response.getRuleType());
        }
    }
}